import {
  game, unitAtTile, isMine, myNecromancer, renderPos, isWalkable,
} from './state.js';
import { TILE } from './render.js';
import * as cmd from './commands.js';
import { summonHotkey, showCameraHint } from './hud.js';

const PAN_SPEED = 900;     // screen pixels per second
const MIN_ZOOM = 0.55;
const MAX_ZOOM = 2.2;
const FOLLOW_INSET = 0.25; // the camera follows once the necromancer is this close to an edge
const WALK_KEYS = { w: [0, -1], a: [-1, 0], s: [0, 1], d: [1, 0] };

export function createInput(canvas, minimap, renderer) {
  const held = new Set();
  let panning = null;
  let minimapDragging = false;
  let walkTarget = null;   // the tile the necromancer was last steered to, while WASD is held
  let gatheredTo = null;   // the necromancer's tile the legion was last called to, while G is held

  function updatePointer(e) {
    const p = game.pointer;
    p.sx = e.clientX;
    p.sy = e.clientY;
    const { x, y, zoom } = game.camera;
    p.tx = Math.floor((e.clientX / zoom + x) / TILE);
    p.ty = Math.floor((e.clientY / zoom + y) / TILE);
  }

  function clampCamera() {
    if (!game.map) return;
    const cam = game.camera;
    const { width, height } = renderer.viewport();
    const worldW = game.map.width * TILE;
    const worldH = game.map.height * TILE;
    const viewW = width / cam.zoom;
    const viewH = height / cam.zoom;
    const margin = TILE * 3;
    cam.x = viewW >= worldW + margin * 2 ? (worldW - viewW) / 2 : Math.min(Math.max(cam.x, -margin), worldW - viewW + margin);
    cam.y = viewH >= worldH + margin * 2 ? (worldH - viewH) / 2 : Math.min(Math.max(cam.y, -margin), worldH - viewH + margin);
  }

  function centerOn(tx, ty) {
    const { width, height } = renderer.viewport();
    game.camera.x = (tx + 0.5) * TILE - width / 2 / game.camera.zoom;
    game.camera.y = (ty + 0.5) * TILE - height / 2 / game.camera.zoom;
    clampCamera();
  }

  function setFollow(follow) {
    game.camera.follow = follow;
  }

  function centerOnNecromancer() {
    const necromancer = myNecromancer();
    if (necromancer) centerOn(necromancer.x, necromancer.y);
    setFollow(true);
  }

  /** Nudges the camera just enough to keep the necromancer inside the central box. */
  function follow(now) {
    const necromancer = myNecromancer();
    if (!necromancer) return;
    const cam = game.camera;
    const { width, height } = renderer.viewport();
    const p = renderPos(necromancer, now);
    const sx = ((p.x + 0.5) * TILE - cam.x) * cam.zoom;
    const sy = ((p.y + 0.5) * TILE - cam.y) * cam.zoom;
    const insetX = width * FOLLOW_INSET;
    const insetY = height * FOLLOW_INSET;
    let dx = 0;
    let dy = 0;
    if (sx < insetX) dx = sx - insetX;
    else if (sx > width - insetX) dx = sx - (width - insetX);
    if (sy < insetY) dy = sy - insetY;
    else if (sy > height - insetY) dy = sy - (height - insetY);
    if (dx || dy) {
      cam.x += dx / cam.zoom;
      cam.y += dy / cam.zoom;
      clampCamera();
    }
  }

  // ---------------------------------------------------------------- walking

  function walkDirection() {
    let dx = 0;
    let dy = 0;
    for (const [key, [kx, ky]] of Object.entries(WALK_KEYS)) {
      if (held.has(key)) {
        dx += kx;
        dy += ky;
      }
    }
    return [dx, dy];
  }

  /** Whether one step of (dx, dy) from (x, y) is legal, diagonals included, as the server sees it. */
  function canStep(x, y, dx, dy) {
    if (!isWalkable(x + dx, y + dy)) return false;
    return !(dx && dy) || (isWalkable(x + dx, y) && isWalkable(x, y + dy));
  }

  /** Steers the necromancer one or two tiles along the held direction, sliding along walls. */
  function walk() {
    const [dx, dy] = walkDirection();
    const necromancer = myNecromancer();
    if (!dx && !dy) {
      if (walkTarget && necromancer) cmd.halt();
      walkTarget = null;
      return;
    }
    if (!necromancer) return;
    setFollow(true);
    const { x, y } = necromancer;
    const steps = [[dx, dy], [dx, 0], [0, dy]].filter(([sx, sy]) => sx || sy);
    const step = steps.find(([sx, sy]) => canStep(x, y, sx, sy));
    if (!step) return;
    const [sx, sy] = step;
    const far = canStep(x + sx, y + sy, sx, sy);
    const tx = x + sx * (far ? 2 : 1);
    const ty = y + sy * (far ? 2 : 1);
    if (walkTarget && walkTarget.x === tx && walkTarget.y === ty) return;
    walkTarget = { x: tx, y: ty };
    cmd.walkTo(tx, ty);
  }

  // ---------------------------------------------------------------- mouse

  canvas.addEventListener('mousedown', (e) => {
    updatePointer(e);
    const { tx, ty, sx, sy } = game.pointer;
    if (e.button === 0) {
      if (game.mode === 'attack-move') {
        cmd.moveTo(tx, ty, true);
        if (!e.shiftKey) cmd.setMode(null);
        return;
      }
      if (game.mode === 'raise') {
        if (cmd.raiseAt(tx, ty) && !e.shiftKey) cmd.setMode(null);
        return;
      }
      if (game.mode === 'explode') {
        if (cmd.explodeAt(tx, ty) && !e.shiftKey) cmd.setMode(null);
        return;
      }
      game.drag = { sx0: sx, sy0: sy, sx1: sx, sy1: sy, additive: e.shiftKey };
    } else if (e.button === 1) {
      e.preventDefault();
      panning = { sx, sy, x: game.camera.x, y: game.camera.y };
      setFollow(false);
    } else if (e.button === 2) {
      if (game.mode) {
        cmd.setMode(null);
        return;
      }
      if (game.selection.size === 0) return;
      const target = unitAtTile(tx, ty);
      if (target && !isMine(target)) {
        cmd.attack(target);
      } else {
        cmd.moveTo(tx, ty, false);
      }
    }
  });

  window.addEventListener('mousemove', (e) => {
    updatePointer(e);
    if (game.drag) {
      game.drag.sx1 = e.clientX;
      game.drag.sy1 = e.clientY;
    }
    if (panning) {
      game.camera.x = panning.x - (e.clientX - panning.sx) / game.camera.zoom;
      game.camera.y = panning.y - (e.clientY - panning.sy) / game.camera.zoom;
      clampCamera();
    }
  });

  window.addEventListener('mouseup', (e) => {
    if (e.button === 0 && game.drag) finishDrag(performance.now());
    if (e.button === 1) panning = null;
    minimapDragging = false;
  });

  canvas.addEventListener('contextmenu', (e) => e.preventDefault());

  canvas.addEventListener('dblclick', (e) => {
    updatePointer(e);
    const unit = unitAtTile(game.pointer.tx, game.pointer.ty);
    if (!unit || !isMine(unit)) return;
    const { width, height } = renderer.viewport();
    const { x, y, zoom } = game.camera;
    for (const other of game.units.values()) {
      if (!isMine(other) || other.type !== unit.type) continue;
      const sx = ((other.x + 0.5) * TILE - x) * zoom;
      const sy = ((other.y + 0.5) * TILE - y) * zoom;
      if (sx >= 0 && sy >= 0 && sx <= width && sy <= height) game.selection.add(other.id);
    }
  });

  canvas.addEventListener('wheel', (e) => {
    e.preventDefault();
    const cam = game.camera;
    const before = { x: e.clientX / cam.zoom + cam.x, y: e.clientY / cam.zoom + cam.y };
    cam.zoom = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, cam.zoom * Math.exp(-e.deltaY * 0.0015)));
    cam.x = before.x - e.clientX / cam.zoom;
    cam.y = before.y - e.clientY / cam.zoom;
    clampCamera();
  }, { passive: false });

  function finishDrag(now) {
    const { sx0, sy0, sx1, sy1, additive } = game.drag;
    game.drag = null;
    if (!additive) game.selection.clear();

    if (Math.abs(sx1 - sx0) < 5 && Math.abs(sy1 - sy0) < 5) {
      const unit = unitAtTile(game.pointer.tx, game.pointer.ty);
      if (unit && isMine(unit)) {
        if (additive && game.selection.has(unit.id)) game.selection.delete(unit.id);
        else game.selection.add(unit.id);
      }
      return;
    }
    const { x, y, zoom } = game.camera;
    const left = Math.min(sx0, sx1) / zoom + x;
    const right = Math.max(sx0, sx1) / zoom + x;
    const top = Math.min(sy0, sy1) / zoom + y;
    const bottom = Math.max(sy0, sy1) / zoom + y;
    for (const unit of game.units.values()) {
      if (!isMine(unit)) continue;
      const p = renderPos(unit, now);
      const cx = (p.x + 0.5) * TILE;
      const cy = (p.y + 0.5) * TILE;
      if (cx >= left && cx <= right && cy >= top && cy <= bottom) game.selection.add(unit.id);
    }
  }

  // ---------------------------------------------------------------- minimap

  function minimapJump(e) {
    const rect = minimap.getBoundingClientRect();
    const tx = ((e.clientX - rect.left) / rect.width) * game.map.width;
    const ty = ((e.clientY - rect.top) / rect.height) * game.map.height;
    centerOn(tx - 0.5, ty - 0.5);
    setFollow(false);
  }
  minimap.addEventListener('mousedown', (e) => {
    if (!game.map) return;
    if (e.button === 2 && game.selection.size) {
      const rect = minimap.getBoundingClientRect();
      cmd.moveTo(
        Math.floor(((e.clientX - rect.left) / rect.width) * game.map.width),
        Math.floor(((e.clientY - rect.top) / rect.height) * game.map.height),
        false,
      );
      return;
    }
    minimapDragging = true;
    minimapJump(e);
  });
  minimap.addEventListener('mousemove', (e) => { if (minimapDragging) minimapJump(e); });
  minimap.addEventListener('contextmenu', (e) => e.preventDefault());

  // ---------------------------------------------------------------- keyboard

  window.addEventListener('keydown', (e) => {
    if (e.target instanceof HTMLInputElement || !game.map || document.body.classList.contains('reading')) return;
    if (e.ctrlKey || e.altKey || e.metaKey) return;
    const key = e.key.toLowerCase();
    if (key.startsWith('arrow') || Object.hasOwn(WALK_KEYS, key)) {
      held.add(key);
      e.preventDefault();
      return;
    }
    if (e.repeat) return;
    if (key === 'g') {
      const necromancer = myNecromancer();
      if (!cmd.gather(true) || !necromancer) return;
      held.add(key);
      gatheredTo = `${necromancer.x},${necromancer.y}`;
      return;
    }

    const summonType = summonHotkey(key);
    if (summonType) {
      cmd.summon(summonType);
      return;
    }
    switch (key) {
      case 'c': cmd.beginAttackMove(); break;
      case 'r': cmd.beginRaise(); break;
      case 'x': cmd.beginExplode(); break;
      case 'f': cmd.fuse(); break;
      case 'm': cmd.mend(); break;
      case 't': cmd.layTrap(); break;
      case 'h': cmd.stop(); break;
      case 'q': cmd.selectLegion(); break;
      case 'e': cmd.selectNecromancer(); break;
      case ' ': e.preventDefault(); centerOnNecromancer(); break;
      case 'escape':
        if (game.mode) cmd.setMode(null);
        else game.selection.clear();
        break;
      default: break;
    }
  });
  window.addEventListener('keyup', (e) => held.delete(e.key.toLowerCase()));
  window.addEventListener('blur', () => held.clear());

  /** While G is held, calls the legion again each time the necromancer reaches a new tile. */
  function gather() {
    const necromancer = myNecromancer();
    if (!held.has('g') || !necromancer) return;
    const tile = `${necromancer.x},${necromancer.y}`;
    if (tile === gatheredTo) return;
    gatheredTo = tile;
    cmd.gather(false);
  }

  /**
   * Called every frame: walks the necromancer for held WASD, gathers the legion for held G, pans
   * for held arrows, and otherwise keeps the necromancer in view while the camera is following.
   */
  function update(dt, now) {
    if (game.map) {
      walk();
      gather();
    }

    let dx = 0;
    let dy = 0;
    if (held.has('arrowleft')) dx -= 1;
    if (held.has('arrowright')) dx += 1;
    if (held.has('arrowup')) dy -= 1;
    if (held.has('arrowdown')) dy += 1;
    if (dx || dy) {
      const scale = (PAN_SPEED * dt) / Math.hypot(dx, dy) / game.camera.zoom;
      game.camera.x += dx * scale;
      game.camera.y += dy * scale;
      clampCamera();
      setFollow(false);
    } else if (game.camera.follow) {
      follow(now);
    }
    showCameraHint(!game.camera.follow && !!myNecromancer());
  }

  return { update, centerOn, centerOnNecromancer, clampCamera };
}
