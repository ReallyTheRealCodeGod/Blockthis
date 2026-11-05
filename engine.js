/*
 * engine.js — a small, complete chess engine (rules + AI). No UI in here.
 *
 * Board: array of 64 cells, index = rank_from_top * 8 + file. Index 0 = a8, 63 = h1.
 * Pieces: uppercase = white, lowercase = black, null = empty.
 * State: { b: board, turn: 'w'|'b', cast: {K,Q,k,q}, ep: index|-1 }
 * Move:  { f, t, piece, cap, promo, ep, castle }
 */
(function (root) {
  'use strict';

  const colorOf = (ch) => (ch === ch.toUpperCase() ? 'w' : 'b');
  const other = (c) => (c === 'w' ? 'b' : 'w');
  const sqName = (i) => 'abcdefgh'[i & 7] + (8 - (i >> 3));

  function fromFEN(fen) {
    const [rows, turn, cast, ep] = fen.split(' ');
    const b = [];
    for (const row of rows.split('/')) {
      for (const ch of row) {
        if (/\d/.test(ch)) for (let k = 0; k < +ch; k++) b.push(null);
        else b.push(ch);
      }
    }
    const castStr = cast || '-';
    return {
      b,
      turn: turn || 'w',
      cast: {
        K: castStr.includes('K'), Q: castStr.includes('Q'),
        k: castStr.includes('k'), q: castStr.includes('q'),
      },
      ep: ep && ep !== '-' ? 'abcdefgh'.indexOf(ep[0]) + (8 - +ep[1]) * 8 : -1,
    };
  }

  const initial = () => fromFEN('rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -');

  const KNIGHT = [[-2, -1], [-2, 1], [-1, -2], [-1, 2], [1, -2], [1, 2], [2, -1], [2, 1]];
  const KING = [[-1, -1], [-1, 0], [-1, 1], [0, -1], [0, 1], [1, -1], [1, 0], [1, 1]];
  const ROOK_D = [[-1, 0], [1, 0], [0, -1], [0, 1]];
  const BISHOP_D = [[-1, -1], [-1, 1], [1, -1], [1, 1]];

  function isAttacked(b, sq, by) {
    const r = sq >> 3, f = sq & 7;
    const pr = by === 'w' ? r + 1 : r - 1;
    if (pr >= 0 && pr < 8) {
      const pawn = by === 'w' ? 'P' : 'p';
      if (f > 0 && b[pr * 8 + f - 1] === pawn) return true;
      if (f < 7 && b[pr * 8 + f + 1] === pawn) return true;
    }
    const kn = by === 'w' ? 'N' : 'n';
    for (const [dr, df] of KNIGHT) {
      const rr = r + dr, ff = f + df;
      if (rr >= 0 && rr < 8 && ff >= 0 && ff < 8 && b[rr * 8 + ff] === kn) return true;
    }
    const ki = by === 'w' ? 'K' : 'k';
    for (const [dr, df] of KING) {
      const rr = r + dr, ff = f + df;
      if (rr >= 0 && rr < 8 && ff >= 0 && ff < 8 && b[rr * 8 + ff] === ki) return true;
    }
    const q = by === 'w' ? 'Q' : 'q', ro = by === 'w' ? 'R' : 'r', bi = by === 'w' ? 'B' : 'b';
    for (const [dr, df] of ROOK_D) {
      let rr = r + dr, ff = f + df;
      while (rr >= 0 && rr < 8 && ff >= 0 && ff < 8) {
        const p = b[rr * 8 + ff];
        if (p) { if (p === q || p === ro) return true; break; }
        rr += dr; ff += df;
      }
    }
    for (const [dr, df] of BISHOP_D) {
      let rr = r + dr, ff = f + df;
      while (rr >= 0 && rr < 8 && ff >= 0 && ff < 8) {
        const p = b[rr * 8 + ff];
        if (p) { if (p === q || p === bi) return true; break; }
        rr += dr; ff += df;
      }
    }
    return false;
  }

  const findKing = (b, c) => b.indexOf(c === 'w' ? 'K' : 'k');

  function genPseudo(s) {
    const { b, turn: us, cast, ep } = s;
    const them = other(us);
    const out = [];
    const push = (f, t, extra) => out.push(Object.assign({ f, t, piece: b[f], cap: b[t] || null, promo: null, ep: false, castle: null }, extra));

    for (let i = 0; i < 64; i++) {
      const p = b[i];
      if (!p || colorOf(p) !== us) continue;
      const r = i >> 3, f = i & 7;
      const kind = p.toLowerCase();

      if (kind === 'p') {
        const dir = us === 'w' ? -1 : 1;
        const startR = us === 'w' ? 6 : 1;
        const promoR = us === 'w' ? 0 : 7;
        const nr = r + dir;
        if (nr >= 0 && nr < 8) {
          const fwd = nr * 8 + f;
          if (!b[fwd]) {
            if (nr === promoR) for (const pr of 'qrbn') push(i, fwd, { promo: pr });
            else {
              push(i, fwd);
              const two = (r + 2 * dir) * 8 + f;
              if (r === startR && !b[two]) push(i, two);
            }
          }
          for (const df of [-1, 1]) {
            const ff = f + df;
            if (ff < 0 || ff > 7) continue;
            const t = nr * 8 + ff;
            if (b[t] && colorOf(b[t]) === them) {
              if (nr === promoR) for (const pr of 'qrbn') push(i, t, { promo: pr });
              else push(i, t);
            } else if (t === ep) {
              push(i, t, { ep: true, cap: us === 'w' ? 'p' : 'P' });
            }
          }
        }
      } else if (kind === 'n' || kind === 'k') {
        for (const [dr, df] of kind === 'n' ? KNIGHT : KING) {
          const rr = r + dr, ff = f + df;
          if (rr < 0 || rr > 7 || ff < 0 || ff > 7) continue;
          const t = rr * 8 + ff;
          if (!b[t] || colorOf(b[t]) === them) push(i, t);
        }
        if (kind === 'k') {
          const home = us === 'w' ? 60 : 4;
          if (i === home && !isAttacked(b, home, them)) {
            const ks = us === 'w' ? cast.K : cast.k;
            const qs = us === 'w' ? cast.Q : cast.q;
            const rook = us === 'w' ? 'R' : 'r';
            if (ks && b[home + 3] === rook && !b[home + 1] && !b[home + 2] &&
                !isAttacked(b, home + 1, them) && !isAttacked(b, home + 2, them)) {
              push(i, home + 2, { castle: 'K' });
            }
            if (qs && b[home - 4] === rook && !b[home - 1] && !b[home - 2] && !b[home - 3] &&
                !isAttacked(b, home - 1, them) && !isAttacked(b, home - 2, them)) {
              push(i, home - 2, { castle: 'Q' });
            }
          }
        }
      } else {
        const dirs = kind === 'r' ? ROOK_D : kind === 'b' ? BISHOP_D : ROOK_D.concat(BISHOP_D);
        for (const [dr, df] of dirs) {
          let rr = r + dr, ff = f + df;
          while (rr >= 0 && rr < 8 && ff >= 0 && ff < 8) {
            const t = rr * 8 + ff;
            if (!b[t]) push(i, t);
            else { if (colorOf(b[t]) === them) push(i, t); break; }
            rr += dr; ff += df;
          }
        }
      }
    }
    return out;
  }

  function apply(s, m) {
    const b = s.b.slice();
    const us = s.turn;
    const piece = b[m.f];
    b[m.t] = piece;
    b[m.f] = null;
    if (m.promo) b[m.t] = us === 'w' ? m.promo.toUpperCase() : m.promo;
    if (m.ep) b[m.t + (us === 'w' ? 8 : -8)] = null;
    if (m.castle) {
      const home = m.f;
      if (m.castle === 'K') { b[home + 1] = b[home + 3]; b[home + 3] = null; }
      else { b[home - 1] = b[home - 4]; b[home - 4] = null; }
    }
    const cast = { K: s.cast.K, Q: s.cast.Q, k: s.cast.k, q: s.cast.q };
    if (piece === 'K') { cast.K = false; cast.Q = false; }
    if (piece === 'k') { cast.k = false; cast.q = false; }
    for (const sq of [m.f, m.t]) {
      if (sq === 63) cast.K = false;
      if (sq === 56) cast.Q = false;
      if (sq === 7) cast.k = false;
      if (sq === 0) cast.q = false;
    }
    const ep = piece.toLowerCase() === 'p' && Math.abs(m.t - m.f) === 16 ? (m.f + m.t) / 2 : -1;
    return { b, turn: other(us), cast, ep };
  }

  function inCheck(s) {
    const k = findKing(s.b, s.turn);
    return k >= 0 && isAttacked(s.b, k, other(s.turn));
  }

  function legalMoves(s) {
    const us = s.turn, them = other(us);
    const res = [];
    for (const m of genPseudo(s)) {
      const ns = apply(s, m);
      const k = findKing(ns.b, us);
      if (k >= 0 && !isAttacked(ns.b, k, them)) res.push(m);
    }
    return res;
  }

  function insufficient(b) {
    const rest = b.filter((p) => p && p.toLowerCase() !== 'k').map((p) => p.toLowerCase());
    if (rest.length === 0) return true;
    if (rest.length === 1 && (rest[0] === 'n' || rest[0] === 'b')) return true;
    return false;
  }

  // 'play' | 'checkmate' | 'stalemate' | 'draw'
  function status(s) {
    const moves = legalMoves(s);
    if (!moves.length) return inCheck(s) ? 'checkmate' : 'stalemate';
    if (insufficient(s.b)) return 'draw';
    return 'play';
  }

  function perft(s, depth) {
    if (depth === 0) return 1;
    let n = 0;
    for (const m of legalMoves(s)) n += perft(apply(s, m), depth - 1);
    return n;
  }

  /* ---------------- AI ---------------- */
  const VAL = { p: 100, n: 320, b: 330, r: 500, q: 900, k: 0 };
  const MATE = 100000;

  function evaluate(s) {
    // score from the side-to-move's perspective
    let score = 0;
    for (let i = 0; i < 64; i++) {
      const p = s.b[i];
      if (!p) continue;
      const kind = p.toLowerCase();
      const white = p === p.toUpperCase();
      const r = i >> 3, f = i & 7;
      const cf = 3.5 - Math.abs(f - 3.5), cr = 3.5 - Math.abs(r - 3.5);
      const center = cf + cr; // 0..7
      let v = VAL[kind];
      if (kind === 'n' || kind === 'b') v += center * 6;
      else if (kind === 'p') {
        const adv = white ? 6 - r : r - 1; // 0..5
        v += adv * 8 + cf * 3;
      } else if (kind === 'q') v += center * 2;
      else if (kind === 'k') {
        const backRank = white ? r === 7 : r === 0;
        v += backRank ? 15 : -20;
        if (backRank && (f <= 2 || f >= 6)) v += 15; // tucked-away / castled
      }
      score += white ? v : -v;
    }
    return s.turn === 'w' ? score : -score;
  }

  function orderScore(m) {
    let sc = 0;
    if (m.cap) sc += 10 * VAL[m.cap.toLowerCase()] - VAL[m.piece.toLowerCase()];
    if (m.promo) sc += 800;
    return sc;
  }

  function qsearch(s, alpha, beta, qd) {
    const stand = evaluate(s);
    if (qd === 0) return stand;
    if (stand >= beta) return stand;
    if (stand > alpha) alpha = stand;
    const moves = legalMoves(s).filter((m) => m.cap || m.promo === 'q');
    moves.sort((a, c) => orderScore(c) - orderScore(a));
    for (const m of moves) {
      const sc = -qsearch(apply(s, m), -beta, -alpha, qd - 1);
      if (sc >= beta) return sc;
      if (sc > alpha) alpha = sc;
    }
    return alpha;
  }

  function negamax(s, depth, alpha, beta, ply) {
    if (depth === 0) return qsearch(s, alpha, beta, 4);
    const moves = legalMoves(s);
    if (!moves.length) return inCheck(s) ? -MATE + ply : 0;
    moves.sort((a, c) => orderScore(c) - orderScore(a));
    for (const m of moves) {
      const sc = -negamax(apply(s, m), depth - 1, -beta, -alpha, ply + 1);
      if (sc >= beta) return sc;
      if (sc > alpha) alpha = sc;
    }
    return alpha;
  }

  // level 0: sloppy (depth 1 + noise), 1: depth 2, 2: depth 3
  function bestMove(s, level) {
    const moves = legalMoves(s);
    if (!moves.length) return null;
    const depth = [1, 2, 3][level] || 2;
    const noise = [140, 12, 4][level] || 10;
    let best = null, bestScore = -Infinity;
    moves.sort((a, c) => orderScore(c) - orderScore(a));
    let alpha = -Infinity;
    for (const m of moves) {
      let sc = -negamax(apply(s, m), depth - 1, -Infinity, -alpha, 1);
      // noise on every root move: alpha-beta bounds are only exact for the best move,
      // which is fine — noise just breaks ties / makes weak levels blunder a bit.
      sc += (Math.random() - 0.5) * noise;
      if (sc > bestScore) { bestScore = sc; best = m; }
      if (level > 0 && sc > alpha) alpha = sc - noise; // keep a margin so noisy near-ties survive
    }
    return best;
  }

  const api = {
    colorOf, other, sqName, fromFEN, initial, isAttacked, findKing,
    legalMoves, apply, inCheck, status, perft, bestMove, evaluate, VAL,
  };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.ChessEngine = api;
})(typeof window !== 'undefined' ? window : globalThis);
