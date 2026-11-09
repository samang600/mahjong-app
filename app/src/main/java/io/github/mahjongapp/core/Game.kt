package io.github.mahjongapp.core

import kotlin.random.Random

/** A tile in a player's discard river. */
data class RiverTile(val id: Int, val tsumogiri: Boolean, val riichi: Boolean, var calledAway: Boolean = false)

class PlayerState(val seat: Int) {
    val hand = ArrayList<Int>()
    val melds = ArrayList<Meld>()
    val river = ArrayList<RiverTile>()
    var score = 0
    /** 0 none, 1 riichi, 2 double riichi. */
    var riichi = 0
    var ippatsu = false
    /** Missed a ron since the last own discard. */
    var tempFuriten = false
    /** Missed a ron after declaring riichi. */
    var riichiFuriten = false
    /** Kinds this player may not discard right after a call (kuikae). */
    val forbidden = HashSet<Int>()

    val closed get() = melds.none { it.isOpen }
    fun handKinds() = Tiles.counts(hand.map(Tiles::kind))
}

sealed interface TurnAction {
    data class Discard(val id: Int, val riichi: Boolean = false) : TurnAction
    data object Tsumo : TurnAction
    data class Ankan(val kind: Int) : TurnAction
    data class Kakan(val kind: Int) : TurnAction
}

sealed interface CallAction {
    data object Pass : CallAction
    data object Ron : CallAction
    data class Pon(val ids: List<Int>) : CallAction
    data class Chi(val ids: List<Int>) : CallAction
    data object Minkan : CallAction
}

data class TurnOptions(
    val canTsumo: Boolean,
    /** Tiles (ids) that may be discarded with a riichi declaration. */
    val riichiDiscards: Set<Int>,
    val ankan: List<Int>,
    val kakan: List<Int>,
    /** Ids that may be discarded now. */
    val discardable: Set<Int>,
)

data class CallOptions(
    val tile: Int,
    val from: Int,
    val canRon: Boolean,
    val pon: List<List<Int>>,
    val chi: List<List<Int>>,
    val minkan: Boolean,
    /** The call opportunity comes from an added kan (only ron = chankan is possible). */
    val chankan: Boolean = false,
) {
    val hasAny get() = canRon || pon.isNotEmpty() || chi.isNotEmpty() || minkan
}

data class WinInfo(
    val seat: Int,
    val from: Int,
    val tsumo: Boolean,
    val result: WinResult,
    val hand: List<Int>,
    val melds: List<Meld>,
    val winTile: Int,
    val uraIndicators: List<Int>,
)

data class RoundResult(
    val wins: List<WinInfo>,
    /** Exhaustive draw: seats in tenpai. */
    val tenpai: List<Int>,
    val draw: Boolean,
    val deltas: IntArray,
)

enum class Phase { TURN, CALLS, ROUND_END, GAME_END }

/** Something that happened, for animation, logs and (later) mjai export. */
sealed interface GameEvent {
    data class StartRound(val wind: Int, val kyoku: Int, val honba: Int, val dealer: Int) : GameEvent
    data class Draw(val seat: Int, val id: Int, val rinshan: Boolean) : GameEvent
    data class Discard(val seat: Int, val id: Int, val tsumogiri: Boolean, val riichi: Boolean) : GameEvent
    data class Call(val seat: Int, val meld: Meld) : GameEvent
    data class Dora(val indicator: Int) : GameEvent
    data class Win(val info: WinInfo) : GameEvent
    data class ExhaustiveDraw(val tenpai: List<Int>) : GameEvent
}

/**
 * Riichi mahjong game for four players (seats 0..3, seat 0 starts as the first dealer).
 *
 * The game advances only through [doTurn] (in [Phase.TURN], by [turnSeat]) and [respond] (in
 * [Phase.CALLS], by every seat in [pendingCalls]); draws happen automatically. Agents (CPU or UI)
 * decide; the game validates nothing beyond what the options allow.
 */
class Game(val rules: Rules = Rules(), seed: Long = System.nanoTime()) {
    private val rng = Random(seed)
    val players = Array(4) { PlayerState(it).also { p -> p.score = rules.startPoints } }
    val events = ArrayList<GameEvent>()

