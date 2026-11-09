package io.github.mahjongapp.assist

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import io.github.mahjongapp.core.Tiles
import io.github.mahjongapp.ui.TableLayout
import io.github.mahjongapp.ui.TileRenderer
import kotlin.math.roundToInt

/** One river as read from the screen. */
data class SeenRiver(val kinds: List<Int>, val calledAway: List<Boolean>, val riichiIndex: Int)

/** Everything read from one screenshot of this app. */
data class ScreenState(
    val perspective: Boolean,
    val hand: SeenHand,
    val doraIndicators: List<Int>,
    /** Rivers by seat relative to the player: 0 = own, 1 = right, 2 = across, 3 = left. */
    val rivers: List<SeenRiver>,
    /** Called sets (kinds) by relative seat (0 = own). */
    val melds: List<List<List<Int>>>,
)

/**
 * Reads the whole table from a screenshot of this app: the player's hand ([HandRecognizer]),
 * dora indicators, all four rivers (with riichi tiles drawn sideways and dimmed tiles taken by a
 * call) and every player's called sets, in the 2D and 2.5D views.
 *
 * The table's geometry is shared with the view ([TableLayout]), so the reader knows where each
 * river slot, meld row and dora slot lies; at each place it decides from the pixels whether a tile
 * is there, how it is oriented and which tile it is (template matching through the perspective
 * mapping of that spot).
 */
class ScreenReader {

    private val handRecognizer = HandRecognizer()
    private val renderer = TileRenderer { true }

    private class Template(val kind: Int, val red: Boolean, val px: FloatArray)
    private class Sample(val px: FloatArray, val cream: Float, val bright: Float)
    private data class Match(val kind: Int, val red: Boolean, val distance: Float, val cream: Float, val dimmed: Boolean, val bright: Float = 0f)

    private enum class Style { RIVER_2D, PLAIN_2D, TABLE_25D }

    /** Templates per (style, tile width in px): text glyphs (kanji, honors) do not scale exactly with
     *  font hinting, so templates are drawn at the size the tile has on the table. */
    private val templates = HashMap<Triple<Style, Int, Boolean>, List<Template>>()

    companion object {
        private const val SW = 16
        private const val SH = 20
        private const val U0 = 0.12f; private const val U1 = 0.88f
        private const val V0 = 0.09f; private const val V1 = 0.68f
        private const val PRESENT = 0.45f
    }

    // ------------------------------------------------------------------ entry point

    fun read(bmp: Bitmap): ScreenState? {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val hand = handRecognizer.recognize(bmp) ?: return null
        val perspective = detectPerspective(px, w, h)
        val L = TableLayout(w.toFloat(), h.toFloat(), perspective)
        val img = Img(px, w, h)

        val dora = ArrayList<Int>()
        for (i in 0 until 5) {
            val slot = L.doraSlot(i)
            val m = classify(img, upright(slot.left, slot.top, slot.width(), slot.height(), Matrix()), Style.PLAIN_2D, slot.width())
            if (m.cream < PRESENT) break
            dora += m.kind
        }

        val tableStyle = if (perspective) Style.TABLE_25D else Style.RIVER_2D
        val meldStyle = if (perspective) Style.TABLE_25D else Style.PLAIN_2D
        val rivers = (0 until 4).map { rel -> readRiver(img, L, rel, tableStyle) }
        val melds = ArrayList<List<List<Int>>>()
        // Own melds: flat at the bottom right, to the right of the hand.
        val handRight = hand.tiles.maxOf { it.rect.right }.toFloat()
        melds += readMeldRow(img, Matrix(), L.ownMeldY, handRight + L.tw * 0.3f, w.toFloat(), L.mw, L.mh, meldStyle)
        for (rel in 1..3) {
            // A row holds at most 14 tiles' width plus four melds, centred on the seat.
            val reachX = L.ow * 12f
            val bottom = android.graphics.RectF(0f, L.handY - L.th * 0.2f, w.toFloat(), h.toFloat())
            melds += readMeldRow(img, L.frameToScreen(rel), L.opponentRowY(rel), -reachX, reachX, L.ow, L.oh, meldStyle,
                exclude = listOf(L.doraBox, bottom), tilesSeparated = perspective && rel == 1)
        }
        return ScreenState(perspective, hand, dora, rivers, melds)
    }

