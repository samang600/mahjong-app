package io.github.mahjongapp.core

/** One candidate discard with its evaluation (also shown by the hint). */
data class DiscardEval(
    val id: Int,
    val shanten: Int,
    /** Number of unseen tiles that improve the hand after this discard. */
    val ukeire: Int,
    /** 0 = safe ... larger = more dangerous against riichi players. */
    val danger: Int,
)

data class Advice(val action: TurnAction, val evals: List<DiscardEval>, val defending: Boolean)

/**
 * Rule-based CPU: tile efficiency (shanten + acceptance), defence against riichi, simple call and
 * riichi decisions. Deterministic for a given game state.
 */
object Ai {

    fun decideTurn(game: Game, seat: Int): TurnAction = advise(game, seat).action

    fun advise(game: Game, seat: Int): Advice {
        val p = game.players[seat]
        val opts = game.turnOptions()
        if (opts.canTsumo) return Advice(TurnAction.Tsumo, emptyList(), false)
        val counts = p.handKinds()

        // Closed kan when it does not hurt the hand.
        for (k in opts.ankan) {
            if (p.riichi > 0) return Advice(TurnAction.Ankan(k), emptyList(), false)
            val rest = p.hand.filter { Tiles.kind(it) != k }
            val rc = Tiles.counts(rest.map(Tiles::kind))
            // After the kan a replacement tile is drawn: compare with the 13-tile shanten.
            if (Shanten.of(rc, p.melds.size + 1) <= bestShantenAfterDiscard(counts, p.melds.size)) {
                return Advice(TurnAction.Ankan(k), emptyList(), false)
            }
        }
        if (opts.kakan.isNotEmpty() && game.players.none { it.seat != seat && it.riichi > 0 }) {
            return Advice(TurnAction.Kakan(opts.kakan[0]), emptyList(), false)
        }

        // Discard choice: shared strategy (unseen acceptance, value, threats, defence).
        val sit = Strategy.situationOf(game, seat)
        val red = game.rules.redFives
        val candTiles = opts.discardable.map { HandTile(Tiles.kind(it), Tiles.isRed(it, red)) }
        val decision = Strategy.decide(sit, candTiles)!!
        val evals = decision.ranked.map { e ->
            val id = opts.discardable.first { Tiles.kind(it) == e.kind && Tiles.isRed(it, red) == e.red }
            DiscardEval(id, e.shanten, e.ukeire, e.danger.toInt())
        }
        val defending = decision.defending
        val choice = evals.first()
        val riichi = choice.id in opts.riichiDiscards && choice.shanten == 0
        return Advice(TurnAction.Discard(choice.id, riichi), evals, defending)
    }

    private fun bestShantenAfterDiscard(counts: IntArray, melds: Int): Int {
        var best = 99
        for (k in 0 until Tiles.KINDS) {
            if (counts[k] == 0) continue
            counts[k]--
            best = minOf(best, Shanten.of(counts, melds))
            counts[k]++
        }
        return best
    }

    fun evaluateDiscards(game: Game, seat: Int, candidates: Set<Int>): List<DiscardEval> {
        val p = game.players[seat]
        val counts = p.handKinds()
        // Tiles seen outside this hand (rivers, melds, dora indicators).
        val visible = game.visibleCounts(seat).also { v -> for (k in 0 until Tiles.KINDS) v[k] -= counts[k] }
        val seen = HashSet<Int>()
        val out = ArrayList<DiscardEval>()
        // One evaluation per kind (prefer discarding a non-red copy).
        for (id in candidates.sortedBy { if (Tiles.isRed(it, game.rules.redFives)) 1 else 0 }) {
            val k = Tiles.kind(id)
            if (!seen.add(k)) continue
            counts[k]--
            val s = Shanten.of(counts, p.melds.size)
            val uke = Shanten.ukeire(counts, p.melds.size).sumOf { (kind, _) -> maxOf(0, 4 - visible[kind] - counts[kind]) }
            counts[k]++
            out += DiscardEval(id, s, uke, danger(game, seat, k))
        }
        return out
    }

    /** Higher = more isolated (better to discard). */
    private fun isolationValue(counts: IntArray, k: Int): Int {
        if (Tiles.isHonor(k)) return if (counts[k] == 1) 10 else 0
        var neighbours = 0
        for (d in -2..2) {
            if (d == 0) continue
            val n = k % 9 + d
            if (n in 0..8 && counts[k + d] > 0) neighbours++
        }
        val edge = if (Tiles.isTerminal(k)) 2 else if (k % 9 == 1 || k % 9 == 7) 1 else 0
        return (if (counts[k] == 1) 5 else 0) + edge - neighbours * 2
    }

