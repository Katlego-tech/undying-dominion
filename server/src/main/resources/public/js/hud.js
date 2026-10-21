import { game, selectedUnits, myNecromancer, label } from './state.js';
import * as cmd from './commands.js';

const $ = (id) => document.getElementById(id);

const MODE_HINTS = {
  'attack-move': 'Attack-move: click where to march. Right-click or Esc cancels.',
  raise: 'Raise the dead: click a corpse inside the dashed square. Shift-click to keep raising.',
  explode: 'Corpse Explosion: click a corpse inside the dashed square. Everything around it takes the blast.',
};
const TOUCH_MODE_HINTS = {
  'attack-move': 'Attack-move: tap where to march. Tap here to cancel.',
  raise: 'Raise the dead: tap a corpse inside the dashed square. Tap here to cancel.',
  explode: 'Corpse Explosion: tap a corpse inside the dashed square. Tap here to cancel.',
};
const touch = () => document.body.classList.contains('touch');

let summonOrder = [];
let lastScoreboard = '';

/** Summonable unit types, cheapest first. Their hotkeys are 1, 2, 3... in this order. */
function summonable() {
  return Object.entries(game.unitTypes)
    .filter(([, t]) => t.summonCost >= 0)
    .sort((a, b) => a[1].summonCost - b[1].summonCost)
    .map(([type]) => type);
}

export function summonHotkey(key) {
  const index = Number.parseInt(key, 10) - 1;
  return Number.isInteger(index) && index >= 0 ? summonOrder[index] ?? null : null;
}

function button(label, key, onClick, extra = '') {
  const el = document.createElement('button');
  el.type = 'button';
  el.className = 'cmd';
  el.innerHTML = `<span class="key">${key}</span><span>${label}</span>${extra}`;
  el.addEventListener('click', onClick);
  return el;
}

export function initHud() {
  summonOrder = summonable();
  const summons = $('summon-buttons');
  summons.replaceChildren(...summonOrder.map((type, i) => {
    const el = button(label(type), i + 1, () => cmd.summon(type),
      `<span class="cost">${game.unitTypes[type].summonCost} soul</span>`);
    el.dataset.type = type;
    return el;
  }));

  const abilities = [
    ['attack-move', 'Attack-move', 'C', cmd.beginAttackMove],
    ['raise', 'Raise dead', 'R', cmd.beginRaise],
    ['explode', 'Corpse Explosion', 'X', cmd.beginExplode, `<span class="cost">${game.rules.explodeSoulCost} soul</span>`],
    ['fuse', 'Fuse king', 'F', cmd.fuse, `<span class="cost">${game.rules.fuseSoulCost} soul</span>`],
    ['mend', 'Mend', 'M', cmd.mend],
    ['trap', 'Lay trap', 'T', cmd.layTrap],
    ['gather', 'Gather', 'G', () => cmd.gather(true)],
    ['stop', 'Stop', 'H', cmd.stop],
  ];
  $('ability-buttons').replaceChildren(...abilities.map(([id, text, key, action, extra = '']) => {
    const el = button(text, key, action, extra);
    el.dataset.ability = id;
    return el;
  }));

  $('hud').hidden = false;
}