    /** The 2.5D view has a wooden rim around the felt; the 2D view has none. */
    private fun detectPerspective(px: IntArray, w: Int, h: Int): Boolean {
        var n = 0
        for (y in 0 until (h * 0.75f).toInt() step 3) for (x in 0 until w step 3) {
            val c = px[y * w + x]
            if (kotlin.math.abs(Color.red(c) - 0x4E) < 8 && kotlin.math.abs(Color.green(c) - 0x34) < 8 && kotlin.math.abs(Color.blue(c) - 0x2E) < 8) n++
        }
        return n > 150
    }

    // ------------------------------------------------------------------ geometry helpers

    private class Img(val px: IntArray, val w: Int, val h: Int)

    /** Tile-local unit square (upright tile) -> frame box (x, y, bw, bh), then -> screen via [frame]. */
    private fun upright(x: Float, y: Float, bw: Float, bh: Float, frame: Matrix) = Matrix().apply {
        setScale(bw, bh)
        postTranslate(x, y)
        postConcat(frame)
    }

    /** A tile drawn rotated 90 degrees in the box (x, y, bw = tile height, bh = tile width). */
    private fun sideways(x: Float, y: Float, bw: Float, bh: Float, frame: Matrix) = Matrix().apply {
        val tw = bh; val th = bw
        setScale(tw, th)
        postTranslate(-tw / 2, -th / 2)
        postRotate(90f)
        postTranslate(x + bw / 2, y + bh / 2)
        postConcat(frame)
    }

    private fun sample(img: Img, m: Matrix): Sample {
        val pts = FloatArray(SW * SH * 4 * 2)
        var k = 0
        for (j in 0 until SH) for (i in 0 until SW) for (sj in 0..1) for (si in 0..1) {
            pts[k++] = U0 + (i + (si + 0.5f) / 2f) / SW * (U1 - U0)
            pts[k++] = V0 + (j + (sj + 0.5f) / 2f) / SH * (V1 - V0)
        }
        m.mapPoints(pts)
        val out = FloatArray(SW * SH * 3)
        var cream = 0; var total = 0
        val lum = FloatArray(SW * SH)
        var sum = 0f
        k = 0
        for (cell in 0 until SW * SH) {
            var r = 0f; var g = 0f; var b = 0f
            for (s in 0 until 4) {
                val x = pts[k++].toInt(); val y = pts[k++].toInt()
                val c = if (x in 0 until img.w && y in 0 until img.h) img.px[y * img.w + x] else Color.BLACK
                r += Color.red(c); g += Color.green(c); b += Color.blue(c)
                if (HandRecognizer.isCream(c)) cream++
                total++
            }
            out[cell * 3] = r / 4; out[cell * 3 + 1] = g / 4; out[cell * 3 + 2] = b / 4
            lum[cell] = (r + g + b) / 12
            sum += (r + g + b) / 4
        }
        val mean = sum / out.size
        if (mean > 0) for (i in out.indices) out[i] /= mean
        lum.sort()
        return Sample(out, cream.toFloat() / total, lum[(lum.size * 0.9f).toInt()])
    }

    /** Templates drawn upright or (for [side]) rotated 90 degrees exactly as the table draws them. */
    private fun templatesFor(style: Style, tileW: Float, side: Boolean = false): List<Template> {
        val tw = maxOf(12, tileW.roundToInt())
        return templates.getOrPut(Triple(style, tw, side)) {
            val th = (tw * 1.36f).roundToInt()
            val bw = if (side) th else tw; val bh = if (side) tw else th
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val buf = IntArray(bw * bh)
            val ids = (0 until Tiles.KINDS).map { it * 4 + 1 } + Tiles.RED_IDS.toList()
            val img = Img(buf, bw, bh)
            val fw = tw.toFloat(); val fbw = bw.toFloat(); val fbh = bh.toFloat()
            val rot = if (side) 90 else 0
            val unit = if (side) sideways(0f, 0f, fbw, fbh, Matrix()) else upright(0f, 0f, fbw, fbh, Matrix())
            ids.map { id ->
                bmp.eraseColor(Color.rgb(0x1E, 0x5E, 0x8C))
                when (style) {
                    Style.RIVER_2D -> renderer.draw(canvas, id, 0f, 0f, fbw, fbh, rot, depth = fw * if (side) 0.06f else 0.08f)
                    Style.PLAIN_2D -> renderer.draw(canvas, id, 0f, 0f, fbw, fbh, rot)
                    Style.TABLE_25D -> renderer.draw(canvas, id, 0f, 0f, fbw, fbh, rot, depth = fw * 0.2f, worldRotation = 0f)
                }
                bmp.getPixels(buf, 0, bw, 0, 0, bw, bh)
                Template(Tiles.kind(id), id in Tiles.RED_IDS, sample(img, unit).px)
            }
        }
    }

