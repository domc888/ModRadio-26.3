param([string]$CoverPath, [string]$CmdPath, [string]$LogPath)

$ErrorActionPreference = 'SilentlyContinue'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)

$script:logLines = 0
function Log($msg) {
    if ($script:logLines -gt 200) { return }
    $script:logLines++
    try { Add-Content -LiteralPath $LogPath -Value ("{0} {1}" -f (Get-Date -Format 'HH:mm:ss'), $msg) } catch {}
}
try { Set-Content -LiteralPath $LogPath -Value '' } catch {}

Add-Type -AssemblyName System.Runtime.WindowsRuntime
$null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
$null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties, Windows.Media.Control, ContentType = WindowsRuntime]
$null = [Windows.Storage.Streams.IRandomAccessStreamWithContentType, Windows.Storage.Streams, ContentType = WindowsRuntime]
$null = [Windows.Graphics.Imaging.BitmapDecoder, Windows.Graphics.Imaging, ContentType = WindowsRuntime]
$null = [Windows.Graphics.Imaging.BitmapTransform, Windows.Graphics.Imaging, ContentType = WindowsRuntime]
$null = [Windows.Graphics.Imaging.PixelDataProvider, Windows.Graphics.Imaging, ContentType = WindowsRuntime]
$null = [Windows.Graphics.Imaging.BitmapPixelFormat, Windows.Graphics.Imaging, ContentType = WindowsRuntime]
$null = [Windows.Graphics.Imaging.BitmapAlphaMode, Windows.Graphics.Imaging, ContentType = WindowsRuntime]
$null = [Windows.Graphics.Imaging.ExifOrientationMode, Windows.Graphics.Imaging, ContentType = WindowsRuntime]
$null = [Windows.Graphics.Imaging.ColorManagementMode, Windows.Graphics.Imaging, ContentType = WindowsRuntime]
$null = [Windows.Graphics.Imaging.BitmapInterpolationMode, Windows.Graphics.Imaging, ContentType = WindowsRuntime]

$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
})[0]

function Await($op, $resultType) {
    $task = $asTaskGeneric.MakeGenericMethod($resultType).Invoke($null, @($op))
    [void]$task.Wait(5000)
    if ($task.IsCompleted) { return $task.Result }
    return $null
}

$mgrType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]
$mgr = Await ($mgrType::RequestAsync()) $mgrType
if ($null -eq $mgr) {
    Log 'could not get the media session manager'
    [Console]::Out.WriteLine('{"active":false}')
    [Console]::Out.Flush()
    exit 1
}

$propsType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties]
$streamType = [Windows.Storage.Streams.IRandomAccessStreamWithContentType]
$decoderType = [Windows.Graphics.Imaging.BitmapDecoder]
$pixelType = [Windows.Graphics.Imaging.PixelDataProvider]

$COVER = 128

# Writes the cover to $CoverPath. Preferred format: "RAW1" + width + height + BGRA pixels
# (decoded by Windows itself, so any format Windows can open works). If that fails the
# original encoded image is dumped instead and the Java side tries to decode it.
function Save-Cover($thumb) {
    $tmp = $CoverPath + '.tmp'

    try {
        $stream = Await ($thumb.OpenReadAsync()) $streamType
        if ($null -eq $stream) { throw 'OpenReadAsync returned nothing' }
        $decoder = Await ($decoderType::CreateAsync($stream)) $decoderType
        if ($null -eq $decoder) { throw 'no decoder for this image' }
        $t = New-Object Windows.Graphics.Imaging.BitmapTransform
        $t.ScaledWidth = [uint32]$COVER
        $t.ScaledHeight = [uint32]$COVER
        $t.InterpolationMode = [Windows.Graphics.Imaging.BitmapInterpolationMode]::Fant
        $pd = Await ($decoder.GetPixelDataAsync(
                [Windows.Graphics.Imaging.BitmapPixelFormat]::Bgra8,
                [Windows.Graphics.Imaging.BitmapAlphaMode]::Straight,
                $t,
                [Windows.Graphics.Imaging.ExifOrientationMode]::RespectExifOrientation,
                [Windows.Graphics.Imaging.ColorManagementMode]::DoNotColorManage)) $pixelType
        if ($null -eq $pd) { throw 'no pixel data' }
        $pix = $pd.DetachPixelData()
        if ($null -eq $pix -or $pix.Length -ne ($COVER * $COVER * 4)) { throw "unexpected pixel buffer size" }

        $hdr = [byte[]](@(0x52, 0x41, 0x57, 0x31) + [BitConverter]::GetBytes([int]$COVER) + [BitConverter]::GetBytes([int]$COVER))
        $fs = [System.IO.File]::Create($tmp)
        try {
            $fs.Write($hdr, 0, $hdr.Length)
            $fs.Write($pix, 0, $pix.Length)
        } finally { $fs.Close() }
        Move-Item -LiteralPath $tmp -Destination $CoverPath -Force
        return $true
    } catch {
        Log ("cover decode failed: " + $_.Exception.Message)
    }

    try {
        $stream = Await ($thumb.OpenReadAsync()) $streamType
        if ($null -eq $stream) { throw 'OpenReadAsync returned nothing' }
        $net = [System.IO.WindowsRuntimeStreamExtensions]::AsStreamForRead($stream)
        $fs = [System.IO.File]::Create($tmp)
        try { $net.CopyTo($fs) } finally { $fs.Close(); $net.Close() }
        Move-Item -LiteralPath $tmp -Destination $CoverPath -Force
        return $true
    } catch {
        Log ("cover fallback failed: " + $_.Exception.Message)
    }
    return $false
}

