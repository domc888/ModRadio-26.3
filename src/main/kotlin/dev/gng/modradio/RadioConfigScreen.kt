package dev.gng.modradio

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractSliderButton
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import kotlin.math.max
import kotlin.math.roundToInt

/** Opened from Mod Menu. Everything saves to config/modradio.json when the screen closes. */
class RadioConfigScreen(private val parent: Screen?) : Screen(Component.literal("Radio Settings")) {

    private class Row(val widget: AbstractWidget, val baseY: Int)

    private class ValueSlider(
        x: Int, y: Int, w: Int, h: Int,
        private val label: String,
        private val min: Double,
        private val max: Double,
        initial: Double,
        private val decimals: Int,
        private val onChange: (Double) -> Unit,
    ) : AbstractSliderButton(
        x, y, w, h, Component.empty(),
        ((initial - min) / (max - min)).coerceIn(0.0, 1.0),
    ) {
        private fun current(): Double = min + (max - min) * this.value

        init {
            updateMessage()
        }

        override fun updateMessage() {
            setMessage(Component.literal("$label: " + "%.${decimals}f".format(current())))
        }

        override fun applyValue() {
            onChange(current())
        }
    }

    private val rows = ArrayList<Row>()
    private val colorLabels = ArrayList<Pair<String, Row>>()
    private var scroll = 0
    private var maxScroll = 0

    private val topLimit = 34
    private val bottomLimit get() = height - 36

    override fun init() {
        rows.clear()
        colorLabels.clear()
        scroll = 0
        val cfg = RadioConfigManager.config
        val mc = Minecraft.getInstance()
        val colX = width / 2 - 100
        var y = topLimit

        fun addRow(w: AbstractWidget): Row {
            addRenderableWidget(w)
            val r = Row(w, y)
            rows.add(r)
            y += 24
            return r
        }

        fun toggle(label: String, get: () -> Boolean, set: (Boolean) -> Unit) {
            fun text() = Component.literal(label + ": " + if (get()) "ON" else "OFF")
            addRow(
                Button.builder(text()) { b ->
                    set(!get())
                    b.setMessage(text())
                }.bounds(colX, y, 200, 20).build(),
            )
        }

        fun slider(label: String, min: Double, max: Double, decimals: Int, get: () -> Double, set: (Double) -> Unit) {
            addRow(ValueSlider(colX, y, 200, 20, label, min, max, get(), decimals, set))
        }

        fun color(label: String, get: () -> Int, set: (Int) -> Unit) {
            val box = EditBox(mc.font, colX + 100, y, 100, 20, Component.literal(label))
            box.setMaxLength(9)
            box.setValue(toHex(get()))
            box.setResponder { s ->
                val parsed = parseColor(s)
                if (parsed != null) set(parsed)
            }
            val row = addRow(box)
            colorLabels.add(Pair(label, row))
        }

        toggle("Enabled", { cfg.enabled }, { cfg.enabled = it })
        slider("Scale", 0.25, 5.0, 2, { cfg.scale.toDouble() }, { cfg.scale = it.toFloat() })
        toggle("Show cover art", { cfg.showCoverArt }, { cfg.showCoverArt = it })
        toggle("Show progress", { cfg.showProgress }, { cfg.showProgress = it })
        toggle("Show when nothing playing", { cfg.showWhenNothingPlaying }, { cfg.showWhenNothingPlaying = it })
        toggle("Bold artist name", { cfg.boldArtistName }, { cfg.boldArtistName = it })
        toggle("Bold song name", { cfg.boldSongName }, { cfg.boldSongName = it })
        toggle("Show duration", { cfg.showDuration }, { cfg.showDuration = it })
        toggle("Show play buttons", { cfg.showPlayButton }, { cfg.showPlayButton = it })
        toggle("Background", { cfg.background }, { cfg.background = it })
        toggle("Static background width", { cfg.staticBackgroundWidth }, { cfg.staticBackgroundWidth = it })
        toggle("Static background height", { cfg.staticBackgroundHeight }, { cfg.staticBackgroundHeight = it })
        slider("Background width", 100.0, 300.0, 0, { cfg.backgroundWidth.toDouble() }, { cfg.backgroundWidth = it.roundToInt() })
        slider("Background height", 30.0, 64.0, 0, { cfg.backgroundHeight.toDouble() }, { cfg.backgroundHeight = it.roundToInt() })
        toggle("Border", { cfg.border }, { cfg.border = it })
        slider("Border thickness", 0.5, 3.0, 1, { cfg.borderThickness.toDouble() }, { cfg.borderThickness = it.toFloat() })
        color("Border color", { cfg.borderColor }, { cfg.borderColor = it })
        color("Background color", { cfg.backgroundColor }, { cfg.backgroundColor = it })
        color("Song name color", { cfg.songNameTextColor }, { cfg.songNameTextColor = it })
        color("Artist name color", { cfg.artistNameTextColor }, { cfg.artistNameTextColor = it })
        color("Progress bar color", { cfg.progressBarColor }, { cfg.progressBarColor = it })

        maxScroll = max(0, y - bottomLimit)

        addRenderableWidget(
            Button.builder(Component.literal("Move radio")) {
                RadioConfigManager.save()
                mc.gui.setScreen(HudEditorScreen(this))
            }.bounds(width / 2 - 102, height - 28, 100, 20).build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal("Done")) { onClose() }
                .bounds(width / 2 + 2, height - 28, 100, 20).build(),
        )

        applyScroll()
    }

    private fun applyScroll() {
        for (r in rows) {
            r.widget.setY(r.baseY - scroll)
            val top = r.widget.y
            r.widget.visible = top >= topLimit - 2 && top + r.widget.height <= bottomLimit + 2
        }
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (maxScroll > 0) {
            scroll = (scroll - (scrollY * 18).toInt()).coerceIn(0, maxScroll)
            applyScroll()
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        val mc = Minecraft.getInstance()
        graphics.centeredText(mc.font, title, width / 2, 14, 0xFFFFFFFF.toInt())
        val colX = width / 2 - 100
        for ((label, row) in colorLabels) {
            if (row.widget.visible) {
                graphics.text(mc.font, Component.literal(label), colX, row.widget.y + 6, 0xFFFFFFFF.toInt(), true)
            }
        }
    }

    override fun onClose() {
        RadioConfigManager.save()
        Minecraft.getInstance().gui.setScreen(parent)
    }

    private fun toHex(color: Int): String = "#%08X".format(color)

    private fun parseColor(input: String): Int? {
        return try {
            val s = input.trim().removePrefix("#")
            when (s.length) {
                6 -> (0xFF000000L or s.toLong(16)).toInt()
                8 -> s.toLong(16).toInt()
                else -> null
            }
        } catch (e: Throwable) {
            null
        }
    }
}
