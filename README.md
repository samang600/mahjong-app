# Mahjong (riichi) for Android

Japanese riichi mahjong against three CPU players, with a hint that recommends your discard.

## Features (v0.1)

- **Rules**: 4 players, 136 tiles with red fives (optional), open tanyao (optional), east-only or
  east+south games. Chi / pon / kans (closed, added, open) with replacement draws and kan dora,
  riichi (double riichi, ippatsu, ura dora), furiten (own discards, missed ron, riichi), kuikae,
  multiple ron, exhaustive draw with tenpai payments, honba and riichi sticks, dealer repeat,
  game ends after the last round or when someone drops below zero.
- **Scoring**: all standard yaku and yakuman, fu with wait shapes, limit hands (mangan to kazoe
  yakuman); the best interpretation of the hand is chosen.
- **CPU / hint**: tile efficiency (shanten and number of accepting tiles), defence against riichi
  (genbutsu, suji, visible honors), call and riichi decisions. The hint shows the recommended discard,
  shanten and acceptance.
- **2D table** drawn in code (no image assets); Korean and English.

Not yet: the 2.5D (perspective) table, abortive draws (four winds, nine terminals, ...), nagashi
mangan, local yaku, saving a game in progress.

## Install

`release/MahjongApp.apk` (Android 8.0+, signed with a debug key: allow installing unknown apps).

## Build and test

JDK 17+ and the Android SDK (platform 35):

```
./gradlew testDebugUnitTest   # scoring, game self-play and a Robolectric UI game
./gradlew assembleRelease
```

## Structure

- `core/` — pure Kotlin, no Android: `Tiles`, `Shanten`, `Scoring` (yaku / fu / points),
  `Game` (state machine), `Ai`.
- `ui/` — `TableView` (2D table), `TileRenderer` (tile faces), `YakuNames`.
- `MainActivity` — game loop, player actions, dialogs.
