import {
  game, loadMap, renderPos, myNecromancer, resetWorld, applyOrientation, lockLandscape, viewport, inApp, APP_FINDER,
} from './state.js';
import { createRenderer, TILE } from './render.js';
import { createInput } from './input.js';
import {
  initHud, updateHud, notice, saveReply, showStory, initStory, initPause, initRiseAs,
} from './hud.js';
import {
  initPicker, showPicker, chosenKit, closeRiseAs, risingAs,
} from './picker.js';
import { pick, setMode } from './commands.js';

const $ = (id) => document.getElementById(id);

const canvas = $('world');
const minimap = $('minimap');
const renderer = createRenderer(canvas, minimap);
const input = createInput(canvas, minimap, renderer);

let socket = null;
let joined = false;
let rejoining = null;
let followedNecromancer = null;

// The seat's token, kept per tab so a refresh takes the same necromancer back (docs/design/reconnect.md).
const SESSION_KEY = 'undying-dominion.session';

function storedSession() {
  try {
    const session = JSON.parse(sessionStorage.getItem(SESSION_KEY));
    return session && typeof session.token === 'string' && typeof session.name === 'string' ? session : null;
  } catch {
    return null;
  }
}

function storeSession(token, name) {
  try {
    sessionStorage.setItem(SESSION_KEY, JSON.stringify({ token, name }));
  } catch {
    // Storage blocked: the game plays on, and a refresh shows the join screen.
  }
}

function forgetSession() {
  try {
    sessionStorage.removeItem(SESSION_KEY);
  } catch {
    // Nothing was stored.
  }
}

applyOrientation();
renderer.resize();
window.addEventListener('resize', () => {
  applyOrientation();
  renderer.resize();
  input.clampCamera();
});

// ---------------------------------------------------------------- joining

// The free-for-all's join form shows the kits before there's a socket; a campaign's stays a name box.
fetch('/roster')
  .then((response) => (response.ok ? response.json() : { roster: [] }))
  .then(({ roster, unitTypes }) => {
    if (initPicker(roster, unitTypes) && !rejoining && !joined) showPicker();
  })
  .catch(() => {});

$('join-form').addEventListener('submit', (e) => {
  e.preventDefault();
  if (risingAs()) {
    pick(chosenKit());
    closeRiseAs();
    return;
  }
  const name = $('name').value.trim();
  // Disabled while a join is on its way, so a second press can't open a second seat.
  if (!name || $('join-button').disabled) return;
  $('join-error').textContent = '';
  $('join-button').disabled = true;
  game.name = name;
  lockLandscape();

  const commander = chosenKit();
  const join = commander ? { command: 'join', name, commander } : { command: 'join', name };
  if (socket && socket.readyState === WebSocket.OPEN) {
    socket.send(JSON.stringify(join));
    return;
  }
  connect(join);
});

window.addEventListener('keydown', (e) => {
  if (e.key === 'Escape' && risingAs()) closeRiseAs();
});

// After a severed connection: the browser reloads; the app goes back to its finder.
$('return-button').addEventListener('click', () => {
  if (inApp) location.href = APP_FINDER;
  else location.reload();
});

/** The app's Back button: closes the rise-as picker or an aiming mode, as Esc would. Returns whether it did. */
window.appBack = () => {
  if (risingAs()) {
    closeRiseAs();
    return true;
  }
  if (game.mode) {
    setMode(null);
    return true;
  }
  return false;
};

function rejoin(session) {
  rejoining = session;
  $('rejoin-name').textContent = session.name;
  $('join-form').classList.add('rejoining');
  connect({ command: 'rejoin', token: session.token });
}

/** Back to the form after a rejoin that didn't take, with the name filled in and why it failed. */
function stopRejoining(reason) {
  $('join-form').classList.remove('rejoining');
  $('name').value = rejoining.name;
  rejoining = null;
  $('join-error').textContent = reason;
  $('join-button').disabled = false;
  $('join-button').focus();
  showPicker();
}

function connect(first) {
  const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
  socket = new WebSocket(`${scheme}://${location.host}/ws`);
  game.send = (message) => {
    if (socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(message));
  };
  socket.addEventListener('open', () => socket.send(JSON.stringify(first)));
  socket.addEventListener('message', (e) => handle(JSON.parse(e.data)));
  socket.addEventListener('close', (e) => {
    if (joined) {
      $('lost-reason').textContent = e.reason || 'The Dominion is unreachable.';
      $('lost').hidden = false;
    } else if (rejoining) {
      // The session is kept, so a later refresh tries again.
      stopRejoining('Could not reach the Dominion. Is the server running?');
    } else {
      $('join-error').textContent = 'Could not reach the Dominion. Is the server running?';
      $('join-button').disabled = false;
    }
  });
}

function handle(message) {
  switch (message.type) {
    case 'welcome': return welcome(message);
    case 'story': return showStory(message);
    case 'state': return applyState(message, performance.now());
    case 'error':
      if (joined) {
        notice(message.message);
      } else if (rejoining) {
        forgetSession();
        stopRejoining(message.message);
      } else {
        $('join-error').textContent = message.message;
        $('join-button').disabled = false;
      }
      return undefined;
    default: return undefined;
  }
}

