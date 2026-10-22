// Everything the client knows, in one place. The server is authoritative: this is only ever a
// mirror of the last state it sent, plus what the player is doing with the mouse.

export const TERRAIN = { GROUND: 0, RUINS: 1, SWAMP: 2, ABYSS: 3, FOREST: 4 };

export const WILD_COLOR = '#b4a48c';

export const game = {
  playerId: null,
  name: '',
  tickMs: 100,
  map: null,            // { width, height, terrain: Uint8Array }
  unitTypes: {},
  /** The player's commander kit from the welcome: its retinue is what the summon buttons offer. */
  commander: null,
  /** Every PvP kit, by id: a commander unit's type is its kit's id. Empty in a campaign. */
  kits: {},
  rules: {},

  tick: 0,
  lastStateAt: 0,
  you: null,
  players: [],
  units: new Map(),     // id -> unit, with render interpolation fields
  corpses: [],
  traps: [],

  visible: null,        // Uint8Array, 1 where we see this tick
  explored: null,       // Uint8Array, 1 where we have ever seen
  fogVersion: 0,

  selection: new Set(),
  mode: null,           // null | 'attack-move' | 'raise' | 'explode' | 'cast:<ability id>'
  story: null,          // the campaign's progress, from the latest state
  paused: null,         // { by, reason: 'player' | 'host' | 'story' } while the world is stopped
  effects: [],
  camera: { x: 0, y: 0, zoom: 1, follow: true },  // follow: keep the necromancer in view
  pointer: { sx: 0, sy: 0, tx: -1, ty: -1 },
  drag: null,           // { sx0, sy0, sx1, sy1 } while box-selecting

  send: () => {},
};

/** Builds the terrain grid from the map's obstacles, painting them in order as the server does. */
export function loadMap(map) {
  const terrain = new Uint8Array(map.width * map.height);
  for (const o of map.obstacles) {
    const kind = TERRAIN[o.terrain] ?? TERRAIN.RUINS;
    for (let y = Math.max(0, o.y1); y <= Math.min(map.height - 1, o.y2); y++) {
      for (let x = Math.max(0, o.x1); x <= Math.min(map.width - 1, o.x2); x++) {
        terrain[y * map.width + x] = kind;
      }
    }
  }
  game.map = { width: map.width, height: map.height, terrain };
  game.visible = new Uint8Array(map.width * map.height);
  game.explored = new Uint8Array(map.width * map.height);
}

export function terrainAt(x, y) {
  const { map } = game;
  if (!map || x < 0 || y < 0 || x >= map.width || y >= map.height) return TERRAIN.RUINS;
  return map.terrain[y * map.width + x];
}

/** Whether a unit can stand on this tile. Ruins and the abyss block, as on the server. */
export function isWalkable(x, y) {
  const terrain = terrainAt(x, y);
  return terrain !== TERRAIN.RUINS && terrain !== TERRAIN.ABYSS;
}

export function colorOf(ownerId) {
  if (ownerId === 0) return WILD_COLOR;
  return game.players.find((p) => p.id === ownerId)?.color ?? '#8a8580';
}

export function isMine(unit) {
  return unit.owner === game.playerId;
}

/** Whether the player is the necromancer (always, in a campaign), rather than a PvP commander. */
export function playsNecromancer() {
  return !game.commander || game.commander.role === 'NECROMANCER';
}

/** The kit a commander unit plays, or null for any other unit. */
export function kitOf(unit) {
  return game.kits[unit.type] ?? null;
}

/** The ability the player is aiming, while in a cast mode. */
export function aimedAbility() {
  if (!game.mode?.startsWith('cast:')) return null;
  const id = game.mode.slice(5);
  return game.commander?.abilities?.find((a) => a.id === id) ?? null;
}

/** Whether the aimed ability may be cast on this unit: an ally is one of yours, an enemy anyone else's. */
export function canTarget(ability, unit) {
  if (!unit) return false;
  if (ability.target === 'ALLY') return isMine(unit);
  if (ability.target === 'ENEMY_COMMANDER') return !isMine(unit) && !!kitOf(unit);
  return ability.target === 'ENEMY' && !isMine(unit);
}

export function myNecromancer() {
  const id = game.you?.necromancerId;
  return id == null ? null : game.units.get(id) ?? null;
}

