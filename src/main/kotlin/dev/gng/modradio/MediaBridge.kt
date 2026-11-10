package dev.gng.modradio

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.abs

data class MediaState(
    val active: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val playing: Boolean = false,
    val positionSec: Double = 0.0,
    val durationSec: Double = 0.0,
    val receivedAtNanos: Long = 0L,
)

/** Square ARGB cover, always COVER_SIZE x COVER_SIZE. */
class CoverImage(val version: Long, val pixels: IntArray) {
    companion object {
        const val SIZE = 128
    }
}

/**
 * Talks to the Windows media session (the same thing behind the volume flyout's
 * "now playing" card) through a long-running PowerShell process. Works for Spotify,
 * browsers, anything that registers with SMTC. Every failure path ends in an
 * "inactive" state, never an exception.
 */
object MediaBridge {
    @Volatile
    var state: MediaState = MediaState()
        private set

    @Volatile
    var cover: CoverImage? = null
        private set

    @Volatile
    private var running = false

    @Volatile
    private var process: Process? = null

    // After a play/pause click, trust our own guess for a moment so the icon doesn't bounce
    // back while Windows catches up.
    @Volatile
    private var holdPlayingUntilNanos = 0L

    @Volatile
    private var heldPlaying = false

    // After a seek, keep our own clock for a moment: Windows keeps reporting the old
    // position until the player has processed the seek.
    @Volatile
    private var holdSeekUntilNanos = 0L

    private val isWindows: Boolean =
        System.getProperty("os.name", "").lowercase().contains("win")

    private fun dir(): Path = FabricLoader.getInstance().configDir.resolve("modradio")

    @Synchronized
    fun start() {
        if (!isWindows || running) return
        running = true
        val t = Thread({ loop() }, "ModRadio-Media")
        t.isDaemon = true
        t.start()
    }

    fun stop() {
        running = false
        try {
            process?.destroyForcibly()
        } catch (e: Throwable) {
            // nothing to do
        }
    }

    fun send(command: String) {
        if (!isWindows) return
        try {
            val s = state
            if (command == "toggle" && s.active) {
                heldPlaying = !s.playing
                holdPlayingUntilNanos = System.nanoTime() + 1_500_000_000L
                state = s.copy(
                    playing = !s.playing,
                    positionSec = currentPosition(s),
                    receivedAtNanos = System.nanoTime(),
                )
            }
            Files.createDirectories(dir())
            Files.writeString(dir().resolve("cmd.txt"), command)
        } catch (e: Throwable) {
            ModRadioClient.LOGGER.warn("Could not send media command", e)
        }
    }

    /** Jump to [sec] in the current track (clamped). Safe to call with any value. */
    fun seek(sec: Double) {
        if (!isWindows) return
        try {
            val s = state
            if (!s.active || s.durationSec <= 0.0 || sec.isNaN()) return
            val target = sec.coerceIn(0.0, s.durationSec)
            holdSeekUntilNanos = System.nanoTime() + 2_000_000_000L
            state = s.copy(positionSec = target, receivedAtNanos = System.nanoTime())
            Files.createDirectories(dir())
            Files.writeString(dir().resolve("cmd.txt"), String.format(Locale.ROOT, "seek:%.3f", target))
        } catch (e: Throwable) {
            ModRadioClient.LOGGER.warn("Could not seek", e)
        }
    }

    fun currentPosition(s: MediaState): Double {
        if (!s.active) return 0.0
        var pos = s.positionSec
        if (s.playing && s.receivedAtNanos > 0L) {
            pos += (System.nanoTime() - s.receivedAtNanos) / 1_000_000_000.0
        }
        if (s.durationSec > 0.0) pos = pos.coerceAtMost(s.durationSec)
        return pos.coerceAtLeast(0.0)
    }

    private fun loop() {
        var failures = 0
        while (running) {
            val started = System.currentTimeMillis()
            try {
                runOnce()
            } catch (e: Throwable) {
                ModRadioClient.LOGGER.warn("Media bridge stopped", e)
            }
            state = MediaState()
            cover = null
            if (!running) break
            failures = if (System.currentTimeMillis() - started > 30_000L) 0 else failures + 1
            if (failures > 5) {
                ModRadioClient.LOGGER.warn("Media bridge keeps failing, giving up")
                break
            }
            try {
                Thread.sleep(3000L)
            } catch (e: InterruptedException) {
                break
            }
        }
        running = false
    }

    private fun runOnce() {
        val d = dir()
        Files.createDirectories(d)
        val script = d.resolve("media.ps1")
        val stream = MediaBridge::class.java.getResourceAsStream("/modradio/media.ps1") ?: return
        stream.use { Files.copy(it, script, StandardCopyOption.REPLACE_EXISTING) }
        val coverFile = d.resolve("cover.bin")
        val cmdFile = d.resolve("cmd.txt")
        try {
            Files.deleteIfExists(cmdFile)
        } catch (e: Throwable) {
            // ignore
        }

        val pb = ProcessBuilder(
            "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
            "-File", script.toString(), coverFile.toString(), cmdFile.toString(),
            d.resolve("media.log").toString(),
        )
        pb.redirectError(ProcessBuilder.Redirect.DISCARD)
        val p = pb.start()
        process = p
        try {
            BufferedReader(InputStreamReader(p.inputStream, StandardCharsets.UTF_8)).use { reader ->
                while (running) {
                    val line = reader.readLine() ?: break
                    handleLine(line, coverFile)
                }
            }
        } finally {
            p.destroy()
        }
    }

