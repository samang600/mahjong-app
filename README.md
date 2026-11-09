# Mahjong (riichi) for Android

Japanese riichi mahjong against three CPU players, with a hint that recommends your discard.

## Features (v0.4)

- **Rules**: 4 players, 136 tiles with red fives (optional), open tanyao (optional), east-only or
  east+south games. Chi / pon / kans (closed, added, open) with replacement draws and kan dora,
  riichi (double riichi, ippatsu, ura dora), furiten (own discards, missed ron, riichi), kuikae,
  multiple ron, exhaustive draw with tenpai payments, honba and riichi sticks, dealer repeat,
  game ends after the last round or when someone drops below zero.
- **Scoring**: all standard yaku and yakuman, fu with wait shapes, limit hands (mangan to kazoe
  yakuman); the best interpretation of the hand is chosen.
- **Shared strategy** (`core/Strategy`, used by the CPU, the in-game hint and the screen assistant):
  acceptance counts only unseen tiles (rivers, melds, dora indicators), hand value (dora, red fives,
  yakuhai pairs), a threat model for riichi and open hands (number of calls, flush, yakuhai / dora
  calls) and threat-weighted danger (genbutsu, suji, visible honors, flush suit) to decide between
  attack and defence. Against the previous CPU over 2000 games: average rank 2.458 vs 2.514,
  deal-in 12.6% vs 14.0% of rounds.
- **2.5D table** (default): the table is drawn in perspective (a homography from the table rectangle
  to a trapezoid), tiles on the table show their edge towards the viewer, and your hand stands in
  front. **2D table** (top view) available from the menu. Everything is drawn in code (no image
  assets); Korean and English.

- **Screen-recognition assistant (test version)**: menu → "Screen recognition assistant". After
  allowing "display over other apps" and screen capture, a floating **AI** button stays on screen;
  tap it on your turn to capture the screen, recognise your hand and show the recommended discard
  with a ring around the tile (long-press the button or use the notification to stop).
  The whole table is read from the screenshot (`assist/ScreenReader`): the hand (found from pixels),
  dora indicators, all four rivers (riichi tile drawn sideways, tiles taken by calls dimmed) and every
  player's melds (including added and closed kans), in the 2D and 2.5D views. The table geometry is
  shared with the view (`ui/TableLayout`); at each place the reader decides from the pixels whether a
  tile is there, its orientation and which tile it is (templates drawn by the app's renderer at the
  same size, sampled through the perspective of that spot). The advice then uses the same shared
  strategy as the CPU (seat / round wind and the wall count are not read).
  Tested at every player turn of real games (~820 screens, 2D and 2.5D): hand and dora 100%,
  rivers ≥ 99.5%, melds 100% (2D) / 97.6% (2.5D), advice identical to the strategy's advice on
  the true game state in 819 of 820 screens.
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
  `Game` (state machine), `Strategy` (shared discard decision), `Ai` (CPU turns and calls).
- `ui/` — `TableView` (2D / 2.5D table), `TableLayout` (shared geometry), `TileRenderer` (tile faces),
  `YakuNames`, `TileNames`.
- `assist/` — `HandRecognizer` (screenshot → hand), `ScreenReader` (→ whole table),
  `AssistAnalyzer` (→ situation → advice), `OverlayService`
  (MediaProjection capture, floating button, marker), `AssistStartActivity` (permissions).
- `MainActivity` — game loop, player actions, dialogs.