    private fun matchAt(img: Img, m: Matrix, style: Style, tileW: Float, side: Boolean): Match {
        val s = sample(img, m)
        var best: Template? = null
        var bestD = Float.MAX_VALUE
        for (t in templatesFor(style, tileW, side)) {
            var d = 0f
            for (i in s.px.indices) {
                val e = s.px[i] - t.px[i]; d += e * e
                if (d >= bestD) break
            }
            if (d < bestD) { bestD = d; best = t }
        }
        val b = best!!
        return Match(b.kind, b.red, bestD / s.px.size, s.cream, s.bright < 200f, s.bright)
    }

    /**
     * Classifies the tile whose unit square maps through [m]. When the first match is poor, a small
     * position search (in tile units, up to [range]) absorbs pixel snapping of rotated drawing and
     * imprecise group edges.
     */
    private fun classify(img: Img, m: Matrix, style: Style, tileW: Float, range: Float = 0.08f, side: Boolean = false): Match {
        var best = matchAt(img, m, style, tileW, side)
        if (best.distance < 0.006f || best.cream < PRESENT) return best
        val steps = 3
        for (j in -steps..steps) for (i in -steps..steps) {
            if (i == 0 && j == 0) continue
            val mm = Matrix(m).apply { preTranslate(i * range / steps, j * range / steps) }
            val r = matchAt(img, mm, style, tileW, side)
            if (r.distance < best.distance) best = r.copy(cream = maxOf(r.cream, best.cream))
        }
        return best
    }

    // ------------------------------------------------------------------ rivers

    private fun readRiver(img: Img, L: TableLayout, rel: Int, style: Style): SeenRiver {
        val frame = L.frameToScreen(rel)
        val kinds = ArrayList<Int>(); val called = ArrayList<Boolean>()
        var riichi = -1
        var row = 0; var x = L.riverLeft
        for (i in 0 until 24) {
            val r = minOf(i / 6, 3)
            if (r != row) { row = r; x = L.riverLeft }
            val y = L.riverTop + row * L.rh
            val up = classify(img, upright(x, y, L.rw, L.rh, frame), style, L.rw)
            // Only one tile per river is sideways (the riichi declaration).
            val side = if (riichi < 0) classify(img, sideways(x, y + (L.rh - L.rw), L.rh, L.rw, frame), style, L.rw, side = true) else null
            // Sideways (riichi) only when clearly better: one poor upright match must not shift the row.
            val useSide = side != null && side.cream >= PRESENT && (up.cream < PRESENT || side.distance < up.distance * 0.7f)
            val m = if (useSide) side!! else up
            if (m.cream < PRESENT) break
            kinds += m.kind
            called += m.dimmed
            if (useSide) { riichi = i; x += L.rh } else x += L.rw
        }
        return SeenRiver(kinds, called, riichi)
    }

    // ------------------------------------------------------------------ melds

