package io.github.mahjongapp.ui

import io.github.mahjongapp.core.Tiles
import java.util.Locale

/** Human-readable tile names (Korean: 3만 5통 7삭 동 백 ..., otherwise English). */
object TileNames {
    fun of(kind: Int): String {
        val ko = Locale.getDefault().language == "ko"
        return when {
            kind < 27 && ko -> "${kind % 9 + 1}${"만통삭"[kind / 9]}"
            kind < 27 -> Tiles.name(kind)
            ko -> listOf("동", "남", "서", "북", "백", "발", "중")[kind - 27]
            else -> listOf("East", "South", "West", "North", "White", "Green", "Red")[kind - 27]
        }
    }
}
