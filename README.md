# Mahjong (riichi) for Android

Japanese riichi mahjong against three CPU players, with a hint that recommends your discard.

## Features (v0.3)

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
- **2.5D table** (default): the table is drawn in perspective (a homography from the table rectangle
  to a trapezoid), tiles on the table show their edge towards the viewer, and your hand stands in
  front. **2D table** (top view) available from the menu. Everything is drawn in code (no image
  assets); Korean and English.

- **Screen-recognition assistant (test version)**: menu → "Screen recognition assistant". After
  allowing "display over other apps" and screen capture, a floating **AI** button stays on screen;
  tap it on your turn to capture the screen, recognise your hand and show the recommended discard
  with a ring around the tile (long-press the button or use the notification to stop).
  Recognition (`assist/HandRecognizer`) works from pixels only: it finds the band of tile faces at
  the bottom, splits it into tiles and matches each against templates drawn by the app's own tile
  renderer (brightness-normalised, small position search). It is tuned for this app's screens (2D
  and 2.5D); advice uses only the hand (shanten / acceptance), not rivers or dora.
  Tested on screenshots of real games: 100% of hands read exactly (hundreds of screens, including
  selected, highlighted and dimmed tiles and hands with calls).
- Tile backs are plain (no pattern).

Not yet: abortive draws (four winds, nine terminals, ...), nagashi
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
- `assist/` — `HandRecognizer` (screenshot → hand), `AssistAnalyzer` (→ advice), `OverlayService`
  (MediaProjection capture, floating button, marker), `AssistStartActivity` (permissions).
- `MainActivity` — game loop, player actions, dialogs.