    /**
     * Reads called sets along a row (frame coordinates: tiles of width [ow] / height [oh] whose top is
     * at [rowY]), between [x0] and [x1]. Face-down tiles (plain backs) are not tile-coloured, so
     * only melds show up; within a meld tiles touch, between melds there is a small gap.
     */
    private fun readMeldRow(
        img: Img, frame: Matrix, rowY: Float, x0: Float, x1: Float, ow: Float, oh: Float, style: Style,
        exclude: List<android.graphics.RectF> = emptyList(),
        tilesSeparated: Boolean = false,
    ): List<List<Int>> {
        val step = ow / 20f
        val n = ((x1 - x0) / step).toInt().coerceAtLeast(0)
        val on = BooleanArray(n)
        val pt = FloatArray(2)
        for (i in 0 until n) {
            // Probe a short vertical segment low in the row (inside both upright and sideways tiles).
            var c = 0
            for (fy in floatArrayOf(0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 0.96f)) {
                pt[0] = x0 + (i + 0.5f) * step; pt[1] = rowY + oh * fy
                frame.mapPoints(pt)
                val x = pt[0].toInt(); val y = pt[1].toInt()
                if (exclude.any { it.contains(pt[0], pt[1]) }) continue
                if (x in 0 until img.w && y in 0 until img.h) {
                    val px = img.px[y * img.w + x]
                    // 2.5D: bright face only (the thickness edge would fill the gaps between melds).
                    if (isMeldPixel(px, style)) c++
                }
            }
            // A tile column always has some face colour (symbols never fill it); the gap between
            // melds and the plain backs have none.
            on[i] = c >= 1
        }
        // Runs of covered positions -> groups (melds).
        val groups = ArrayList<Pair<Float, Float>>()
        var i = 0
        while (i < n) {
            if (!on[i]) { i++; continue }
            val s = i
            // Bridge gaps of up to 2 steps (tile outlines inside a meld); melds are 4 steps apart.
            while (i < n && (on[i] || (i + 1 < n && on[i + 1]) || (i + 2 < n && on[i + 2]))) i++
            val a = x0 + s * step; val b = x0 + i * step
            if (b - a > ow * 0.6f) groups += a to b
        }
        if (tilesSeparated) return readSeparatedTiles(img, frame, rowY, groups, ow, oh, style)
        return groups.mapNotNull { (a, b) -> readMeld(img, frame, rowY, a, b - a, ow, oh, style) }
    }

    /**
     * Right-hand seat in 2.5D: every tile's thickness edge covers the end of the previous tile's
     * face, so each face run is one tile and meld gaps cannot be seen. Split the tile sequence into
     * melds by the layout rules (3 or 4 tiles with one sideways tile, or a closed kan's 2 face-up
     * tiles) and keep the split whose melds are valid sets.
     */
    private fun readSeparatedTiles(
        img: Img, frame: Matrix, rowY: Float, runs: List<Pair<Float, Float>>, ow: Float, oh: Float, style: Style,
    ): List<List<Int>> {
        val tiles = runs.map { (a, b) ->
            val side = b - a > ow * 1.0f
            val m = if (side) classify(img, sideways(a, rowY + (oh - ow), oh, ow, frame), style, ow, range = 0.15f, side = true)
            else classify(img, upright(a, rowY, ow, oh, frame), style, ow, range = 0.15f)
            Triple(side, m.kind, a)
        }
        // DP over the sequence: best (valid melds, then fewest tiles left over).
        val n = tiles.size
        val best = arrayOfNulls<Pair<Int, List<List<Int>>>>(n + 1)
        best[n] = 0 to emptyList()
        for (i in n - 1 downTo 0) {
            var cand: Pair<Int, List<List<Int>>>? = best[i + 1]?.let { (sc, ms) -> sc - 1 to ms } // skip a tile
            for (size in intArrayOf(2, 3, 4)) {
                if (i + size > n) continue
                val chunk = tiles.subList(i, i + size)
                val sides = chunk.count { it.first }
                // Sideways position: called from left / across / right -> index 0 / 1 / last.
                val sideIdx = chunk.indexOfFirst { it.first }
                val ok = when (size) {
                    2 -> sides == 0
                    3 -> sides == 1
                    else -> sides == 1 && sideIdx in intArrayOf(0, 1, 3)
                }
                if (!ok) continue
                var kinds = chunk.map { it.second }
                if (size == 2) kinds = listOf(kinds[0], kinds[0], kinds[1], kinds[1])
                // Added kan: a fourth tile lies on top of the sideways tile.
                if (size == 3) {
                    val sx = chunk[sideIdx].third
                    val top = classify(img, sideways(sx, rowY + (oh - ow) - ow, oh, ow, frame), style, ow, range = 0.15f, side = true)
                    if (top.cream >= PRESENT) kinds = addedKan(kinds + top.kind)
                }
                // A structurally valid chunk with a misread tile still counts as a meld (lower score).
                val rest = best[i + size] ?: continue
                val sc = rest.first + if (isValidSet(kinds)) 3 else 1
                if (cand == null || sc > cand.first) cand = sc to (listOf(kinds) + rest.second)
            }
            best[i] = cand
        }
        return best[0]?.second ?: emptyList()
    }