    /** 0 = east round, 1 = south round. */
    var roundWind = 0; private set
    /** Dealer seat = kyoku (0..3). */
    var kyoku = 0; private set
    var honba = 0; private set
    var riichiSticks = 0; private set

    var phase = Phase.TURN; private set
    var turnSeat = 0; private set
    /** The tile drawn this turn (null after a call). */
    var drawnTile: Int? = null; private set

    private val wall = ArrayList<Int>()
    private val deadWall = ArrayList<Int>()
    private var rinshanUsed = 0
    val doraIndicators = ArrayList<Int>()
    private val uraIndicators = ArrayList<Int>()
    private var kanCount = 0
    private var rinshanDraw = false
    /** No call has happened yet this round (first go-around flags). */
    private var uninterrupted = true
    private var discardCount = 0

    // pending call state
    var pendingCalls: Map<Int, CallOptions> = emptyMap(); private set
    private val responses = HashMap<Int, CallAction>()
    private var pendingKakan: Meld? = null
    private var riichiDeclared = false

    var lastResult: RoundResult? = null; private set

    val dealer get() = kyoku
    val wallRemaining get() = wall.size
    fun seatWind(seat: Int) = Tiles.EAST + (seat - dealer + 4) % 4
    val roundWindKind get() = Tiles.EAST + roundWind

    init {
        startRound()
    }

    // ------------------------------------------------------------------ round setup

    private fun startRound() {
        val tiles = (0 until Tiles.COUNT).shuffled(rng)
        wall.clear(); deadWall.clear(); doraIndicators.clear(); uraIndicators.clear()
        deadWall.addAll(tiles.subList(0, 14))
        wall.addAll(tiles.subList(14, tiles.size))
        rinshanUsed = 0; kanCount = 0
        doraIndicators += deadWall[0]
        uraIndicators += deadWall[1]
        for (p in players) {
            p.hand.clear(); p.melds.clear(); p.river.clear(); p.riichi = 0; p.ippatsu = false
            p.tempFuriten = false; p.riichiFuriten = false; p.forbidden.clear()
        }
        repeat(13) { for (i in 0 until 4) players[(dealer + i) % 4].hand += wall.removeAt(wall.size - 1) }
        for (p in players) p.hand.sortWith(compareBy({ Tiles.kind(it) }, { it }))
        uninterrupted = true
        discardCount = 0
        lastResult = null
        events += GameEvent.StartRound(roundWind, kyoku, honba, dealer)
        draw(dealer, rinshan = false)
    }

    private fun draw(seat: Int, rinshan: Boolean) {
        val id = if (rinshan) deadWall[10 + rinshanUsed++].also { wall.removeAt(0) } else wall.removeAt(wall.size - 1)
        players[seat].hand += id
        drawnTile = id
        rinshanDraw = rinshan
        turnSeat = seat
        phase = Phase.TURN
        events += GameEvent.Draw(seat, id, rinshan)
    }

    private fun flipDora() {
        doraIndicators += deadWall[2 * doraIndicators.size]
        uraIndicators += deadWall[2 * uraIndicators.size + 1]
        events += GameEvent.Dora(doraIndicators.last())
    }

    // ------------------------------------------------------------------ queries

    /** Kinds that complete this player's 13-tile hand. */
    fun waits(seat: Int, hand: List<Int> = players[seat].hand): List<Int> {
        val p = players[seat]
        val c = Tiles.counts(hand.map(Tiles::kind))
        if (Shanten.of(c, p.melds.size) != 0) return emptyList()
        return (0 until Tiles.KINDS).filter { k ->
            if (c[k] >= 4) false else {
                c[k]++; val ok = Shanten.of(c, p.melds.size) == -1; c[k]--; ok
            }
        }
    }

    fun isFuriten(seat: Int): Boolean {
        val p = players[seat]
        if (p.tempFuriten || p.riichiFuriten) return true
        val own = p.river.map { Tiles.kind(it.id) }.toSet()
        return waits(seat).any { it in own }
    }

