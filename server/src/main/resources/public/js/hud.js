import {
  game, selectedUnits, myNecromancer, playsNecromancer, label, aimedAbility, blastOf, explodeStage,
} from './state.js';
import * as cmd from './commands.js';
import {
  SLOTS, SLOT_KEYS, familyOf, statusName, describePassive,
} from './kits.js';
import { openRiseAs, risingAs } from './picker.js';

const $ = (id) => document.getElementById(id);

const MODE_HINTS = {
  'attack-move': 'Attack-move: click where to march. Right-click or Esc cancels.',
  raise: 'Raise the dead: click a corpse inside the dashed square. Shift-click to keep raising.',
  explode: 'Corpse Explosion: click a corpse inside the dashed square. Esc cancels.',
  collect: 'Collect: click a corpse inside the dashed square to carry it. Esc cancels.',
};
const TOUCH_MODE_HINTS = {
  'attack-move': 'Attack-move: tap where to march. Tap here to cancel.',
  raise: 'Raise the dead: tap a corpse inside the dashed square. Tap here to cancel.',
  explode: 'Corpse Explosion: tap a corpse inside the dashed square. Tap here to cancel.',
  collect: 'Collect: tap a corpse inside the dashed square to carry it. Tap here to cancel.',
};
const touch = () => document.body.classList.contains('touch');
/** Whether a mode belongs to a button: Corpse Explosion stays lit through its picker and target steps. */
const inMode = (mode, id) => mode === id || !!mode?.startsWith(`${id}-`);
const phone = () => document.body.classList.contains('phone');
const NEEDS = { TILE: 'a tile', ENEMY: 'an enemy', ALLY: 'an ally', ENEMY_COMMANDER: 'an enemy commander' };

/** The hint while aiming an ability, by what it needs. */
function castHint(ability) {
  const what = NEEDS[ability.target];
  return touch()
    ? `${ability.label}: tap ${what} in the square. Tap here to cancel.`
    : `${ability.label}: click ${what} inside the dashed square. Esc cancels.`;
}

let summonOrder = [];
let lastScoreboard = '';

/** What the commander's retinue lists, by type name; null from a server that sends no commander. */
function retinue() {
  const list = game.commander?.retinue;
  return list ? new Set(list.map((unit) => (typeof unit === 'string' ? unit : unit.name))) : null;
}

