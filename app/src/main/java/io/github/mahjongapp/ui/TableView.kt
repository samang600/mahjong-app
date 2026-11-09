package io.github.mahjongapp.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import io.github.mahjongapp.core.Game
import io.github.mahjongapp.core.Meld
import io.github.mahjongapp.core.MeldType
import io.github.mahjongapp.core.Tiles

/**
 * The table, seen from the human player (seat 0, bottom). Opponents are drawn in frames rotated
 * around the table centre: right = seat 1, top = seat 2, left = seat 3.
 *
 * In [perspective] (2.5D) mode the table layer is tilted with a camera transform and fitted above
 * the player's hand; tiles on the table show their thickness towards the viewer, and the player's
 * own hand stays upright in front.
 */
class TableView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var game: Game? = null
        set(v) { field = v; invalidate() }
    val humanSeat = 0
    var selected = -1
        set(v) { field = v; invalidate() }
    var hint = -1
        set(v) { field = v; invalidate() }
    /** Ids that may be discarded now (others are dimmed); empty = not the player's turn. */
    var allowed: Set<Int> = emptySet()
        set(v) { field = v; invalidate() }
    /** Show opponents' hands face up (after a round). */
    var revealAll = false
        set(v) { field = v; invalidate() }
    var onTileTap: ((Int) -> Unit)? = null
    var tileNames: (Int) -> String = { Tiles.name(it) }
    var perspective = false
        set(v) { field = v; invalidate() }
    /** How much narrower the far edge of the table is, in percent (2.5D view). */
    var tilt = 38f

    val renderer = TileRenderer { game?.rules?.redFives ?: true }

    private val bg = Paint()
    private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 0x10, 0x18, 0x20) }
    private val panelEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xCF, 0xD8, 0xDC); textAlign = Paint.Align.LEFT }
    private val stick = Paint(Paint.ANTI_ALIAS_FLAG)
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xFF, 0xC1, 0x07) }
    private val turnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xFF, 0xC1, 0x07) }

    private val handRects = ArrayList<Pair<Int, RectF>>()

    // geometry (computed per draw)
    private var tw = 0f; private var th = 0f // own hand tile
    private var rw = 0f; private var rh = 0f // river tile
    private var cx = 0f; private var cy = 0f // table centre
    private var cs = 0f // centre panel side
    /** Fixed screen unit for overlays (same as the 2D river tile). */
    private var hud = 0f
    /** Rotation of the current drawing frame relative to the table (for 2.5D tile edges). */
    private var frameRot = 0f
    private val tableMatrix = android.graphics.Matrix()
    private val felt = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val g = game ?: return
        val w = width.toFloat(); val h = height.toFloat()
        bg.shader = RadialGradient(w / 2, h * 0.45f, maxOf(w, h) * 0.7f, Color.rgb(0x1E, 0x5E, 0x8C), Color.rgb(0x0B, 0x24, 0x3A), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, bg)

        tw = minOf(h / 8.2f, w / 19f); th = tw * 1.36f
        val tableH = h - th * 1.25f
        // Layout space of the table layer. In 2.5D it is laid out taller and then projected (the tilt
        // compresses it vertically) and fitted into the screen above the hand.
        hud = minOf(tableH / 20f, tw * 0.62f)
        val lw = w
        val lh = if (perspective) tableH * 1.45f else tableH
        // Above the centre: panel half (3) + 4 river rows (5.4) + top hand (1.6) ≈ 10 river widths.
        rw = minOf(lh / 20f, tw * if (perspective) 0.8f else 0.62f); rh = rw * 1.36f
        cs = rw * 6f
        cx = lw / 2; cy = lh / 2 + rh * 0.2f

        canvas.save()
        if (perspective) {
            fitTableMatrix(lw, lh, w, tableH)
            canvas.concat(tableMatrix)
            drawTableSurface(canvas, lw, lh)
        }
        // Opponents and own river, each in its rotated frame.
        for (rel in 0 until 4) {
            val seat = (humanSeat + rel) % 4
            canvas.save()
            canvas.translate(cx, cy)
            canvas.rotate(-90f * rel)
            frameRot = -90f * rel
            // Distance from the table centre to this side's edge (the centre sits above mid-screen).
            val reach = when (rel) { 0 -> lh - cy; 2 -> cy; else -> lw / 2 }
            drawRiver(canvas, g, seat)
            if (rel != 0) drawOpponentHand(canvas, g, seat, reach)
            drawPanelSide(canvas, g, seat)
            canvas.restore()
        }
        frameRot = 0f
        drawPanelCenter(canvas, g)
        canvas.restore()
        drawOwnHand(canvas, g, w, h)
        drawDoraHud(canvas, g)
    }

    /**
     * Perspective (homography) mapping the table rectangle onto a trapezoid that fills the screen
     * above the hand: the far edge is narrower, as seen from a seat at the table.
     */
    private fun fitTableMatrix(lw: Float, lh: Float, w: Float, tableH: Float) {
        val top = hud * 0.15f
        val bottom = tableH + th * 0.35f
        val nearW = w * 1.04f
        val farW = nearW * (1f - tilt / 100f)
        // Project only down to just below the player's own river: the table nearer than that would
        // be magnified into empty space above the hand.
        val nearY = minOf(lh, cy + cs / 2 + rw * 0.35f + 4 * rh + rw * 0.6f)
        val src = floatArrayOf(0f, 0f, lw, 0f, lw, nearY, 0f, nearY)
        val dst = floatArrayOf(
            (w - farW) / 2, top, (w + farW) / 2, top,
            (w + nearW) / 2, bottom, (w - nearW) / 2, bottom,
        )
        tableMatrix.setPolyToPoly(src, 0, dst, 0, 4)
    }

    private fun drawTableSurface(c: Canvas, lw: Float, lh: Float) {
        val pad = rw * 0.2f
        rim.color = Color.rgb(0x4E, 0x34, 0x2E)
        c.drawRoundRect(RectF(-pad * 3, -pad * 3, lw + pad * 3, lh + pad * 3), rw, rw, rim)
        felt.shader = RadialGradient(cx, cy, maxOf(lw, lh) * 0.7f, Color.rgb(0x22, 0x6A, 0x9A), Color.rgb(0x10, 0x3A, 0x5C), Shader.TileMode.CLAMP)
        c.drawRoundRect(RectF(-pad, -pad, lw + pad, lh + pad), rw * 0.6f, rw * 0.6f, felt)
    }

    /** Draws a tile on the table in the current frame (thickness towards the viewer in 2.5D). */
    private fun tile(
        c: Canvas, id: Int, x: Float, y: Float, w: Float, h: Float,
        rotation: Int = 0, faceDown: Boolean = false, dimmed: Boolean = false, depth: Float = 0f,
    ) {
        if (perspective) {
            val d = (if (rotation % 180 == 0) w else h) * 0.2f
            renderer.draw(c, id, x, y, w, h, rotation, faceDown, dimmed = dimmed, depth = d, worldRotation = frameRot)
        } else {
            renderer.draw(c, id, x, y, w, h, rotation, faceDown, dimmed = dimmed, depth = depth)
        }
    }

    // ------------------------------------------------------------------ rivers

    private fun drawRiver(c: Canvas, g: Game, seat: Int) {
        val p = g.players[seat]
        val left = -3 * rw
        val top = cs / 2 + rw * 0.35f
        var row = 0; var x = left
        val lastDiscard = g.events.lastOrNull { it is io.github.mahjongapp.core.GameEvent.Discard } as? io.github.mahjongapp.core.GameEvent.Discard
        for ((i, t) in p.river.withIndex()) {
            val r = minOf(i / 6, 3)
            if (r != row) { row = r; x = left }
            val y = top + row * rh
            if (t.riichi) {
                tile(c, t.id, x, y + (rh - rw), rh, rw, rotation = 90, dimmed = t.calledAway, depth = rw * 0.06f)
                x += rh
            } else {
                tile(c, t.id, x, y, rw, rh, dimmed = t.calledAway, depth = rw * 0.08f)
                if (lastDiscard != null && lastDiscard.seat == seat && i == p.river.size - 1 && !t.calledAway) {
                    c.drawCircle(x + rw / 2, y + rh + rw * 0.18f, rw * 0.12f, marker)
                }
                x += rw
            }
        }
        // Riichi stick in front of the river.
        if (p.riichi > 0) {
            stick.color = Color.WHITE
            val sy = cs / 2 + rw * 0.12f
            c.drawRoundRect(RectF(-rw * 2f, sy - rw * 0.07f, rw * 2f, sy + rw * 0.07f), rw * 0.07f, rw * 0.07f, stick)
            stick.color = TileRenderer.RED
            c.drawCircle(0f, sy, rw * 0.06f, stick)
        }
    }

    // ------------------------------------------------------------------ hands and melds

    private fun drawOpponentHand(c: Canvas, g: Game, seat: Int, reach: Float) {
        val p = g.players[seat]
        val ow = rw * 0.95f; val oh = ow * 1.36f
        val y = reach - oh - rw * 0.25f
        val meldW = p.melds.sumOf { meldWidth(it, ow, oh).toDouble() }.toFloat() + p.melds.size * ow * 0.2f
        val n = p.hand.size
        val total = n * ow + (if (meldW > 0) ow * 0.5f + meldW else 0f)
        var x = -total / 2
        val reveal = revealAll
        for (id in p.hand.sortedBy { Tiles.kind(it) }) {
            tile(c, id, x, y, ow, oh, faceDown = !reveal)
            x += ow
        }
        x += ow * 0.5f
        for (m in p.melds) x = drawMeld(c, m, seat, x, y, ow, oh) + ow * 0.2f
    }

    private fun meldWidth(m: Meld, w: Float, h: Float): Float = when (m.type) {
        MeldType.ANKAN -> 4 * w
        MeldType.MINKAN -> 3 * w + h
        else -> 2 * w + h
    }

    /** Draws a meld left-to-right from x (frame coordinates); returns the x after it. */
    private fun drawMeld(c: Canvas, m: Meld, seat: Int, x0: Float, y: Float, w: Float, h: Float): Float {
        var x = x0
        if (m.type == MeldType.ANKAN) {
            for ((i, t) in m.tiles.withIndex()) {
                tile(c, t, x, y, w, h, faceDown = i == 0 || i == 3)
                x += w
            }
            return x
        }
        val rel = (m.from - seat + 4) % 4 // 3 = left, 2 = across, 1 = right
        val sidewaysIdx = when (rel) { 3 -> 0; 2 -> 1; else -> if (m.type == MeldType.MINKAN) 3 else 2 }
        val others = m.tiles.filter { it != m.called }.take(if (m.type == MeldType.CHI || m.type == MeldType.PON) 2 else 3)
        val added = if (m.type == MeldType.KAKAN) m.tiles.last() else -1
        val order = ArrayList<Int>(others.filter { it != added })
        order.add(sidewaysIdx.coerceAtMost(order.size), m.called)
        for (t in order) {
            if (t == m.called) {
                tile(c, t, x, y + (h - w), h, w, rotation = 90)
                if (added >= 0) tile(c, added, x, y + (h - w) - w, h, w, rotation = 90)
                x += h
            } else {
                tile(c, t, x, y, w, h)
                x += w
            }
        }
        return x
    }

    private fun drawOwnHand(c: Canvas, g: Game, w: Float, h: Float) {
        handRects.clear()
        val p = g.players[humanSeat]
        val y = h - th - th * 0.12f
        val drawn = g.drawnTile.takeIf { g.turnSeat == humanSeat }
        val hand = p.hand.filter { it != drawn }.sortedWith(compareBy({ Tiles.kind(it) }, { it }))
        var x = tw * 0.5f
        fun put(id: Int) {
            val raised = if (id == selected) th * 0.18f else 0f
            val canDiscard = allowed.isEmpty() || id in allowed
            val hl = when {
                id == hint -> Color.rgb(0x66, 0xFF, 0x66)
                id == selected -> Color.rgb(0xFF, 0xC1, 0x07)
                else -> 0
            }
            renderer.draw(c, id, x, y - raised, tw, th, highlightColor = hl, dimmed = allowed.isNotEmpty() && !canDiscard,
                depth = th * if (perspective) 0.16f else 0.09f)
            handRects += id to RectF(x, y - raised, x + tw, y + th)
            x += tw
        }
        for (id in hand) put(id)
        if (drawn != null && drawn in p.hand) { x += tw * 0.35f; put(drawn) }
        // Own melds at the right edge.
        val mw = tw * 0.78f; val mh = mw * 1.36f
        var mx = w - tw * 0.3f - p.melds.sumOf { (meldWidth(it, mw, mh) + mw * 0.2f).toDouble() }.toFloat()
        for (m in p.melds) mx = drawMeld(c, m, humanSeat, mx, h - mh - th * 0.12f, mw, mh) + mw * 0.2f
    }

    // ------------------------------------------------------------------ centre

    private fun drawPanelSide(c: Canvas, g: Game, seat: Int) {
        // Seat wind and score along this side of the centre panel (frame: this side is "down").
        val p = g.players[seat]
        val windChar = "東南西北"[g.seatWind(seat) - Tiles.EAST].toString()
        text.textSize = rw * 0.42f
        text.color = if (seat == g.dealer) Color.rgb(0xFF, 0x8A, 0x65) else Color.WHITE
        c.drawText("$windChar  ${p.score}", 0f, cs / 2 - rw * 0.28f, text)
        if (seat == g.turnSeat) {
            c.drawRoundRect(RectF(-cs * 0.32f, cs / 2 - rw * 0.16f, cs * 0.32f, cs / 2 - rw * 0.07f), 4f, 4f, turnPaint)
        }
    }

    private fun drawPanelCenter(c: Canvas, g: Game) {
        val half = cs / 2 - rw * 0.02f
        val rect = RectF(cx - half, cy - half, cx + half, cy + half)
        panelEdge.color = Color.argb(160, 0x90, 0xA4, 0xAE)
        panelEdge.strokeWidth = rw * 0.05f
        c.drawRoundRect(RectF(rect.left + cs * 0.17f, rect.top + cs * 0.17f, rect.right - cs * 0.17f, rect.bottom - cs * 0.17f), rw * 0.2f, rw * 0.2f, panel)
        c.drawRoundRect(rect, rw * 0.25f, rw * 0.25f, panelEdge)
        val wind = "東南西北"[g.roundWind].toString()
        text.color = Color.WHITE
        text.textSize = rw * 0.5f
        c.drawText("$wind${g.kyoku + 1}局", cx, cy - rw * 0.25f, text)
        text.textSize = rw * 0.32f
        text.color = Color.rgb(0xB0, 0xBE, 0xC5)
        c.drawText("${g.honba}本場  供託${g.riichiSticks}", cx, cy + rw * 0.2f, text)
        text.color = Color.rgb(0x80, 0xDE, 0xEA)
        text.textSize = rw * 0.4f
        c.drawText("× ${g.wallRemaining}", cx, cy + rw * 0.7f, text)
    }

    private fun drawDoraHud(c: Canvas, g: Game) {
        val dw = hud * 0.85f; val dh = dw * 1.36f
        // Right of the left opponent's hand column.
        val x0 = hud * 1.9f
        val box = RectF(x0, hud * 0.3f, x0 + dw * 5.4f, hud * 0.3f + dh + hud * 0.75f)
        c.drawRoundRect(box, hud * 0.2f, hud * 0.2f, panel)
        small.textSize = hud * 0.38f
        c.drawText(context.getString(io.github.mahjongapp.R.string.dora), box.left + hud * 0.2f, box.top + hud * 0.5f, small)
        for (i in 0 until 5) {
            val id = g.doraIndicators.getOrNull(i)
            renderer.draw(c, id ?: 0, box.left + hud * 0.2f + i * dw, box.top + hud * 0.65f, dw, dh, faceDown = id == null)
        }
    }

    // ------------------------------------------------------------------ input

    /** Screen rectangle of a tile in the player's hand (for tests and accessibility). */
    fun handTileRect(id: Int): RectF? = handRects.lastOrNull { it.first == id }?.second

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) return true
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            val hit = handRects.lastOrNull { it.second.contains(e.x, e.y) }?.first
            if (hit != null) {
                performClick()
                onTileTap?.invoke(hit)
            }
            return true
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean = super.performClick()
}