    private fun winContext(seat: Int, winTile: Int, tsumo: Boolean, chankan: Boolean = false): WinContext {
        val p = players[seat]
        return WinContext(
            winTile = winTile, tsumo = tsumo, seatWind = seatWind(seat), roundWind = roundWindKind,
            dealer = seat == dealer, riichi = p.riichi, ippatsu = p.ippatsu,
            rinshan = tsumo && rinshanDraw, chankan = chankan,
            lastTile = wall.isEmpty() && !rinshanDraw,
            firstDraw = tsumo && uninterrupted && p.river.isEmpty(),
            doraIndicators = doraIndicators.toList(), uraIndicators = uraIndicators.toList(),
        )
    }

    fun turnOptions(): TurnOptions {
        val p = players[turnSeat]
        val drawn = drawnTile
        val counts = p.handKinds()
        val canTsumo = drawn != null &&
            Scoring.score(p.hand, p.melds, winContext(turnSeat, drawn, tsumo = true), rules) != null
        val canKan = drawn != null && wall.isNotEmpty() && kanCount < 4
        val ankan = if (!canKan) emptyList() else (0 until Tiles.KINDS).filter { k ->
            counts[k] == 4 && (p.riichi == 0 || (Tiles.kind(drawn!!) == k && riichiKanKeepsWaits(p, k)))
        }
        val kakan = if (!canKan || p.riichi > 0) emptyList() else p.melds.filter { it.type == MeldType.PON && counts[it.kind] > 0 }.map { it.kind }
        val discardable: Set<Int> = when {
            p.riichi > 0 && drawn != null -> setOf(drawn)
            else -> p.hand.filter { Tiles.kind(it) !in p.forbidden }.toSet().ifEmpty { p.hand.toSet() }
        }
        val riichiDiscards = if (p.riichi == 0 && p.closed && p.score >= 1000 && wall.size >= 4 && drawn != null) {
            p.hand.filter { id ->
                val rest = ArrayList(p.hand).apply { remove(id) }
                Shanten.of(Tiles.counts(rest.map(Tiles::kind)), p.melds.size) == 0
            }.toSet()
        } else emptySet()
        return TurnOptions(canTsumo, riichiDiscards, ankan, kakan, discardable)
    }

    private fun riichiKanKeepsWaits(p: PlayerState, kind: Int): Boolean {
        val before = waits(p.seat, p.hand.filter { it != drawnTile })
        val after = p.hand.filter { Tiles.kind(it) != kind }
        val c = Tiles.counts(after.map(Tiles::kind))
        if (Shanten.of(c, p.melds.size + 1) != 0) return false
        val afterWaits = (0 until Tiles.KINDS).filter { k -> c[k] < 4 && run { c[k]++; val ok = Shanten.of(c, p.melds.size + 1) == -1; c[k]--; ok } }
        return before == afterWaits
    }

    // ------------------------------------------------------------------ turn actions

    fun doTurn(action: TurnAction) {
        check(phase == Phase.TURN)
        val p = players[turnSeat]
        when (action) {
            is TurnAction.Tsumo -> {
                val drawn = drawnTile ?: error("no tile to tsumo")
                val r = Scoring.score(p.hand, p.melds, winContext(turnSeat, drawn, true), rules) ?: error("not a win")
                finishWins(listOf(WinInfo(turnSeat, turnSeat, true, r, p.hand.toList(), p.melds.toList(), drawn, uraIndicators.toList())))
            }
            is TurnAction.Ankan -> {
                val ids = p.hand.filter { Tiles.kind(it) == action.kind }
                require(ids.size == 4)
                p.hand.removeAll(ids.toSet())
                val meld = Meld(MeldType.ANKAN, ids, -1, -1)
                p.melds += meld
                afterKan(meld)
            }
            is TurnAction.Kakan -> {
                val idx = p.melds.indexOfFirst { it.type == MeldType.PON && it.kind == action.kind }
                val id = p.hand.first { Tiles.kind(it) == action.kind }
                p.hand.remove(id)
                val old = p.melds[idx]
                val meld = Meld(MeldType.KAKAN, old.tiles + id, old.called, old.from)
                p.melds[idx] = meld
                // Chankan chance for the others.
                pendingKakan = meld
                val opts = HashMap<Int, CallOptions>()
                for (s in others(turnSeat)) {
                    if (canRonOn(s, id, chankan = true)) opts[s] = CallOptions(id, turnSeat, true, emptyList(), emptyList(), false, chankan = true)
                }
                if (opts.isEmpty()) { pendingKakan = null; afterKan(meld) } else openCalls(opts)
            }
            is TurnAction.Discard -> discard(action.id, action.riichi)
        }
    }

