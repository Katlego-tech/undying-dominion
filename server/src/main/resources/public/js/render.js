import {
  game, TERRAIN, colorOf, renderPos, isMine, myNecromancer, label, terrainAt,
} from './state.js';

export const TILE = 28;

const TAU = Math.PI * 2;
const BONE = '#e8e2d0';
const SOUL = '#5eead4';
const VOID = '#0b0a0e';

const MINIMAP_COLORS = {
  [TERRAIN.GROUND]: [30, 27, 36],
  [TERRAIN.RUINS]: [78, 72, 88],
  [TERRAIN.SWAMP]: [30, 52, 42],
  [TERRAIN.ABYSS]: [8, 4, 14],
};

/** A repeatable 0..1 value per tile, so the terrain looks hand-scattered but never changes. */
function hash(x, y, salt) {
  let n = Math.imul(x, 374761393) + Math.imul(y, 668265263) + Math.imul(salt, 982451653);
  n = Math.imul(n ^ (n >>> 13), 1274126177);
  return ((n ^ (n >>> 16)) >>> 0) / 4294967295;
}

function darken(hex, amount) {
  const n = parseInt(hex.slice(1), 16);
  const r = (n >> 16) & 255, g = (n >> 8) & 255, b = n & 255;
  const k = 1 - amount;
  return `rgb(${Math.round(r * k + 11 * amount)}, ${Math.round(g * k + 10 * amount)}, ${Math.round(b * k + 14 * amount)})`;
}