    /** Higher = more valuable to keep (dora, red fives, yakuhai pairs). */
    private fun keepValue(game: Game, id: Int): Int {
        val k = Tiles.kind(id)
        var v = 0
        for (ind in game.doraIndicators) if (Tiles.doraFromIndicator(Tiles.kind(ind)) == k) v += 3
        if (Tiles.isRed(id, game.rules.redFives)) v += 3
        return v
    }

    /** Danger of discarding kind [k] against all riichi players (0 = completely safe). */
    fun danger(game: Game, seat: Int, k: Int): Int {
        var worst = 0
        val visible = game.visibleCounts(seat)
        for (q in game.players) {
            if (q.seat == seat || q.riichi == 0) continue
            val riichiIdx = q.river.indexOfFirst { it.riichi }
            val genbutsu = q.river.any { Tiles.kind(it.id) == k } ||
                // Tiles discarded by anyone after the riichi are safe against it (no ron was called).
                game.players.any { o -> o.seat != q.seat && o.river.takeLast(maxOf(0, o.river.size - riichiIdx)).any { Tiles.kind(it.id) == k } }
            val d = when {
                genbutsu -> 0
                Tiles.isHonor(k) -> when (visible[k]) { 3, 4 -> 1; 2 -> 3; 1 -> 6; else -> 8 }
                else -> {
                    val n = k % 9
                    val riverKinds = q.river.map { Tiles.kind(it.id) }.toSet()
                    val lowSuji = n - 3 < 0 || (k - 3) in riverKinds
                    val highSuji = n + 3 > 8 || (k + 3) in riverKinds
                    when {
                        lowSuji && highSuji -> if (n == 0 || n == 8) 2 else 4
                        n == 0 || n == 8 -> 6
                        n == 1 || n == 7 -> 9
                        else -> 12
                    }
                }
            }
            worst = maxOf(worst, d)
        }
        return worst
    }

    // ------------------------------------------------------------------ calls

    fun decideCall(game: Game, seat: Int, o: CallOptions): CallAction {
        if (o.canRon) return CallAction.Ron
        val p = game.players[seat]
        if (game.players.any { it.seat != seat && it.riichi > 0 }) return CallAction.Pass
        val counts = p.handKinds()
        val before = Shanten.of(counts, p.melds.size)
        val k = Tiles.kind(o.tile)
        fun after(remove: List<Int>): Int {
            val c = counts.copyOf()
            for (id in remove) c[Tiles.kind(id)]--
            // After a call the player discards one more tile: best shanten over discards.
            return bestShantenAfterDiscard(c, p.melds.size + 1)
        }
        val yakuhai = Tiles.isDragon(k) || k == game.seatWind(seat) || k == game.roundWindKind
        for (ids in o.pon) {
            if (after(ids) < before && (yakuhai || hasOpenYakuPath(game, seat, ids + o.tile))) return CallAction.Pon(ids)
        }
        for (ids in o.chi) {
            if (after(ids) < before && hasOpenYakuPath(game, seat, ids + o.tile)) return CallAction.Chi(ids)
        }
        return CallAction.Pass
    }

    /** Would the hand still have a likely yaku after calling these tiles? */
    private fun hasOpenYakuPath(game: Game, seat: Int, meldTiles: List<Int>): Boolean {
        val p = game.players[seat]
        val meldKinds = p.melds.flatMap { it.tiles.map(Tiles::kind) } + meldTiles.map(Tiles::kind)
        val handKinds = p.hand.map(Tiles::kind)
        // Already has a yakuhai triplet open.
        if (p.melds.any { it.type != MeldType.CHI && (Tiles.isDragon(it.kind) || it.kind == game.seatWind(seat) || it.kind == game.roundWindKind) }) return true
        // Yakuhai pair in hand that can still become a triplet.
        val c = p.handKinds()
        val yakuhaiPair = (Tiles.HAKU..Tiles.CHUN).any { c[it] >= 2 } || c[game.seatWind(seat)] >= 2 || c[game.roundWindKind] >= 2
        if (yakuhaiPair) return true
        // Tanyao: everything called is simple and at most one non-simple left in hand.
        if (game.rules.kuitan && meldKinds.all { Tiles.isSimple(it) } && handKinds.count { !Tiles.isSimple(it) } <= 1) return true
        // Flush: all called tiles and nearly all hand tiles in one suit (+ honors).
        val suit = meldKinds.firstOrNull { it < 27 }?.div(9) ?: return false
        if (meldKinds.all { it >= 27 || it / 9 == suit } && handKinds.count { it < 27 && it / 9 != suit } <= 1) return true
        return false
    }
}
