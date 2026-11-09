package io.github.mahjongapp.core

/** One tile of the player's hand as known to the strategy. */
data class HandTile(val kind: Int, val red: Boolean = false)

/** What is known about one opponent (from the game state or read from the screen). */
data class OpponentView(
    /** Discarded kinds in order (tiles called away included). */
    val river: List<Int>,
    /** Index in [river] of the riichi declaration tile, or -1. */
    val riichiIndex: Int = -1,
    /** Called sets, each as its kinds. */
    val melds: List<List<Int>> = emptyList(),
    /** Per river tile: taken by someone's call (shown dimmed; its copy is counted in that meld). */
    val calledAway: List<Boolean> = emptyList(),
) {
    val riichi get() = riichiIndex >= 0
}

/**
 * Everything the discard decision may use. Fields that cannot be known (e.g. seat wind when read
 * from the screen) are null and simply not used.
 */
data class Situation(
    val hand: List<HandTile>,
    /** The player's own called sets (kinds); their number may exceed the list when only inferred. */
    val myMelds: List<List<Int>> = emptyList(),
    val myMeldCount: Int = myMelds.size,
    val doraIndicators: List<Int> = emptyList(),
    /** Opponents in turn order after the player (right, across, left). */
    val opponents: List<OpponentView> = emptyList(),
    /** Other visible kinds (e.g. the player's own discards), counted as seen. */
    val extraVisible: List<Int> = emptyList(),
    val seatWind: Int? = null,
    val roundWind: Int? = null,
    val wallRemaining: Int? = null,
) {
    val doraKinds get() = doraIndicators.map(Tiles::doraFromIndicator)
}

/**
 * Discard strategy shared by the CPU, the in-game hint and the screen assistant:
 * - acceptance counts only tiles not yet seen (rivers, melds, dora indicators, own hand);
 * - hand value: dora, red fives and yakuhai pairs / triplets are worth keeping;
 * - threat model: riichi, and open hands by number of calls, flush or yakuhai / dora calls;
 * - defence when the hand is slow compared with the threats, choosing the tile with the lowest
 *   threat-weighted danger (genbutsu, suji, visible honors, flush suit).
 */
object Strategy {

    data class Eval(
        val kind: Int,
        val red: Boolean,
        val shanten: Int,
        val ukeire: Int,
        /** Value kept in the hand after this discard (dora, red fives, yakuhai). */
        val value: Int,
        /** Threat-weighted danger of discarding this tile (0 = safe). */
        val danger: Float,
    )

    data class Threat(val opponent: Int, val level: Float, val reason: String, val flushSuit: Int?)

    data class Decision(
        val ranked: List<Eval>,
        val defending: Boolean,
        val threats: List<Threat>,
    ) {
        val best get() = ranked.first()
    }

    // ------------------------------------------------------------------ visibility

    fun visibleCounts(s: Situation, includeHand: Boolean = true): IntArray {
        val c = IntArray(Tiles.KINDS)
        if (includeHand) for (t in s.hand) c[t.kind]++
        for (m in s.myMelds) for (k in m) c[k]++
        for (k in s.doraIndicators) c[k]++
        for (o in s.opponents) {
            for ((i, k) in o.river.withIndex()) if (o.calledAway.getOrNull(i) != true) c[k]++
            for (m in o.melds) for (k in m) c[k]++
        }
        for (k in s.extraVisible) c[k]++
        for (k in c.indices) if (c[k] > 4) c[k] = 4
        return c
    }

    // ------------------------------------------------------------------ threats

    private fun isYakuhai(k: Int, s: Situation) = Tiles.isDragon(k) || k == s.seatWind || k == s.roundWind

    fun threats(s: Situation): List<Threat> {
        val dora = s.doraKinds.toSet()
        return s.opponents.mapIndexed { i, o ->
            if (o.riichi) return@mapIndexed Threat(i, 1f, "riichi", null)
            val melds = o.melds
            var level = when (melds.size) { 0 -> 0f; 1 -> 0.1f; 2 -> 0.3f; 3 -> 0.6f; else -> 0.85f }
            val reasons = ArrayList<String>()
            // Flush: every called set in one suit (+ honors).
            val meldSuits = melds.flatten().filter { it < 27 }.map { it / 9 }.toSet()
            val flushSuit = if (melds.size >= 2 && meldSuits.size == 1) meldSuits.first() else null
            if (flushSuit != null) { level += 0.25f; reasons += "flush" }
            // Yakuhai or dora in the calls: the hand has a yaku / value.
            if (melds.any { m -> m.size >= 3 && m.all { it == m[0] } && Tiles.isDragon(m[0]) }) { level += 0.1f; reasons += "yakuhai" }
            val doraCalled = melds.flatten().count { it in dora }
            if (doraCalled > 0) { level += 0.08f * doraCalled; reasons += "dora" }
            if (melds.isNotEmpty() && s.wallRemaining != null && s.wallRemaining < 25) level += 0.1f
            Threat(i, level.coerceAtMost(0.95f), (listOf("${melds.size} calls") + reasons).joinToString(), flushSuit)
        }
    }