export function createRenderer(canvas, minimap) {
  const ctx = canvas.getContext('2d');
  const mctx = minimap.getContext('2d');
  let dpr = 1;
  let vw = 0;
  let vh = 0;
  let terrainCanvas = null;
  let fogCanvas = null;
  let fogCtx = null;
  let fogImage = null;
  let fogDrawn = -1;
  let minimapBase = null;
  let minimapDrawnAt = 0;

  function resize() {
    dpr = Math.min(window.devicePixelRatio || 1, 2);
    vw = window.innerWidth;
    vh = window.innerHeight;
    canvas.width = Math.round(vw * dpr);
    canvas.height = Math.round(vh * dpr);
  }

  function viewport() {
    return { width: vw, height: vh };
  }

  // ---------------------------------------------------------------- terrain, baked once

  function buildTerrain() {
    const { width, height } = game.map;
    terrainCanvas = document.createElement('canvas');
    terrainCanvas.width = width * TILE;
    terrainCanvas.height = height * TILE;
    const t = terrainCanvas.getContext('2d');

    for (let y = 0; y < height; y++) {
      for (let x = 0; x < width; x++) paintTile(t, x, y, terrainAt(x, y));
    }
    for (let y = 0; y < height; y++) {
      for (let x = 0; x < width; x++) paintEdges(t, x, y, terrainAt(x, y));
    }

    t.strokeStyle = 'rgba(255, 255, 255, 0.022)';
    t.lineWidth = 1;
    t.beginPath();
    for (let x = 0; x <= width; x++) { t.moveTo(x * TILE + 0.5, 0); t.lineTo(x * TILE + 0.5, height * TILE); }
    for (let y = 0; y <= height; y++) { t.moveTo(0, y * TILE + 0.5); t.lineTo(width * TILE, y * TILE + 0.5); }
    t.stroke();

    fogCanvas = document.createElement('canvas');
    fogCanvas.width = width;
    fogCanvas.height = height;
    fogCtx = fogCanvas.getContext('2d');
    fogImage = fogCtx.createImageData(width, height);
    fogDrawn = -1;

    minimapBase = mctx.createImageData(width, height);
    for (let i = 0; i < width * height; i++) {
      const [r, g, b] = MINIMAP_COLORS[game.map.terrain[i]];
      minimapBase.data.set([r, g, b, 255], i * 4);
    }
  }

  function paintTile(t, x, y, kind) {
    const px = x * TILE;
    const py = y * TILE;
    const r = (salt) => hash(x, y, salt);

    if (kind === TERRAIN.GROUND) {
      t.fillStyle = '#16141b';
      t.fillRect(px, py, TILE, TILE);
      t.fillStyle = `rgba(255, 255, 255, ${0.012 + r(1) * 0.025})`;
      t.fillRect(px, py, TILE, TILE);
      for (let i = 0; i < 4; i++) {
        if (r(10 + i) > 0.5) {
          t.fillStyle = `rgba(232, 226, 208, ${0.03 + r(20 + i) * 0.05})`;
          t.fillRect(px + r(30 + i) * (TILE - 2), py + r(40 + i) * (TILE - 2), 1 + (r(50 + i) > 0.7), 1);
        }
      }
      if (r(60) > 0.84) {
        t.strokeStyle = 'rgba(80, 78, 52, 0.55)';
        t.lineWidth = 1;
        const gx = px + 6 + r(61) * (TILE - 12);
        const gy = py + 8 + r(62) * (TILE - 12);
        t.beginPath();
        t.moveTo(gx, gy); t.lineTo(gx - 2, gy - 5);
        t.moveTo(gx + 1, gy); t.lineTo(gx + 2, gy - 6);
        t.moveTo(gx + 3, gy); t.lineTo(gx + 5, gy - 4);
        t.stroke();
      }
      if (r(70) > 0.975) {
        t.strokeStyle = 'rgba(232, 226, 208, 0.22)';
        t.lineWidth = 1.5;
        const bx = px + 6 + r(71) * (TILE - 12);
        const by = py + 6 + r(72) * (TILE - 12);
        t.beginPath();
        t.moveTo(bx, by); t.lineTo(bx + 6, by + 3);
        t.stroke();
      }
    } else if (kind === TERRAIN.RUINS) {
      t.fillStyle = '#25212b';
      t.fillRect(px, py, TILE, TILE);
      t.fillStyle = r(1) > 0.5 ? '#3a3542' : '#36313e';
      t.fillRect(px + 1, py + 1, TILE - 2, TILE - 2);
      t.fillStyle = '#4a4452';
      t.fillRect(px + 1, py + 1, TILE - 2, 2);
      t.fillRect(px + 1, py + 1, 2, TILE - 2);
      t.fillStyle = '#1c1a21';
      t.fillRect(px + 1, py + TILE - 3, TILE - 2, 2);
      t.fillRect(px + TILE - 3, py + 1, 2, TILE - 2);
      t.strokeStyle = 'rgba(0, 0, 0, 0.28)';
      t.lineWidth = 1;
      t.beginPath();
      t.moveTo(px + 3, py + TILE / 2 + (r(2) - 0.5) * 6);
      t.lineTo(px + TILE - 3, py + TILE / 2 + (r(3) - 0.5) * 6);
      if (r(4) > 0.5) {
        const cx = px + 6 + r(5) * (TILE - 12);
        t.moveTo(cx, py + 4);
        t.lineTo(cx + (r(6) - 0.5) * 8, py + TILE / 2);
      }
      t.stroke();
    } else if (kind === TERRAIN.SWAMP) {
      t.fillStyle = '#121c17';
      t.fillRect(px, py, TILE, TILE);
      t.fillStyle = `rgba(70, 120, 90, ${0.08 + r(1) * 0.07})`;
      t.fillRect(px, py, TILE, TILE);
      t.strokeStyle = 'rgba(150, 200, 165, 0.12)';
      t.lineWidth = 1;
      t.beginPath();
      t.ellipse(px + 6 + r(2) * (TILE - 12), py + 6 + r(3) * (TILE - 12), 4 + r(4) * 4, 1.5 + r(5) * 1.5, 0, 0, TAU);
      t.stroke();
      if (r(6) > 0.72) {
        t.strokeStyle = 'rgba(96, 112, 60, 0.7)';
        const rx = px + 5 + r(7) * (TILE - 10);
        const ry = py + TILE - 5;
        t.beginPath();
        t.moveTo(rx, ry); t.lineTo(rx - 1, ry - 9);
        t.moveTo(rx + 3, ry); t.lineTo(rx + 4, ry - 7);
        t.stroke();
      }
    } else {
      t.fillStyle = '#030205';
      t.fillRect(px, py, TILE, TILE);
      if (r(1) > 0.6) {
        t.fillStyle = `rgba(167, 139, 250, ${0.2 + r(2) * 0.3})`;
        t.fillRect(px + r(3) * TILE, py + r(4) * TILE, 1, 1);
      }
    }
  }

  // Soft edges between terrain kinds: violet light rising from the abyss, shadows cast by ruins.
  function paintEdges(t, x, y, kind) {
    const px = x * TILE;
    const py = y * TILE;
    if (kind === TERRAIN.ABYSS) {
      const sides = [[0, -1], [1, 0], [0, 1], [-1, 0]];
      for (const [dx, dy] of sides) {
        if (terrainAt(x + dx, y + dy) === TERRAIN.ABYSS) continue;
        if (x + dx < 0 || y + dy < 0 || x + dx >= game.map.width || y + dy >= game.map.height) continue;
        const depth = TILE * 0.7;
        const x0 = dx === 1 ? px + TILE : px;
        const y0 = dy === 1 ? py + TILE : py;
        const g = t.createLinearGradient(x0, y0, x0 - dx * depth, y0 - dy * depth);
        g.addColorStop(0, 'rgba(124, 58, 237, 0.42)');
        g.addColorStop(1, 'rgba(124, 58, 237, 0)');
        t.fillStyle = g;
        t.fillRect(px, py, TILE, TILE);
      }
    } else if (kind === TERRAIN.RUINS && terrainAt(x, y + 1) !== TERRAIN.RUINS && y + 1 < game.map.height) {
      const g = t.createLinearGradient(0, py + TILE, 0, py + TILE + TILE * 0.4);
      g.addColorStop(0, 'rgba(0, 0, 0, 0.45)');
      g.addColorStop(1, 'rgba(0, 0, 0, 0)');
      t.fillStyle = g;
      t.fillRect(px, py + TILE, TILE, TILE * 0.4);
    }
  }

  function refreshFog() {
    if (fogDrawn === game.fogVersion) return;
    const data = fogImage.data;
    const n = game.map.width * game.map.height;
    for (let i = 0; i < n; i++) {
      const alpha = game.visible[i] ? 0 : game.explored[i] ? 155 : 250;
      const o = i * 4;
      data[o] = 6; data[o + 1] = 5; data[o + 2] = 10; data[o + 3] = alpha;
    }
    fogCtx.putImageData(fogImage, 0, 0);
    fogDrawn = game.fogVersion;
  }

  // ---------------------------------------------------------------- unit glyphs

  function skull(x, y, s, eyes = VOID) {
    ctx.fillStyle = BONE;
    ctx.beginPath();
    ctx.arc(x, y - s * 0.15, s, Math.PI * 0.85, Math.PI * 2.15);
    ctx.lineTo(x + s * 0.5, y + s * 0.8);
    ctx.lineTo(x - s * 0.5, y + s * 0.8);
    ctx.closePath();
    ctx.fill();
    ctx.fillStyle = eyes;
    ctx.beginPath();
    ctx.ellipse(x - s * 0.38, y - s * 0.05, s * 0.25, s * 0.3, 0, 0, TAU);
    ctx.ellipse(x + s * 0.38, y - s * 0.05, s * 0.25, s * 0.3, 0, 0, TAU);
    ctx.fill();
    ctx.fillStyle = VOID;
    ctx.beginPath();
    ctx.moveTo(x, y + s * 0.25);
    ctx.lineTo(x - s * 0.1, y + s * 0.45);
    ctx.lineTo(x + s * 0.1, y + s * 0.45);
    ctx.fill();
  }

  function line(x1, y1, x2, y2, color, width) {
    ctx.strokeStyle = color;
    ctx.lineWidth = width;
    ctx.lineCap = 'round';
    ctx.beginPath();
    ctx.moveTo(x1, y1);
    ctx.lineTo(x2, y2);
    ctx.stroke();
  }

  const GLYPHS = {
    NECROMANCER(x, y, r, color) {
      ctx.fillStyle = darken(color, 0.35);
      ctx.beginPath();
      ctx.moveTo(x - r * 0.05, y - r * 0.9);
      ctx.quadraticCurveTo(x + r * 0.55, y - r * 0.55, x + r * 0.6, y + r * 0.8);
      ctx.lineTo(x - r * 0.7, y + r * 0.8);
      ctx.quadraticCurveTo(x - r * 0.6, y - r * 0.5, x - r * 0.05, y - r * 0.9);
      ctx.fill();
      ctx.fillStyle = VOID;
      ctx.beginPath();
      ctx.ellipse(x - r * 0.05, y - r * 0.18, r * 0.3, r * 0.36, 0, 0, TAU);
      ctx.fill();
      ctx.fillStyle = SOUL;
      ctx.shadowColor = SOUL;
      ctx.shadowBlur = 6;
      ctx.beginPath();
      ctx.arc(x - r * 0.16, y - r * 0.2, r * 0.07, 0, TAU);
      ctx.arc(x + r * 0.06, y - r * 0.2, r * 0.07, 0, TAU);
      ctx.fill();
      line(x + r * 0.78, y - r * 0.85, x + r * 0.78, y + r * 0.85, '#8b7355', r * 0.11);
      ctx.beginPath();
      ctx.arc(x + r * 0.78, y - r * 0.92, r * 0.17, 0, TAU);
      ctx.fill();
      ctx.shadowBlur = 0;
    },
    SKELETON_WARRIOR(x, y, r) {
      line(x - r * 0.5, y + r * 0.6, x + r * 0.62, y - r * 0.62, '#cbd5e1', r * 0.13);
      line(x - r * 0.42, y + r * 0.22, x - r * 0.1, y + r * 0.54, '#94a3b8', r * 0.11);
      skull(x - r * 0.12, y + r * 0.05, r * 0.42);
    },
    SKELETON_ARCHER(x, y, r) {
      ctx.strokeStyle = '#b08d57';
      ctx.lineWidth = r * 0.12;
      ctx.beginPath();
      ctx.arc(x - r * 0.05, y, r * 0.66, -1.15, 1.15);
      ctx.stroke();
      const ax = x - r * 0.05 + Math.cos(1.15) * r * 0.66;
      const ay = Math.sin(1.15) * r * 0.66;
      line(ax, y - ay, ax, y + ay, 'rgba(232, 226, 208, 0.7)', 1);
      skull(x - r * 0.18, y + r * 0.02, r * 0.38);
    },
    LICH(x, y, r) {
      const g = ctx.createRadialGradient(x, y, r * 0.1, x, y, r * 0.9);
      g.addColorStop(0, '#ddd6fe');
      g.addColorStop(1, '#6d28d9');
      ctx.fillStyle = g;
      ctx.shadowColor = '#a78bfa';
      ctx.shadowBlur = 10;
      ctx.beginPath();
      ctx.moveTo(x, y - r * 0.92);
      ctx.lineTo(x + r * 0.58, y);
      ctx.lineTo(x, y + r * 0.82);
      ctx.lineTo(x - r * 0.58, y);
      ctx.closePath();
      ctx.fill();
      ctx.shadowBlur = 0;
      skull(x, y + r * 0.05, r * 0.3, SOUL);
    },
    GRAVE_WARDEN(x, y, r) {
      line(x + r * 0.6, y - r * 0.7, x - r * 0.1, y + r * 0.45, '#8b7355', r * 0.12);
      ctx.fillStyle = '#9ca3af';
      ctx.beginPath();
      ctx.moveTo(x - r * 0.1, y + r * 0.3);
      ctx.lineTo(x + r * 0.08, y + r * 0.5);
      ctx.lineTo(x - r * 0.25, y + r * 0.85);
      ctx.lineTo(x - r * 0.42, y + r * 0.66);
      ctx.closePath();
      ctx.fill();
      skull(x - r * 0.2, y - r * 0.12, r * 0.36);
    },
    SKELETON_KING(x, y, r) {
      skull(x, y + r * 0.12, r * 0.5);
      ctx.fillStyle = '#fbbf24';
      ctx.shadowColor = '#fbbf24';
      ctx.shadowBlur = 8;
      ctx.beginPath();
      ctx.moveTo(x - r * 0.5, y - r * 0.38);
      ctx.lineTo(x - r * 0.55, y - r * 0.85);
      ctx.lineTo(x - r * 0.25, y - r * 0.6);
      ctx.lineTo(x, y - r * 0.95);
      ctx.lineTo(x + r * 0.25, y - r * 0.6);
      ctx.lineTo(x + r * 0.55, y - r * 0.85);
      ctx.lineTo(x + r * 0.5, y - r * 0.38);
      ctx.closePath();
      ctx.fill();
      ctx.shadowBlur = 0;
    },
    WOLF(x, y, r) {
      ctx.fillStyle = '#8b7d6b';
      ctx.beginPath();
      ctx.moveTo(x - r * 0.5, y - r * 0.75);
      ctx.lineTo(x - r * 0.22, y - r * 0.36);
      ctx.lineTo(x + r * 0.04, y - r * 0.82);
      ctx.lineTo(x + r * 0.2, y - r * 0.3);
      ctx.lineTo(x + r * 0.82, y + r * 0.06);
      ctx.lineTo(x + r * 0.22, y + r * 0.46);
      ctx.lineTo(x - r * 0.56, y + r * 0.42);
      ctx.lineTo(x - r * 0.62, y - r * 0.12);
      ctx.closePath();
      ctx.fill();
      ctx.fillStyle = '#fca5a5';
      ctx.beginPath();
      ctx.arc(x + r * 0.1, y - r * 0.06, r * 0.08, 0, TAU);
      ctx.fill();
    },
    KNIGHT(x, y, r) {
      ctx.fillStyle = '#94a3b8';
      ctx.strokeStyle = '#e2e8f0';
      ctx.lineWidth = r * 0.08;
      ctx.beginPath();
      ctx.moveTo(x - r * 0.55, y - r * 0.6);
      ctx.lineTo(x + r * 0.55, y - r * 0.6);
      ctx.quadraticCurveTo(x + r * 0.58, y + r * 0.3, x, y + r * 0.82);
      ctx.quadraticCurveTo(x - r * 0.58, y + r * 0.3, x - r * 0.55, y - r * 0.6);
      ctx.fill();
      ctx.stroke();
      line(x, y - r * 0.45, x, y + r * 0.5, '#7f1d1d', r * 0.14);
      line(x - r * 0.35, y - r * 0.1, x + r * 0.35, y - r * 0.1, '#7f1d1d', r * 0.14);
    },
  };

  function unitRadius(type) {
    if (type === 'SKELETON_KING') return TILE * 0.47;
    if (type === 'NECROMANCER') return TILE * 0.42;
    return TILE * 0.36;
  }

  function drawUnit(unit, now) {
    const p = renderPos(unit, now);
    const cx = (p.x + 0.5) * TILE;
    const cy = (p.y + 0.5) * TILE;
    const r = unitRadius(unit.type);
    const color = colorOf(unit.owner);
    const selected = game.selection.has(unit.id);

    ctx.fillStyle = 'rgba(0, 0, 0, 0.42)';
    ctx.beginPath();
    ctx.ellipse(cx, cy + r * 0.8, r * 0.9, r * 0.3, 0, 0, TAU);
    ctx.fill();

    if (selected) {
      ctx.strokeStyle = SOUL;
      ctx.lineWidth = 1.5;
      ctx.beginPath();
      ctx.ellipse(cx, cy + r * 0.55, r * 1.15, r * 0.5, 0, 0, TAU);
      ctx.stroke();
    }
    if (unit.raised) {
      ctx.fillStyle = 'rgba(94, 234, 212, 0.13)';
      ctx.beginPath();
      ctx.arc(cx, cy, r * 1.3, 0, TAU);
      ctx.fill();
    }

    ctx.fillStyle = darken(color, 0.78);
    ctx.strokeStyle = color;
    ctx.lineWidth = isMine(unit) ? 2 : 1.5;
    ctx.beginPath();
    ctx.arc(cx, cy, r, 0, TAU);
    ctx.fill();
    ctx.stroke();

    (GLYPHS[unit.type] ?? GLYPHS.SKELETON_WARRIOR)(cx, cy, r * 0.82, color);

    if (unit.modeEndsIn != null) {
      const total = unit.order === 'MEND' ? game.rules.mendTicks : game.rules.trapSetTicks;
      const remaining = unit.modeEndsIn - (now - game.lastStateAt) / game.tickMs;
      const done = Math.min(1, Math.max(0, 1 - remaining / total));
      ctx.strokeStyle = unit.order === 'MEND' ? '#4ade80' : '#fbbf24';
      ctx.lineWidth = 2.5;
      ctx.beginPath();
      ctx.arc(cx, cy, r + 4, -Math.PI / 2, -Math.PI / 2 + TAU * done);
      ctx.stroke();
    }

    const maxHp = game.unitTypes[unit.type]?.maxHp ?? unit.hp;
    if (unit.hp < maxHp || selected) {
      const ratio = unit.hp / maxHp;
      const w = r * 2;
      const x0 = cx - r;
      const y0 = cy - r - 7;
      ctx.fillStyle = 'rgba(0, 0, 0, 0.65)';
      ctx.fillRect(x0 - 1, y0 - 1, w + 2, 5);
      ctx.fillStyle = ratio > 0.6 ? '#4ade80' : ratio > 0.3 ? '#fbbf24' : '#f87171';
      ctx.fillRect(x0, y0, w * ratio, 3);
    }
  }

  function drawCorpse(corpse, now, raiseable) {
    const cx = (corpse.x + 0.5) * TILE;
    const cy = (corpse.y + 0.5) * TILE;
    const freshness = Math.min(1, corpse.decaysIn / 120);
    ctx.globalAlpha = 0.35 + 0.5 * freshness;
    if (raiseable) {
      const pulse = 0.5 + 0.5 * Math.sin(now / 220);
      ctx.fillStyle = `rgba(94, 234, 212, ${0.12 + pulse * 0.18})`;
      ctx.beginPath();
      ctx.arc(cx, cy, TILE * 0.45, 0, TAU);
      ctx.fill();
    }
    line(cx - 7, cy - 4, cx + 7, cy + 5, BONE, 2.5);
    line(cx - 7, cy + 5, cx + 7, cy - 4, BONE, 2.5);
    ctx.fillStyle = BONE;
    ctx.beginPath();
    ctx.arc(cx + 1, cy - 7, 3.5, 0, TAU);
    ctx.fill();
    ctx.globalAlpha = 1;
  }

  function drawTrap(trap) {
    const cx = (trap.x + 0.5) * TILE;
    const cy = (trap.y + 0.5) * TILE;
    const mine = trap.owner === game.playerId;
    ctx.fillStyle = mine ? 'rgba(232, 226, 208, 0.75)' : 'rgba(248, 113, 113, 0.85)';
    for (let i = -1; i <= 1; i++) {
      ctx.beginPath();
      ctx.moveTo(cx + i * 6 - 3, cy + 6);
      ctx.lineTo(cx + i * 6, cy - 5 + Math.abs(i) * 3);
      ctx.lineTo(cx + i * 6 + 3, cy + 6);
      ctx.fill();
    }
  }

  // ---------------------------------------------------------------- effects

  function drawEffects(now) {
    game.effects = game.effects.filter((e) => now - e.t0 < e.duration);
    for (const e of game.effects) {
      const k = (now - e.t0) / e.duration;
      const x = (e.x + 0.5) * TILE;
      const y = (e.y + 0.5) * TILE;
      switch (e.kind) {
        case 'attack': drawAttack(e, k); break;
        case 'death':
          for (let i = 0; i < 7; i++) {
            const a = (i / 7) * TAU + e.x;
            const d = 4 + k * 16;
            ctx.fillStyle = `rgba(232, 226, 208, ${1 - k})`;
            ctx.fillRect(x + Math.cos(a) * d - 1.5, y + Math.sin(a) * d - 1.5 + k * 6, 3, 3);
          }
          break;
        case 'rise':
          ctx.strokeStyle = `rgba(94, 234, 212, ${1 - k})`;
          ctx.lineWidth = 2;
          ctx.beginPath();
          ctx.ellipse(x, y + 6, 6 + k * 14, (6 + k * 14) * 0.4, 0, 0, TAU);
          ctx.stroke();
          for (let i = 0; i < 5; i++) {
            ctx.fillStyle = `rgba(94, 234, 212, ${(1 - k) * 0.9})`;
            ctx.fillRect(x - 8 + i * 4, y - k * 22 - (i % 2) * 6, 2, 4);
          }
          break;
        case 'fuse':
          ctx.strokeStyle = `rgba(251, 191, 36, ${1 - k})`;
          ctx.lineWidth = 3;
          ctx.beginPath();
          ctx.arc(x, y, 10 + k * 60, 0, TAU);
          ctx.stroke();
          break;
        case 'level':
          ctx.strokeStyle = `rgba(251, 191, 36, ${1 - k})`;
          ctx.lineWidth = 2;
          ctx.beginPath();
          ctx.arc(x, y, 14 + k * 30, 0, TAU);
          ctx.stroke();
          break;
        case 'mend':
          ctx.fillStyle = `rgba(74, 222, 128, ${1 - k})`;
          ctx.fillRect(x - 1.5, y - 14 - k * 12, 3, 9);
          ctx.fillRect(x - 4.5, y - 11 - k * 12, 9, 3);
          break;
        case 'trap-set':
          ctx.strokeStyle = `rgba(251, 191, 36, ${1 - k})`;
          ctx.lineWidth = 1.5;
          ctx.beginPath();
          ctx.arc(x, y, 4 + k * 10, 0, TAU);
          ctx.stroke();
          break;
        case 'spikes':
          ctx.fillStyle = `rgba(248, 113, 113, ${1 - k})`;
          for (let i = 0; i < 6; i++) {
            const a = (i / 6) * TAU;
            const d = 4 + k * 12;
            ctx.beginPath();
            ctx.moveTo(x + Math.cos(a) * (d + 8), y + Math.sin(a) * (d + 8));
            ctx.lineTo(x + Math.cos(a + 0.25) * d, y + Math.sin(a + 0.25) * d);
            ctx.lineTo(x + Math.cos(a - 0.25) * d, y + Math.sin(a - 0.25) * d);
            ctx.fill();
          }
          break;
        case 'marker':
          ctx.strokeStyle = e.hostile ? `rgba(248, 113, 113, ${1 - k})` : `rgba(94, 234, 212, ${1 - k})`;
          ctx.lineWidth = 2;
          ctx.beginPath();
          ctx.arc(x, y, 12 * (1 - k) + 3, 0, TAU);
          ctx.stroke();
          break;
        default:
          break;
      }
    }
  }

  function drawAttack(e, k) {
    const x1 = (e.x + 0.5) * TILE;
    const y1 = (e.y + 0.5) * TILE;
    const x2 = (e.x2 + 0.5) * TILE;
    const y2 = (e.y2 + 0.5) * TILE;
    const distance = Math.max(Math.abs(e.x2 - e.x), Math.abs(e.y2 - e.y));

    if (distance <= 1) {
      ctx.strokeStyle = `rgba(255, 255, 255, ${0.9 * (1 - k)})`;
      ctx.lineWidth = 2;
      const angle = Math.atan2(y2 - y1, x2 - x1);
      ctx.beginPath();
      ctx.arc(x2, y2, 9, angle - 1.4 + k * 1.2, angle - 0.2 + k * 1.2);
      ctx.stroke();
      return;
    }
    const px = x1 + (x2 - x1) * k;
    const py = y1 + (y2 - y1) * k;
    if (e.attackerType === 'SKELETON_ARCHER') {
      const len = 9;
      const dx = (x2 - x1) / Math.hypot(x2 - x1, y2 - y1);
      const dy = (y2 - y1) / Math.hypot(x2 - x1, y2 - y1);
      line(px - dx * len, py - dy * len, px, py, BONE, 1.5);
      return;
    }
    const color = e.attackerType === 'LICH' ? '#a78bfa' : SOUL;
    ctx.fillStyle = color;
    ctx.shadowColor = color;
    ctx.shadowBlur = 12;
    ctx.beginPath();
    ctx.arc(px, py, e.attackerType === 'LICH' ? 5 : 3.5, 0, TAU);
    ctx.fill();
    ctx.shadowBlur = 0;
    if (e.attackerType === 'LICH' && k > 0.75) {
      ctx.strokeStyle = `rgba(167, 139, 250, ${(1 - k) * 3})`;
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.arc(x2, y2, TILE * 1.5 * k, 0, TAU);
      ctx.stroke();
    }
  }

  // ---------------------------------------------------------------- overlays

  function drawRaiseRange(necromancer, now) {
    const p = renderPos(necromancer, now);
    const range = game.rules.raiseRange;
    ctx.strokeStyle = 'rgba(94, 234, 212, 0.55)';
    ctx.setLineDash([6, 6]);
    ctx.lineWidth = 1.5;
    ctx.strokeRect((p.x - range) * TILE, (p.y - range) * TILE, (range * 2 + 1) * TILE, (range * 2 + 1) * TILE);
    ctx.setLineDash([]);
    ctx.fillStyle = 'rgba(94, 234, 212, 0.04)';
    ctx.fillRect((p.x - range) * TILE, (p.y - range) * TILE, (range * 2 + 1) * TILE, (range * 2 + 1) * TILE);
  }

  function drawHoverTile() {
    const { tx, ty, inside } = game.pointer;
    if (!inside || tx < 0 || ty < 0 || tx >= game.map.width || ty >= game.map.height) return;
    ctx.strokeStyle = 'rgba(232, 226, 208, 0.18)';
    ctx.lineWidth = 1;
    ctx.strokeRect(tx * TILE + 0.5, ty * TILE + 0.5, TILE - 1, TILE - 1);
  }

  function drawTooltip(now) {
    const { tx, ty, sx, sy, inside } = game.pointer;
    if (!inside || game.drag) return;
    let text = null;
    for (const unit of game.units.values()) {
      if (unit.x === tx && unit.y === ty) {
        const maxHp = game.unitTypes[unit.type]?.maxHp ?? unit.hp;
        const owner = unit.owner === 0 ? 'Wild' : game.players.find((p) => p.id === unit.owner)?.name ?? '';
        text = `${unit.raised ? 'Raised ' : ''}${label(unit.type)} · ${unit.hp}/${maxHp}${owner && !isMine(unit) ? ` · ${owner}` : ''}`;
        break;
      }
    }
    if (!text) {
      const corpse = game.corpses.find((c) => c.x === tx && c.y === ty);
      if (corpse) {
        const cost = game.unitTypes[corpse.type]?.raiseCost;
        text = `${label(corpse.type)} corpse · raise for ${cost} soul · ${Math.ceil(corpse.decaysIn / 10)}s`;
      }
    }
    if (!text) return;
    ctx.font = '500 12px Inter, system-ui, sans-serif';
    const w = ctx.measureText(text).width + 16;
    const x = Math.min(sx + 14, vw - w - 8);
    const y = Math.max(sy - 30, 8);
    ctx.fillStyle = 'rgba(14, 12, 18, 0.92)';
    ctx.strokeStyle = 'rgba(232, 226, 208, 0.14)';
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.roundRect(x, y, w, 24, 6);
    ctx.fill();
    ctx.stroke();
    ctx.fillStyle = BONE;
    ctx.fillText(text, x + 8, y + 16);
  }

  // ---------------------------------------------------------------- frame

  function frame(now) {
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.fillStyle = '#050407';
    ctx.fillRect(0, 0, vw, vh);
    if (!game.map || !terrainCanvas) return;

    const { x: camX, y: camY, zoom } = game.camera;
    ctx.save();
    ctx.scale(zoom, zoom);
    ctx.translate(-camX, -camY);
    ctx.imageSmoothingEnabled = true;
    ctx.drawImage(terrainCanvas, 0, 0);

    const necromancer = myNecromancer();
    if (game.mode === 'raise' && necromancer) drawRaiseRange(necromancer, now);

    for (const corpse of game.corpses) {
      const raiseable = game.mode === 'raise' && necromancer
        && Math.max(Math.abs(corpse.x - necromancer.x), Math.abs(corpse.y - necromancer.y)) <= game.rules.raiseRange;
      drawCorpse(corpse, now, raiseable);
    }
    for (const trap of game.traps) drawTrap(trap);

    const units = [...game.units.values()];
    units.sort((a, b) => renderPos(a, now).y - renderPos(b, now).y);
    for (const unit of units) drawUnit(unit, now);

    drawEffects(now);

    refreshFog();
    ctx.drawImage(fogCanvas, 0, 0, game.map.width * TILE, game.map.height * TILE);
    drawHoverTile();
    ctx.restore();

    if (game.drag) {
      const { sx0, sy0, sx1, sy1 } = game.drag;
      ctx.fillStyle = 'rgba(94, 234, 212, 0.08)';
      ctx.strokeStyle = 'rgba(94, 234, 212, 0.8)';
      ctx.lineWidth = 1;
      ctx.fillRect(Math.min(sx0, sx1), Math.min(sy0, sy1), Math.abs(sx1 - sx0), Math.abs(sy1 - sy0));
      ctx.strokeRect(Math.min(sx0, sx1) + 0.5, Math.min(sy0, sy1) + 0.5, Math.abs(sx1 - sx0), Math.abs(sy1 - sy0));
    }
    drawTooltip(now);

    if (now - minimapDrawnAt > 200) {
      drawMinimap();
      minimapDrawnAt = now;
    }
  }

  function drawMinimap() {
    if (!minimapBase) return;
    const { width, height } = game.map;
    const image = new ImageData(new Uint8ClampedArray(minimapBase.data), width, height);
    const d = image.data;
    for (let i = 0; i < width * height; i++) {
      const o = i * 4;
      if (!game.explored[i]) { d[o] = 4; d[o + 1] = 3; d[o + 2] = 6; } else if (!game.visible[i]) {
        d[o] *= 0.45; d[o + 1] *= 0.45; d[o + 2] *= 0.45;
      }
    }
    for (const unit of game.units.values()) {
      const n = parseInt(colorOf(unit.owner).slice(1), 16);
      d.set([(n >> 16) & 255, (n >> 8) & 255, n & 255, 255], (unit.y * width + unit.x) * 4);
    }
    const scratch = new OffscreenCanvas(width, height);
    scratch.getContext('2d').putImageData(image, 0, 0);

    mctx.imageSmoothingEnabled = false;
    mctx.clearRect(0, 0, minimap.width, minimap.height);
    mctx.drawImage(scratch, 0, 0, minimap.width, minimap.height);

    const sx = minimap.width / (width * TILE);
    const sy = minimap.height / (height * TILE);
    const { x, y, zoom } = game.camera;
    mctx.strokeStyle = 'rgba(232, 226, 208, 0.8)';
    mctx.lineWidth = 1;
    mctx.strokeRect(x * sx + 0.5, y * sy + 0.5, (vw / zoom) * sx, (vh / zoom) * sy);
  }

  return { resize, viewport, buildTerrain, frame };
}