/** The commander's summonable unit types, cheapest first. Their hotkeys are 1, 2, 3... in this order. */
function summonable() {
  const answers = retinue();
  return Object.entries(game.unitTypes)
    .filter(([type, t]) => t.summonCost >= 0 && (!answers || answers.has(type)))
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
      `<span class="cost">${game.unitTypes[type].summonCost} ${(game.commander?.energy ?? 'Soul').toLowerCase()}</span>`);
    el.dataset.type = type;
    return el;
  }));

  // Raising, Corpse Explosion and fusing are the necromancer's; nothing else can use them.
  const necromancy = playsNecromancer() ? [
    ['raise', 'Raise dead', 'R', cmd.beginRaise],
    ['explode', 'Corpse Explosion', 'X', cmd.beginExplode, `<span class="cost">${game.rules.explodeSoulCost} soul</span>`],
    ['collect', 'Collect', 'B', cmd.beginCollect],
    ['fuse', 'Fuse king', 'F', cmd.fuse, `<span class="cost">${game.rules.fuseSoulCost} soul</span>`],
  ] : [];
  const abilities = [
    ['attack-move', 'Attack-move', 'C', cmd.beginAttackMove],
    ...necromancy,
    ['mend', 'Mend', 'M', cmd.mend],
    ['trap', 'Lay trap', 'T', cmd.layTrap],
    ['gather', 'Gather', 'G', () => cmd.gather(true)],
    ['stop', 'Stop', 'H', cmd.stop],
    ['dismiss', 'Dismiss', 'Del', cmd.dismiss],
  ];
  $('ability-buttons').replaceChildren(...abilities.map(([id, text, key, action, extra = '']) => {
    const el = button(text, key, action, extra);
    el.dataset.ability = id;
    return el;
  }));

  buildKitBar();

  const necromancer = playsNecromancer();
  document.querySelector('#soul-panel .label').textContent = game.commander?.energy ?? 'Soul';
  document.querySelector('#scores h2').textContent = necromancer ? 'Necromancers' : 'Commanders';
  $('select-necromancer').textContent = necromancer ? 'Necromancer' : 'Commander';

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
  $('legion-raised').textContent = you.raised ? `+${you.raised} raised` : '';

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
    collect: alive && (you.ossuary?.length ?? 0) < (you.ossuaryCap ?? 0),
    fuse: warriors >= game.rules.fuseCount && you.soul >= game.rules.fuseSoulCost,
    mend: selected.some((u) => u.modeEndsIn == null && u.hp < game.unitTypes[u.type].maxHp),
    trap: selected.some((u) => game.unitTypes[u.type].laysTraps && u.modeEndsIn == null),
    gather: alive && you.legion > 0,
    stop: selected.length > 0,
    dismiss: selected.some((u) => u.id !== you.necromancerId),
  };
  for (const el of $('ability-buttons').children) {
    el.disabled = !enabled[el.dataset.ability];
    el.classList.toggle('active', inMode(game.mode, el.dataset.ability));
  }

  $('selection').innerHTML = describeSelection(selected);
  updateScoreboard();
  updateObjective();

  updateKitBar(alive);
  updateStatuses();
  updateOssuary();

  $('fallen').hidden = alive || !!game.paused || risingAs();
  showPause();
  if (!alive) {
    $('respawn-in').textContent = Math.ceil(you.respawnIn / (1000 / game.tickMs));
    updateRising();
  }
}

// ---------------------------------------------------------------- a commander's kit

/** The R / F / X tiles and the passive: in the command panel, or round the thumb's corner on a phone. */
function buildKitBar() {
  const bar = $('kit-bar');
  const kit = game.commander;
  const abilities = playsNecromancer() ? [] : SLOTS.map((slot) => kit.abilities?.find((a) => a.slot === slot)).filter(Boolean);
  bar.hidden = abilities.length === 0;
  if (bar.hidden) return;
  const energy = kit.energy.toLowerCase();
  bar.replaceChildren(...abilities.map((a) => {
    const el = document.createElement('button');
    el.type = 'button';
    el.className = `ability ${a.slot.toLowerCase()}${a.label.length > 12 ? ' long' : ''}`;
    el.dataset.ability = a.id;
    el.innerHTML = `<span class="key">${SLOT_KEYS[a.slot]}</span><span class="slot">${a.slot.toLowerCase()}</span><span class="ability-label"></span>
      <span class="cost">${a.cost} ${energy}${a.range ? `<span class="range"> · ${a.range} tiles</span>` : ''}</span>
      <span class="why"></span><span class="secs"></span>`;
    el.querySelector('.ability-label').textContent = a.label;
    el.addEventListener('click', () => cmd.useAbility(a.slot));
    return el;
  }));
  if (kit.passive) {
    const passive = document.createElement('div');
    passive.className = 'ability passive';
    passive.title = describePassive(kit.passive);
    passive.innerHTML = '<span class="slot">passive</span><span class="ability-label"></span><span class="cost"></span>';
    passive.querySelector('.ability-label').textContent = kit.passive.label;
    passive.querySelector('.cost').textContent = describePassive(kit.passive);
    bar.append(passive);
  }
  // On a phone the abilities are thumb buttons in the bottom-right corner, outside the panel.
  bar.classList.toggle('thumbs', phone());
  if (phone()) $('hud').append(bar);
  else $('command').insertBefore(bar, $('summon-buttons'));
}