$lastKey = ''
$coverVersion = 0
$hasCover = $false
$coverTries = 0
$tick = 99

while ($true) {
    $session = $null
    try { $session = $mgr.GetCurrentSession() } catch {}

    if (Test-Path -LiteralPath $CmdPath) {
        $cmd = ''
        try {
            $cmd = (Get-Content -Raw -LiteralPath $CmdPath).Trim()
            Remove-Item -LiteralPath $CmdPath -Force
        } catch {}
        if ($null -ne $session -and $cmd -ne '') {
            try {
                if ($cmd -eq 'toggle') { [void]$session.TryTogglePlayPauseAsync() }
                elseif ($cmd -eq 'play') { [void]$session.TryPlayAsync() }
                elseif ($cmd -eq 'pause') { [void]$session.TryPauseAsync() }
                elseif ($cmd -eq 'next') { [void]$session.TrySkipNextAsync() }
                elseif ($cmd -eq 'prev') { [void]$session.TrySkipPreviousAsync() }
                elseif ($cmd.StartsWith('seek:')) {
                    $sec = [double]::Parse($cmd.Substring(5), [System.Globalization.CultureInfo]::InvariantCulture)
                    $ticks = [long]($sec * 10000000)
                    $ok = Await ($session.TryChangePlaybackPositionAsync($ticks)) ([bool])
                    if ($ok -ne $true) { Log ("seek to $sec s was refused by the player") }
                }
            } catch {
                Log ("command '$cmd' failed: " + $_.Exception.Message)
            }
        }
        $tick = 99
    }

    if ($tick -ge 4) {
        $tick = 0
        $line = '{"active":false}'
        if ($null -ne $session) {
            try {
                $props = Await ($session.TryGetMediaPropertiesAsync()) $propsType
                $title = ''; $artist = ''; $album = ''
                if ($null -ne $props) {
                    $title = [string]$props.Title
                    $artist = [string]$props.Artist
                    $album = [string]$props.AlbumTitle
                }

                $key = "$title|$artist|$album"
                if ($key -ne $lastKey) {
                    $lastKey = $key
                    $hasCover = $false
                    $coverTries = 0
                    $coverVersion++
                }

                if (-not $hasCover -and $coverTries -lt 15 -and $null -ne $props) {
                    $coverTries++
                    if ($null -eq $props.Thumbnail) {
                        if ($coverTries -eq 1) { Log "no thumbnail offered for '$title' yet" }
                    } elseif (Save-Cover $props.Thumbnail) {
                        $hasCover = $true
                        $coverVersion++
                    }
                }

                $timeline = $session.GetTimelineProperties()
                $info = $session.GetPlaybackInfo()
                $status = ''
                if ($null -ne $info) { $status = $info.PlaybackStatus.ToString() }
                $pos = 0.0; $dur = 0.0
                if ($null -ne $timeline) {
                    $pos = $timeline.Position.TotalSeconds
                    $dur = [math]::Round(($timeline.EndTime - $timeline.StartTime).TotalSeconds, 2)
                    # Position is only valid at LastUpdatedTime (Spotify only refreshes it on
                    # seeks / track changes), so bring it up to "now" while playing.
                    if ($status -eq 'Playing' -and $null -ne $timeline.LastUpdatedTime -and $timeline.LastUpdatedTime.Year -gt 2000) {
                        $age = ([DateTimeOffset]::UtcNow - $timeline.LastUpdatedTime).TotalSeconds
                        $rate = 1.0
                        if ($null -ne $info -and $null -ne $info.PlaybackRate) { $rate = [double]$info.PlaybackRate }
                        if ($age -gt 0) { $pos += ($age * $rate) }
                    }
                    if ($dur -gt 0 -and $pos -gt $dur) { $pos = $dur }
                    if ($pos -lt 0) { $pos = 0 }
                    $pos = [math]::Round($pos, 2)
                }

                $line = ([ordered]@{
                    active       = $true
                    title        = $title
                    artist       = $artist
                    album        = $album
                    playing      = ($status -eq 'Playing')
                    position     = $pos
                    duration     = $dur
                    hasCover     = $hasCover
                    coverVersion = $coverVersion
                } | ConvertTo-Json -Compress)
            } catch {
                Log ("poll failed: " + $_.Exception.Message)
            }
        }
        [Console]::Out.WriteLine($line)
        [Console]::Out.Flush()
    }

    $tick++
    Start-Sleep -Milliseconds 250
}