    private fun afterKan(meld: Meld) {
        kanCount++
        uninterrupted = false
        for (q in players) q.ippatsu = false
        events += GameEvent.Call(turnSeat, meld)
        flipDora()
        draw(turnSeat, rinshan = true)
    }

    private fun discard(id: Int, riichi: Boolean) {
        val p = players[turnSeat]
        require(id in p.hand)
        val tsumogiri = id == drawnTile
        p.hand.remove(id)
        p.hand.sortWith(compareBy({ Tiles.kind(it) }, { it }))
        p.forbidden.clear()
        p.tempFuriten = false
        if (p.ippatsu && p.riichi > 0) p.ippatsu = false
        riichiDeclared = riichi
        if (riichi) {
            p.riichi = if (uninterrupted && p.river.isEmpty()) 2 else 1
        }
        p.river += RiverTile(id, tsumogiri, riichi)
        discardCount++
        if (discardCount > 4) uninterrupted = false
        drawnTile = null
        events += GameEvent.Discard(turnSeat, id, tsumogiri, riichi)

        val opts = HashMap<Int, CallOptions>()
        val kind = Tiles.kind(id)
        for (s in others(turnSeat)) {
            val q = players[s]
            val ron = canRonOn(s, id)
            val canCall = wall.isNotEmpty() && q.riichi == 0
            val same = q.hand.filter { Tiles.kind(it) == kind }
            val pon = if (canCall && same.size >= 2) ponChoices(same) else emptyList()
            val minkan = canCall && same.size == 3 && kanCount < 4
            val chi = if (canCall && s == (turnSeat + 1) % 4) chiChoices(q, id) else emptyList()
            val o = CallOptions(id, turnSeat, ron, pon, chi, minkan)
            if (o.hasAny) opts[s] = o
        }
        if (opts.isEmpty()) afterDiscardNoCall() else openCalls(opts)
    }

    private fun ponChoices(same: List<Int>): List<List<Int>> {
        // Distinguish red-five choices; otherwise one choice is enough.
        val choices = LinkedHashSet<List<Int>>()
        for (i in same.indices) for (j in i + 1 until same.size) {
            val pair = listOf(same[i], same[j]).sorted()
            val key = pair.map { Tiles.isRed(it, rules.redFives) }
            if (choices.none { c -> c.map { Tiles.isRed(it, rules.redFives) } == key }) choices += pair
        }
        return choices.toList()
    }

    private fun chiChoices(q: PlayerState, id: Int): List<List<Int>> {
        val k = Tiles.kind(id)
        if (k >= 27) return emptyList()
        val n = k % 9
        val out = ArrayList<List<Int>>()
        for (start in (n - 2)..n) {
            if (start < 0 || start + 2 > 8) continue
            val need = (start..start + 2).map { k - n + it }.filter { it != k }
            val a = q.hand.filter { Tiles.kind(it) == need[0] }
            val b = q.hand.filter { Tiles.kind(it) == need[1] }
            if (a.isEmpty() || b.isEmpty()) continue
            // Prefer non-red tiles; offer the red variant separately.
            val pick = { l: List<Int> -> l.sortedBy { if (Tiles.isRed(it, rules.redFives)) 1 else 0 } }
            val variants = LinkedHashSet<List<Int>>()
            for (x in pick(a).take(2)) for (y in pick(b).take(2)) variants += listOf(x, y)
            out += variants.distinctBy { v -> v.map { Tiles.isRed(it, rules.redFives) } }
        }
        // A chi must leave at least one legal discard (kuikae).
        return out.filter { pair ->
            val rest = q.hand.toMutableList().apply { removeAll(pair.toSet()) }
            val forb = kuikae(MeldType.CHI, k, pair)
            rest.any { Tiles.kind(it) !in forb }
        }
    }

