package io.github.mahjongapp.ui

import android.graphics.Matrix
import android.graphics.RectF

/**
 * Geometry of the table for a screen of [w] x [h] pixels, shared by [TableView] (drawing) and the
 * screen reader (recognition), so both always agree on where things are.
 *
 * Coordinates: the *layout* space is the table before projection (identical to the screen in 2D);
 * each seat has a *frame* rotated around the table centre (rel 0 = player, 1 = right, 2 = across,
 * 3 = left) in which its river grows "down" from the centre panel.
 */
class TableLayout(val w: Float, val h: Float, val perspective: Boolean, val tilt: Float = 38f) {
    /** Player's hand tile. */
    val tw = minOf(h / 8.2f, w / 19f)
    val th = tw * 1.36f
    val tableH = h - th * 1.25f
    /** Fixed screen unit for overlays. */
    val hud = minOf(tableH / 20f, tw * 0.62f)
    val lw = w
    /** In 2.5D the table is laid out taller, then projected (the tilt compresses it). */
    val lh = if (perspective) tableH * 1.45f else tableH
    /** River tile. Above the centre: panel half (3) + 4 river rows (5.4) + top hand (1.6) ≈ 10 widths. */
    val rw = minOf(lh / 20f, tw * if (perspective) 0.8f else 0.62f)
    val rh = rw * 1.36f
    val cs = rw * 6f
    val cx = lw / 2
    val cy = lh / 2 + rh * 0.2f

    /** Layout -> screen (identity in 2D). */
    val tableMatrix = Matrix().also { if (perspective) fitTable(it) }

    // rivers (frame coordinates)
    val riverLeft get() = -3 * rw
    val riverTop get() = cs / 2 + rw * 0.35f

    // opponents' concealed hands and melds (frame coordinates)
    val ow = rw * 0.95f
    val oh = ow * 1.36f
    fun reach(rel: Int) = when (rel) { 0 -> lh - cy; 2 -> cy; else -> lw / 2 }
    fun opponentRowY(rel: Int) = reach(rel) - oh - rw * 0.25f

    // player's own melds (screen coordinates, right edge)
    val mw = tw * 0.78f
    val mh = mw * 1.36f
    val ownMeldY = h - mh - th * 0.12f
    val ownMeldRight = w - tw * 0.3f
    val handY = h - th - th * 0.12f

    // dora indicators (screen coordinates)
    val doraW = hud * 0.85f
    val doraH = doraW * 1.36f
    val doraBox = RectF(hud * 1.9f, hud * 0.3f, hud * 1.9f + doraW * 5.4f, hud * 0.3f + doraH + hud * 0.75f)
    fun doraSlot(i: Int) = RectF(
        doraBox.left + hud * 0.2f + i * doraW, doraBox.top + hud * 0.65f,
        doraBox.left + hud * 0.2f + (i + 1) * doraW, doraBox.top + hud * 0.65f + doraH,
    )

    /** Frame of seat [rel] -> screen. */
    fun frameToScreen(rel: Int): Matrix = Matrix().apply {
        setRotate(-90f * rel)
        postTranslate(cx, cy)
        if (perspective) postConcat(tableMatrix)
    }

    /**
     * Perspective (homography) mapping the table rectangle onto a trapezoid that fills the screen
     * above the hand: the far edge is narrower, as seen from a seat at the table. Only the table
     * down to just below the player's river is projected (nearer parts would be empty space).
     */
    private fun fitTable(m: Matrix) {
        val top = hud * 0.15f
        val bottom = tableH + th * 0.35f
        val nearW = w * 1.04f
        val farW = nearW * (1f - tilt / 100f)
        val nearY = minOf(lh, cy + cs / 2 + rw * 0.35f + 4 * rh + rw * 0.6f)
        val src = floatArrayOf(0f, 0f, lw, 0f, lw, nearY, 0f, nearY)
        val dst = floatArrayOf(
            (w - farW) / 2, top, (w + farW) / 2, top,
            (w + nearW) / 2, bottom, (w - nearW) / 2, bottom,
        )
        m.setPolyToPoly(src, 0, dst, 0, 4)
    }
}