    private fun JsonObject.str(key: String): String {
        val e = get(key)
        return if (e == null || e.isJsonNull) "" else e.asString
    }

    private fun JsonObject.dbl(key: String): Double {
        val e = get(key)
        return if (e == null || e.isJsonNull) 0.0 else e.asDouble
    }

    private fun JsonObject.bool(key: String): Boolean {
        val e = get(key)
        return if (e == null || e.isJsonNull) false else e.asBoolean
    }

    private fun JsonObject.lng(key: String): Long {
        val e = get(key)
        return if (e == null || e.isJsonNull) 0L else e.asLong
    }

    private fun handleLine(line: String, coverFile: Path) {
        try {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return
            val obj = JsonParser.parseString(trimmed).asJsonObject
            if (!obj.bool("active")) {
                state = MediaState()
                cover = null
                return
            }

            val hasCover = obj.bool("hasCover")
            val version = obj.lng("coverVersion")
            val current = cover
            if (!hasCover) {
                if (current != null) cover = null
            } else if (current == null || current.version != version) {
                cover = loadCover(coverFile, version)
            }

            val prev = state
            val now = System.nanoTime()
            val title = obj.str("title")
            val artist = obj.str("artist")
            val album = obj.str("album")
            val sameTrack = prev.active && prev.title == title && prev.artist == artist && prev.album == album

            val playing = if (now < holdPlayingUntilNanos) heldPlaying else obj.bool("playing")

            // Some players report 0 for the duration on a poll or two; keep the last good one.
            var duration = obj.dbl("duration")
            if (duration <= 0.0 && sameTrack) duration = prev.durationSec

            // Smooth the clock: only re-anchor on a real jump (seek / new track / state change).
            // Re-anchoring on every poll is what made the time bounce between 0:00 and 0:01.
            val reported = obj.dbl("position").coerceAtLeast(0.0)
            var anchorPos = reported
            var anchorAt = now
            if (sameTrack && now < holdSeekUntilNanos) {
                anchorPos = prev.positionSec
                anchorAt = prev.receivedAtNanos
            } else if (sameTrack && prev.playing == playing) {
                val expected = currentPosition(prev)
                if (abs(reported - expected) <= 2.0) {
                    anchorPos = prev.positionSec
                    anchorAt = prev.receivedAtNanos
                }
            }

            state = MediaState(
                active = true,
                title = title,
                artist = artist,
                album = album,
                playing = playing,
                positionSec = anchorPos,
                durationSec = duration,
                receivedAtNanos = anchorAt,
            )
        } catch (e: Throwable) {
            // bad line, keep the previous state
        }
    }

    private fun loadCover(file: Path, version: Long): CoverImage? {
        return try {
            val size = CoverImage.SIZE
            val bytes = Files.readAllBytes(file)
            val raw = readRaw(bytes, size)
            if (raw != null) return CoverImage(version, raw)

            val src = ImageIO.read(java.io.ByteArrayInputStream(bytes))
            if (src == null) {
                ModRadioClient.LOGGER.warn("Cover file is not an image Java can read")
                return null
            }
            val out = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val g = out.createGraphics()
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                g.drawImage(src, 0, 0, size, size, null)
            } finally {
                g.dispose()
            }
            val pixels = IntArray(size * size)
            out.getRGB(0, 0, size, size, pixels, 0, size)
            CoverImage(version, pixels)
        } catch (e: Throwable) {
            ModRadioClient.LOGGER.warn("Could not load cover", e)
            null
        }
    }

    /** "RAW1" + int32 w + int32 h (little endian) + BGRA bytes, as written by media.ps1. */
    private fun readRaw(b: ByteArray, size: Int): IntArray? {
        if (b.size < 12 || b[0] != 0x52.toByte() || b[1] != 0x41.toByte() || b[2] != 0x57.toByte() || b[3] != 0x31.toByte()) return null
        fun le(o: Int): Int =
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
                ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
        val w = le(4)
        val h = le(8)
        if (w != size || h != size || b.size < 12 + w * h * 4) return null
        val out = IntArray(w * h)
        var o = 12
        for (i in out.indices) {
            val bl = b[o].toInt() and 0xFF
            val gr = b[o + 1].toInt() and 0xFF
            val rd = b[o + 2].toInt() and 0xFF
            val al = b[o + 3].toInt() and 0xFF
            out[i] = (al shl 24) or (rd shl 16) or (gr shl 8) or bl
            o += 4
        }
        return out
    }
}
