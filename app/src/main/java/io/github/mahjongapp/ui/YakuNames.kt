package io.github.mahjongapp.ui

import java.util.Locale

/** Display names for yaku (romaji in English, Korean readings in Korean). */
object YakuNames {
    private val KO = mapOf(
        "Riichi" to "리치", "Double riichi" to "더블 리치", "Ippatsu" to "일발", "Menzen tsumo" to "멘젠 쯔모",
        "Pinfu" to "핑후", "Tanyao" to "탕야오", "Iipeikou" to "이페코", "Ryanpeikou" to "량페코",
        "Yakuhai: haku" to "역패 백", "Yakuhai: hatsu" to "역패 발", "Yakuhai: chun" to "역패 중",
        "Yakuhai: seat wind" to "역패 자풍", "Yakuhai: round wind" to "역패 장풍",
        "Rinshan kaihou" to "영상개화", "Chankan" to "창깡", "Haitei raoyue" to "해저로월", "Houtei raoyui" to "하저로어",
        "Sanshoku doujun" to "삼색동순", "Ittsu" to "일기통관", "Chanta" to "찬타", "Junchan" to "준찬타",
        "Chiitoitsu" to "치또이츠", "Toitoi" to "또이또이", "Sanankou" to "산안커", "Sanshoku doukou" to "삼색동각",
        "Sankantsu" to "산깡쯔", "Shousangen" to "소삼원", "Honroutou" to "혼노두", "Honitsu" to "혼일색", "Chinitsu" to "청일색",
        "Kokushi musou" to "국사무쌍", "Suuankou" to "스안커", "Daisangen" to "대삼원", "Shousuushii" to "소사희",
        "Daisuushii" to "대사희", "Tsuuiisou" to "자일색", "Ryuuiisou" to "녹일색", "Chinroutou" to "청노두",
        "Chuuren poutou" to "구련보등", "Suukantsu" to "스깡쯔", "Tenhou" to "천화", "Chiihou" to "지화",
        "Dora" to "도라", "Ura dora" to "뒷도라", "Red five" to "적도라",
        "Mangan" to "만관", "Haneman" to "하네만", "Baiman" to "배만", "Sanbaiman" to "삼배만",
        "Kazoe yakuman" to "헤아림 역만", "Yakuman" to "역만",
    )

    fun of(name: String): String =
        if (Locale.getDefault().language == "ko") KO[name] ?: name else name
}
