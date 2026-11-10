package dev.gng.modradio

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FontDescription
import net.minecraft.network.chat.Style
import net.minecraft.resources.Identifier
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

object RadioHud {
    const val PAD = 5

    const val HIT_NONE = 0
    const val HIT_PREV = 1
    const val HIT_PLAY = 2
    const val HIT_NEXT = 3
    const val HIT_BODY = 4
    const val HIT_BAR = 5

    private const val BTN = 11
    private const val BTN_AREA = BTN * 3
    private const val GAP = 6
    private const val LINE = 9
    private const val TEXT_BLOCK = 19 // title row + artist row
    private const val FULL_BLOCK = 32 // title, artist, time row, bar
    private const val COVER_PAD = 4
    private const val MAX_COVER = 40
    private const val TIME_SCALE = 0.8f
    private const val TRACK_COLOR = 0x40FFFFFF
    private const val HOVER_COLOR = 0x33FFFFFF

    private val WHITE: Int = 0xFFFFFFFF.toInt()
    private val COVER_PLACEHOLDER: Int = 0xFF2B2B2B.toInt()

    private val STYLE_REGULAR: Style =
        Style.EMPTY.withFont(FontDescription.Resource(Identifier.fromNamespaceAndPath(ModRadioClient.MOD_ID, "radio")))
    private val STYLE_BOLD: Style =
        Style.EMPTY.withFont(FontDescription.Resource(Identifier.fromNamespaceAndPath(ModRadioClient.MOD_ID, "radio_bold")))

    class Layout(
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
        val scale: Float,
        val coverSize: Int,
        val textLeft: Int,
        val textRight: Int,
        val contentTop: Int,
        val hasBottom: Boolean,
        val showButtons: Boolean,
        val btnX: Int,
        val btnY: Int,
        val rowTop: Int,
        val barY: Int,
        val barX0: Int,
        val barX1: Int,
        val barEnabled: Boolean,
    ) {
        val screenW: Int get() = (w * scale).roundToInt()
        val screenH: Int get() = (h * scale).roundToInt()
    }

    @Volatile
    var lastLayout: Layout? = null
        private set

    private var coverKey = -1L
    private var coverCells = -1
    private var coverPixels = IntArray(0)

    // ---------------------------------------------------------------- text helpers

    private fun comp(text: String, bold: Boolean): Component =
        Component.literal(text).withStyle(if (bold) STYLE_BOLD else STYLE_REGULAR)

    private fun fmt(sec: Double, long: Boolean): String {
        val total = sec.coerceAtLeast(0.0).toLong()
        return if (long) {
            "%d:%02d:%02d".format(total / 3600, (total / 60) % 60, total % 60)
        } else {
            "%d:%02d".format(total / 60, total % 60)
        }
    }

