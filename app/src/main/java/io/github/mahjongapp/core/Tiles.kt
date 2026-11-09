package io.github.mahjongapp.core

/**
 * Tiles.
 *
 * A tile *kind* is 0..33: 0-8 man (characters) 1-9, 9-17 pin (circles), 18-26 sou (bamboo),
 * 27-30 winds E S W N, 31-33 dragons white (haku), green (hatsu), red (chun).
 * A physical tile *id* is 0..135: kind = id / 4. The copy-0 five of each suit is the red five
 * when red fives are enabled.
 */
object Tiles {
    const val KINDS = 34
    const val COUNT = 136
    const val EAST = 27
    const val SOUTH = 28
    const val WEST = 29
    const val NORTH = 30
    const val HAKU = 31
    const val HATSU = 32
    const val CHUN = 33

    val RED_IDS = intArrayOf(4 * 4, 13 * 4, 22 * 4)

    fun kind(id: Int) = id / 4
    fun isRed(id: Int, redFives: Boolean) = redFives && (id == 16 || id == 52 || id == 88)

    fun suit(kind: Int) = if (kind < 27) kind / 9 else 3
    fun number(kind: Int) = if (kind < 27) kind % 9 + 1 else 0
    fun isHonor(kind: Int) = kind >= 27
    fun isTerminal(kind: Int) = kind < 27 && (kind % 9 == 0 || kind % 9 == 8)
    fun isTerminalOrHonor(kind: Int) = isHonor(kind) || isTerminal(kind)
    fun isSimple(kind: Int) = !isTerminalOrHonor(kind)
    fun isDragon(kind: Int) = kind >= HAKU

    val TERMINALS_AND_HONORS = intArrayOf(0, 8, 9, 17, 18, 26, 27, 28, 29, 30, 31, 32, 33)

    /** The dora indicated by an indicator kind (wraps within suit / winds / dragons). */
    fun doraFromIndicator(ind: Int): Int = when {
        ind < 27 -> ind / 9 * 9 + (ind % 9 + 1) % 9
        ind < 31 -> 27 + (ind - 27 + 1) % 4
        else -> 31 + (ind - 31 + 1) % 3
    }

    /** Short ASCII name, e.g. 1m 5p 9s E S W N P F C (haku = P, hatsu = F, chun = C). */
    fun name(kind: Int): String = when {
        kind < 27 -> "${kind % 9 + 1}${"mps"[kind / 9]}"
        else -> listOf("E", "S", "W", "N", "P", "F", "C")[kind - 27]
    }

    /** Parses "123m456p789s11z" style (z: 1-7 = E S W N P F C) into kinds. 0m/0p/0s = red five (kind 5). */
    fun parse(s: String): List<Int> {
        val out = ArrayList<Int>()
        val digits = ArrayList<Int>()
        for (c in s) {
            when {
                c.isDigit() -> digits.add(c - '0')
                c in "mpsz" -> {
                    for (d in digits) {
                        val n = if (d == 0) 5 else d
                        out.add(
                            when (c) {
                                'm' -> n - 1
                                'p' -> 9 + n - 1
                                's' -> 18 + n - 1
                                else -> 27 + n - 1
                            }
                        )
                    }
                    digits.clear()
                }
                c == ' ' -> {}
                else -> throw IllegalArgumentException("bad tile string $s")
            }
        }
        return out
    }

    fun counts(kinds: Iterable<Int>): IntArray {
        val c = IntArray(KINDS)
        for (k in kinds) c[k]++
        return c
    }
}

/** Meld (open or closed kan) types. */
enum class MeldType { CHI, PON, MINKAN, ANKAN, KAKAN }

/**
 * A called set. [tiles] are physical ids; [called] is the id taken from another player (or -1 for
 * ankan); [from] is the seat it came from (relative seat index, -1 for ankan).
 */
data class Meld(val type: MeldType, val tiles: List<Int>, val called: Int, val from: Int) {
    val kind get() = tiles.minOf { Tiles.kind(it) }
    val isOpen get() = type != MeldType.ANKAN
    val isKan get() = type == MeldType.MINKAN || type == MeldType.ANKAN || type == MeldType.KAKAN
    val isSequence get() = type == MeldType.CHI
}