    /** Danger (0 safe .. ~12) of discarding [k] against one opponent. */
    fun dangerAgainst(o: OpponentView, t: Threat, k: Int, visible: IntArray, others: List<OpponentView>): Int {
        val riverKinds = o.river.toSet()
        if (k in riverKinds) return 0
        if (o.riichi) {
            // Discarded by someone else after the riichi (approximated by river positions).
            val after = others.any { other -> other !== o && other.river.drop(o.riichiIndex).contains(k) }
            if (after) return 0
        }
        if (Tiles.isHonor(k)) {
            return when (visible[k]) { 3, 4 -> 1; 2 -> 3; 1 -> 6; else -> 8 }
        }
        val fs = t.flushSuit
        if (fs != null && !o.riichi) return if (k / 9 == fs) 11 else 1
        val n = k % 9
        val lowSuji = n - 3 < 0 || (k - 3) in riverKinds
        val highSuji = n + 3 > 8 || (k + 3) in riverKinds
        return when {
            lowSuji && highSuji -> if (n == 0 || n == 8) 2 else 4
            n == 0 || n == 8 -> 6
            n == 1 || n == 7 -> 9
            else -> 12
        }
    }

    // ------------------------------------------------------------------ evaluation

    private fun handValue(counts: IntArray, reds: Int, s: Situation): Int {
        var v = 0
        for (d in s.doraKinds) v += 3 * counts[d]
        v += 3 * reds
        for (k in 0 until Tiles.KINDS) if (counts[k] >= 2 && isYakuhai(k, s)) v += 4
        return v
    }

    fun decide(s: Situation, candidates: List<HandTile>? = null): Decision? {
        if (s.hand.isEmpty()) return null
        val counts = Tiles.counts(s.hand.map { it.kind })
        if (counts.any { it > 4 }) return null
        val melds = s.myMeldCount
        val seenOutside = visibleCounts(s, includeHand = false)
        val visibleAll = visibleCounts(s)
        val ths = threats(s)
        val allowed = (candidates ?: s.hand).distinctBy { it.kind to it.red }
        // Discard a non-red copy when both may be discarded.
        val cands = allowed.filter { c -> !c.red || allowed.none { it.kind == c.kind && !it.red } }
        val reds = s.hand.count { it.red }

        val evals = cands.map { c ->
            counts[c.kind]--
            val sh = Shanten.of(counts, melds)
            val uke = Shanten.ukeire(counts, melds).sumOf { (k, _) -> maxOf(0, 4 - counts[k] - seenOutside[k] - (if (k == c.kind) 1 else 0)) }
            val value = handValue(counts, reds - (if (c.red) 1 else 0), s)
            counts[c.kind]++
            val danger = ths.sumOf { t ->
                (t.level * dangerAgainst(s.opponents[t.opponent], t, c.kind, visibleAll, s.opponents)).toDouble()
            }.toFloat()
            Eval(c.kind, c.red, sh, uke, value, danger)
        }
        if (evals.isEmpty()) return null
        val bestShanten = evals.minOf { it.shanten }
        val maxThreat = ths.maxOfOrNull { it.level } ?: 0f
        val lateWall = s.wallRemaining != null && s.wallRemaining <= 30
        val defending = when {
            maxThreat >= 0.9f -> bestShanten >= 2 || (bestShanten == 1 && (s.wallRemaining == null || lateWall))
            maxThreat >= 0.55f -> bestShanten >= 2
            maxThreat >= 0.3f -> bestShanten >= 3 && lateWall
            else -> false
        }
        val isolationOf = { k: Int -> isolation(counts, k) }
        val ranked = if (defending) {
            evals.sortedWith(compareBy<Eval> { it.danger }.thenBy { it.shanten }.thenByDescending { it.ukeire })
        } else {
            // Attack: fewest shanten; then acceptance plus kept value; avoid danger among equals.
            evals.sortedWith(
                compareBy<Eval> { it.shanten }
                    .thenByDescending { it.ukeire + it.value * 2 - (it.danger * maxThreat).toInt() }
                    .thenByDescending { isolationOf(it.kind) }
                    .thenBy { it.danger }
            )
        }
        return Decision(ranked, defending, ths.filter { it.level > 0f })
    }

    /** Higher = more isolated (better to discard among equals). */
    fun isolation(counts: IntArray, k: Int): Int {
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

    // ------------------------------------------------------------------ from the game

    /** The situation of [seat] in a running game (everything the player can see). */
    fun situationOf(game: Game, seat: Int): Situation {
        val p = game.players[seat]
        val red = game.rules.redFives
        val opponents = (1..3).map { d ->
            val q = game.players[(seat + d) % 4]
            OpponentView(
                river = q.river.map { Tiles.kind(it.id) },
                riichiIndex = q.river.indexOfFirst { it.riichi },
                melds = q.melds.map { m -> m.tiles.map(Tiles::kind) },
                calledAway = q.river.map { it.calledAway },
            )
        }
        return Situation(
            hand = p.hand.map { HandTile(Tiles.kind(it), Tiles.isRed(it, red)) },
            myMelds = p.melds.map { m -> m.tiles.map(Tiles::kind) },
            doraIndicators = game.doraIndicators.map(Tiles::kind),
            opponents = opponents,
            extraVisible = p.river.filter { !it.calledAway }.map { Tiles.kind(it.id) },
            seatWind = game.seatWind(seat),
            roundWind = game.roundWindKind,
            wallRemaining = game.wallRemaining,
        )
    }
}