    private fun withAlpha(color: Int, factor: Float): Int {
        val a = ((color ushr 24) * factor).roundToInt().coerceIn(0, 255)
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    private fun truncate(font: Font, text: String, bold: Boolean, maxW: Int): String {
        if (maxW <= 0) return ""
        var end = text.length
        while (end > 0) {
            val candidate = text.substring(0, end).trimEnd() + "..."
            if (font.width(comp(candidate, bold)) <= maxW) return candidate
            end--
        }
        return ""
    }

    /** Draws text on one line: shrinks it to fit, and only truncates once it hits [minScale]. */
    private fun drawFitted(
        g: GuiGraphicsExtractor, font: Font, text: String, bold: Boolean,
        x: Int, y: Int, maxW: Int, minScale: Float, color: Int, shadow: Boolean,
    ) {
        if (text.isEmpty() || maxW <= 0) return
        val natural = font.width(comp(text, bold))
        var scale = 1f
        var shown = text
        if (natural > maxW) {
            scale = max(minScale, maxW.toFloat() / natural)
            if (natural * scale > maxW + 0.5f) {
                shown = truncate(font, text, bold, (maxW / scale).toInt())
            }
        }
        val pose = g.pose()
        pose.pushMatrix()
        try {
            pose.translate(x.toFloat(), y.toFloat() + LINE * (1f - scale) / 2f)
            pose.scale(scale, scale)
            g.text(font, comp(shown, bold), 0, 0, color, shadow)
        } finally {
            pose.popMatrix()
        }
    }

    private fun drawSmall(g: GuiGraphicsExtractor, font: Font, text: String, x: Int, y: Int, color: Int, shadow: Boolean, bold: Boolean = false) {
        val pose = g.pose()
        pose.pushMatrix()
        try {
            pose.translate(x.toFloat(), y.toFloat())
            pose.scale(TIME_SCALE, TIME_SCALE)
            g.text(font, comp(text, bold), 0, 0, color, shadow)
        } finally {
            pose.popMatrix()
        }
    }

    private fun titleOf(s: MediaState): String =
        if (s.active) s.title.ifBlank { "Unknown" } else "Nothing playing"

    private fun artistOf(s: MediaState): String = if (s.active) s.artist else ""

    // ---------------------------------------------------------------- layout

    private fun computeLayout(mc: Minecraft, cfg: RadioConfig, s: MediaState): Layout {
        val font = mc.font
        val buttons = cfg.showPlayButton
        val bottom = cfg.showProgress || cfg.showDuration
        val contentH = if (bottom) FULL_BLOCK else TEXT_BLOCK

        val h = if (cfg.staticBackgroundHeight) {
            max(cfg.backgroundHeight, contentH + 8)
        } else {
            contentH + 2 * PAD
        }
        val cs = if (cfg.showCoverArt) min(h - 2 * COVER_PAD, MAX_COVER).coerceAtLeast(8) else 0
        val left = if (cs > 0) COVER_PAD + cs + 7 else PAD + 2

        val w = if (cfg.staticBackgroundWidth) {
            cfg.backgroundWidth
        } else {
            val titleW = font.width(comp(titleOf(s), cfg.boldSongName))
            val artistW = font.width(comp(artistOf(s), cfg.boldArtistName))
            (left + max(titleW, artistW) + PAD + 4).coerceIn(100, 300)
        }

        val textRight = w - PAD - 2
        val contentTop = ((h - contentH) / 2).coerceAtLeast(2)
        val btnX = w - PAD - BTN_AREA
        val btnY = contentTop - 1

        val scale = cfg.scale
        val sw = (w * scale).roundToInt()
        val sh = (h * scale).roundToInt()
        val gw = mc.window.guiScaledWidth
        val gh = mc.window.guiScaledHeight
        val x = (gw - sw - cfg.offsetRight).coerceIn(0, max(0, gw - sw))
        val y = cfg.offsetTop.coerceIn(0, max(0, gh - sh))

        val rowTop = contentTop + 21
        val barY = contentTop + 30
        val hasDur = s.active && s.durationSec > 0.0
        val barEnabled = cfg.showProgress && hasDur && textRight - left >= 16
        return Layout(x, y, w, h, scale, cs, left, textRight, contentTop, bottom, buttons, btnX, btnY, rowTop, barY, left, textRight, barEnabled)
    }

    // ---------------------------------------------------------------- shapes

    /** Rectangle with 1px chamfered corners. */
    private fun roundFill(g: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        if (x1 - x0 < 4 || y1 - y0 < 4) {
            g.fill(x0, y0, x1, y1, color)
            return
        }
        g.fill(x0 + 1, y0, x1 - 1, y0 + 1, color)
        g.fill(x0, y0 + 1, x1, y1 - 1, color)
        g.fill(x0 + 1, y1 - 1, x1 - 1, y1, color)
    }

    private fun drawBorder(g: GuiGraphicsExtractor, w: Int, h: Int, thickness: Float, color: Int) {
        val t = thickness.roundToInt().coerceAtLeast(1)
        g.fill(1, 0, w - 1, t, color)
        g.fill(1, h - t, w - 1, h, color)
        g.fill(0, t, t, h - t, color)
        g.fill(w - t, t, w, h - t, color)
    }

    private fun triRight(g: GuiGraphicsExtractor, x: Int, y: Int, color: Int) {
        for (r in 0 until 7) {
            g.fill(x, y + r, x + 4 - abs(r - 3), y + r + 1, color)
        }
    }

    private fun triLeft(g: GuiGraphicsExtractor, x: Int, y: Int, color: Int) {
        for (r in 0 until 7) {
            g.fill(x + abs(r - 3), y + r, x + 4, y + r + 1, color)
        }
    }

    private fun drawIcon(g: GuiGraphicsExtractor, kind: Int, playing: Boolean, bx: Int, by: Int, color: Int) {
        val iy = by + 2
        when (kind) {
            HIT_PREV -> {
                g.fill(bx + 3, iy, bx + 4, iy + 7, color)
                triLeft(g, bx + 4, iy, color)
            }
            HIT_NEXT -> {
                triRight(g, bx + 3, iy, color)
                g.fill(bx + 7, iy, bx + 8, iy + 7, color)
            }
            else -> {
                if (playing) {
                    g.fill(bx + 3, iy, bx + 5, iy + 7, color)
                    g.fill(bx + 6, iy, bx + 8, iy + 7, color)
                } else {
                    triRight(g, bx + 4, iy, color)
                }
            }
        }
    }

    private fun drawBar(g: GuiGraphicsExtractor, x0: Int, y: Int, x1: Int, frac: Double, color: Int, knob: Boolean) {
        val bw = x1 - x0
        if (bw < 8) return
        g.fill(x0, y, x1, y + 2, TRACK_COLOR)
        val fw = (bw * frac.coerceIn(0.0, 1.0)).roundToInt().coerceIn(0, bw)
        if (fw > 0) g.fill(x0, y, x0 + fw, y + 2, color)
        if (knob) {
            val kx = (x0 + fw - 2).coerceIn(x0, x1 - 4)
            g.fill(kx, y - 1, kx + 4, y + 3, WHITE)
        }
    }

    // ---------------------------------------------------------------- cover

    private fun resampleCover(cover: CoverImage, cells: Int): IntArray {
        val n = CoverImage.SIZE
        val src = cover.pixels
        val out = IntArray(cells * cells)
        for (cy in 0 until cells) {
            val y0 = cy * n / cells
            val y1 = max(y0 + 1, (cy + 1) * n / cells)
            for (cx in 0 until cells) {
                val x0 = cx * n / cells
                val x1 = max(x0 + 1, (cx + 1) * n / cells)
                var r = 0
                var gr = 0
                var b = 0
                var count = 0
                for (sy in y0 until min(y1, n)) {
                    for (sx in x0 until min(x1, n)) {
                        val p = src[sy * n + sx]
                        r += (p shr 16) and 0xFF
                        gr += (p shr 8) and 0xFF
                        b += p and 0xFF
                        count++
                    }
                }
                if (count == 0) count = 1
                out[cy * cells + cx] = (0xFF shl 24) or ((r / count) shl 16) or ((gr / count) shl 8) or (b / count)
            }
        }
        return out
    }

    private fun drawCover(g: GuiGraphicsExtractor, mc: Minecraft, x: Int, y: Int, size: Int, scale: Float) {
        val cover = MediaBridge.cover
        if (cover == null) {
            roundFill(g, x, y, x + size, y + size, COVER_PLACEHOLDER)
            return
        }
        // One cell per real screen pixel (capped), so the art stays sharp at any GUI scale.
        val device = size * scale * mc.window.guiScale.toDouble()
        val cells = device.roundToInt().coerceIn(8, 48)
        if (coverKey != cover.version || coverCells != cells) {
            coverPixels = resampleCover(cover, cells)
            coverKey = cover.version
            coverCells = cells
        }
        val px = coverPixels
        if (px.size != cells * cells) return

        val rc = max(1, (cells * 0.14).roundToInt())
        val pose = g.pose()
        pose.pushMatrix()
        try {
            pose.translate(x.toFloat(), y.toFloat())
            val k = size.toFloat() / cells
            pose.scale(k, k)
            for (cy in 0 until cells) {
                val ey = min(cy, cells - 1 - cy)
                for (cx in 0 until cells) {
                    if (ey < rc) {
                        val ex = min(cx, cells - 1 - cx)
                        if (ex < rc) {
                            val dx = rc - ex - 0.5
                            val dy = rc - ey - 0.5
                            if (sqrt(dx * dx + dy * dy) > rc) continue
                        }
                    }
                    g.fill(cx, cy, cx + 1, cy + 1, px[cy * cells + cx])
                }
            }
        } finally {
            pose.popMatrix()
        }
    }

    // ---------------------------------------------------------------- render

    fun render(g: GuiGraphicsExtractor, forceShow: Boolean, mouseX: Int = -1, mouseY: Int = -1) {
        val mc = Minecraft.getInstance()
        val cfg = RadioConfigManager.config
        val s = MediaBridge.state

        if (!forceShow && (!cfg.enabled || (!s.active && !cfg.showWhenNothingPlaying))) {
            lastLayout = null
            return
        }

        scrubWatchdog()
        val l = computeLayout(mc, cfg, s)
        lastLayout = l

        val pose = g.pose()
        pose.pushMatrix()
        try {
            pose.translate(l.x.toFloat(), l.y.toFloat())
            pose.scale(l.scale, l.scale)
            drawCard(g, mc, cfg, s, l, mouseX, mouseY)
        } finally {
            pose.popMatrix()
        }
    }

    private fun drawCard(
        g: GuiGraphicsExtractor, mc: Minecraft, cfg: RadioConfig, s: MediaState, l: Layout,
        mouseX: Int, mouseY: Int,
    ) {
        val font = mc.font
        val w = l.w
        val h = l.h
        val shadow = !cfg.background

        if (cfg.background) roundFill(g, 0, 0, w, h, cfg.backgroundColor)
        if (cfg.border) drawBorder(g, w, h, cfg.borderThickness, cfg.borderColor)

        if (l.coverSize > 0) {
            drawCover(g, mc, COVER_PAD, (h - l.coverSize) / 2, l.coverSize, l.scale)
        }

        // title + artist
        val textW = l.textRight - l.textLeft
        drawFitted(
            g, font, titleOf(s), cfg.boldSongName, l.textLeft, l.contentTop,
            textW, 0.6f, cfg.songNameTextColor, shadow,
        )
        drawFitted(
            g, font, artistOf(s), cfg.boldArtistName, l.textLeft, l.contentTop + 10,
            textW, 0.75f, withAlpha(cfg.artistNameTextColor, 0.8f), shadow,
        )

        // time labels (elapsed left, remaining right) and the thin bar under them
        if (l.hasBottom && s.active) {
            val hasDur = s.durationSec > 0.0
            val scrubbingNow = scrubbing && hasDur
            val pos = if (scrubbingNow) scrubFrac * s.durationSec else MediaBridge.currentPosition(s)
            val long = s.durationSec >= 3600.0
            val timeColor = withAlpha(cfg.songNameTextColor, 0.9f)

            if (cfg.showDuration) {
                drawSmall(g, font, fmt(pos, long), l.textLeft, l.rowTop, timeColor, shadow, true)
                if (hasDur) {
                    val rest = "-" + fmt((s.durationSec - pos).coerceAtLeast(0.0), long)
                    val restW = (font.width(comp(rest, true)) * TIME_SCALE).roundToInt()
                    drawSmall(g, font, rest, l.textRight - restW, l.rowTop, timeColor, shadow, true)
                }
            }
            if (l.barEnabled) {
                drawBar(g, l.barX0, l.barY, l.barX1, pos / s.durationSec, cfg.progressBarColor, scrubbingNow)
            }
        }

        // transport buttons only appear while the pointer is over the card (chat / move screen)
        if (l.showButtons && mouseX >= 0 && s.active) {
            val lmx = (mouseX - l.x) / l.scale
            val lmy = (mouseY - l.y) / l.scale
            if (lmx >= 0 && lmx <= w && lmy >= 0 && lmy <= h) {
                val back = if (cfg.background) cfg.backgroundColor else 0xCC000000.toInt()
                roundFill(g, l.btnX - 3, l.btnY - 1, l.btnX + BTN_AREA + 3, l.btnY + BTN + 1, back)
                for (i in 0..2) {
                    val bx = l.btnX + i * BTN
                    val by = l.btnY
                    val hover = lmx >= bx && lmx < bx + BTN && lmy >= by && lmy < by + BTN
                    if (hover) roundFill(g, bx, by, bx + BTN, by + BTN, HOVER_COLOR)
                    drawIcon(g, HIT_PREV + i, s.playing, bx, by, WHITE)
                }
            }
        }
    }

    // ---------------------------------------------------------------- input

    fun hit(mx: Double, my: Double): Int {
        val l = lastLayout ?: return HIT_NONE
        val lx = (mx - l.x) / l.scale
        val ly = (my - l.y) / l.scale
        if (lx < 0 || ly < 0 || lx > l.w || ly > l.h) return HIT_NONE
        if (l.barEnabled && lx >= l.barX0 - 3 && lx <= l.barX1 + 3 && ly >= l.barY - 5 && ly <= l.barY + 6) {
            return HIT_BAR
        }
        if (l.showButtons && lx >= l.btnX && lx < l.btnX + BTN_AREA && ly >= l.btnY && ly < l.btnY + BTN) {
            val idx = ((lx - l.btnX) / BTN).toInt().coerceIn(0, 2)
            return HIT_PREV + idx
        }
        return HIT_BODY
    }

    fun perform(hit: Int) {
        when (hit) {
            HIT_PREV -> MediaBridge.send("prev")
            HIT_PLAY -> MediaBridge.send("toggle")
            HIT_NEXT -> MediaBridge.send("next")
        }
    }

    // ---------------------------------------------------------------- scrubbing

    @Volatile
    var scrubbing = false
        private set

    @Volatile
    private var scrubFrac = 0.0
    private var scrubStartedNanos = 0L

    private fun fracAt(mx: Double): Double {
        val l = lastLayout ?: return scrubFrac
        val span = (l.barX1 - l.barX0).toDouble()
        if (span <= 0.0) return scrubFrac
        val lx = (mx - l.x) / l.scale
        return ((lx - l.barX0) / span).coerceIn(0.0, 1.0)
    }

    fun beginScrub(mx: Double) {
        val l = lastLayout ?: return
        if (!l.barEnabled) return
        scrubbing = true
        scrubStartedNanos = System.nanoTime()
        scrubFrac = fracAt(mx)
    }

    fun dragScrub(mx: Double) {
        if (scrubbing) scrubFrac = fracAt(mx)
    }

    /** Commits the seek. Returns false if there was no scrub in progress. */
    fun endScrub(mx: Double): Boolean {
        if (!scrubbing) return false
        scrubbing = false
        val s = MediaBridge.state
        if (s.active && s.durationSec > 0.0) {
            MediaBridge.seek(fracAt(mx) * s.durationSec)
        }
        return true
    }

    fun cancelScrub() {
        scrubbing = false
    }

    /** Called every frame: drops a scrub that never got its mouse-release. */
    private fun scrubWatchdog() {
        if (scrubbing && System.nanoTime() - scrubStartedNanos > 60_000_000_000L) scrubbing = false
    }
}
