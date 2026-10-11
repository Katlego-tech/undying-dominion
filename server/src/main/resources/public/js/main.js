import { game, loadMap, renderPos, myNecromancer, resetWorld } from './state.js';
import { createRenderer, TILE } from './render.js';
import { createInput } from './input.js';
import { initHud, updateHud, notice, showStory, initStory } from './hud.js';

const $ = (id) => document.getElementById(id);

const canvas = $('world');
const minimap = $('minimap');
const renderer = createRenderer(canvas, minimap);
const input = createInput(canvas, minimap, renderer);

let socket = null;
let joined = false;
let followedNecromancer = null;

renderer.resize();
window.addEventListener('resize', () => {
  renderer.resize();
  input.clampCamera();
});

// ---------------------------------------------------------------- joining

$('join-form').addEventListener('submit', (e) => {
  e.preventDefault();
  const name = $('name').value.trim();
  if (!name) return;
  $('join-error').textContent = '';
  $('join-button').disabled = true;
  game.name = name;

  if (socket && socket.readyState === WebSocket.OPEN) {
    socket.send(JSON.stringify({ command: 'join', name }));
    return;
  }
  connect(name);
});

function connect(name) {
  const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
  socket = new WebSocket(`${scheme}://${location.host}/ws`);
  game.send = (message) => {
    if (socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(message));
  };
  socket.addEventListener('open', () => socket.send(JSON.stringify({ command: 'join', name })));
  socket.addEventListener('message', (e) => handle(JSON.parse(e.data)));
  socket.addEventListener('close', (e) => {
    if (joined) {
      $('lost-reason').textContent = e.reason || 'The Dominion is unreachable.';
      $('lost').hidden = false;
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
  game.playerId = message.playerId;
  game.name = message.name;
  game.tickMs = 1000 / message.tickRate;
  game.unitTypes = message.unitTypes;
  game.rules = message.rules;
  loadMap(message.map);
  renderer.buildTerrain();
  // Frame roughly thirty tiles across, so the legion is readable on any screen.
  game.camera.zoom = Math.min(1.8, Math.max(1, window.innerWidth / (TILE * 30)));
  initHud();
  if (!nextChapter) initStory();
  $('join').hidden = true;
}

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

  for (const text of message.you.notices) notice(text);

  // Find the necromancer on joining, and again each time it rises from death somewhere new.
  const necromancer = myNecromancer();
  if (necromancer && necromancer.id !== followedNecromancer) {
    input.centerOn(necromancer.x, necromancer.y);
    followedNecromancer = necromancer.id;
  }
  updateHud();
}

// ---------------------------------------------------------------- frame loop

let last = performance.now();
function loop(now) {
  const dt = Math.min(0.05, (now - last) / 1000);
  last = now;
  input.update(dt);
  renderer.frame(now);
  requestAnimationFrame(loop);
}
requestAnimationFrame(loop);