/** Each tile: ready, cooling (a shutter the height of the time left), blocked with its reason, or aiming. */
function updateKitBar(alive) {
  const bar = $('kit-bar');
  if (bar.hidden) return;
  const you = game.you;
  const silenced = myNecromancer()?.statuses?.some((s) => s.id === 'silenced');
  const rooted = myNecromancer()?.statuses?.some((s) => s.id === 'rooted');
  const energy = game.commander.energy.toLowerCase();
  for (const el of bar.querySelectorAll('button.ability')) {
    const a = game.commander.abilities.find((x) => x.id === el.dataset.ability);
    const left = you.cooldowns?.[a.id] ?? 0;
    let why = '';
    if (!alive) why = 'Fallen';
    else if (silenced) why = 'Silenced';
    else if (rooted && cmd.dashes(a)) why = 'Rooted';
    else if (left <= 0 && you.soul < a.cost) why = `Needs ${a.cost} ${energy}`;
    const cooling = !why && left > 0;
    el.classList.toggle('cooling', cooling);
    el.classList.toggle('blocked', !!why);
    el.classList.toggle('aiming', game.mode === `cast:${a.id}`);
    el.style.setProperty('--left', cooling ? Math.min(1, left / a.cooldown) : 0);
    el.querySelector('.why').textContent = why;
    el.querySelector('.secs').textContent = cooling ? Math.ceil(left) : '';
    // On a phone the reason takes the cost's place on the round button.
    if (phone()) el.querySelector('.cost').textContent = why || `${a.cost} ${energy}`;
    el.setAttribute('aria-disabled', String(!!why || cooling));
  }
}

/** A chip per status on your own commander: family colour, name and whole seconds. */
function updateStatuses() {
  const statuses = myNecromancer()?.statuses ?? [];
  const box = $('my-statuses');
  const key = statuses.map((s) => `${s.id}${Math.ceil(s.for)}`).join();
  if (box.dataset.key === key) return;
  box.dataset.key = key;
  box.replaceChildren(...statuses.map((s) => {
    const id = s.id.toUpperCase();
    const chip = document.createElement('span');
    chip.className = `status-chip ${familyOf(id)}`;
    chip.append(`${statusName(id)} `);
    const secs = document.createElement('span');
    secs.textContent = `${Math.ceil(s.for)}s`;
    chip.append(secs);
    return chip;
  }));
}

/** While fallen in the free-for-all: who you'll rise as, and the button to pick another. */
function updateRising() {
  const offered = Object.keys(game.kits).length > 0;
  $('rise-as').hidden = !offered;
  const next = game.you.next ? game.kits[game.you.next] : null;
  $('rising').hidden = !next;
  if (next) $('rising').textContent = `You will rise as ${next.name}, ${next.title}`;
  $('rise-as').textContent = next ? 'Pick another' : 'Rise as…';
}

export function initRiseAs() {
  $('rise-as').addEventListener('click', () => {
    openRiseAs(game.commander, Math.ceil(game.you.respawnIn / (1000 / game.tickMs)));
  });
}

