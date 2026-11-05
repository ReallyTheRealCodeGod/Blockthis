# Chess But Brainrot 💀

Real, fully legal chess (castling, en passant, promotion, checkmate/stalemate, threefold repetition, 50-move rule) wrapped in a TikTok-brained
attention-span-destroyer. No build step — open `index.html` in a browser.

You play cyan (white) against a bot. Tap a piece, tap a green dot.

## The brainrot
- **Aura** score + rank (NPC → NORMIE → MID → SIGMA → GIGACHAD → ULTRA INSTINCT)
- **Combo streaks**: capture or check on consecutive turns of yours for multiplied aura (x3 drops a card)
- **Eval bar** ("who's winning") and chess.com-style move grading: BEST MOVE ⭐, MISTAKE ?, BLUNDER ??, BOT BLUNDERED 🤡
- Screen shake, particle bursts, giant meme popups, synth sound effects (vine-boom-ish capture thud, airhorn mate, sad trombone)
- Fake live chat, viewer count, scrolling news ticker, like button
- **Chaos events** every 10 plies: board spin, vibe shift, emoji roulette, chat surge
- **UNO** reverse: undo your last turn (once per game)
- **ADHD mode**: 10 seconds per move or the game plays a random one for you
- **Skins**: meme / animals / food / classic
- Difficulty: NPC (sloppy) → SIGMA → GIGACHAD (depth-3 search)
- **Brainrot cards** (FLUSH, GLOW UP, CLOWN, BIG BRAIN), a talking trash-talk bot (speech synthesis), a beat whose tempo scales with rank, a split-screen endless-runner strip, YEET captures, fake sponsor breaks, cursor emoji trail, emoji rain and Fanum tax events

`prefers-reduced-motion` turns off shake/spin/hue effects.

## Files
- `engine.js` — rules + AI (no UI).
- `tests/engine.test.js` — `node tests/engine.test.js`: perft counts, draw rules, and the bot converting K+Q / K+R endgames.
- `index.html` — UI, effects, audio.