    private fun kuikae(type: MeldType, calledKind: Int, own: List<Int>): Set<Int> {
        val s = hashSetOf(calledKind)
        if (type == MeldType.CHI) {
            val kinds = (own.map(Tiles::kind) + calledKind).sorted()
            val n = calledKind % 9
            if (calledKind == kinds[0] && n + 3 <= 8) s += calledKind + 3
            if (calledKind == kinds[2] && n - 3 >= 0) s += calledKind - 3
        }
        return s
    }

    private fun canRonOn(seat: Int, id: Int, chankan: Boolean = false): Boolean {
        val p = players[seat]
        if (isFuriten(seat)) return false
        if (Tiles.kind(id) !in waits(seat)) return false
        return Scoring.score(p.hand + id, p.melds, winContext(seat, id, tsumo = false, chankan = chankan), rules) != null
    }

    private fun others(seat: Int) = (1..3).map { (seat + it) % 4 }

    private fun openCalls(opts: Map<Int, CallOptions>) {
        pendingCalls = opts
        responses.clear()
        phase = Phase.CALLS
    }

    // ------------------------------------------------------------------ call responses

    fun hasResponded(seat: Int) = seat in responses

    fun respond(seat: Int, action: CallAction) {
        check(phase == Phase.CALLS && seat in pendingCalls && seat !in responses)
        responses[seat] = action
        if (responses.size == pendingCalls.size) resolveCalls()
    }

    private fun resolveCalls() {
        val from = pendingCalls.values.first().from
        val tile = pendingCalls.values.first().tile
        val order = others(from)
        // Missed rons cause furiten.
        for ((s, o) in pendingCalls) {
            if (o.canRon && responses[s] != CallAction.Ron) {
                players[s].tempFuriten = true
                if (players[s].riichi > 0) players[s].riichiFuriten = true
            }
        }
        val kakan = pendingKakan
        pendingCalls = emptyMap()
        val rons = order.filter { responses[it] == CallAction.Ron }
        if (rons.isNotEmpty()) {
            if (kakan == null) players[from].river.last().calledAway = true
            finishWins(rons.map { s ->
                val p = players[s]
                val r = Scoring.score(p.hand + tile, p.melds, winContext(s, tile, false, chankan = kakan != null), rules)!!
                WinInfo(s, from, false, r, p.hand + tile, p.melds.toList(), tile, uraIndicators.toList())
            })
            return
        }
        if (kakan != null) {
            pendingKakan = null
            turnSeat = from
            afterKan(kakan)
            return
        }
        establishRiichi(from)
        val caller = order.firstOrNull { responses[it] is CallAction.Pon || responses[it] == CallAction.Minkan }
            ?: order.firstOrNull { responses[it] is CallAction.Chi }
        if (caller == null) {
            afterDiscardNoCall(riichiDone = true)
            return
        }
        val p = players[caller]
        players[from].river.last().calledAway = true
        uninterrupted = false
        for (q in players) q.ippatsu = false
        when (val a = responses[caller]) {
            is CallAction.Pon -> {
                p.hand.removeAll(a.ids.toSet())
                val meld = Meld(MeldType.PON, a.ids + tile, tile, from)
                p.melds += meld
                p.forbidden.clear(); p.forbidden += kuikae(MeldType.PON, Tiles.kind(tile), a.ids)
                callDone(caller, meld)
            }
            is CallAction.Chi -> {
                p.hand.removeAll(a.ids.toSet())
                val meld = Meld(MeldType.CHI, (a.ids + tile).sortedBy(Tiles::kind), tile, from)
                p.melds += meld
                p.forbidden.clear(); p.forbidden += kuikae(MeldType.CHI, Tiles.kind(tile), a.ids)
                callDone(caller, meld)
            }
            CallAction.Minkan -> {
                val ids = p.hand.filter { Tiles.kind(it) == Tiles.kind(tile) }
                p.hand.removeAll(ids.toSet())
                val meld = Meld(MeldType.MINKAN, ids + tile, tile, from)
                p.melds += meld
                turnSeat = caller
                afterKan(meld)
            }
            else -> error("unexpected")
        }
    }

    private fun callDone(seat: Int, meld: Meld) {
        events += GameEvent.Call(seat, meld)
        turnSeat = seat
        drawnTile = null
        rinshanDraw = false
        phase = Phase.TURN
    }

