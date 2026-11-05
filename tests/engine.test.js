// Run: node tests/engine.test.js
const E = require('../engine.js');
// The bot adds random noise to its choices. Seed it so this suite plays the same games every
// run (a small LCG). Measured over ~150 unseeded games: SIGMA converts K+Q/K+R vs K 100% of
// the time, GIGACHAD ~99%.
let seed = 20261105;
Math.random = () => ((seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0) / 4294967296);
let fails = 0;
const check = (name, ok, extra = '') => { console.log((ok ? 'ok   ' : 'FAIL ') + name + (extra ? '  ' + extra : '')); if (!ok) fails++; };

// Move generation against standard perft counts (covers castling, en passant, promotion, pins).
const perft = [
  ['start d4', 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -', 4, 197281],
  ['kiwipete d3', 'r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq -', 3, 97862],
  ['pos3 d4', '8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - -', 4, 43238],
  ['pos4 d3', 'r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq -', 3, 9467],
  ['pos5 d3', 'rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ -', 3, 62379],
];
for (const [name, fen, d, exp] of perft) { const n = E.perft(E.fromFEN(fen), d); check('perft ' + name, n === exp, n + ' vs ' + exp); }

// Game-ending rules.
let s = E.initial();
for (const [f, t] of [[53, 45], [12, 28], [54, 38], [3, 39]]) s = E.apply(s, E.legalMoves(s).find((m) => m.f === f && m.t === t));
check("fool's mate is checkmate", E.status(s) === 'checkmate');
check('stalemate', E.status(E.fromFEN('7k/5Q2/6K1/8/8/8/8/8 b - -')) === 'stalemate');
check('K+N vs K is a draw', E.status(E.fromFEN('8/8/4k3/8/8/3NK3/8/8 w - -')) === 'draw');
check('50-move rule', E.status(E.fromFEN('8/8/4k3/8/8/3RK3/8/8 w - - 100')) === 'fifty');
const kn = E.apply(E.initial(), E.legalMoves(E.initial()).find((m) => m.f === 62 && m.t === 45));
check('halfmove clock counts quiet moves', kn.half === 1);
check('pawn move resets halfmove clock', E.apply(kn, E.legalMoves(kn).find((m) => m.f === 12 && m.t === 28)).half === 0);

// The bot must convert basic won endgames (needs the mop-up eval) and find mate in 1.
// Moves are randomized, so each case runs several games, under real draw rules.
const mate1 = E.bestMove(E.fromFEN('6k1/5ppp/8/8/8/8/8/R3K3 w - -'), 2);
check('finds back-rank mate in 1', E.sqName(mate1.f) + E.sqName(mate1.t) === 'a1a8');
for (const [name, fen] of [['K+Q vs K', '8/8/8/4k3/8/8/8/3QK3 w - -'], ['K+R vs K', '8/8/8/4k3/8/8/8/R3K3 w - -']]) {
  for (const level of [1, 2]) {
    let mated = 0, worst = 0;
    for (let g = 0; g < 6; g++) {
      let p = E.fromFEN(fen), n = 0; const seen = new Map();
      while (E.status(p) === 'play') { // stops at mate, stalemate, or the 50-move rule
        const k = E.key(p); seen.set(k, (seen.get(k) || 0) + 1);
        if (seen.get(k) >= 3) break; // threefold repetition
        p = E.apply(p, E.bestMove(p, level, seen)); n++;
      }
      if (E.status(p) === 'checkmate') { mated++; worst = Math.max(worst, Math.ceil(n / 2)); }
    }
    check(`bot (level ${level}) mates with ${name}`, mated === 6, `${mated}/6 games, slowest ${worst} moves`);
  }
}

// search() reports mate scores the UI can turn into "mate in N".
const r = E.search(E.fromFEN('6k1/5ppp/8/8/8/8/8/R3K3 w - -'), 2);
check('search sees mate in 1', r.score === E.MATE - 1);

if (fails) { console.log(fails + ' failing'); process.exit(1); } else console.log('all passed');
