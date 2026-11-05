# Chess But Brainrot 💀

Real, fully legal chess (castling, en passant, promotion, checkmate/stalemate) wrapped in a TikTok-brained
attention-span-destroyer. No build step — open `index.html` in a browser.

You play cyan (white) against a bot. Tap a piece, tap a green dot.

## The brainrot
- **Aura** score + rank (NPC → NORMIE → MID → SIGMA → GIGACHAD → ULTRA INSTINCT)
- **Combo streaks**: chain captures/checks on consecutive turns for multiplied aura
- Screen shake, particle bursts, giant meme popups, synth sound effects (vine-boom-ish capture thud, airhorn mate, sad trombone)
- Fake live chat, viewer count, scrolling news ticker, like button
- **Chaos events** every 10 plies: board spin, vibe shift, emoji roulette, chat surge
- **UNO** reverse: undo your last turn (once per game)
- **ADHD mode**: 10 seconds per move or the game plays a random one for you
- **Skins**: meme / animals / food / classic
- Difficulty: NPC (sloppy) → SIGMA → GIGACHAD (depth-3 search)

`prefers-reduced-motion` turns off shake/spin/hue effects.

## Files
- `engine.js` — rules + AI (no UI). Verified against standard perft counts.
- `index.html` — UI, effects, audio.
