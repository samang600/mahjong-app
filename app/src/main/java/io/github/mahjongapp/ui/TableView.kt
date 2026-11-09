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
 * The 2D table, seen from the human player (seat 0, bottom). Opponents are drawn in frames rotated
 * around the table centre: right = seat 1, top = seat 2, left = seat 3.
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

    override fun onDraw(canvas: Canvas) {
        val g = game ?: return
        val w = width.toFloat(); val h = height.toFloat()
        bg.shader = RadialGradient(w / 2, h * 0.45f, maxOf(w, h) * 0.7f, Color.rgb(0x1E, 0x5E, 0x8C), Color.rgb(0x0B, 0x24, 0x3A), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, bg)

        tw = minOf(h / 8.2f, w / 19f); th = tw * 1.36f
        val tableH = h - th * 1.25f
        // Above the centre: panel half (3) + 4 river rows (5.4) + top hand (1.6) ≈ 10 river widths.
        rw = minOf(tableH / 20f, tw * 0.62f); rh = rw * 1.36f
        cs = rw * 6f
        cx = w / 2; cy = tableH / 2 + rh * 0.2f

        // Opponents and own river, each in its rotated frame.
        for (rel in 0 until 4) {
            val seat = (humanSeat + rel) % 4
            canvas.save()
            canvas.translate(cx, cy)
            canvas.rotate(-90f * rel)
            // Distance from the table centre to this side's screen edge (the centre sits above mid-screen).
            val reach = when (rel) { 0 -> h - cy; 2 -> cy; else -> w / 2 }
            drawRiver(canvas, g, seat)
            if (rel != 0) drawOpponentHand(canvas, g, seat, reach)
            drawPanelSide(canvas, g, seat)
            canvas.restore()
        }
        drawPanelCenter(canvas, g)
        drawOwnHand(canvas, g, w, h)
        drawDoraHud(canvas, g)
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
                renderer.draw(c, t.id, x, y + (rh - rw), rh, rw, rotation = 90, dimmed = t.calledAway, depth = rw * 0.06f)
                x += rh
            } else {
                renderer.draw(c, t.id, x, y, rw, rh, dimmed = t.calledAway, depth = rw * 0.08f)
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
            renderer.draw(c, id, x, y, ow, oh, faceDown = !reveal)
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
                renderer.draw(c, t, x, y, w, h, faceDown = i == 0 || i == 3)
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
                renderer.draw(c, t, x, y + (h - w), h, w, rotation = 90)
                if (added >= 0) renderer.draw(c, added, x, y + (h - w) - w, h, w, rotation = 90)
                x += h
            } else {
                renderer.draw(c, t, x, y, w, h)
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
            renderer.draw(c, id, x, y - raised, tw, th, highlightColor = hl, dimmed = allowed.isNotEmpty() && !canDiscard, depth = th * 0.09f)
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
        val dw = rw * 0.85f; val dh = dw * 1.36f
        // Right of the left opponent's hand column.
        val x0 = rw * 1.9f
        val box = RectF(x0, rw * 0.3f, x0 + dw * 5.4f, rw * 0.3f + dh + rw * 0.75f)
        c.drawRoundRect(box, rw * 0.2f, rw * 0.2f, panel)
        small.textSize = rw * 0.38f
        c.drawText(context.getString(io.github.mahjongapp.R.string.dora), box.left + rw * 0.2f, box.top + rw * 0.5f, small)
        for (i in 0 until 5) {
            val id = g.doraIndicators.getOrNull(i)
            renderer.draw(c, id ?: 0, box.left + rw * 0.2f + i * dw, box.top + rw * 0.65f, dw, dh, faceDown = id == null)
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
