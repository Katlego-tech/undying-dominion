import { game, selectedUnits, myNecromancer, label } from './state.js';
import * as cmd from './commands.js';

const $ = (id) => document.getElementById(id);

const MODE_HINTS = {
  'attack-move': 'Attack-move: click where to march. Right-click or Esc cancels.',
  raise: 'Raise the dead: click a corpse inside the dashed square. Shift-click to keep raising.',
};

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
    ['attack-move', 'Attack-move', 'A', cmd.beginAttackMove],
    ['raise', 'Raise dead', 'R', cmd.beginRaise],
    ['fuse', 'Fuse king', 'F', cmd.fuse, `<span class="cost">${game.rules.fuseSoulCost} soul</span>`],
    ['mend', 'Mend', 'M', cmd.mend],
    ['trap', 'Lay trap', 'T', cmd.layTrap],
    ['stop', 'Stop', 'S', cmd.stop],
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
    fuse: warriors >= game.rules.fuseCount && you.soul >= game.rules.fuseSoulCost,
    mend: selected.some((u) => u.modeEndsIn == null && u.hp < game.unitTypes[u.type].maxHp),
    trap: selected.some((u) => game.unitTypes[u.type].laysTraps && u.modeEndsIn == null),
    stop: selected.length > 0,
  };
  for (const el of $('ability-buttons').children) {
    el.disabled = !enabled[el.dataset.ability];
    el.classList.toggle('active', game.mode === el.dataset.ability);
  }

  $('selection').innerHTML = describeSelection(selected);
  updateScoreboard();

  $('fallen').hidden = alive;
  if (!alive) $('respawn-in').textContent = Math.ceil(you.respawnIn / (1000 / game.tickMs));
}

function describeSelection(selected) {
  if (selected.length === 0) {
    return myNecromancer() ? 'Nothing selected · <b>Q</b> selects your legion, <b>E</b> your necromancer' : 'Nothing selected';
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
    li.className = `${p.id === game.playerId ? 'me' : ''} ${p.alive ? '' : 'fallen'}`;
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
    li.append(swatch, who, kd);
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
  hint.textContent = MODE_HINTS[mode] ?? '';
  for (const el of $('ability-buttons').children) {
    el.classList.toggle('active', mode === el.dataset.ability);
  }
}
