package dev.gng.modradio

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import kotlin.math.max

/** Drag the radio anywhere. Position is stored as distance from the top-right corner. */
class HudEditorScreen(private val parent: Screen?) : Screen(Component.literal("Move Radio")) {
    private var dragging = false
    private var grabX = 0.0
    private var grabY = 0.0

    override fun init() {
        addRenderableWidget(
            Button.builder(Component.literal("Done")) { onClose() }
                .bounds(width / 2 - 102, height - 28, 100, 20).build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal("Reset position")) {
                val c = RadioConfigManager.config
                c.offsetRight = 5
                c.offsetTop = 5
            }.bounds(width / 2 + 2, height - 28, 100, 20).build(),
        )
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        val mc = Minecraft.getInstance()
        graphics.centeredText(mc.font, Component.literal("Drag the radio to move it"), width / 2, 14, 0xFFFFFFFF.toInt())
        RadioHud.render(graphics, true, mouseX, mouseY)
        val l = RadioHud.lastLayout
        if (l != null) {
            val c = 0xFFFFFFFF.toInt()
            val x2 = l.x + l.screenW
            val y2 = l.y + l.screenH
            graphics.fill(l.x - 1, l.y - 1, x2 + 1, l.y, c)
            graphics.fill(l.x - 1, y2, x2 + 1, y2 + 1, c)
            graphics.fill(l.x - 1, l.y, l.x, y2, c)
            graphics.fill(x2, l.y, x2 + 1, y2, c)
        }
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (super.mouseClicked(event, doubleClick)) return true
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return false
        val hit = RadioHud.hit(event.x(), event.y())
        if (hit >= RadioHud.HIT_PREV && hit <= RadioHud.HIT_NEXT) {
            RadioHud.perform(hit)
            return true
        }
        if (hit == RadioHud.HIT_BAR) {
            RadioHud.beginScrub(event.x())
            return true
        }
        val l = RadioHud.lastLayout
        if (hit == RadioHud.HIT_BODY && l != null) {
            dragging = true
            grabX = event.x() - l.x
            grabY = event.y() - l.y
            return true
        }
        return false
    }

    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        if (RadioHud.scrubbing) {
            RadioHud.dragScrub(event.x())
            return true
        }
        val l = RadioHud.lastLayout
        if (dragging && l != null) {
            val mc = Minecraft.getInstance()
            val gw = mc.window.guiScaledWidth
            val gh = mc.window.guiScaledHeight
            val newLeft = (event.x() - grabX).toInt().coerceIn(0, max(0, gw - l.screenW))
            val newTop = (event.y() - grabY).toInt().coerceIn(0, max(0, gh - l.screenH))
            val c = RadioConfigManager.config
            c.offsetRight = gw - l.screenW - newLeft
            c.offsetTop = newTop
            return true
        }
        return super.mouseDragged(event, dragX, dragY)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        if (RadioHud.scrubbing) {
            RadioHud.endScrub(event.x())
            return true
        }
        if (dragging) {
            dragging = false
            RadioConfigManager.save()
            return true
        }
        return super.mouseReleased(event)
    }

    override fun onClose() {
        RadioHud.cancelScrub()
        RadioConfigManager.save()
        Minecraft.getInstance().gui.setScreen(parent)
    }
}
