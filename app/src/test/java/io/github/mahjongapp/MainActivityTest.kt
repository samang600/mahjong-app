package io.github.mahjongapp

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import io.github.mahjongapp.core.Ai
import io.github.mahjongapp.core.CallAction
import io.github.mahjongapp.core.Phase
import io.github.mahjongapp.core.TurnAction
import io.github.mahjongapp.ui.TableView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ko-w891dp-h411dp-land-xxhdpi")
class MainActivityTest {

    private fun shot(view: View, name: String) {
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        val dir = File(System.getProperty("screenshotDir") ?: "build/screenshots").apply { mkdirs() }
        File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun tap(view: View, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        view.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        view.dispatchTouchEvent(MotionEvent.obtain(t, t + 30, MotionEvent.ACTION_UP, x, y, 0))
    }

    private fun buttons(group: ViewGroup) = (0 until group.childCount).map { group.getChildAt(it) as Button }

    @Test
    fun playsAWholeGameIn25D() = runGame(view3d = true, prefix = "25d_", fullGame = true)

    @Test
    fun playsARoundIn2D() = runGame(view3d = false, prefix = "2d_", fullGame = false)

    private fun runGame(view3d: Boolean, prefix: String, fullGame: Boolean) {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mahjong", android.content.Context.MODE_PRIVATE)
            .edit().clear().putBoolean("view3d", view3d).commit()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val table = activity.findViewById<TableView>(R.id.table)
        val actions = activity.findViewById<ViewGroup>(R.id.actions)
        val looper = shadowOf(Looper.getMainLooper())
        var humanTurns = 0; var humanCalls = 0; var rounds = 0
        var shotTurn = false; var shotCall = false; var shotResult = false
        var guard = 0
        var finalScores: List<Int> = emptyList()
        while (true) {
            check(++guard < 20_000) { "game did not finish" }
            looper.idleFor(Duration.ofMillis(500))
            val g = activity.game
            val dialog = ShadowDialog.getLatestDialog() as? AlertDialog
            if (dialog != null && dialog.isShowing) {
                if (dialog.listView != null && dialog.listView.adapter != null && dialog.listView.adapter.count > 0) {
                    dialog.listView.performItemClick(null, 0, 0); dialog.dismiss(); continue
                }
                rounds++
                if (!shotResult) { shot(dialog.window!!.decorView, prefix + "result_dialog.png"); shot(activity.window.decorView, prefix + "result_table.png"); shotResult = true }
                val over = g.phase == Phase.GAME_END
                if (!fullGame) { finalScores = g.players.map { it.score }; break }
                if (over) {
                    finalScores = g.players.map { it.score }
                    assertEquals(100_000, g.totalPoints())
                    shot(dialog.window!!.decorView, prefix + "game_over.png")
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                looper.idle()
                if (over) break
                continue
            }
            if (g.phase == Phase.TURN && g.turnSeat == MainActivity.HUMAN && table.allowed.isNotEmpty()) {
                humanTurns++
                if (!shotTurn && g.players[0].melds.isEmpty() && humanTurns >= 4) {
                    activity.findViewById<Button>(R.id.btn_hint).performClick()
                    looper.idle()
                    shot(activity.window.decorView, prefix + "turn_with_hint.png"); shotTurn = true
                }
                when (val a = Ai.decideTurn(g, 0)) {
                    TurnAction.Tsumo -> buttons(actions).first { it.text == activity.getString(R.string.tsumo) }.performClick()
                    is TurnAction.Discard -> {
                        if (a.riichi) buttons(actions).first { it.text == activity.getString(R.string.riichi) }.performClick()
                        if (g.players[0].riichi > 0) { continue } // auto-discard in riichi
                        // Render once so the hand's hit boxes reflect the current state (a device draws before touch).
                        table.draw(Canvas(Bitmap.createBitmap(table.width, table.height, Bitmap.Config.ARGB_8888)))
                        val r = table.handTileRect(a.id)!!
                        tap(table, r.centerX(), r.centerY()); tap(table, r.centerX(), r.centerY())
                    }
                    is TurnAction.Ankan, is TurnAction.Kakan -> buttons(actions).first { it.text == activity.getString(R.string.kan) }.performClick()
                }
                continue
            }
            if (g.phase == Phase.CALLS && MainActivity.HUMAN in g.pendingCalls && actions.childCount > 0) {
                humanCalls++
                if (!shotCall) { shot(activity.window.decorView, prefix + "call_prompt.png"); shotCall = true }
                val label = when (Ai.decideCall(g, 0, g.pendingCalls.getValue(0))) {
                    CallAction.Ron -> R.string.ron
                    is CallAction.Pon -> R.string.pon
                    is CallAction.Chi -> R.string.chi
                    CallAction.Minkan -> R.string.kan
                    CallAction.Pass -> R.string.skip
                }
                buttons(actions).first { it.text == activity.getString(label) }.performClick()
                continue
            }
        }
        println("UI game: rounds=$rounds humanTurns=$humanTurns humanCalls=$humanCalls final=$finalScores")
        assertTrue(if (fullGame) rounds >= 4 && humanTurns > 20 else rounds >= 1)
        assertTrue(finalScores.isNotEmpty())
    }
}
