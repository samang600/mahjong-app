package io.github.mahjongapp.assist

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import io.github.mahjongapp.core.Tiles
import io.github.mahjongapp.ui.TileRenderer
import kotlin.math.roundToInt

/** One recognised tile of the player's hand. */
data class SeenTile(val kind: Int, val red: Boolean, val rect: Rect, val distance: Float)

data class SeenHand(
    /** Tiles left to right as shown (the drawn tile, if any, is last). */
    val tiles: List<SeenTile>,
    /** The last tile stands apart from the others (a freshly drawn tile). */
    val drawnSeparated: Boolean,
) {
    val kinds get() = tiles.map { it.kind }
}

/**
 * Image-based recognition of the player's hand in a screenshot of this app (2D or 2.5D view).
 *
 * Works only from pixels: it finds the band of cream-coloured tile faces at the bottom of the
 * screen, splits it into tiles using the tile aspect ratio and the gaps between groups, and
 * classifies each tile against templates drawn by the app's own [TileRenderer] at the same size.
 * Brightness-normalised comparison makes it robust to dimmed and highlighted tiles.
 */
class HandRecognizer {

    private val renderer = TileRenderer { true }

    /** Diagnostic description of the last recognition (band, groups). */
    var lastDebug = ""
        private set

    /** Template cache per (tile width, depth). */
    private val templates = HashMap<Pair<Int, Int>, List<Template>>()

    private class Template(val kind: Int, val red: Boolean, val px: FloatArray)

    companion object {
        private const val ASPECT = 1.36f
        private const val SW = 18 // sample grid
        private const val SH = 22

        /** Tile face / body colours (cream), possibly dimmed; excludes the blue table, buttons and dark panels. */
        fun isCream(c: Int): Boolean {
            val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
            return r > 110 && g > 105 && b > 95 && r >= b && r - b < 40 && kotlin.math.abs(r - g) < 18
        }
    }

    fun recognize(bmp: Bitmap): SeenHand? {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)

        // 1. Hand band: rows near the bottom where tiles cover the left part of the screen. The hand
        //    always starts at the left edge and melds are at the right, so measure only the left
        //    35% (a hand of 2 tiles after 4 calls still covers ~40% of it).
        val probeW = (w * 0.35f).toInt()
        val rowFrac = FloatArray(h) { y ->
            var n = 0
            var x = 0
            while (x < probeW) { if (isCream(px[y * w + x])) n++; x += 2 }
            n * 2f / probeW
        }
        var y1 = -1
        for (y in h - 1 downTo h / 2) if (rowFrac[y] > 0.2f) { y1 = y; break }
        if (y1 < 0) return null
        // Extend upwards, tolerating a few rows of tile outlines (dark on dimmed tiles).
        var y0 = y1
        val gapTol = maxOf(2, h / 150)
        while (true) {
            val next = (1..gapTol).firstOrNull { d -> y0 - d > h / 2 && rowFrac[y0 - d] > 0.2f } ?: break
            y0 -= next
        }
        val bandH = y1 - y0 + 1
        if (bandH < 12) return null
        val tw = bandH / ASPECT

        // 2. Columns covered by tiles (symbols interrupt the cream, but the face margins and the
        //    body strip keep at least ~1/5 of every tile column cream); split into groups at gaps.
        var sampled = 0
        for (y in y0..y1 step 2) sampled++
        val colOn = BooleanArray(w) { x ->
            var n = 0
            for (y in y0..y1 step 2) if (isCream(px[y * w + x])) n++
            n > sampled / 5
        }
        val raw = ArrayList<IntRange>()
        var x = 0
        while (x < w) {
            if (!colOn[x]) { x++; continue }
            val start = x
            while (x < w && colOn[x]) x++
            raw += start until x
        }
        // Merge runs separated by tiny gaps (seams inside a group of tiles).
        val groups = ArrayList<IntRange>()
        for (r in raw) {
            val last = groups.lastOrNull()
            if (last != null && r.first - last.last < tw * 0.15f) groups[groups.size - 1] = last.first..r.last
            else groups += r
        }
        groups.removeAll { it.last - it.first + 1 < tw * 0.5f }
        if (groups.isEmpty()) return null

        // 3. Hand groups: the first group and a following single tile (the drawn tile). Melds at the
        //    right edge are smaller and far away; stop at a gap larger than one tile.
        val handGroups = ArrayList<IntRange>()
        handGroups += groups[0]
        for (i in 1 until groups.size) {
            val gap = groups[i].first - groups[i - 1].last
            if (gap > tw * 1.0f) break
            // Only a single drawn tile follows the hand; a wider group is the player's melds.
            if (groups[i].last - groups[i].first + 1 > tw * 1.5f) break
            handGroups += groups[i]
        }

        lastDebug = "size=${w}x$h band=$y0..$y1 tw=$tw groups=$groups hand=$handGroups"
        val boxes = ArrayList<Rect>()
        for (g in handGroups) {
            val width = g.last - g.first + 1
            val n = maxOf(1, (width / tw).roundToInt())
            val step = width.toFloat() / n
            for (i in 0 until n) {
                val left = (g.first + i * step).roundToInt()
                val right = (g.first + (i + 1) * step).roundToInt()
                boxes += Rect(left, y0, right, y1 + 1)
            }
        }
        // A raised (selected) tile extends above the band: find each tile's own top.
        // Scan across the whole tile width (symbols fill the middle, the face margins never do).
        val fixed = boxes.map { r ->
            val cx0 = r.left + r.width() * 6 / 100; val cx1 = r.right - r.width() * 6 / 100
            var top = r.top
            val limit = maxOf(0, r.top - (bandH * 0.4f).toInt())
            while (top - 1 > limit) {
                var n = 0
                for (cx in cx0 until cx1) if (isCream(px[(top - 1) * w + cx])) n++
                if (n < (cx1 - cx0) / 4) break
                top--
            }
            Rect(r.left, top, r.right, top + (r.width() * ASPECT).roundToInt())
        }