export function updateHud() {
  const you = game.you;
  if (!you) return;
  const me = game.players.find((p) => p.id === game.playerId);

  $('my-name').textContent = game.name;
  $('my-color').style.color = me?.color ?? '#fff';
  $('my-color').style.background = me?.color ?? '#fff';
  $('my-level').textContent = you.level;
  const previousLevelXp = 50 * (you.level - 1) * you.level;
  const span = Math.max(1, you.xpNext - previousLevelXp);
  $('xp-fill').style.width = `${Math.min(100, ((you.xp - previousLevelXp) / span) * 100)}%`;
  $('xp-text').textContent = you.level >= 10 ? `${you.xp} xp · max level` : `${you.xp} / ${you.xpNext} xp`;

  $('soul-value').textContent = you.soul;
  $('soul-max').textContent = `/${you.soulMax}`;
  $('soul-fill').style.width = `${(you.soul / you.soulMax) * 100}%`;
  $('legion-value').textContent = you.legion;
  $('legion-cap').textContent = you.legionCap;

  const alive = you.necromancerId != null;
  const legionFull = you.legion >= you.legionCap;
  for (const el of $('summon-buttons').children) {
    const cost = game.unitTypes[el.dataset.type].summonCost;
    el.disabled = !alive || legionFull || you.soul < cost;
  }

  const selected = selectedUnits();
  const warriors = selected.filter((u) => u.type === 'SKELETON_WARRIOR').length;
  const enabled = {
    'attack-move': selected.length > 0,
    raise: alive,
    explode: alive && you.soul >= game.rules.explodeSoulCost,
    fuse: warriors >= game.rules.fuseCount && you.soul >= game.rules.fuseSoulCost,
    mend: selected.some((u) => u.modeEndsIn == null && u.hp < game.unitTypes[u.type].maxHp),
    trap: selected.some((u) => game.unitTypes[u.type].laysTraps && u.modeEndsIn == null),
    gather: alive && you.legion > 0,
    stop: selected.length > 0,
  };
  for (const el of $('ability-buttons').children) {
    el.disabled = !enabled[el.dataset.ability];
    el.classList.toggle('active', game.mode === el.dataset.ability);
  }

  $('selection').innerHTML = describeSelection(selected);
  updateScoreboard();
  updateObjective();

  $('fallen').hidden = alive || !!game.paused;
  showPause();
  if (!alive) $('respawn-in').textContent = Math.ceil(you.respawnIn / (1000 / game.tickMs));
}

function describeSelection(selected) {
  if (selected.length === 0) {
    if (!myNecromancer() || touch()) return 'Nothing selected';
    return 'Nothing selected · <b>Q</b> selects your legion, <b>E</b> your necromancer';
  }
  if (selected.length === 1) {
    const u = selected[0];
    const max = game.unitTypes[u.type].maxHp;
    return `<b>${u.raised ? 'Raised ' : ''}${label(u.type)}</b> · ${u.hp}/${max} hp · ${u.order.toLowerCase().replace('_', '-')}`;
  }
  const counts = new Map();
  for (const u of selected) counts.set(u.type, (counts.get(u.type) ?? 0) + 1);
  return [...counts].map(([type, n]) => `<b>${n}</b> ${label(type)}`).join(' · ');
}

function updateScoreboard() {
  const rows = [...game.players].sort((a, b) => b.kills - a.kills || a.deaths - b.deaths);
  const key = JSON.stringify(rows);
  if (key === lastScoreboard) return;
  lastScoreboard = key;
  $('score-list').replaceChildren(...rows.map((p) => {
    const li = document.createElement('li');
    li.className = `${p.id === game.playerId ? 'me' : ''} ${p.alive ? '' : 'fallen'} ${p.away ? 'away' : ''}`;
    const swatch = document.createElement('span');
    swatch.className = 'swatch';
    swatch.style.background = p.color;
    swatch.style.color = p.color;
    const who = document.createElement('span');
    who.className = 'who';
    who.textContent = `${p.name} · ${p.level}`;
    const kd = document.createElement('span');
    kd.className = 'kd';
    kd.textContent = `${p.kills} / ${p.deaths}`;
    li.append(swatch, who);
    if (p.away) {
      const away = document.createElement('span');
      away.className = 'away-tag';
      away.textContent = 'away';
      away.title = 'Connection lost. Their place is kept for a while.';
      li.append(away);
    }
    li.append(kd);
    return li;
  }));
}

export function notice(text) {
  const box = $('notices');
  const el = document.createElement('div');
  el.className = 'notice';
  el.textContent = text;
  box.append(el);
  while (box.children.length > 5) box.firstElementChild.remove();
  setTimeout(() => el.classList.add('fade'), 4500);
  setTimeout(() => el.remove(), 5200);
}

export function showModeHint(mode) {
  const hint = $('mode-hint');
  hint.hidden = !mode;
  hint.textContent = (touch() ? TOUCH_MODE_HINTS : MODE_HINTS)[mode] ?? '';
  for (const el of $('ability-buttons').children) {
    el.classList.toggle('active', mode === el.dataset.ability);
  }
}

/** Shows the free-look pill while the camera has stopped following the necromancer. */
export function showCameraHint(freeLook) {
  $('camera-hint').hidden = !freeLook;
}

// ---------------------------------------------------------------- pause

