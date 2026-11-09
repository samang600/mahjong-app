package io.github.mahjongapp.core

/**
 * Advice from the player's hand alone (as recognised from the screen): no rivers, no dora, no
 * opponents. Uses shanten and tile acceptance (counting only the player's own tiles as seen).
 */
object HandAdvisor {

    data class Option(val kind: Int, val shanten: Int, val ukeire: Int, val accepting: List<Int>)

    data class Advice(
        /** Calls made so far, inferred from the hand size. */
        val melds: Int,
        val shantenNow: Int,
        /** Ranked discard options (empty when it is not the player's turn, i.e. 13 - 3n tiles). */
        val discards: List<Option>,
        /** For a waiting hand (13 - 3n tiles): kinds that complete or improve it. */
        val waits: List<Int>,
    ) {
        val best get() = discards.firstOrNull()
    }

    fun advise(kinds: List<Int>): Advice? {
        val n = kinds.size
        if (n == 0 || n > 14) return null
        val counts = Tiles.counts(kinds)
        if (counts.any { it > 4 }) return null
        return when (n % 3) {
            2 -> {
                val melds = (14 - n) / 3
                val now = Shanten.of(counts, melds)
                val opts = ArrayList<Option>()
                for (k in kinds.toSet()) {
                    counts[k]--
                    val s = Shanten.of(counts, melds)
                    val acc = Shanten.ukeire(counts, melds)
                    val uke = acc.sumOf { (kind, _) -> maxOf(0, 4 - counts[kind] - (if (kind == k) 1 else 0)) }
                    counts[k]++
                    opts += Option(k, s, uke, acc.map { it.first })
                }
                opts.sortWith(compareBy<Option> { it.shanten }.thenByDescending { it.ukeire }.thenByDescending { isolation(counts, it.kind) })
                Advice(melds, now, opts, emptyList())
            }
            1 -> {
                val melds = (13 - n) / 3
                val now = Shanten.of(counts, melds)
                Advice(melds, now, emptyList(), Shanten.ukeire(counts, melds).map { it.first })
            }
            else -> null
        }
    }

    /** Higher = more isolated (better to discard among equals). */
    private fun isolation(counts: IntArray, k: Int): Int {
        if (Tiles.isHonor(k)) return if (counts[k] == 1) 10 else 0
        var neighbours = 0
        for (d in -2..2) {
            if (d == 0) continue
            val n = k % 9 + d
            if (n in 0..8 && counts[k + d] > 0) neighbours++
        }
        val edge = if (Tiles.isTerminal(k)) 2 else 0
        return (if (counts[k] == 1) 5 else 0) + edge - neighbours * 2
    }
}