        val tiles = fixed.map { classify(px, w, h, it) }
        lastDebug += " boxes=" + fixed.take(3).joinToString { "${it.left},${it.top}-${it.right},${it.bottom}" } +
            " picked=" + tiles.take(3).joinToString { "${Tiles.name(it.kind)}${if (it.red) "r" else ""}@${it.rect.left},${it.rect.top} d=${"%.4f".format(it.distance)}" }
        return SeenHand(tiles, drawnSeparated = handGroups.size >= 2)
    }

    // ------------------------------------------------------------------ classification

    /** Samples the upper face area of a tile box into a brightness-normalised RGB grid. */
    private fun sample(src: IntArray, sw: Int, sh: Int, box: Rect): FloatArray {
        val out = FloatArray(SW * SH * 3)
        val tw = box.width().toFloat()
        // Upper part of the face only: the face ends at 84% (2.5D) or 91% (2D) of the tile height
        // and a selection / hint frame covers its last ~3%, so stop at 74%.
        val fx0 = box.left + tw * 0.12f; val fx1 = box.right - tw * 0.12f
        val fy0 = box.top + tw * 0.12f; val fy1 = box.top + box.height() * 0.74f
        var sum = 0f
        for (j in 0 until SH) for (i in 0 until SW) {
            var r = 0f; var g = 0f; var b = 0f; var cnt = 0
            for (sj in 0..1) for (si in 0..1) {
                val xx = (fx0 + (i + (si + 0.5f) / 2f) / SW * (fx1 - fx0)).toInt().coerceIn(0, sw - 1)
                val yy = (fy0 + (j + (sj + 0.5f) / 2f) / SH * (fy1 - fy0)).toInt().coerceIn(0, sh - 1)
                val c = src[yy * sw + xx]
                r += Color.red(c); g += Color.green(c); b += Color.blue(c); cnt++
            }
            val k = (j * SW + i) * 3
            out[k] = r / cnt; out[k + 1] = g / cnt; out[k + 2] = b / cnt
            sum += out[k] + out[k + 1] + out[k + 2]
        }
        val mean = sum / out.size
        if (mean > 0) for (k in out.indices) out[k] /= mean
        return out
    }

    private fun templatesFor(tw: Int, depthPct: Int): List<Template> = templates.getOrPut(tw to depthPct) {
        val th = (tw * ASPECT).roundToInt()
        val bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val buf = IntArray(tw * th)
        val ids = (0 until Tiles.KINDS).map { it * 4 + 1 } + Tiles.RED_IDS.toList()
        ids.map { id ->
            bmp.eraseColor(Color.rgb(0x1E, 0x5E, 0x8C))
            renderer.draw(canvas, id, 0f, 0f, tw.toFloat(), th.toFloat(), depth = th * depthPct / 100f)
            bmp.getPixels(buf, 0, tw, 0, 0, tw, th)
            Template(Tiles.kind(id), id in Tiles.RED_IDS, sample(buf, tw, th, Rect(0, 0, tw, th)))
        }
    }

    /** Best (distance, template, box) over a small position search and both template depths. */
    private fun match(px: IntArray, w: Int, h: Int, box: Rect, only: ((Template) -> Boolean)? = null): Triple<Float, Template, Rect> {
        var best: Template? = null
        var bestD = Float.MAX_VALUE
        var bestBox = box
        // Tile boxes from the split can be off by a few pixels (raised / highlighted tiles, rounding).
        val stepPx = maxOf(1, (box.width() * 0.025f).toInt())
        for (dy in -3..3) for (dx in -2..2) {
            val b = Rect(box).apply { offset(dx * stepPx, dy * stepPx) }
            val s = sample(px, w, h, b)
            for (depth in intArrayOf(9, 16)) {
                for (t in templatesFor(box.width(), depth)) {
                    if (only != null && !only(t)) continue
                    var d = 0f
                    for (k in s.indices) {
                        val e = s[k] - t.px[k]; d += e * e
                        if (d >= bestD) break
                    }
                    if (d < bestD) { bestD = d; best = t; bestBox = b }
                }
            }
        }
        return Triple(bestD / (SW * SH * 3), best!!, bestBox)
    }

    private fun classify(px: IntArray, w: Int, h: Int, box: Rect): SeenTile {
        val (d, t, b) = match(px, w, h, box)
        return SeenTile(t.kind, t.red, b, d)
    }

    /** Debug: best distance for each of the given kinds at this box. */
    fun debugDistances(bmp: Bitmap, box: Rect, kinds: List<Int>): String {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        return kinds.joinToString { k ->
            val (d, t, b) = match(px, w, h, box) { it.kind == k }
            "${Tiles.name(k)}${if (t.red) "r" else ""}=${"%.4f".format(d)}@(${b.left - box.left},${b.top - box.top})"
        }
    }
}
