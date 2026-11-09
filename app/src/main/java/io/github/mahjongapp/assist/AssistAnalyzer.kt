package io.github.mahjongapp.assist

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import io.github.mahjongapp.R
import io.github.mahjongapp.core.HandAdvisor
import io.github.mahjongapp.core.HandTile
import io.github.mahjongapp.core.OpponentView
import io.github.mahjongapp.core.Situation
import io.github.mahjongapp.core.Strategy
import io.github.mahjongapp.ui.TileNames

/** What the overlay shows after analysing one screenshot. */
data class AssistResult(
    val text: String,
    /** Screen rectangle of the tile to discard (null when there is nothing to mark). */
    val mark: Rect?,
    val recommendedKind: Int?,
    val seen: SeenHand?,
    val state: ScreenState? = null,
    val situation: Situation? = null,
    val defending: Boolean = false,
)

/**
 * Screenshot -> table read from the screen ([ScreenReader]) -> shared [Strategy] (the same one the
 * CPU and the in-game hint use). Seat / round wind and the wall count are not read, so they are
 * left out of the situation.
 */
class AssistAnalyzer(private val context: Context) {

    private val reader = ScreenReader()

    fun situationOf(st: ScreenState): Situation {
        val n = st.hand.tiles.size
        val inferredMelds = if (n % 3 == 2) (14 - n) / 3 else (13 - n) / 3
        return Situation(
            hand = st.hand.tiles.map { HandTile(it.kind, it.red) },
            myMelds = st.melds[0],
            myMeldCount = maxOf(st.melds[0].size, inferredMelds),
            doraIndicators = st.doraIndicators,
            opponents = (1..3).map { rel ->
                val r = st.rivers[rel]
                OpponentView(r.kinds, r.riichiIndex, st.melds[rel], r.calledAway)
            },
            extraVisible = st.rivers[0].kinds.filterIndexed { i, _ -> !st.rivers[0].calledAway[i] },
        )
    }

    fun analyze(screen: Bitmap): AssistResult {
        val st = reader.read(screen)
            ?: return AssistResult(context.getString(R.string.assist_not_found), null, null, null)
        val seen = st.hand
        val sit = situationOf(st)
        val handText = context.getString(R.string.assist_seen, seen.kinds.joinToString(" ") { TileNames.of(it) })
        val tableText = tableSummary(st)
        if (seen.tiles.size % 3 != 2) {
            // Not the player's turn: report the state of the hand.
            val advice = HandAdvisor.advise(seen.kinds)
                ?: return AssistResult(context.getString(R.string.assist_not_found), null, null, seen, st, sit)
            val state = if (advice.shantenNow <= 0) {
                context.getString(R.string.assist_waiting, advice.waits.joinToString(" ") { TileNames.of(it) })
            } else context.getString(R.string.shanten, advice.shantenNow)
            return AssistResult("$state\n$tableText\n$handText", null, null, seen, st, sit)
        }
        val decision = Strategy.decide(sit)
            ?: return AssistResult(context.getString(R.string.assist_not_found), null, null, seen, st, sit)
        val best = decision.best
        val shText = if (best.shanten <= 0) context.getString(R.string.tenpai) else context.getString(R.string.shanten, best.shanten)
        val head = if (decision.defending) context.getString(R.string.hint_defend, TileNames.of(best.kind))
        else context.getString(R.string.hint_text, TileNames.of(best.kind), shText, best.ukeire)
        val text = head + "\n" + tableText + "\n" + handText
        // Mark the drawn tile if it is the one to discard, otherwise the first tile of that kind.
        val target = seen.tiles.lastOrNull()?.takeIf { seen.drawnSeparated && it.kind == best.kind && it.red == best.red }
            ?: seen.tiles.firstOrNull { it.kind == best.kind && it.red == best.red }
            ?: seen.tiles.first { it.kind == best.kind }
        return AssistResult(text, target.rect, best.kind, seen, st, sit, decision.defending)
    }

    private fun tableSummary(st: ScreenState): String {
        val dora = st.doraIndicators.joinToString(" ") { TileNames.of(io.github.mahjongapp.core.Tiles.doraFromIndicator(it)) }
        val seats = context.resources.getStringArray(R.array.assist_seats)
        val riichi = (1..3).filter { st.rivers[it].riichiIndex >= 0 }.joinToString(" ") { seats[it] }
        val calls = (1..3).filter { st.melds[it].isNotEmpty() }.joinToString(" ") { "${seats[it]} ${st.melds[it].size}" }
        val parts = ArrayList<String>()
        parts += context.getString(R.string.assist_dora, dora.ifEmpty { "-" })
        if (riichi.isNotEmpty()) parts += context.getString(R.string.assist_riichi, riichi)
        if (calls.isNotEmpty()) parts += context.getString(R.string.assist_calls, calls)
        return parts.joinToString(" · ")
    }
}
