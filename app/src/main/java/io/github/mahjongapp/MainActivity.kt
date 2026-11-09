package io.github.mahjongapp

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.mahjongapp.core.Ai
import io.github.mahjongapp.core.CallAction
import io.github.mahjongapp.core.Game
import io.github.mahjongapp.core.Phase
import io.github.mahjongapp.core.Rules
import io.github.mahjongapp.core.Shanten
import io.github.mahjongapp.core.Tiles
import io.github.mahjongapp.core.TurnAction
import io.github.mahjongapp.core.WinInfo
import io.github.mahjongapp.ui.TableView
import io.github.mahjongapp.ui.YakuNames
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        const val HUMAN = 0
        private const val CPU_DELAY = 450L
        private const val CALL_DELAY = 250L
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var table: TableView
    private lateinit var actions: LinearLayout
    private lateinit var info: TextView
    lateinit var game: Game
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val stepRunnable = Runnable { step() }
    private var waitingForHuman = false
    private var riichiMode = false
    private var paused = false
    private var dialogOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("mahjong", Context.MODE_PRIVATE)
        setContentView(R.layout.activity_main)
        table = findViewById(R.id.table)
        actions = findViewById(R.id.actions)
        info = findViewById(R.id.info)
        table.onTileTap = ::onTileTap
        findViewById<Button>(R.id.btn_hint).setOnClickListener { showHint() }
        findViewById<Button>(R.id.btn_menu).setOnClickListener { showMenu() }
        table.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> placeActions(v.width, v.height) }
        newGame(prefs.getInt("rounds", 1))
        hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        paused = false
        hideSystemBars()
        schedule(CALL_DELAY)
    }

    override fun onPause() {
        super.onPause()
        paused = true
        handler.removeCallbacks(stepRunnable)
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    /** Action buttons float above the player's hand on the right. */
    private fun placeActions(w: Int, h: Int) {
        // Same geometry as TableView: stay above the hand and inside the right opponent's column.
        val tw = minOf(h / 8.2f, w / 19f)
        val rw = minOf((h - tw * 1.36f * 1.25f) / 20f, tw * 0.62f)
        val lp = actions.layoutParams as FrameLayout.LayoutParams
        val bottom = (tw * 1.36f * 1.35f).toInt()
        val end = (rw * 2.0f).toInt()
        if (lp.bottomMargin != bottom || lp.marginEnd != end) {
            lp.bottomMargin = bottom
            lp.marginEnd = end
            actions.post { actions.layoutParams = lp }
        }
        // Hint / info text just above the player's hand.
        val ip = info.layoutParams as FrameLayout.LayoutParams
        if (ip.bottomMargin != bottom) {
            ip.bottomMargin = bottom
            info.post { info.layoutParams = ip }
        }
    }

    // ------------------------------------------------------------------ game loop

    private fun newGame(rounds: Int) {
        handler.removeCallbacks(stepRunnable)
        prefs.edit().putInt("rounds", rounds).apply()
        val rules = Rules(
            redFives = prefs.getBoolean("red", true),
            kuitan = prefs.getBoolean("kuitan", true),
            rounds = rounds,
        )
        game = Game(rules)
        table.game = game
        table.revealAll = false
        resetHumanUi()
        schedule(600)
    }

    private fun schedule(delay: Long) {
        handler.removeCallbacks(stepRunnable)
        if (!paused && !dialogOpen) handler.postDelayed(stepRunnable, delay)
    }

    /** Advances the game by one CPU action, or stops to wait for the player. */
    private fun step() {
        if (waitingForHuman || dialogOpen) return
        val g = game
        when (g.phase) {
            Phase.TURN -> {
                if (g.turnSeat == HUMAN) {
                    humanTurn()
                } else {
                    g.doTurn(Ai.decideTurn(g, g.turnSeat))
                    table.invalidate()
                    schedule(CPU_DELAY)
                }
            }
            Phase.CALLS -> {
                for ((s, o) in g.pendingCalls.toMap()) {
                    if (s != HUMAN && !g.hasResponded(s)) g.respond(s, Ai.decideCall(g, s, o))
                }
                if (g.phase == Phase.CALLS && HUMAN in g.pendingCalls && !g.hasResponded(HUMAN)) {
                    humanCall()
                } else {
                    table.invalidate()
                    schedule(CALL_DELAY)
                }
            }
            Phase.ROUND_END, Phase.GAME_END -> showRoundResult()
        }
    }

    private fun setInfo(text: CharSequence) {
        info.text = text
        info.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun resetHumanUi() {
        waitingForHuman = false
        riichiMode = false
        table.selected = -1
        table.hint = -1
        table.allowed = emptySet()
        actions.removeAllViews()
        setInfo("")
    }

    private fun humanDone() {
        resetHumanUi()
        table.invalidate()
        schedule(CALL_DELAY)
    }

    // ------------------------------------------------------------------ player's turn

    private fun humanTurn() {
        val g = game
        val opts = g.turnOptions()
        val p = g.players[HUMAN]
        waitingForHuman = true
        table.allowed = opts.discardable
        actions.removeAllViews()
        if (opts.canTsumo) addAction(getString(R.string.tsumo), primary = true) { act(TurnAction.Tsumo) }
        if (opts.riichiDiscards.isNotEmpty()) addAction(getString(R.string.riichi)) {
            riichiMode = !riichiMode
            table.allowed = if (riichiMode) opts.riichiDiscards else opts.discardable
            table.selected = -1
            setInfo(if (riichiMode) getString(R.string.riichi_pick) else "")
        }
        val kans = opts.ankan.map { TurnAction.Ankan(it) as TurnAction } + opts.kakan.map { TurnAction.Kakan(it) }
        if (kans.isNotEmpty()) addAction(getString(R.string.kan)) {
            if (kans.size == 1) act(kans[0]) else {
                val names = kans.map { tileName(if (it is TurnAction.Ankan) it.kind else (it as TurnAction.Kakan).kind) }.toTypedArray()
                AlertDialog.Builder(this).setItems(names) { _, i -> act(kans[i]) }.show()
            }
        }
        // In riichi with nothing to decide, discard the drawn tile automatically.
        if (p.riichi > 0 && !opts.canTsumo && kans.isEmpty()) {
            val drawn = g.drawnTile
            if (drawn != null) {
                handler.postDelayed({ if (waitingForHuman && game === g && g.phase == Phase.TURN && g.turnSeat == HUMAN) act(TurnAction.Discard(drawn)) }, CPU_DELAY)
            }
        }
        table.invalidate()
    }

    private fun onTileTap(id: Int) {
        val g = game
        if (!waitingForHuman || g.phase != Phase.TURN || g.turnSeat != HUMAN) return
        if (id !in table.allowed) return
        if (table.selected == id) {
            act(TurnAction.Discard(id, riichi = riichiMode))
        } else {
            table.selected = id
        }
    }

    private fun act(a: TurnAction) {
        if (!waitingForHuman) return
        game.doTurn(a)
        humanDone()
    }

    // ------------------------------------------------------------------ calls

    private fun humanCall() {
        val g = game
        val o = g.pendingCalls[HUMAN] ?: return
        waitingForHuman = true
        actions.removeAllViews()
        if (o.canRon) addAction(getString(R.string.ron), primary = true) { respond(CallAction.Ron) }
        if (o.pon.isNotEmpty()) addAction(getString(R.string.pon)) { respond(CallAction.Pon(o.pon[0])) }
        if (o.minkan) addAction(getString(R.string.kan)) { respond(CallAction.Minkan) }
        if (o.chi.isNotEmpty()) addAction(getString(R.string.chi)) {
            if (o.chi.size == 1) respond(CallAction.Chi(o.chi[0])) else {
                val names = o.chi.map { pair -> (pair.map(Tiles::kind) + Tiles.kind(o.tile)).sorted().joinToString(" ") { tileName(it) } }.toTypedArray()
                AlertDialog.Builder(this).setTitle(R.string.chi_pick).setItems(names) { _, i -> respond(CallAction.Chi(o.chi[i])) }.show()
            }
        }
        addAction(getString(R.string.skip)) { respond(CallAction.Pass) }
        setInfo("")
    }

    private fun respond(a: CallAction) {
        if (!waitingForHuman) return
        game.respond(HUMAN, a)
        humanDone()
    }

    private fun addAction(label: String, primary: Boolean = false, onClick: () -> Unit) {
        val b = Button(this).apply {
            text = label
            textSize = 18f
            isAllCaps = false
            setTextColor(if (primary) 0xFFFFFFFF.toInt() else 0xFF102030.toInt())
            setBackgroundResource(if (primary) R.drawable.btn_primary else R.drawable.btn_action)
            setOnClickListener { onClick() }
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.marginStart = (8 * resources.displayMetrics.density).toInt()
        actions.addView(b, lp)
    }

    // ------------------------------------------------------------------ hint

    private fun showHint() {
        val g = game
        if (g.phase != Phase.TURN || g.turnSeat != HUMAN || !waitingForHuman) return
        val advice = Ai.advise(g, HUMAN)
        val a = advice.action
        when (a) {
            is TurnAction.Tsumo -> setInfo(getString(R.string.tsumo) + "!")
            is TurnAction.Discard -> {
                table.hint = a.id
                val e = advice.evals.firstOrNull { Tiles.kind(it.id) == Tiles.kind(a.id) }
                setInfo(
                    if (advice.defending) getString(R.string.hint_defend, tileName(Tiles.kind(a.id)))
                    else {
                        val sh = e?.shanten ?: Shanten.of(g.players[HUMAN].handKinds(), g.players[HUMAN].melds.size)
                        val shText = if (sh <= 0) getString(R.string.tenpai) else getString(R.string.shanten, sh)
                        getString(R.string.hint_text, tileName(Tiles.kind(a.id)), shText, e?.ukeire ?: 0) +
                            if (a.riichi) "  · " + getString(R.string.riichi) + "!" else ""
                    }
                )
            }
            is TurnAction.Ankan, is TurnAction.Kakan -> setInfo(getString(R.string.kan) + "!")
        }
    }

    // ------------------------------------------------------------------ results

    private fun playerName(seat: Int) = if (seat == HUMAN) getString(R.string.you) else getString(R.string.cpu, seat)

    fun tileName(kind: Int): String {
        val ko = Locale.getDefault().language == "ko"
        return when {
            kind < 27 && ko -> "${kind % 9 + 1}${"만통삭"[kind / 9]}"
            kind < 27 -> Tiles.name(kind)
            ko -> listOf("동", "남", "서", "북", "백", "발", "중")[kind - 27]
            else -> listOf("East", "South", "West", "North", "White", "Green", "Red")[kind - 27]
        }
    }

    private fun winText(w: WinInfo): String {
        val sb = StringBuilder()
        val how = if (w.tsumo) getString(R.string.tsumo) else getString(R.string.ron)
        sb.append(getString(R.string.win_title, playerName(w.seat), how)).append('\n')
        if (!w.tsumo) sb.append(getString(R.string.win_from, playerName(w.seat), playerName(w.from))).append('\n')
        val hand = w.hand.map(Tiles::kind).sorted().joinToString(" ") { tileName(it) }
        sb.append(hand)
        for (m in w.melds) sb.append("  [").append(m.tiles.map(Tiles::kind).sorted().joinToString(" ") { tileName(it) }).append(']')
        sb.append("\n\n")
        for (y in w.result.yaku) {
            sb.append(YakuNames.of(y.name))
            if (y.yakuman == 0) sb.append("  ").append(y.han).append(if (Locale.getDefault().language == "ko") "판" else " han")
            sb.append('\n')
        }
        val r = w.result
        if (r.yakuman == 0) sb.append(getString(R.string.han_fu, r.han, r.fu))
        r.limitName?.let { sb.append("  ").append(YakuNames.of(it)) }
        val dealer = w.seat == game.dealer
        val pts = if (w.tsumo) r.tsumoPoints(dealer).let { (a, b) -> if (dealer) 3 * a else a + 2 * b } else r.ronPoints(dealer)
        sb.append("  ").append(getString(R.string.points, pts)).append('\n')
        return sb.toString()
    }

    private fun showRoundResult() {
        val g = game
        val r = g.lastResult ?: return
        dialogOpen = true
        table.revealAll = true
        resetHumanUi()
        val sb = StringBuilder()
        if (r.draw) {
            sb.append(getString(R.string.round_draw)).append('\n')
            val names = if (r.tenpai.isEmpty()) getString(R.string.nobody) else r.tenpai.joinToString(", ") { playerName(it) }
            sb.append(getString(R.string.tenpai_players, names)).append("\n\n")
        } else {
            for (w in r.wins) sb.append(winText(w)).append('\n')
        }
        for (s in 0 until 4) {
            val d = r.deltas[s]
            sb.append(playerName(s)).append("  ").append(g.players[s].score)
            if (d != 0) sb.append("  (").append(if (d > 0) "+" else "").append(d).append(')')
            sb.append('\n')
        }
        val over = g.phase == Phase.GAME_END
        if (over) {
            sb.append('\n').append(getString(R.string.game_over)).append('\n')
            for ((i, s) in g.ranking().withIndex()) sb.append(getString(R.string.rank, i + 1, playerName(s), g.players[s].score)).append('\n')
        }
        AlertDialog.Builder(this)
            .setMessage(sb.toString().trim())
            .setCancelable(false)
            .setPositiveButton(if (over) R.string.new_game else R.string.next) { _, _ ->
                dialogOpen = false
                table.revealAll = false
                if (over) newGame(g.rules.rounds) else { g.nextRound(); table.invalidate(); schedule(500) }
            }
            .show()
    }

    // ------------------------------------------------------------------ menu

    private fun showMenu() {
        val red = prefs.getBoolean("red", true)
        val kuitan = prefs.getBoolean("kuitan", true)
        val items = arrayOf(
            getString(R.string.east_game),
            getString(R.string.south_game),
            getString(R.string.settings_red) + if (red) "  ✓" else "",
            getString(R.string.settings_kuitan) + if (kuitan) "  ✓" else "",
            getString(R.string.about),
        )
        AlertDialog.Builder(this).setItems(items) { _, i ->
            when (i) {
                0 -> newGame(1)
                1 -> newGame(2)
                2 -> { prefs.edit().putBoolean("red", !red).apply(); setInfo(getString(R.string.rules_next_game)) }
                3 -> { prefs.edit().putBoolean("kuitan", !kuitan).apply(); setInfo(getString(R.string.rules_next_game)) }
                4 -> AlertDialog.Builder(this).setTitle(R.string.app_name).setMessage(R.string.about_text).setPositiveButton(android.R.string.ok, null).show()
            }
        }.show()
    }
}
