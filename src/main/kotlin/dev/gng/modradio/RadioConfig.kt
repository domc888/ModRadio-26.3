package dev.gng.modradio

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path

/**
 * Mirrors Lunar's ModRadio options. Plain class with a no-arg constructor so Gson
 * always falls back to the defaults for anything missing in the file (no nulls).
 * Volume / mute are intentionally gone: Windows media sessions don't expose them.
 */
class RadioConfig {
    var enabled: Boolean = true
    var scale: Float = 1.0f
    var showCoverArt: Boolean = true
    var showProgress: Boolean = true
    var showWhenNothingPlaying: Boolean = false
    var boldArtistName: Boolean = false
    var boldSongName: Boolean = true
    var showDuration: Boolean = true
    var showPlayButton: Boolean = true
    var background: Boolean = true
    var staticBackgroundWidth: Boolean = true
    var staticBackgroundHeight: Boolean = true
    var backgroundWidth: Int = 170
    var backgroundHeight: Int = 40
    var border: Boolean = false
    var borderThickness: Float = 0.5f
    var borderColor: Int = 0x9F000000.toInt()
    var backgroundColor: Int = 0x6F000000
    var songNameTextColor: Int = 0xFFFFFFFF.toInt()
    var artistNameTextColor: Int = 0xFFFFFFFF.toInt()
    var progressBarColor: Int = 0xFF4F94FC.toInt()

    // Position: distance from the top-right corner of the screen (GUI pixels).
    var offsetRight: Int = 5
    var offsetTop: Int = 5

    // Bumped when the HUD layout changes so old saved sizes get reset once.
    var layoutVersion: Int = 2
}

object RadioConfigManager {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    @Volatile
    var config: RadioConfig = RadioConfig()

    private fun path(): Path = FabricLoader.getInstance().configDir.resolve("modradio.json")

    fun load() {
        try {
            val p = path()
            if (Files.exists(p)) {
                val text = Files.readString(p)
                val loaded = gson.fromJson(text, RadioConfig::class.java)
                if (loaded != null) {
                    config = loaded
                    val root = JsonParser.parseString(text)
                    val hasVersion = root.isJsonObject && root.asJsonObject.has("layoutVersion")
                    if (!hasVersion) {
                        // config from the old, smaller layout: move to the new default size once
                        config.backgroundWidth = 170
                        config.backgroundHeight = 40
                        config.layoutVersion = 2
                    }
                }
            }
        } catch (e: Throwable) {
            ModRadioClient.LOGGER.warn("Could not read modradio.json, using defaults", e)
            config = RadioConfig()
        }
        sanitize()
        save()
    }

    fun save() {
        try {
            sanitize()
            Files.writeString(path(), gson.toJson(config))
        } catch (e: Throwable) {
            ModRadioClient.LOGGER.warn("Could not write modradio.json", e)
        }
    }

    fun sanitize() {
        val c = config
        c.scale = c.scale.coerceIn(0.25f, 5.0f)
        c.backgroundWidth = c.backgroundWidth.coerceIn(100, 300)
        c.backgroundHeight = c.backgroundHeight.coerceIn(30, 64)
        c.borderThickness = c.borderThickness.coerceIn(0.5f, 3.0f)
        c.offsetRight = c.offsetRight.coerceIn(-2000, 4000)
        c.offsetTop = c.offsetTop.coerceIn(-2000, 4000)
    }

    fun reset() {
        config = RadioConfig()
        save()
    }
}