    private fun establishRiichi(seat: Int) {
        if (!riichiDeclared) return
        riichiDeclared = false
        val p = players[seat]
        p.score -= 1000
        riichiSticks++
        p.ippatsu = true
    }

    private fun afterDiscardNoCall(riichiDone: Boolean = false) {
        if (!riichiDone) establishRiichi(turnSeat)
        if (wall.isEmpty()) {
            exhaustiveDraw()
            return
        }
        draw((turnSeat + 1) % 4, rinshan = false)
    }

    // ------------------------------------------------------------------ round end

    private fun exhaustiveDraw() {
        val tenpai = (0 until 4).filter { s -> waits(s).isNotEmpty() }
        val deltas = IntArray(4)
        if (tenpai.size in 1..3) {
            val gain = 3000 / tenpai.size
            val loss = 3000 / (4 - tenpai.size)
            for (s in 0 until 4) deltas[s] = if (s in tenpai) gain else -loss
        }
        for (s in 0 until 4) players[s].score += deltas[s]
        events += GameEvent.ExhaustiveDraw(tenpai)
        lastResult = RoundResult(emptyList(), tenpai, true, deltas)
        phase = Phase.ROUND_END
        advanceAfter(dealerKeeps = dealer in tenpai, honbaUp = true)
    }

    private fun finishWins(wins: List<WinInfo>) {
        val deltas = IntArray(4)
        for ((i, w) in wins.withIndex()) {
            val isDealer = w.seat == dealer
            if (w.tsumo) {
                val (fromDealer, fromOther) = w.result.tsumoPoints(isDealer)
                for (s in others(w.seat)) {
                    val pay = (if (s == dealer || isDealer) fromDealer else fromOther) + 100 * honba
                    deltas[s] -= pay; deltas[w.seat] += pay
                }
            } else {
                // Honba goes to the first winner only.
                val pay = w.result.ronPoints(isDealer) + if (i == 0) 300 * honba else 0
                deltas[w.from] -= pay; deltas[w.seat] += pay
            }
        }
        deltas[wins[0].seat] += 1000 * riichiSticks
        riichiSticks = 0
        for (s in 0 until 4) players[s].score += deltas[s]
        for (w in wins) events += GameEvent.Win(w)
        lastResult = RoundResult(wins, emptyList(), false, deltas)
        phase = Phase.ROUND_END
        advanceAfter(dealerKeeps = wins.any { it.seat == dealer }, honbaUp = wins.any { it.seat == dealer })
    }

    private var nextDealerKeeps = false
    private var nextHonbaUp = false

    private fun advanceAfter(dealerKeeps: Boolean, honbaUp: Boolean) {
        nextDealerKeeps = dealerKeeps
        nextHonbaUp = honbaUp
        val bust = players.any { it.score < 0 }
        val lastRound = roundWind == rules.rounds - 1 && kyoku == 3
        if (bust || (lastRound && !dealerKeeps)) phase = Phase.GAME_END
    }

    /** Starts the next round after [Phase.ROUND_END]. */
    fun nextRound() {
        check(phase == Phase.ROUND_END)
        honba = if (nextHonbaUp) honba + 1 else 0
        if (!nextDealerKeeps) {
            kyoku++
            if (kyoku == 4) { kyoku = 0; roundWind++ }
        }
        startRound()
    }

    /** Final ranking: seats sorted by score (ties: closer to the first dealer wins). Leftover sticks go to the top. */
    fun ranking(): List<Int> = (0 until 4).sortedWith(compareByDescending<Int> { players[it].score }.thenBy { it })

    /** Total points in play (scores + riichi sticks): always 4 x start points. */
    fun totalPoints() = players.sumOf { it.score } + 1000 * riichiSticks

    /** Tiles visible to [seat]: rivers, open melds, dora indicators and its own hand. */
    fun visibleCounts(seat: Int): IntArray {
        val c = IntArray(Tiles.KINDS)
        for (p in players) {
            for (r in p.river) if (!r.calledAway) c[Tiles.kind(r.id)]++
            for (m in p.melds) for (t in m.tiles) c[Tiles.kind(t)]++
        }
        for (d in doraIndicators) c[Tiles.kind(d)]++
        for (t in players[seat].hand) c[Tiles.kind(t)]++
        return c
    }
}
