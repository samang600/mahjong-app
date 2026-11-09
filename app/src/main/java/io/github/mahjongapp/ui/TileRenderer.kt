package io.github.mahjongapp.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import io.github.mahjongapp.core.Tiles

/** Draws mahjong tiles with plain Canvas shapes and CJK glyphs (no image assets). */
class TileRenderer(private val redFives: () -> Boolean) {

    private val face = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xFB, 0xF8, 0xF0) }
    private val side = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xD9, 0xCF, 0xB8) }
    private val back = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xE0, 0x8A, 0x2E) }
    private val backSide = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xB8, 0x6A, 0x1E) }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.argb(90, 0, 0, 0) }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 0, 0, 0) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    }
    private val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 0, 0, 0) }
    private val r = RectF()
    private val stickRect = RectF()

    companion object {
        val BLUE = Color.rgb(0x15, 0x65, 0xC0)
        val RED = Color.rgb(0xC6, 0x28, 0x28)
        val GREEN = Color.rgb(0x2E, 0x7D, 0x32)
        val BLACK = Color.rgb(0x21, 0x21, 0x21)
        private val KANJI_NUM = arrayOf("一", "二", "三", "四", "五", "六", "七", "八", "九")
        private val HONOR = arrayOf("東", "南", "西", "北", "", "發", "中")
    }

    /**
     * Draws tile [id] (or a face-down tile when [faceDown]) in the axis-aligned box (x, y, w, h)
     * rotated by [rotation] degrees (0, 90, 180, 270) about the box centre. For 90/270 pass the
     * box of the rotated tile (w > h).
     */
    fun draw(
        canvas: Canvas, id: Int, x: Float, y: Float, w: Float, h: Float,
        rotation: Int = 0, faceDown: Boolean = false, highlightColor: Int = 0, dimmed: Boolean = false,
        depth: Float = 0f,
        worldRotation: Float? = null,
    ) {
        canvas.save()
        val cx = x + w / 2; val cy = y + h / 2
        canvas.translate(cx, cy)
        canvas.rotate(rotation.toFloat())
        val tw = if (rotation % 180 == 0) w else h
        val th = if (rotation % 180 == 0) h else w
        val rad = tw * 0.12f
        val d = if (depth > 0f) depth else th * 0.08f
        val faceBottom: Float
        if (worldRotation == null) {
            // 2D: thickness shown as a darker strip below the face.
            r.set(-tw / 2 + d * 0.3f, -th / 2 + d * 0.6f, tw / 2 + d * 0.3f, th / 2 + d * 0.6f)
            canvas.drawRoundRect(r, rad, rad, shadow)
            r.set(-tw / 2, -th / 2 + d, tw / 2, th / 2)
            canvas.drawRoundRect(r, rad, rad, if (faceDown) backSide else side)
            faceBottom = th / 2 - d
        } else {
            // 2.5D: a tile lying on the table shows its edge towards the viewer (world +y). In this
            // tile's local axes that direction is (sin R, cos R), R = total rotation from world.
            val a = Math.toRadians(worldRotation.toDouble() + rotation)
            val dx = (Math.sin(a) * d).toFloat(); val dy = (Math.cos(a) * d).toFloat()
            r.set(-tw / 2 + dx * 1.6f, -th / 2 + dy * 1.6f, tw / 2 + dx * 1.6f, th / 2 + dy * 1.6f)
            canvas.drawRoundRect(r, rad, rad, shadow)
            r.set(-tw / 2 + dx, -th / 2 + dy, tw / 2 + dx, th / 2 + dy)
            canvas.drawRoundRect(r, rad, rad, if (faceDown) backSide else side)
            faceBottom = th / 2
        }
        r.set(-tw / 2, -th / 2, tw / 2, faceBottom)
        if (faceDown) {
            // Plain back: one colour, no pattern (the darker body below shows the thickness).
            canvas.drawRoundRect(r, rad, rad, back)
        } else {
            canvas.drawRoundRect(r, rad, rad, face)
            outline.strokeWidth = maxOf(1f, tw * 0.02f)
            canvas.drawRoundRect(r, rad, rad, outline)
            drawFace(canvas, id, RectF(r.left + tw * 0.08f, r.top + tw * 0.08f, r.right - tw * 0.08f, r.bottom - tw * 0.08f))
            if (dimmed) canvas.drawRoundRect(r, rad, rad, dim)
        }
        if (highlightColor != 0) {
            highlight.color = highlightColor
            highlight.strokeWidth = tw * 0.07f
            r.set(-tw / 2, -th / 2, tw / 2, faceBottom)
            canvas.drawRoundRect(r, rad, rad, highlight)
        }
        canvas.restore()
    }

    private fun drawFace(c: Canvas, id: Int, f: RectF) {
        val kind = Tiles.kind(id)
        val red = Tiles.isRed(id, redFives())
        when {
            kind < 9 -> {
                text.color = if (red) RED else BLACK
                text.textSize = f.height() * 0.42f
                c.drawText(KANJI_NUM[kind], f.centerX(), f.top + f.height() * 0.42f, text)
                text.color = RED
                text.textSize = f.height() * 0.44f
                c.drawText("萬", f.centerX(), f.top + f.height() * 0.93f, text)
            }
            kind < 18 -> drawPin(c, kind - 9 + 1, f, red)
            kind < 27 -> drawSou(c, kind - 18 + 1, f, red)
            kind == Tiles.HAKU -> {
                outline.strokeWidth = f.width() * 0.08f
                val inner = RectF(f.left + f.width() * 0.12f, f.top + f.height() * 0.14f, f.right - f.width() * 0.12f, f.bottom - f.height() * 0.14f)
                val old = outline.color
                outline.color = BLUE
                c.drawRoundRect(inner, f.width() * 0.08f, f.width() * 0.08f, outline)
                outline.color = old
            }
            else -> {
                text.color = when (kind) { Tiles.HATSU -> GREEN; Tiles.CHUN -> RED; else -> BLACK }
                text.textSize = f.height() * 0.62f
                c.drawText(HONOR[kind - 27], f.centerX(), f.centerY() + text.textSize * 0.36f, text)
            }
        }
    }

    private fun dot(c: Canvas, f: RectF, x: Float, y: Float, rr: Float, color: Int) {
        val px = f.left + x * f.width(); val py = f.top + y * f.height(); val rad = rr * f.width()
        fill.color = color
        c.drawCircle(px, py, rad, fill)
        fill.color = Color.WHITE
        c.drawCircle(px, py, rad * 0.45f, fill)
        fill.color = color
        c.drawCircle(px, py, rad * 0.22f, fill)
    }

    private fun drawPin(c: Canvas, n: Int, f: RectF, red: Boolean) {
        val b = BLUE; val g = GREEN; val rd = RED
        when (n) {
            1 -> dot(c, f, .5f, .5f, .40f, rd)
            2 -> { dot(c, f, .5f, .27f, .2f, g); dot(c, f, .5f, .73f, .2f, b) }
            3 -> { dot(c, f, .25f, .2f, .17f, b); dot(c, f, .5f, .5f, .17f, rd); dot(c, f, .75f, .8f, .17f, g) }
            4 -> { for ((x, y) in listOf(.28f to .27f, .72f to .73f)) dot(c, f, x, y, .18f, b); for ((x, y) in listOf(.72f to .27f, .28f to .73f)) dot(c, f, x, y, .18f, g) }
            5 -> {
                for ((x, y) in listOf(.26f to .22f, .74f to .78f)) dot(c, f, x, y, .16f, b)
                for ((x, y) in listOf(.74f to .22f, .26f to .78f)) dot(c, f, x, y, .16f, g)
                dot(c, f, .5f, .5f, .16f, rd)
                if (red) for ((x, y) in listOf(.26f to .22f, .74f to .78f, .74f to .22f, .26f to .78f)) dot(c, f, x, y, .16f, rd)
            }
            6 -> { for (x in listOf(.3f, .7f)) { dot(c, f, x, .18f, .15f, g); dot(c, f, x, .52f, .15f, rd); dot(c, f, x, .82f, .15f, rd) } }
            7 -> {
                dot(c, f, .22f, .13f, .12f, g); dot(c, f, .5f, .24f, .12f, g); dot(c, f, .78f, .35f, .12f, g)
                for (x in listOf(.3f, .7f)) { dot(c, f, x, .6f, .13f, rd); dot(c, f, x, .85f, .13f, rd) }
            }
            8 -> for (x in listOf(.3f, .7f)) for (y in listOf(.13f, .38f, .63f, .87f)) dot(c, f, x, y, .13f, b)
            9 -> for ((i, x) in listOf(.2f, .5f, .8f).withIndex()) for (y in listOf(.17f, .5f, .83f)) dot(c, f, x, y, .13f, if (i == 1) rd else b)
        }
    }

    private fun stick(c: Canvas, f: RectF, x: Float, y: Float, hh: Float, color: Int) {
        val px = f.left + x * f.width(); val py = f.top + y * f.height()
        val sw = f.width() * 0.11f; val sh = f.height() * hh
        fill.color = color
        // Own rectangle: `r` holds the face outline, still needed after the face is drawn (dimming).
        stickRect.set(px - sw / 2, py - sh / 2, px + sw / 2, py + sh / 2)
        c.drawRoundRect(stickRect, sw / 2, sw / 2, fill)
        fill.color = Color.WHITE
        c.drawRect(px - sw / 2, py - sh * 0.04f, px + sw / 2, py + sh * 0.04f, fill)
    }

    private fun drawSou(c: Canvas, n: Int, f: RectF, red: Boolean) {
        val g = GREEN; val rd = RED; val b = BLUE
        val col = if (red) rd else g
        when (n) {
            1 -> {
                // A stylised bird.
                fill.color = g
                c.drawOval(RectF(f.left + f.width() * .2f, f.top + f.height() * .28f, f.right - f.width() * .2f, f.bottom - f.height() * .2f), fill)
                fill.color = rd
                c.drawCircle(f.centerX() + f.width() * .12f, f.top + f.height() * .24f, f.width() * .13f, fill)
                fill.color = b
                c.drawCircle(f.centerX() + f.width() * .14f, f.top + f.height() * .22f, f.width() * .04f, fill)
            }
            2 -> { stick(c, f, .5f, .27f, .4f, g); stick(c, f, .5f, .73f, .4f, b) }
            3 -> { stick(c, f, .5f, .27f, .4f, g); stick(c, f, .28f, .73f, .4f, b); stick(c, f, .72f, .73f, .4f, b) }
            4 -> for (x in listOf(.3f, .7f)) { stick(c, f, x, .27f, .4f, g); stick(c, f, x, .73f, .4f, b) }
            5 -> { for (x in listOf(.22f, .78f)) { stick(c, f, x, .27f, .4f, col); stick(c, f, x, .73f, .4f, col) }; stick(c, f, .5f, .5f, .4f, rd) }
            6 -> for (x in listOf(.2f, .5f, .8f)) { stick(c, f, x, .27f, .4f, g); stick(c, f, x, .73f, .4f, g) }
            7 -> { stick(c, f, .5f, .17f, .28f, rd); for (x in listOf(.2f, .5f, .8f)) { stick(c, f, x, .5f, .28f, g); stick(c, f, x, .83f, .28f, g) } }
            8 -> for (x in listOf(.14f, .38f, .62f, .86f)) { stick(c, f, x, .27f, .4f, g); stick(c, f, x, .73f, .4f, g) }
            9 -> for ((i, x) in listOf(.2f, .5f, .8f).withIndex()) for (y in listOf(.17f, .5f, .83f)) stick(c, f, x, y, .28f, if (i == 1) rd else g)
        }
    }
}
