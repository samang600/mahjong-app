package io.github.mahjongapp.core

/** Rule options. */
data class Rules(
    val redFives: Boolean = true,
    val kuitan: Boolean = true,
    /** 1 = east-only (tonpuusen), 2 = east + south (hanchan). */
    val rounds: Int = 1,
    val startPoints: Int = 25000,
)

/** Everything about a win that the hand itself does not tell. */
data class WinContext(
    val winTile: Int,
    val tsumo: Boolean,
    val seatWind: Int,
    val roundWind: Int,
    val dealer: Boolean,
    /** 0 = none, 1 = riichi, 2 = double riichi. */
    val riichi: Int = 0,
    val ippatsu: Boolean = false,
    val rinshan: Boolean = false,
    val chankan: Boolean = false,
    /** Win on the last tile of the wall (haitei by tsumo, houtei by ron). */
    val lastTile: Boolean = false,
    /** Tenhou / chiihou (dealer / non-dealer win on the first uninterrupted draw). */
    val firstDraw: Boolean = false,
    val doraIndicators: List<Int> = emptyList(),
    val uraIndicators: List<Int> = emptyList(),
)

data class Yaku(val name: String, val han: Int, val yakuman: Int = 0)

data class WinResult(
    val yaku: List<Yaku>,
    /** han including dora (0 for yakuman). */
    val han: Int,
    val fu: Int,
    val yakuman: Int,
    /** Base points (fu * 2^(han+2), capped by limit hands). */
    val base: Int,
    val limitName: String?,
) {
    val dora get() = yaku.filter { it.name in DORA_NAMES }.sumOf { it.han }

    /** Points paid by the discarder on ron (before honba). */
    fun ronPoints(dealer: Boolean) = roundUp(base * if (dealer) 6 else 4)

    /** Tsumo payments (before honba): first = paid by the dealer (or each player if the winner is dealer), second = by each non-dealer. */
    fun tsumoPoints(dealer: Boolean): Pair<Int, Int> =
        if (dealer) roundUp(base * 2).let { it to it } else roundUp(base * 2) to roundUp(base)

    companion object {
        val DORA_NAMES = setOf("Dora", "Ura dora", "Red five")
        fun roundUp(x: Int) = (x + 99) / 100 * 100
    }
}

/**
 * Win detection, yaku, fu and points. [concealed] are the hand's physical tile ids including the
 * winning tile; [melds] are called sets (including closed kans).
 */
object Scoring {

    private enum class GType { SEQ, TRIP, PAIR }

    private data class Group(val type: GType, val kind: Int, val open: Boolean, val kan: Boolean)

    /** Is this a complete hand (any form)? [concealedKinds] counts include the winning tile. */
    fun isComplete(concealedKinds: IntArray, melds: Int): Boolean = Shanten.of(concealedKinds, melds) == -1

    /**
     * Scores the hand, or null when it is not a complete hand or has no yaku.
     */
    fun score(concealed: List<Int>, melds: List<Meld>, ctx: WinContext, rules: Rules): WinResult? {
        val counts = Tiles.counts(concealed.map(Tiles::kind))
        if (!isComplete(counts, melds.size)) return null
        val winKind = Tiles.kind(ctx.winTile)
        val closed = melds.none { it.isOpen }
        val candidates = ArrayList<WinResult>()

        // Kokushi
        if (melds.isEmpty() && Shanten.kokushi(counts) == -1) {
            candidates += limitResult(listOf(Yaku("Kokushi musou", 13, 1)) + situationalYakuman(ctx))
        }
        // Chiitoitsu
        if (melds.isEmpty() && Shanten.chiitoi(counts) == -1 && counts.none { it == 4 }) {
            candidates += scoreChiitoi(counts, concealed, ctx, rules)
        }
        // Standard decompositions
        val meldGroups = melds.map {
            Group(if (it.isSequence) GType.SEQ else GType.TRIP, it.kind, it.isOpen, it.isKan)
        }
        for (dec in decompose(counts)) {
            // The winning tile can complete any closed group that contains it.
            val winIdx = dec.indices.filter { i ->
                val g = dec[i]
                when (g.type) {
                    GType.SEQ -> winKind in g.kind..g.kind + 2
                    else -> g.kind == winKind
                }
            }
            for (wi in winIdx) {
                candidates += scoreStandard(dec, wi, meldGroups, melds, concealed, ctx, rules, closed) ?: continue
            }
        }
        return candidates.filter { r -> r.yakuman > 0 || r.yaku.any { it.name !in WinResult.DORA_NAMES } }
            .maxWithOrNull(compareBy<WinResult> { it.base }.thenBy { it.han }.thenBy { it.fu })
    }