    /**
     * Bright tile-face colour only. The darker thickness edge is excluded: in 2.5D it points towards
     * the viewer, which for the side seats runs along the row and would fill the gaps between melds.
     */
    private fun isFace(c: Int): Boolean {
        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
        // Faces are slightly warm (r - b ≈ 11); pure white / grey text (buttons, overlays) is not.
        return r > 232 && g > 226 && b > 210 && r - b in 5..25 && kotlin.math.abs(r - g) < 14
    }

    /** Tile colour for meld scanning (2D: face, outline and edge; never neutral white / grey text). */
    private fun isMeldPixel(c: Int, style: Style): Boolean {
        if (style == Style.TABLE_25D) return isFace(c)
        return HandRecognizer.isCream(c) && Color.red(c) - Color.blue(c) >= 4
    }

    /**
     * An added kan is always four of one kind; in 2.5D the stacked tile's edge covers part of the
     * sideways tile below it, so take the majority kind for all four.
     */
    private fun addedKan(k: List<Int>): List<Int> {
        val major = k.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key
        return List(4) { major }
    }

    private fun isValidSet(k: List<Int>): Boolean {
        if (k.isEmpty()) return false
        if (k.all { it == k[0] }) return true
        if (k.size != 3) return false
        val s = k.sorted()
        return s[0] < 27 && s[0] / 9 == s[2] / 9 && s[1] == s[0] + 1 && s[2] == s[0] + 2
    }

    private fun readMeld(img: Img, frame: Matrix, rowY: Float, x: Float, width: Float, ow: Float, oh: Float, style: Style): List<Int>? {
        // Candidate layouts by width: pon / chi / added kan (2 upright + 1 sideways), open kan
        // (3 + 1 sideways), closed kan (2 face-up tiles visible between two backs).
        val layouts = ArrayList<List<Boolean>>() // true = sideways
        val units = width / ow
        when {
            units < 2.6f -> layouts += listOf(false, false)
            units < 3.9f -> for (s in 0..2) layouts += List(3) { it == s }
            else -> for (s in listOf(0, 1, 3)) layouts += List(4) { it == s }
        }
        // Score every layout; prefer the best one whose tiles form a valid set.
        // The group's start is known to ~1/20 tile: search it jointly for all tiles of the layout.
        val scored = layouts.flatMap { lay ->
            (-2..2).map { k ->
                var cx = x + k * ow * 0.05f
                var dist = 0f
                val ms = ArrayList<Match>()
                for (sd in lay) {
                    val m = if (sd) classify(img, sideways(cx, rowY + (oh - ow), oh, ow, frame), style, ow, side = true)
                    else classify(img, upright(cx, rowY, ow, oh, frame), style, ow)
                    dist += m.distance
                    ms += m
                    cx += if (sd) oh else ow
                }
                Triple(dist, ms, lay)
            }
        }.sortedBy { it.first }
        val pick = scored.firstOrNull { (_, ms, lay) -> lay.none { it } || isValidSet(ms.map { it.kind }) } ?: scored.firstOrNull()
        val best: Pair<Float, List<Match>>? = pick?.let { it.first to it.second }
        val bestLayout: List<Boolean>? = pick?.third
        val ms = best?.second ?: return null
        val kinds = ms.map { it.kind }.toMutableList()
        if (bestLayout!!.none { it }) {
            // Closed kan: the two face-up tiles stand for four.
            return listOf(kinds[0], kinds[0], kinds[1], kinds[1])
        }
        // Added kan: one more tile lies on top of the sideways tile.
        val sIdx = bestLayout.indexOf(true)
        var sx = x
        for (k in 0 until sIdx) sx += ow
        val top = classify(img, sideways(sx, rowY + (oh - ow) - ow, oh, ow, frame), style, ow, range = 0.15f, side = true)
        if (top.cream >= PRESENT && bestLayout.size == 3) {
            kinds += top.kind
            return addedKan(kinds)
        }
        return kinds
    }
}