function welcome(message) {
  // A second welcome means the campaign has moved on to a new chapter and a new map.
  const nextChapter = joined;
  if (nextChapter) {
    resetWorld();
    followedNecromancer = null;
  }
  joined = true;
  rejoining = null;
  storeSession(message.token, message.name);
  game.playerId = message.playerId;
  game.name = message.name;
  game.tickMs = 1000 / message.tickRate;
  game.unitTypes = message.unitTypes;
  game.commander = message.commander ?? null;
  game.kits = Object.fromEntries((message.roster ?? []).map((kit) => [kit.id, kit]));
  initPicker(message.roster, message.unitTypes);
  game.rules = message.rules;
  loadMap(message.map);
  renderer.buildTerrain();
  // Frame roughly thirty tiles across, so the legion is readable on any screen.
  game.camera.zoom = Math.min(1.8, Math.max(1, viewport().width / (TILE * 30)));
  initHud();
  if (!nextChapter) {
    initStory();
    initPause();
    initRiseAs();
  }
  $('join').hidden = true;
}

const session = storedSession();
if (session) rejoin(session);

// ---------------------------------------------------------------- world state

const EFFECTS = {
  DEATH: ['death', 650],
  SUMMON: ['rise', 800],
  RAISE: ['rise', 900],
  FUSE: ['fuse', 1000],
  MEND: ['mend', 700],
  TRAP_SET: ['trap-set', 500],
  TRAP_SPRUNG: ['spikes', 600],
  EXPLOSION: ['explosion', 700],
};

function applyState(message, now) {
  const before = game.units;
  game.tick = message.tick;
  game.lastStateAt = now;
  game.you = message.you;
  game.players = message.players;
  game.story = message.story ?? null;
  game.paused = message.paused ?? null;

  const units = new Map();
  for (const u of message.units) {
    const known = before.get(u.id);
    if (known) {
      if (known.x !== u.x || known.y !== u.y) {
        const p = renderPos(known, now);
        known.fromX = p.x;
        known.fromY = p.y;
        known.t0 = now;
      }
      Object.assign(known, u, { raised: !!u.raised, modeEndsIn: u.modeEndsIn ?? null });
      units.set(u.id, known);
    } else {
      units.set(u.id, { ...u, raised: !!u.raised, modeEndsIn: u.modeEndsIn ?? null, fromX: u.x, fromY: u.y, t0: now });
    }
  }
  game.units = units;
  for (const id of [...game.selection]) {
    if (!units.has(id)) game.selection.delete(id);
  }
  game.corpses = message.corpses;
  game.traps = message.traps;

  const bits = atob(message.fog);
  const n = game.map.width * game.map.height;
  for (let i = 0; i < n; i++) {
    const seen = (bits.charCodeAt(i >> 3) >> (i & 7)) & 1;
    game.visible[i] = seen;
    if (seen) game.explored[i] = 1;
  }
  game.fogVersion++;

  for (const e of message.events) {
    if (e.kind === 'ATTACK') {
      const attacker = units.get(e.a) ?? before.get(e.a);
      const distance = Math.max(Math.abs(e.x2 - e.x), Math.abs(e.y2 - e.y));
      game.effects.push({
        kind: 'attack', x: e.x, y: e.y, x2: e.x2, y2: e.y2, attackerType: attacker?.type,
        t0: now, duration: distance <= 1 ? 260 : 140 + distance * 45,
      });
    } else if (e.kind === 'CAST') {
      // A short flash at the caster and a ring of the ability's radius where it lands (T085 makes them more).
      game.effects.push({ kind: 'cast-flash', x: e.x, y: e.y, t0: now, duration: 400 });
      if (e.x2 !== e.x || e.y2 !== e.y || e.radius) {
        game.effects.push({ kind: 'cast-ring', x: e.x2, y: e.y2, radius: e.radius ?? 0, t0: now, duration: 600 });
      }
    } else if (e.kind === 'LEVEL_UP') {
      const necromancer = myNecromancer();
      if (e.a === game.playerId && necromancer) {
        game.effects.push({ kind: 'level', x: necromancer.x, y: necromancer.y, t0: now, duration: 1200 });
      }
    } else if (EFFECTS[e.kind]) {
      const [kind, duration] = EFFECTS[e.kind];
      game.effects.push({ kind, x: e.x, y: e.y, radius: e.b, t0: now, duration });
    }
  }
  if (game.effects.length > 400) game.effects.splice(0, game.effects.length - 400);

  for (const text of message.you.notices) {
    notice(text);
    saveReply(text);
  }

  // Risen as another commander: the kit bar and summons follow the new kit.
  const risenAs = game.kits[message.you.commander];
  if (risenAs && risenAs.id !== game.commander?.id) {
    game.commander = risenAs;
    if (game.mode?.startsWith('cast:')) setMode(null);
    initHud();
  }

  // Find the necromancer on joining, and again each time it rises from death somewhere new.
  const necromancer = myNecromancer();
  if (necromancer && necromancer.id !== followedNecromancer) {
    input.centerOn(necromancer.x, necromancer.y);
    game.camera.follow = true;
    followedNecromancer = necromancer.id;
  }
  updateHud();
}

// ---------------------------------------------------------------- frame loop

let last = performance.now();
function loop(now) {
  const dt = Math.min(0.05, (now - last) / 1000);
  last = now;
  input.update(dt, now);
  renderer.frame(now);
  requestAnimationFrame(loop);
}
requestAnimationFrame(loop);
