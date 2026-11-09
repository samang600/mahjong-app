package io.github.mahjongapp.core

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Current CPU (shared Strategy) against the frozen v0.3 CPU, one new seat per game, rotating. */
class StrategyMatchTest {

    private class Stats { var games = 0; var rankSum = 0; var scoreSum = 0L; var wins = 0; var dealIns = 0; var rounds = 0 }

    private fun play(seed: Long, newSeat: Int, newS: Stats, oldS: Stats) {
        val g = Game(Rules(), seed)
        var steps = 0
        while (g.phase != Phase.GAME_END) {
            check(++steps < 200_000)
            when (g.phase) {
                Phase.TURN -> g.doTurn(if (g.turnSeat == newSeat) Ai.decideTurn(g, g.turnSeat) else BaselineAi.decideTurn(g, g.turnSeat))
                Phase.CALLS -> for ((s, o) in g.pendingCalls.toMap()) g.respond(s, Ai.decideCall(g, s, o))
                Phase.ROUND_END -> g.nextRound()
                Phase.GAME_END -> {}
            }
            assertEquals(100_000, g.totalPoints())
        }
        val ranking = g.ranking()
        for (seat in 0 until 4) {
            val st = if (seat == newSeat) newS else oldS
            st.games++
            st.rankSum += ranking.indexOf(seat) + 1
            st.scoreSum += g.players[seat].score
        }
        for (e in g.events) {
            if (e is GameEvent.StartRound) { newS.rounds++; oldS.rounds += 3 }
            if (e is GameEvent.Win) {
                (if (e.info.seat == newSeat) newS else oldS).wins++
                if (!e.info.tsumo) (if (e.info.from == newSeat) newS else oldS).dealIns++
            }
        }
    }

    @Test
    fun newStrategyAgainstBaseline() {
        val n = (System.getenv("STRATEGY_GAMES") ?: System.getProperty("strategyGames"))?.toIntOrNull() ?: 400
        assumeTrue(n > 0)
        val newS = Stats(); val oldS = Stats()
        for (i in 0 until n) play(1000L + i, i % 4, newS, oldS)
        fun line(name: String, s: Stats) = "%s: avg rank %.3f, avg score %d, win %.1f%%, deal-in %.1f%% of rounds".format(
            name, s.rankSum.toDouble() / s.games, s.scoreSum / s.games, 100.0 * s.wins / s.rounds, 100.0 * s.dealIns / s.rounds)
        println("MATCH ($n games)")
        println(line("new strategy", newS))
        println(line("baseline    ", oldS))
    }
}
