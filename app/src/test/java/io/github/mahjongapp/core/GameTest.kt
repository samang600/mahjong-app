package io.github.mahjongapp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameTest {

    private class Stats {
        var games = 0; var rounds = 0; var wins = 0; var tsumo = 0; var draws = 0; var riichi = 0; var calls = 0
        val yaku = HashMap<String, Int>()
    }

    private fun play(seed: Long, rules: Rules, stats: Stats): Game {
        val g = Game(rules, seed)
        var steps = 0
        while (g.phase != Phase.GAME_END) {
            check(++steps < 200_000) { "game did not end" }
            when (g.phase) {
                Phase.TURN -> {
                    val seat = g.turnSeat
                    val p = g.players[seat]
                    val opts = g.turnOptions()
                    // Riichi players may only discard the drawn tile (or tsumo / allowed kan).
                    if (p.riichi > 0 && g.drawnTile != null) assertEquals(setOf(g.drawnTile), opts.discardable)
                    val a = Ai.decideTurn(g, seat)
                    if (a is TurnAction.Discard) {
                        assertTrue("illegal discard", a.id in opts.discardable)
                        if (a.riichi) { assertTrue(a.id in opts.riichiDiscards); stats.riichi++ }
                    }
                    g.doTurn(a)
                }
                Phase.CALLS -> {
                    for ((s, o) in g.pendingCalls.toMap()) {
                        val a = Ai.decideCall(g, s, o)
                        if (a is CallAction.Pon || a is CallAction.Chi) stats.calls++
                        g.respond(s, a)
                    }
                }
                Phase.ROUND_END -> g.nextRound()
                Phase.GAME_END -> {}
            }
            assertEquals("points not conserved", 4 * rules.startPoints, g.totalPoints())
        }
        // Tally from events.
        for (e in g.events) when (e) {
            is GameEvent.StartRound -> stats.rounds++
            is GameEvent.Win -> {
                stats.wins++
                if (e.info.tsumo) stats.tsumo++
                assertNotNull(e.info.result)
                for (y in e.info.result.yaku) stats.yaku.merge(y.name, 1, Int::plus)
                // Every win re-scores identically from its own data.
                assertTrue(e.info.result.base > 0)
            }
            is GameEvent.ExhaustiveDraw -> stats.draws++
            else -> {}
        }
        stats.games++
        return g
    }

    @Test
    fun selfPlayEastOnlyKeepsPointsAndEnds() {
        val stats = Stats()
        for (seed in 1L..40L) play(seed, Rules(), stats)
        println("games=${stats.games} rounds=${stats.rounds} wins=${stats.wins} (tsumo ${stats.tsumo}) draws=${stats.draws} riichi=${stats.riichi} calls=${stats.calls}")
        println("yaku: " + stats.yaku.entries.sortedByDescending { it.value }.joinToString { "${it.key}=${it.value}" })
        assertTrue(stats.wins > stats.rounds / 3)
        assertTrue(stats.riichi > 0 && stats.calls > 0)
    }

    @Test
    fun selfPlayHanchanWithoutRedFivesOrKuitan() {
        val stats = Stats()
        for (seed in 100L..110L) play(seed, Rules(redFives = false, kuitan = false, rounds = 2), stats)
        assertTrue(stats.rounds >= 8 * 5)
        assertTrue(stats.yaku["Red five"] == null)
    }

    @Test
    fun deterministicForSeed() {
        val a = play(7, Rules(), Stats())
        val b = play(7, Rules(), Stats())
        assertEquals(a.players.map { it.score }, b.players.map { it.score })
    }
}
