import { game, unitAtTile, isMine, myNecromancer, renderPos } from './state.js';
import { TILE } from './render.js';
import * as cmd from './commands.js';
import { summonHotkey } from './hud.js';

const PAN_SPEED = 900;     // screen pixels per second
const EDGE = 10;           // pixels from the window edge that start an edge pan
const MIN_ZOOM = 0.55;
const MAX_ZOOM = 2.2;

export function createInput(canvas, minimap, renderer) {
  const held = new Set();
  let panning = null;
  let minimapDragging = false;

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

  function centerOnNecromancer() {
    const necromancer = myNecromancer();
    if (necromancer) centerOn(necromancer.x, necromancer.y);
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
    game.pointer.inside = e.target === canvas;
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

  document.addEventListener('mouseleave', () => { game.pointer.inside = false; });
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
    const key = e.key.toLowerCase();
    if (key.startsWith('arrow')) {
      held.add(key);
      e.preventDefault();
      return;
    }
    if (e.repeat) return;

    const summonType = summonHotkey(key);
    if (summonType) {
      cmd.summon(summonType);
      return;
    }
    switch (key) {
      case 'a': cmd.beginAttackMove(); break;
      case 'r': cmd.beginRaise(); break;
      case 'x': cmd.beginExplode(); break;
      case 'f': cmd.fuse(); break;
      case 'm': cmd.mend(); break;
      case 't': cmd.layTrap(); break;
      case 's': cmd.stop(); break;
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

  /** Called every frame: pans the camera for held arrow keys and the window edges. */
  function update(dt) {
    let dx = 0;
    let dy = 0;
    if (held.has('arrowleft')) dx -= 1;
    if (held.has('arrowright')) dx += 1;
    if (held.has('arrowup')) dy -= 1;
    if (held.has('arrowdown')) dy += 1;

    const { sx, sy, inside } = game.pointer;
    const { width, height } = renderer.viewport();
    if (inside && !game.drag && document.hasFocus()) {
      if (sx < EDGE) dx -= 1;
      if (sx > width - EDGE) dx += 1;
      if (sy < EDGE) dy -= 1;
      if (sy > height - EDGE) dy += 1;
    }
    if (dx || dy) {
      game.camera.x += (dx * PAN_SPEED * dt) / game.camera.zoom;
      game.camera.y += (dy * PAN_SPEED * dt) / game.camera.zoom;
      clampCamera();
    }
  }

  return { update, centerOn, centerOnNecromancer, clampCamera };
}