function describeSelection(selected) {
  if (selected.length === 0) {
    if (!myNecromancer() || touch()) return 'Nothing selected';
    return `Nothing selected · <b>Q</b> selects your legion, <b>E</b> your ${playsNecromancer() ? 'necromancer' : 'commander'}`;
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
  const aimed = aimedAbility();
  hint.textContent = aimed ? castHint(aimed) : (touch() ? TOUCH_MODE_HINTS : MODE_HINTS)[mode] ?? '';
  hint.classList.toggle('ember', !!mode?.startsWith('explode'));
  hint.classList.remove('has-button');
  if (mode === 'explode-target' || mode === 'explode-drop') explodeHint(hint, mode);
  $('corpse-picker').hidden = !(mode === 'explode' && game.you?.ossuary?.length);
  // Just above the command panel, however tall the kit makes it; touch layouts place it themselves.
  hint.style.bottom = touch() ? '' : `${$('command').offsetHeight + 28}px`;
  for (const el of $('ability-buttons').children) {
    el.classList.toggle('active', inMode(mode, el.dataset.ability));
  }
  for (const el of $('kit-bar').querySelectorAll('button.ability')) {
    el.classList.toggle('aiming', mode === `cast:${el.dataset.ability}`);
  }
}

// ---------------------------------------------------------------- Corpse Explosion and the ossuary

let ossuaryKey = '';

/** The ossuary's count in the soul panel, and the picker's cards, strongest first, when it changes. */
function updateOssuary() {
  const you = game.you;
  $('ossuary-row').hidden = !you.ossuary;
  if (!you.ossuary) return;
  $('ossuary-value').textContent = you.ossuary.length;
  $('ossuary-cap').textContent = you.ossuaryCap;
  const key = `${you.explodeStage}|${you.ossuary.map((c) => c.type).join()}`;
  if (key === ossuaryKey) return;
  ossuaryKey = key;
  const stage = you.explodeStage ?? 'BURST';
  $('corpse-stage').textContent = stage.charAt(0) + stage.slice(1).toLowerCase();
  $('corpse-picker-hint').textContent = `Choose a stored corpse, or ${touch() ? 'tap' : 'click'} one on the field`;
  const order = you.ossuary.map((c, i) => ({ ...c, i })).sort((a, b) => b.blast - a.blast);
  $('corpse-cards').replaceChildren(...order.map((c, rank) => {
    const t = game.unitTypes[c.type] ?? { label: c.type, maxHp: '?' };
    const card = document.createElement('button');
    card.type = 'button';
    card.className = `corpse-card${rank === 0 ? ' strongest' : ''}`;
    card.innerHTML = '<span class="who"></span><span class="lv"></span><span class="blast"><small>dmg</small></span>';
    card.querySelector('.who').textContent = t.label;
    card.querySelector('.lv').textContent = `${t.level ? `Lv ${t.level} · ` : ''}${t.maxHp} hp`;
    card.querySelector('.blast').prepend(String(c.blast));
    card.addEventListener('click', () => cmd.pickStored(c.i));
    return card;
  }));
  if (game.mode === 'explode') $('corpse-picker').hidden = you.ossuary.length === 0;
}

/** The target step's hint: the corpse in hand, what to do, how many are marked, and the way back. */
function explodeHint(hint, mode) {
  const hand = game.explodeHand;
  if (!hand) return;
  const type = hand.stored != null ? game.you.ossuary[hand.stored]?.type : hand.type;
  const blast = blastOf(type);
  const what = `${label(type)} corpse · ${blast}`;
  const tap = touch() ? 'tap' : 'click';
  const back = touch() ? 'tap here to choose another' : 'Esc to choose another';
  if (mode === 'explode-drop') {
    hint.textContent = `${what} · ${tap} a tile inside the dashed square to drop it · ${back}`;
    return;
  }
  const reach = game.rules.explodeTargetRange ?? 10;
  if (explodeStage() === 'DIRECTED') {
    hint.textContent = `${what} · ${tap} an enemy within ${reach} tiles · ${back}`;
    return;
  }
  const max = game.rules.explodeSplitMax ?? 3;
  const marked = hand.marks.length;
  hint.textContent = marked
    ? `${what} · ${marked} of ${max} marked · ${Math.floor(blast / marked)} each${touch() ? '' : ' · X or Enter'}`
    : `${what} · ${tap} up to ${max} enemies within ${reach} tiles · ${back}`;
  if (marked) {
    const go = document.createElement('button');
    go.type = 'button';
    go.className = 'go';
    go.textContent = 'Detonate';
    hint.append(go);
    hint.classList.add('has-button');
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
  const box = document.querySelector('.story-box');
  box.classList.toggle('scrolls', box.scrollHeight > box.clientHeight);
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
  const marked = story.stragglers?.length ? ' · marked on the minimap' : '';
  $('objective-progress').textContent = story.progress + marked;
}
