package io.github.mahjongapp.core

/**
 * Shanten numbers (-1 = complete hand, 0 = tenpai, ...).
 *
 * The standard-form calculation splits the hand per suit: for each suit (memoised by its count
 * pattern) it enumerates the Pareto-optimal (sets, partial sets, pair) combinations, then combines
 * the four parts.
 */
object Shanten {

    private class Opt(val m: Int, val t: Int, val p: Int)

    private val memo = java.util.concurrent.ConcurrentHashMap<Int, List<Opt>>()

    /** Shanten of the closed part [counts] (13 - 3*melds or 14 - 3*melds tiles) given [melds] calls. */
    fun standard(counts: IntArray, melds: Int = 0): Int {
        val parts = arrayOf(
            suitOptions(counts, 0, false),
            suitOptions(counts, 9, false),
            suitOptions(counts, 18, false),
            suitOptions(counts, 27, true),
        )
        val need = 4 - melds
        var best = 8
        for (a in parts[0]) for (b in parts[1]) {
            val pab = a.p + b.p
            if (pab > 1) continue
            for (c in parts[2]) {
                val pabc = pab + c.p
                if (pabc > 1) continue
                for (d in parts[3]) {
                    val p = pabc + d.p
                    if (p > 1) continue
                    val m = a.m + b.m + c.m + d.m
                    var t = a.t + b.t + c.t + d.t
                    if (m + t > need) t = need - m
                    if (t < 0) t = 0
                    val s = 8 - 2 * (m + melds) - t - p
                    if (s < best) best = s
                }
            }
        }
        return best
    }

    fun chiitoi(counts: IntArray): Int {
        var pairs = 0
        var kinds = 0
        for (c in counts) {
            if (c >= 1) kinds++
            if (c >= 2) pairs++
        }
        return 6 - pairs + maxOf(0, 7 - kinds)
    }

    fun kokushi(counts: IntArray): Int {
        var kinds = 0
        var pair = 0
        for (k in Tiles.TERMINALS_AND_HONORS) {
            if (counts[k] >= 1) kinds++
            if (counts[k] >= 2) pair = 1
        }
        return 13 - kinds - pair
    }

    /** Minimum over the three forms (chiitoi / kokushi only for closed hands). */
    fun of(counts: IntArray, melds: Int = 0): Int {
        var s = standard(counts, melds)
        if (melds == 0) {
            s = minOf(s, chiitoi(counts), kokushi(counts))
        }
        return s
    }

    private fun suitOptions(counts: IntArray, base: Int, honor: Boolean): List<Opt> {
        val n = if (honor) 7 else 9
        // Base-5 count pattern; honor patterns are offset past every suit pattern (5^9) so they never collide.
        var key = 0
        for (i in 0 until n) key = key * 5 + counts[base + i]
        if (honor) key += 1_953_125
        memo[key]?.let { return it }
        val local = IntArray(n) { counts[base + it] }
        val found = HashSet<Int>()
        dfs(local, 0, honor, 0, 0, 0, found)
        // Keep Pareto-optimal options per pair flag. The combined value is 2M + min(T, need - M) =
        // min(2M + T, M + need), so x is never worse than o when x.m >= o.m and x.m + x.t >= o.m + o.t.
        val all = found.map { Opt(it / 100, it / 10 % 10, it % 10) }
        val opts = all.filter { o ->
            all.none { x ->
                x !== o && x.p == o.p && x.m >= o.m && x.m + x.t >= o.m + o.t &&
                    (x.m > o.m || x.m + x.t > o.m + o.t)
            }
        }
        memo[key] = opts
        return opts
    }

    private fun dfs(c: IntArray, start: Int, honor: Boolean, m: Int, t: Int, p: Int, out: MutableSet<Int>) {
        var i = start
        while (i < c.size && c[i] == 0) i++
        if (i >= c.size) {
            out.add(m * 100 + minOf(t, 9) * 10 + p)
            return
        }
        // Triplet
        if (c[i] >= 3) {
            c[i] -= 3; dfs(c, i, honor, m + 1, t, p, out); c[i] += 3
        }
        // Sequence
        if (!honor && i + 2 < c.size && c[i + 1] > 0 && c[i + 2] > 0) {
            c[i]--; c[i + 1]--; c[i + 2]--
            dfs(c, i, honor, m + 1, t, p, out)
            c[i]++; c[i + 1]++; c[i + 2]++
        }
        // Pair (as the head) or pair as a partial set
        if (c[i] >= 2) {
            c[i] -= 2
            if (p == 0) dfs(c, i, honor, m, t, 1, out)
            dfs(c, i, honor, m, t + 1, p, out)
            c[i] += 2
        }
        if (!honor) {
            if (i + 1 < c.size && c[i + 1] > 0) {
                c[i]--; c[i + 1]--; dfs(c, i, honor, m, t + 1, p, out); c[i]++; c[i + 1]++
            }
            if (i + 2 < c.size && c[i + 2] > 0) {
                c[i]--; c[i + 2]--; dfs(c, i, honor, m, t + 1, p, out); c[i]++; c[i + 2]++
            }
        }
        // Leave this tile isolated
        c[i]--; dfs(c, i, honor, m, t, p, out); c[i]++
    }

    /** Kinds that reduce the shanten of a 13-tile (or 13 - 3n) hand, with how many remain unseen. */
    fun ukeire(counts: IntArray, melds: Int, visible: IntArray? = null): List<Pair<Int, Int>> {
        val base = of(counts, melds)
        val out = ArrayList<Pair<Int, Int>>()
        for (k in 0 until Tiles.KINDS) {
            if (counts[k] >= 4) continue
            counts[k]++
            val s = of(counts, melds)
            counts[k]--
            if (s < base) {
                val left = 4 - counts[k] - (visible?.get(k) ?: 0)
                if (left > 0) out.add(k to left)
            }
        }
        return out
    }
}
