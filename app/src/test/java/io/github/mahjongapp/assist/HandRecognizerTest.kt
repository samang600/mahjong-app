package io.github.mahjongapp.assist

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import io.github.mahjongapp.MainActivity
import io.github.mahjongapp.R
import io.github.mahjongapp.core.Ai
import io.github.mahjongapp.core.CallAction
import io.github.mahjongapp.core.HandAdvisor
import io.github.mahjongapp.core.Phase
import io.github.mahjongapp.core.Tiles
import io.github.mahjongapp.core.TurnAction
import io.github.mahjongapp.ui.TableView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.time.Duration

/**
 * Screen recognition against real screenshots of the app: at every player turn the whole screen
 * is captured (with buttons, hint text, highlights, raised or dimmed tiles) and the recognised hand
 * must equal the game's hand exactly (kinds, red fives and the separated drawn tile).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ko-w891dp-h411dp-land-xxhdpi")
class HandRecognizerTest {

    private fun screenshot(activity: MainActivity): Bitmap {
        val v = activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        return bmp
    }

    /** What the overlay would look like: ring around the recommended tile, result panel, AI button. */
    private fun savePreview(screen: Bitmap, r: AssistResult, name: String) {
        val bmp = screen.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(bmp)
        val d = 3f // xxhdpi density, as on the test screen
        r.mark?.let { m ->
            val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE; color = android.graphics.Color.rgb(0, 0xE6, 0x76); strokeWidth = m.width() * 0.08f
            }
            val rf = android.graphics.RectF(m).apply { inset(-m.width() * 0.06f, -m.width() * 0.06f) }
            c.drawRoundRect(rf, m.width() * 0.15f, m.width() * 0.15f, ring)
        }
        val bubbleX = bmp.width - 56 * d - 16 * d; val bubbleY = bmp.height / 3f
        val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        fill.color = android.graphics.Color.argb(220, 0x10, 0x18, 0x20)
        val lines = r.text.split("\n")
        val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 14 * d }
        val pw = lines.maxOf { text.measureText(it) } + 24 * d
        val px = bubbleX - pw - 10 * d
        c.drawRoundRect(android.graphics.RectF(px, bubbleY, px + pw, bubbleY + lines.size * 20 * d + 14 * d), 12 * d, 12 * d, fill)
        for ((i, l) in lines.withIndex()) c.drawText(l, px + 12 * d, bubbleY + 22 * d + i * 20 * d, text)
        fill.color = android.graphics.Color.rgb(0xE5, 0x39, 0x35)
        c.drawCircle(bubbleX + 28 * d, bubbleY + 28 * d, 28 * d, fill)
        text.textSize = 18 * d; text.textAlign = android.graphics.Paint.Align.CENTER
        c.drawText("AI", bubbleX + 28 * d, bubbleY + 34 * d, text)
        val dir = File(System.getProperty("screenshotDir") ?: "build/screenshots").apply { mkdirs() }
        File(dir, "overlay_preview_$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun buttons(group: ViewGroup) = (0 until group.childCount).map { group.getChildAt(it) as Button }

    private fun run(view3d: Boolean, seedRounds: Int): Pair<Int, Int> {
        RuntimeEnvironment.getApplication().getSharedPreferences("mahjong", Context.MODE_PRIVATE)
            .edit().clear().putBoolean("view3d", view3d).commit()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val table = activity.findViewById<TableView>(R.id.table)
        val actions = activity.findViewById<ViewGroup>(R.id.actions)
        val looper = shadowOf(Looper.getMainLooper())
        val recognizer = HandRecognizer()
        val analyzer = AssistAnalyzer(activity)
        var shotOverlay = false
        var checked = 0; var correct = 0; var rounds = 0; var guard = 0
        var savedFailure = false
        while (rounds < seedRounds) {
            check(++guard < 20_000)
            looper.idleFor(Duration.ofMillis(500))
            val g = activity.game
            val dialog = ShadowDialog.getLatestDialog() as? AlertDialog
            if (dialog != null && dialog.isShowing) {
                if (dialog.listView?.adapter?.count ?: 0 > 0) { dialog.listView.performItemClick(null, 0, 0); dialog.dismiss(); continue }
                rounds++
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); looper.idle()
                continue
            }
            if (g.phase == Phase.TURN && g.turnSeat == 0 && table.allowed.isNotEmpty()) {
                val p = g.players[0]
                // Vary the screen: hint highlight on some turns, a raised (selected) tile on others.
                when (checked % 3) {
                    1 -> { activity.findViewById<Button>(R.id.btn_hint).performClick(); looper.idle() }
                    2 -> table.selected = p.hand.first()
                }
                val drawn = g.drawnTile
                val expected = p.hand.filter { it != drawn }.sortedWith(compareBy({ Tiles.kind(it) }, { it })) + listOfNotNull(drawn)
                val seen = recognizer.recognize(screenshot(activity))
                checked++
                val ok = seen != null && seen.kinds == expected.map(Tiles::kind) &&
                    seen.tiles.map { it.red } == expected.map { Tiles.isRed(it, true) } &&
                    seen.drawnSeparated == (drawn != null)
                if (ok) correct++ else {
                    savedFailure = true
                    val dir = File(System.getenv("RECOGNITION_FAILURE_DIR") ?: System.getProperty("screenshotDir") ?: "build/screenshots").apply { mkdirs() }
                    File(dir, "recognition_failure_${if (view3d) "25d" else "2d"}_${System.nanoTime()}.png").outputStream().use { screenshot(activity).compress(Bitmap.CompressFormat.PNG, 100, it) }
                    println("MISMATCH expected=${expected.map { Tiles.name(Tiles.kind(it)) }} seen=${seen?.tiles?.map { Tiles.name(it.kind) + if (it.red) "r" else "" }} drawnSep=${seen?.drawnSeparated}")
                }
                // The overlay's analyser: same advice as for the true hand, and it marks a tile of that kind.
                val result = analyzer.analyze(screenshot(activity))
                val truth = HandAdvisor.advise(expected.map(Tiles::kind))!!
                assertEquals(truth.best?.kind, result.recommendedKind)
                val mark = result.mark
                if (mark != null) {
                    val markedIdx = seen!!.tiles.indexOfFirst { it.rect == mark }
                    assertEquals(result.recommendedKind, Tiles.kind(expected[markedIdx]))
                }
                if (!shotOverlay && mark != null) {
                    shotOverlay = true
                    println("ANALYZER TEXT: ${result.text.replace("\n", " | ")}")
                    savePreview(screenshot(activity), result, if (view3d) "25d" else "2d")
                }
                table.selected = -1
                when (val a = Ai.decideTurn(g, 0)) {
                    TurnAction.Tsumo -> buttons(actions).first { it.text == activity.getString(R.string.tsumo) }.performClick()
                    is TurnAction.Discard -> {
                        if (a.riichi) buttons(actions).first { it.text == activity.getString(R.string.riichi) }.performClick()
                        if (p.riichi > 0) continue
                        // Riichi mode dims the non-riichi tiles: check recognition on that screen too.
                        if (a.riichi) {
                            val dimmed = recognizer.recognize(screenshot(activity))
                            checked++
                            if (dimmed != null && dimmed.kinds == expected.map(Tiles::kind)) correct++
                            else {
                                println("MISMATCH (riichi dimmed) seen=${dimmed?.kinds} ${recognizer.lastDebug}")
                                val dir = File(System.getProperty("screenshotDir") ?: "build/screenshots").apply { mkdirs() }
                                File(dir, "recognition_failure_riichi.png").outputStream().use { screenshot(activity).compress(Bitmap.CompressFormat.PNG, 100, it) }
                            }
                        }
                        table.draw(Canvas(Bitmap.createBitmap(table.width, table.height, Bitmap.Config.ARGB_8888)))
                        val r = table.handTileRect(a.id)!!
                        val t = android.os.SystemClock.uptimeMillis()
                        for (k in 0..1) {
                            table.dispatchTouchEvent(android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, r.centerX(), r.centerY(), 0))
                            table.dispatchTouchEvent(android.view.MotionEvent.obtain(t, t + 30, android.view.MotionEvent.ACTION_UP, r.centerX(), r.centerY(), 0))
                        }
                    }
                    is TurnAction.Ankan, is TurnAction.Kakan -> buttons(actions).first { it.text == activity.getString(R.string.kan) }.performClick()
                }
                continue
            }
            if (g.phase == Phase.CALLS && 0 in g.pendingCalls && actions.childCount > 0) {
                val label = when (Ai.decideCall(g, 0, g.pendingCalls.getValue(0))) {
                    CallAction.Ron -> R.string.ron
                    is CallAction.Pon -> R.string.pon
                    is CallAction.Chi -> R.string.chi
                    CallAction.Minkan -> R.string.kan
                    CallAction.Pass -> R.string.skip
                }
                buttons(actions).first { it.text == activity.getString(label) }.performClick()
            }
        }
        return checked to correct
    }

    @Test
    fun recognisesTheHandIn25D() {
        val (checked, correct) = run(view3d = true, seedRounds = 4)
        println("2.5D recognition: $correct / $checked screens exactly right")
        assertTrue(checked > 20)
        assertEquals(checked, correct)
    }

    @Test
    fun recognisesTheHandIn2D() {
        val (checked, correct) = run(view3d = false, seedRounds = 4)
        println("2D recognition: $correct / $checked screens exactly right")
        assertTrue(checked > 20)
        assertEquals(checked, correct)
    }

    @Test
    fun menuStartsTheAssistant() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity.findViewById<Button>(R.id.btn_menu).performClick()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.listView.performItemClick(null, 6, 6)
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(AssistStartActivity::class.java.name, started.component!!.className)
        // Without the overlay permission it sends the user to the system setting.
        val start = Robolectric.buildActivity(AssistStartActivity::class.java, started).create().get()
        assertEquals(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, shadowOf(start).nextStartedActivity.action)
    }

    @Test
    fun advisorOnRecognisedHands() {
        val a = HandAdvisor.advise(Tiles.parse("123m456p789s1122z5z"))!!
        assertEquals(0, a.melds)
        assertEquals(Tiles.parse("5z")[0], a.best!!.kind) // isolated haku goes, hand is tenpai
        assertEquals(0, a.best!!.shanten)
        val waiting = HandAdvisor.advise(Tiles.parse("123m456p789s1122z"))!!
        assertEquals(setOf(Tiles.parse("1z")[0], Tiles.parse("2z")[0]), waiting.waits.toSet())
        // One meld inferred from 11 tiles.
        assertEquals(1, HandAdvisor.advise(Tiles.parse("123m456p78s112z"))!!.melds)
    }
}