    // ------------------------------------------------------------------ decomposition

    private fun decompose(counts: IntArray): List<List<Group>> {
        val out = ArrayList<List<Group>>()
        val c = counts.copyOf()
        for (p in 0 until Tiles.KINDS) {
            if (c[p] < 2) continue
            c[p] -= 2
            val sets = ArrayList<Group>()
            extract(c, 0, sets) { out += listOf(Group(GType.PAIR, p, false, false)) + it }
            c[p] += 2
        }
        return out
    }

    private fun extract(c: IntArray, start: Int, acc: ArrayList<Group>, emit: (List<Group>) -> Unit) {
        var i = start
        while (i < Tiles.KINDS && c[i] == 0) i++
        if (i == Tiles.KINDS) {
            emit(ArrayList(acc)); return
        }
        if (c[i] >= 3) {
            c[i] -= 3; acc += Group(GType.TRIP, i, false, false)
            extract(c, i, acc, emit)
            acc.removeAt(acc.size - 1); c[i] += 3
        }
        if (i < 27 && i % 9 <= 6 && c[i + 1] > 0 && c[i + 2] > 0) {
            c[i]--; c[i + 1]--; c[i + 2]--; acc += Group(GType.SEQ, i, false, false)
            extract(c, i, acc, emit)
            acc.removeAt(acc.size - 1); c[i]++; c[i + 1]++; c[i + 2]++
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun isYakuhai(kind: Int, ctx: WinContext) = Tiles.isDragon(kind) || kind == ctx.seatWind || kind == ctx.roundWind

    private fun allTileKinds(concealed: List<Int>, melds: List<Meld>) =
        concealed.map(Tiles::kind) + melds.flatMap { m -> m.tiles.map(Tiles::kind) }

    private fun doraYaku(concealed: List<Int>, melds: List<Meld>, ctx: WinContext, rules: Rules): List<Yaku> {
        val ids = concealed + melds.flatMap { it.tiles }
        val kinds = ids.map(Tiles::kind)
        val out = ArrayList<Yaku>()
        val dora = ctx.doraIndicators.sumOf { ind -> kinds.count { it == Tiles.doraFromIndicator(Tiles.kind(ind)) } }
        if (dora > 0) out += Yaku("Dora", dora)
        if (ctx.riichi > 0) {
            val ura = ctx.uraIndicators.sumOf { ind -> kinds.count { it == Tiles.doraFromIndicator(Tiles.kind(ind)) } }
            if (ura > 0) out += Yaku("Ura dora", ura)
        }
        val red = ids.count { Tiles.isRed(it, rules.redFives) }
        if (red > 0) out += Yaku("Red five", red)
        return out
    }

    private fun situationalYaku(ctx: WinContext, closed: Boolean): List<Yaku> {
        val out = ArrayList<Yaku>()
        if (ctx.riichi == 2) out += Yaku("Double riichi", 2) else if (ctx.riichi == 1) out += Yaku("Riichi", 1)
        if (ctx.ippatsu) out += Yaku("Ippatsu", 1)
        if (ctx.tsumo && closed) out += Yaku("Menzen tsumo", 1)
        if (ctx.rinshan) out += Yaku("Rinshan kaihou", 1)
        if (ctx.chankan) out += Yaku("Chankan", 1)
        if (ctx.lastTile && !ctx.rinshan) out += if (ctx.tsumo) Yaku("Haitei raoyue", 1) else Yaku("Houtei raoyui", 1)
        return out
    }

    private fun situationalYakuman(ctx: WinContext): List<Yaku> =
        if (ctx.firstDraw && ctx.tsumo) listOf(if (ctx.dealer) Yaku("Tenhou", 13, 1) else Yaku("Chiihou", 13, 1)) else emptyList()

    private fun limitResult(yakuman: List<Yaku>): WinResult {
        val n = yakuman.sumOf { it.yakuman }
        return WinResult(yakuman, 0, 0, n, 8000 * n, if (n > 1) "${n}x Yakuman" else "Yakuman")
    }

    private fun result(yaku: List<Yaku>, fu: Int): WinResult {
        val han = yaku.sumOf { it.han }
        val (base, limit) = when {
            han >= 13 -> 8000 to "Kazoe yakuman"
            han >= 11 -> 6000 to "Sanbaiman"
            han >= 8 -> 4000 to "Baiman"
            han >= 6 -> 3000 to "Haneman"
            han >= 5 -> 2000 to "Mangan"
            else -> {
                val b = fu * (1 shl (2 + han))
                if (b >= 2000) 2000 to "Mangan" else b to null
            }
        }
        return WinResult(yaku, han, fu, 0, base, limit)
    }

    // ------------------------------------------------------------------ chiitoitsu

    private fun scoreChiitoi(counts: IntArray, concealed: List<Int>, ctx: WinContext, rules: Rules): WinResult {
        val ym = situationalYakuman(ctx).toMutableList()
        val kinds = counts.indices.filter { counts[it] > 0 }
        if (kinds.all { Tiles.isHonor(it) }) ym += Yaku("Tsuuiisou", 13, 1)
        if (ym.isNotEmpty()) return limitResult(ym)
        val yaku = ArrayList<Yaku>(situationalYaku(ctx, true))
        yaku += Yaku("Chiitoitsu", 2)
        if (kinds.all { Tiles.isSimple(it) }) yaku += Yaku("Tanyao", 1)
        if (kinds.all { Tiles.isTerminalOrHonor(it) }) yaku += Yaku("Honroutou", 2)
        val suits = kinds.filter { it < 27 }.map { it / 9 }.toSet()
        val honors = kinds.any { it >= 27 }
        if (suits.size == 1) yaku += if (honors) Yaku("Honitsu", 3) else Yaku("Chinitsu", 6)
        yaku += doraYaku(concealed, emptyList(), ctx, rules)
        return result(yaku, 25)
    }

    // ------------------------------------------------------------------ standard form

    private fun scoreStandard(
        dec: List<Group>,
        winIdx: Int,
        meldGroups: List<Group>,
        melds: List<Meld>,
        concealed: List<Int>,
        ctx: WinContext,
        rules: Rules,
        closed: Boolean,
    ): WinResult? {
        val winKind = Tiles.kind(ctx.winTile)
        // A triplet completed by ron counts as open (for fu and concealed-triplet yaku).
        val hand = dec.mapIndexed { i, g ->
            if (i == winIdx && g.type == GType.TRIP && !ctx.tsumo) g.copy(open = true) else g
        }
        val pair = hand.first { it.type == GType.PAIR }
        val sets = hand.filter { it.type != GType.PAIR } + meldGroups
        val winGroup = hand[winIdx]

        // Wait shape
        val wait = when (winGroup.type) {
            GType.PAIR -> "tanki"
            GType.TRIP -> "shanpon"
            GType.SEQ -> when {
                winKind == winGroup.kind + 1 -> "kanchan"
                winKind == winGroup.kind && winGroup.kind % 9 == 6 -> "penchan"
                winKind == winGroup.kind + 2 && winGroup.kind % 9 == 0 -> "penchan"
                else -> "ryanmen"
            }
        }

        val allKinds = allTileKinds(concealed, melds)
        val trips = sets.filter { it.type == GType.TRIP }
        val seqs = sets.filter { it.type == GType.SEQ }
        val concealedTrips = trips.count { !it.open }
        val kans = sets.count { it.kan }

        // ---- yakuman
        val ym = ArrayList<Yaku>(situationalYakuman(ctx))
        if (concealedTrips == 4) ym += Yaku("Suuankou", 13, 1)
        val dragonTrips = trips.count { Tiles.isDragon(it.kind) }
        if (dragonTrips == 3) ym += Yaku("Daisangen", 13, 1)
        val windTrips = trips.count { it.kind in 27..30 }
        if (windTrips == 4) ym += Yaku("Daisuushii", 13, 1)
        else if (windTrips == 3 && pair.kind in 27..30) ym += Yaku("Shousuushii", 13, 1)
        if (allKinds.all { Tiles.isHonor(it) }) ym += Yaku("Tsuuiisou", 13, 1)
        if (allKinds.all { Tiles.isTerminal(it) }) ym += Yaku("Chinroutou", 13, 1)
        val greens = setOf(19, 20, 21, 23, 25, Tiles.HATSU)
        if (allKinds.all { it in greens }) ym += Yaku("Ryuuiisou", 13, 1)
        if (kans == 4) ym += Yaku("Suukantsu", 13, 1)
        if (closed && melds.isEmpty()) {
            val c = Tiles.counts(allKinds)
            val suit = allKinds[0] / 9
            if (allKinds.all { it < 27 && it / 9 == suit }) {
                val b = suit * 9
                val need = intArrayOf(3, 1, 1, 1, 1, 1, 1, 1, 3)
                if ((0 until 9).all { c[b + it] >= need[it] }) ym += Yaku("Chuuren poutou", 13, 1)
            }
        }
        if (ym.isNotEmpty()) return limitResult(ym)

        // ---- regular yaku
        val yaku = ArrayList<Yaku>(situationalYaku(ctx, closed))
        val pinfu = closed && seqs.size == 4 && !isYakuhai(pair.kind, ctx) && wait == "ryanmen"
        if (pinfu) yaku += Yaku("Pinfu", 1)
        if ((closed || rules.kuitan) && allKinds.all { Tiles.isSimple(it) }) yaku += Yaku("Tanyao", 1)
        if (closed) {
            val seqKinds = seqs.map { it.kind }
            val dup = seqKinds.groupingBy { it }.eachCount()
            val pairsOfSeq = dup.values.sumOf { it / 2 }
            if (pairsOfSeq >= 2) yaku += Yaku("Ryanpeikou", 3)
            else if (pairsOfSeq == 1) yaku += Yaku("Iipeikou", 1)
        }
        for (t in trips) {
            if (Tiles.isDragon(t.kind)) yaku += Yaku(
                when (t.kind) { Tiles.HAKU -> "Yakuhai: haku"; Tiles.HATSU -> "Yakuhai: hatsu"; else -> "Yakuhai: chun" }, 1
            )
            if (t.kind == ctx.seatWind) yaku += Yaku("Yakuhai: seat wind", 1)
            if (t.kind == ctx.roundWind) yaku += Yaku("Yakuhai: round wind", 1)
        }
        // Sanshoku doujun / ittsu
        val seqSet = seqs.map { it.kind }.toSet()
        if ((0..6).any { n -> n in seqSet && n + 9 in seqSet && n + 18 in seqSet }) yaku += Yaku("Sanshoku doujun", if (closed) 2 else 1)
        if ((0..2).any { s -> s * 9 in seqSet && s * 9 + 3 in seqSet && s * 9 + 6 in seqSet }) yaku += Yaku("Ittsu", if (closed) 2 else 1)
        // Chanta / junchan / honroutou
        val groups = sets + pair
        fun hasTerminalOrHonor(g: Group) = when (g.type) {
            GType.SEQ -> g.kind % 9 == 0 || g.kind % 9 == 6
            else -> Tiles.isTerminalOrHonor(g.kind)
        }
        val honorsPresent = allKinds.any { Tiles.isHonor(it) }
        if (seqs.isNotEmpty() && groups.all { hasTerminalOrHonor(it) }) {
            yaku += if (honorsPresent) Yaku("Chanta", if (closed) 2 else 1) else Yaku("Junchan", if (closed) 3 else 2)
        }
        if (allKinds.all { Tiles.isTerminalOrHonor(it) }) yaku += Yaku("Honroutou", 2)
        if (trips.size == 4) yaku += Yaku("Toitoi", 2)
        if (concealedTrips == 3) yaku += Yaku("Sanankou", 2)
        val tripKinds = trips.map { it.kind }.toSet()
        if ((0..8).any { n -> n in tripKinds && n + 9 in tripKinds && n + 18 in tripKinds }) yaku += Yaku("Sanshoku doukou", 2)
        if (kans == 3) yaku += Yaku("Sankantsu", 2)
        if (dragonTrips == 2 && Tiles.isDragon(pair.kind)) yaku += Yaku("Shousangen", 2)
        val suits = allKinds.filter { it < 27 }.map { it / 9 }.toSet()
        if (suits.size == 1) yaku += if (honorsPresent) Yaku("Honitsu", if (closed) 3 else 2) else Yaku("Chinitsu", if (closed) 6 else 5)

        if (yaku.none { it.name !in WinResult.DORA_NAMES }) return null
        yaku += doraYaku(concealed, melds, ctx, rules)

        // ---- fu
        val fu = if (pinfu) {
            if (ctx.tsumo) 20 else 30
        } else {
            var f = 20
            if (closed && !ctx.tsumo) f += 10
            if (ctx.tsumo) f += 2
            for (t in trips) {
                var v = if (Tiles.isTerminalOrHonor(t.kind)) 4 else 2
                if (!t.open) v *= 2
                if (t.kan) v *= 4
                f += v
            }
            if (Tiles.isDragon(pair.kind)) f += 2
            if (pair.kind == ctx.seatWind) f += 2
            if (pair.kind == ctx.roundWind) f += 2
            if (wait == "kanchan" || wait == "penchan" || wait == "tanki") f += 2
            if (f == 20) f = 30 // open hand with no fu (ron)
            (f + 9) / 10 * 10
        }
        return result(yaku, fu)
    }
}
