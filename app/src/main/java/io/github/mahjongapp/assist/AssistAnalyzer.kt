package io.github.mahjongapp.assist

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import io.github.mahjongapp.R
import io.github.mahjongapp.core.HandAdvisor
import io.github.mahjongapp.ui.TileNames

/** What the overlay shows after analysing one screenshot. */
data class AssistResult(
    val text: String,
    /** Screen rectangle of the tile to discard (null when there is nothing to mark). */
    val mark: Rect?,
    val recommendedKind: Int?,
    val seen: SeenHand?,
)

/** Screenshot -> recognised hand -> advice. Independent of the capture mechanism (testable). */
class AssistAnalyzer(private val context: Context) {

    private val recognizer = HandRecognizer()

    fun analyze(screen: Bitmap): AssistResult {
        val seen = recognizer.recognize(screen)
            ?: return AssistResult(context.getString(R.string.assist_not_found), null, null, null)
        val advice = HandAdvisor.advise(seen.kinds)
            ?: return AssistResult(context.getString(R.string.assist_not_found), null, null, seen)
        val handText = context.getString(R.string.assist_seen, seen.kinds.joinToString(" ") { TileNames.of(it) })
        val best = advice.best
        if (best == null) {
            // Not the player's turn (13 - 3n tiles): report the state of the hand.
            val state = if (advice.shantenNow <= 0) {
                context.getString(R.string.assist_waiting, advice.waits.joinToString(" ") { TileNames.of(it) })
            } else context.getString(R.string.shanten, advice.shantenNow)
            return AssistResult("$state\n$handText", null, null, seen)
        }
        val shText = if (best.shanten <= 0) context.getString(R.string.tenpai) else context.getString(R.string.shanten, best.shanten)
        val text = context.getString(R.string.hint_text, TileNames.of(best.kind), shText, best.ukeire) + "\n" + handText
        // Mark the drawn tile if it is the one to discard, otherwise the first tile of that kind.
        val target = seen.tiles.lastOrNull()?.takeIf { seen.drawnSeparated && it.kind == best.kind }
            ?: seen.tiles.first { it.kind == best.kind }
        return AssistResult(text, target.rect, best.kind, seen)
    }
}