/** Where a unit should be drawn right now: walking smoothly from its last tile to its current one. */
export function renderPos(unit, now) {
  const duration = (game.unitTypes[unit.type]?.moveCooldown ?? 4) * game.tickMs;
  const k = Math.min(1, Math.max(0, (now - unit.t0) / duration));
  return {
    x: unit.fromX + (unit.x - unit.fromX) * k,
    y: unit.fromY + (unit.y - unit.fromY) * k,
  };
}

export function unitAtTile(tx, ty) {
  for (const unit of game.units.values()) {
    if (unit.x === tx && unit.y === ty) return unit;
  }
  return null;
}

/** The corpse on this tile, or failing that the nearest one touching it. */
export function corpseNear(tx, ty) {
  let best = null;
  let bestDistance = 2;
  for (const corpse of game.corpses) {
    const d = Math.max(Math.abs(corpse.x - tx), Math.abs(corpse.y - ty));
    if (d < bestDistance) {
      best = corpse;
      bestDistance = d;
    }
  }
  return best;
}

export function selectedUnits() {
  const units = [];
  for (const id of game.selection) {
    const unit = game.units.get(id);
    if (unit && isMine(unit)) units.push(unit);
  }
  return units;
}

export function label(type) {
  return game.unitTypes[type]?.label ?? type;
}

/** The name with its level, as the story's monsters are shown: "Lv 14 Cave Spider". */
export function levelledLabel(type) {
  const t = game.unitTypes[type];
  return t?.level ? `Lv ${t.level} ${t.label}` : label(type);
}

/** Forgets everything about the previous map, when a campaign moves on to its next chapter. */
export function resetWorld() {
  game.units = new Map();
  game.corpses = [];
  game.traps = [];
  game.effects = [];
  game.selection.clear();
  game.mode = null;
}

// ---------------------------------------------------------------- landscape only

// On a phone the game is landscape from load, and never asks to be turned (docs/design/characters.md
// §6 Client). Held upright, the page draws itself a quarter turned clockwise (body.turned in
// style.css), so the renderer and input read the turned size and map every touch through toLayout().
const coarse = window.matchMedia('(pointer: coarse)').matches;
/** Inside the Android app (docs/design/android.md): the activity holds landscape, so the page never turns. */
export const inApp = new URLSearchParams(location.search).has('app');
/** Where the app's "Return" goes: its own finder page. */
export const APP_FINDER = 'https://appassets.androidplatform.net/assets/finder/index.html';
// The part of the screen actually on show: a phone's address bar takes some of 100vh, so the page is sized from this.
const glass = () => ({ w: window.innerWidth, h: window.innerHeight });

/** A phone held upright: tablets are never turned. */
export function isTurned() {
  const { w, h } = glass();
  return !inApp && coarse && h > w && w <= 500;
}

/** The page's size as it's laid out: the screen's, or its height × width while turned. */
export function viewport() {
  const { w, h } = glass();
  return isTurned() ? { width: h, height: w } : { width: w, height: h };
}

/** A point on the glass to a point on the page: x' = y, y' = W − x while turned. */
export function toLayout(x, y) {
  return isTurned() ? { x: y, y: glass().w - x } : { x, y };
}

/** An element's box on the page, from its box on the glass. */
export function layoutRect(el) {
  const r = el.getBoundingClientRect();
  if (!isTurned()) return { left: r.left, top: r.top, width: r.width, height: r.height };
  return { left: r.top, top: glass().w - r.right, width: r.height, height: r.width };
}

/** Marks a phone (a touch screen at most 500 px on its short side) and whether it's held upright. */
export function applyOrientation() {
  const { w, h } = glass();
  document.body.classList.toggle('phone', coarse && Math.min(w, h) <= 500);
  document.body.classList.toggle('turned', isTurned());
  document.body.classList.toggle('app', inApp);
  const page = viewport();
  const root = document.documentElement.style;
  root.setProperty('--glass-w', `${w}px`);
  root.setProperty('--glass-h', `${h}px`);
  root.setProperty('--page-w', `${page.width}px`);
  root.setProperty('--page-h', `${page.height}px`);
}

/** On a phone's first tap, where the browser allows it: fullscreen, locked to landscape. iOS can't, and the turn covers it. */
export function lockLandscape() {
  if (inApp || !document.body.classList.contains('phone')) return;
  const lock = () => screen.orientation?.lock?.('landscape').catch(() => {});
  const full = document.documentElement.requestFullscreen?.({ navigationUI: 'hide' });
  if (full) full.then(lock, () => {});
  else lock();
}
