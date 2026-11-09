package io.github.mahjongapp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoringTest {

    /** Physical ids for kinds, using a fresh copy for each repeat (copy 1+ so no red fives unless "0"). */
    private fun ids(s: String): List<Int> {
        val used = IntArray(Tiles.KINDS)
        val out = ArrayList<Int>()
        var digits = ArrayList<Char>()
        for (c in s) {
            if (c.isDigit()) digits.add(c) else if (c in "mpsz") {
                for (d in digits) {
                    val kind = Tiles.parse("$d$c")[0]
                    val copy = if (d == '0') 0 else 1 + used[kind]++ % 3
                    out.add(kind * 4 + copy)
                }
                digits = ArrayList()
            }
        }
        return out
    }

    private fun counts(s: String) = Tiles.counts(Tiles.parse(s))

    private val rules = Rules()

    private fun ctx(win: String, tsumo: Boolean, dealer: Boolean = false, riichi: Int = 0, seat: Int = Tiles.SOUTH, round: Int = Tiles.EAST) =
        WinContext(winTile = ids(win)[0], tsumo = tsumo, seatWind = seat, roundWind = round, dealer = dealer, riichi = riichi)

    @Test
    fun shantenBasics() {
        assertEquals(0, Shanten.of(counts("123m456p789s1122z")))
        assertEquals(-1, Shanten.of(counts("123m456p789s11222z")))
        assertEquals(0, Shanten.kokushi(counts("19m19p19s1234567z")))
        assertEquals(0, Shanten.chiitoi(counts("1133557799m113p")))
        assertEquals(8, Shanten.standard(counts("147m258p369s1234z")))
        assertEquals(6, Shanten.of(counts("147m258p369s1234z")))
        // With melds: 4 tiles left, one pair + partial = tenpai
        assertEquals(0, Shanten.of(counts("2355m"), melds = 3))
        assertEquals(-1, Shanten.of(counts("23455m"), melds = 3))
    }

    @Test
    fun ukeireOfRyanmenAndShanpon() {
        val u = Shanten.ukeire(counts("123m456p789s23s11z"), 0).map { it.first }.toSet()
        assertEquals(setOf(Tiles.parse("1s")[0], Tiles.parse("4s")[0]), u)
    }

    @Test
    fun pinfuTanyaoTsumoIs700_1300() {
        val hand = ids("234m567p345s678s22p")
        val r = Scoring.score(hand, emptyList(), ctx("4m", tsumo = true), rules)!!
        assertEquals(setOf("Menzen tsumo", "Pinfu", "Tanyao"), r.yaku.map { it.name }.toSet())
        assertEquals(20, r.fu)
        assertEquals(1300 to 700, r.tsumoPoints(false))
    }

    @Test
    fun pinfuTanyaoRonIs2000() {
        val r = Scoring.score(ids("234m567p345s678s22p"), emptyList(), ctx("4m", tsumo = false), rules)!!
        assertEquals(30, r.fu)
        assertEquals(2, r.han)
        assertEquals(2000, r.ronPoints(false))
        assertEquals(2900, r.ronPoints(true))
    }

    @Test
    fun kanchanRiichiRonIs1300() {
        // 1-3m waiting on 2m: 20 + 10 (closed ron) + 2 (kanchan) = 32 -> 40 fu
        val r = Scoring.score(ids("123m456p789p345s99s"), emptyList(), ctx("2m", tsumo = false, riichi = 1), rules)!!
        assertEquals(40, r.fu)
        assertEquals(1, r.han)
        assertEquals(1300, r.ronPoints(false))
    }

    @Test
    fun chiitoitsuIs25Fu1600() {
        val r = Scoring.score(ids("1199m2288p3366s77z"), emptyList(), ctx("7z", tsumo = false), rules)!!
        assertEquals(25, r.fu)
        assertEquals(listOf("Chiitoitsu"), r.yaku.map { it.name })
        assertEquals(1600, r.ronPoints(false))
    }

    @Test
    fun ryanpeikouBeatsChiitoitsu() {
        val r = Scoring.score(ids("223344m556677p99s"), emptyList(), ctx("2m", tsumo = false), rules)!!
        assertTrue(r.yaku.any { it.name == "Ryanpeikou" })
        assertTrue(r.yaku.none { it.name == "Chiitoitsu" })
    }

    @Test
    fun kokushiIsYakuman() {
        val r = Scoring.score(ids("119m19p19s1234567z"), emptyList(), ctx("1m", tsumo = false), rules)!!
        assertEquals(1, r.yakuman)
        assertEquals(32000, r.ronPoints(false))
        assertEquals(48000, r.ronPoints(true))
    }

    @Test
    fun suuankouTsumo() {
        val r = Scoring.score(ids("111m333p555s777s22z"), emptyList(), ctx("7s", tsumo = true), rules)!!
        assertEquals("Suuankou", r.yaku.single().name)
        // By ron on the triplet it is only sanankou + toitoi.
        val ron = Scoring.score(ids("111m333p555s777s22z"), emptyList(), ctx("7s", tsumo = false), rules)!!
        assertEquals(0, ron.yakuman)
        assertTrue(ron.yaku.any { it.name == "Sanankou" } && ron.yaku.any { it.name == "Toitoi" })
    }

    @Test
    fun openHandWithoutYakuIsNotAWin() {
        val chi = Meld(MeldType.CHI, ids("123m"), ids("1m")[0], 3)
        assertNull(Scoring.score(ids("456p789p345s99s"), listOf(chi), ctx("9s", tsumo = false), rules))
    }

    @Test
    fun openTanyaoAllowedWithKuitanOnly() {
        val chi = Meld(MeldType.CHI, ids("234m"), ids("2m")[0], 3)
        val hand = ids("456p678p345s88s")
        val r = Scoring.score(hand, listOf(chi), ctx("8s", tsumo = false), rules)
        assertNotNull(r)
        assertEquals(30, r!!.fu) // open, no fu -> 30
        assertEquals(1000, r.ronPoints(false))
        assertNull(Scoring.score(hand, listOf(chi), ctx("8s", tsumo = false), rules.copy(kuitan = false)))
    }

    @Test
    fun yakuhaiPonAndFu() {
        // Open pon of chun (4 fu) + concealed 111m (8 fu) + kanchan wait on 6p (2 fu).
        val pon = Meld(MeldType.PON, ids("777z"), ids("7z")[0], 1)
        val r = Scoring.score(ids("111m456p789s55s"), listOf(pon), ctx("6p", tsumo = false), rules)!!
        assertTrue(r.yaku.any { it.name == "Yakuhai: chun" })
        assertEquals(40, r.fu) // 20 + 4 + 8 + 2 = 34 -> 40
    }

    @Test
    fun manganAndLimits() {
        // riichi + tsumo + pinfu + tanyao + sanshoku (2) = 6 han -> haneman
        val r = Scoring.score(ids("234m234p234s567s88p"), emptyList(), ctx("4m", tsumo = true, riichi = 1), rules)!!
        assertTrue(r.yaku.any { it.name == "Sanshoku doujun" })
        assertEquals("Haneman", r.limitName)
        assertEquals(6000 to 3000, r.tsumoPoints(false))
        // 4 han 30 fu non-dealer ron = 7700
        val r2 = Scoring.score(ids("234m567p345s678s22p"), emptyList(), ctx("4m", tsumo = false, riichi = 1).copy(
            doraIndicators = ids("1p")), rules)!!
        assertEquals(5, r2.han) // riichi pinfu tanyao + 2 dora (2p pair)
        assertEquals(8000, r2.ronPoints(false))
    }

    @Test
    fun doraAndRedFives() {
        val r = Scoring.score(ids("234m067p345s678s22p"), emptyList(), ctx("4m", tsumo = false), rules)!!
        assertEquals(1, r.yaku.single { it.name == "Red five" }.han)
        val noRed = Scoring.score(ids("234m067p345s678s22p"), emptyList(), ctx("4m", tsumo = false), rules.copy(redFives = false))!!
        assertTrue(noRed.yaku.none { it.name == "Red five" })
    }

    @Test
    fun honitsuChantaAndIttsu() {
        val r = Scoring.score(ids("123456789m11122z"), emptyList(), ctx("2z", tsumo = false, seat = Tiles.SOUTH), rules)!!
        val names = r.yaku.map { it.name }
        assertTrue(names.contains("Ittsu"))
        assertTrue(names.contains("Honitsu"))
    }
}