/** Shows the pause screen when a player or the host stopped the world. A story pause has its own card. */
function showPause() {
  const paused = game.paused;
  const shown = !!paused && paused.reason !== 'story';
  $('paused').hidden = !shown;
  document.body.classList.toggle('paused', shown);
  // The server only lets a lone player in a campaign save, so the form only shows then.
  $('save-form').hidden = !shown || !game.story || game.players.length !== 1;
  if (!shown) return;
  $('paused-by').textContent = paused.reason === 'host'
    ? 'The keeper of the Dominion has stopped the world.'
    : game.players.length === 1 ? 'The world waits for you.'
      : `${paused.by === game.name ? 'You' : paused.by} stopped the world for everyone.`;
}

export function initPause() {
  $('pause-button').addEventListener('click', cmd.togglePause);
  $('resume-button').addEventListener('click', cmd.togglePause);
  $('save-form').addEventListener('submit', (e) => {
    e.preventDefault();
    $('save-replace').hidden = true;
    cmd.saveGame($('save-name').value, false);
  });
  $('save-replace').addEventListener('click', () => {
    $('save-replace').hidden = true;
    cmd.saveGame($('save-name').value, true);
  });
}

/** The server's replies to a save, word for word (docs/design/persistence.md §6). */
const SAVE_REPLIES = ['Saved "', 'A save called "', 'Saving only works', 'Save names are', 'The save could not'];

/** Shows a save reply under the form too, since the pause screen covers the notices. */
export function saveReply(text) {
  if (!SAVE_REPLIES.some((start) => text.startsWith(start))) return;
  const taken = text.startsWith('A save called "');
  const status = $('save-status');
  // The Replace button is the way to force it, so only the first sentence is needed here.
  status.textContent = taken ? text.slice(0, text.indexOf('. ') + 1) : text;
  status.classList.toggle('refused', !text.startsWith('Saved "'));
  $('save-replace').hidden = !taken;
}

// ---------------------------------------------------------------- the story

const STORY_KICKERS = {
  briefing: (m) => `${m.campaign} · Chapter ${m.chapter} of ${m.chapters}`,
  victory: (m) => `Chapter ${m.chapter} complete · the story continues in a moment`,
  finale: () => 'The end of the arc',
  defeat: (m) => `Chapter ${m.chapter} · failed`,
};

let sayTimer = null;

/** Shows a story message from the server: a full card, or for "say" a banner over the battlefield. */
export function showStory(message) {
  if (message.kind === 'say') {
    const box = $('say');
    box.textContent = message.lines.join(' ');
    box.hidden = false;
    clearTimeout(sayTimer);
    sayTimer = setTimeout(() => { box.hidden = true; }, 5500 + message.lines.join(' ').length * 35);
    return;
  }
  $('story-kicker').textContent = (STORY_KICKERS[message.kind] ?? (() => ''))(message);
  $('story-title').textContent = message.kind === 'finale' ? message.campaign : message.title;
  $('story-source').textContent = message.source;
  $('story-lines').replaceChildren(...message.lines.map((line) => {
    const p = document.createElement('p');
    p.textContent = line;
    return p;
  }));
  $('story-objective').textContent = message.kind === 'briefing' ? `Objective: ${message.objective}` : '';
  $('story-close').textContent = message.kind === 'briefing' ? 'Begin' : 'Continue';
  $('story').hidden = false;
  document.body.classList.add('reading');
  game.send({ command: 'reading', open: true });
}

export function initStory() {
  const close = () => {
    $('story').hidden = true;
    document.body.classList.remove('reading');
    game.send({ command: 'reading', open: false });
  };
  $('story-close').addEventListener('click', close);
  window.addEventListener('keydown', (e) => {
    if (!$('story').hidden && (e.key === 'Enter' || e.key === 'Escape' || e.key === ' ')) {
      e.preventDefault();
      close();
    }
  });
}

function updateObjective() {
  const story = game.story;
  $('objective').hidden = !story;
  if (!story) return;
  $('objective-chapter').textContent = `Chapter ${story.chapter}/${story.chapters} · ${story.title}`;
  $('objective-text').textContent = story.outcome === 'WON' ? 'Chapter complete' : story.objective;
  $('objective-progress').textContent = story.progress;
}
