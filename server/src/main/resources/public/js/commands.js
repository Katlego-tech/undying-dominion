// Every order the player can give, used by the keyboard, the mouse and the command bar alike.

import {
  game, selectedUnits, myNecromancer, corpseNear, isMine, unitAtTile, canTarget, aimedAbility,
} from './state.js';
import { notice, showModeHint } from './hud.js';
import { needsTarget } from './kits.js';

function selectedIds() {
  return selectedUnits().map((u) => u.id);
}

function marker(x, y, hostile) {
  game.effects.push({ kind: 'marker', x, y, hostile, t0: performance.now(), duration: 450 });
}

export function setMode(mode) {
  game.mode = mode;
  showModeHint(mode);
}

export function summon(type) {
  game.send({ command: 'summon', unit: type });
}

export function moveTo(x, y, attackMove) {
  const units = selectedIds();
  if (units.length === 0) return;
  game.send({ command: 'move', units, x, y, attackMove });
  marker(x, y, attackMove);
}

/** Steers the necromancer toward a nearby tile without touching the selection or drawing a marker. */
export function walkTo(x, y) {
  const necromancer = myNecromancer();
  if (necromancer) game.send({ command: 'move', units: [necromancer.id], x, y, attackMove: false });
}

export function halt() {
  const necromancer = myNecromancer();
  if (necromancer) game.send({ command: 'stop', units: [necromancer.id] });
}

export function attack(target) {
  const units = selectedIds();
  if (units.length === 0) return;
  game.send({ command: 'attack', units, target: target.id });
  marker(target.x, target.y, true);
}

export function stop() {
  const units = selectedIds();
  if (units.length) game.send({ command: 'stop', units });
}

/**
 * Marches the selected legion members to the necromancer, or the whole legion when none are
 * selected. Returns whether anyone was sent.
 */
export function gather(showMarker) {
  const necromancer = myNecromancer();
  if (!necromancer) {
    if (showMarker) notice('There is no necromancer to gather to.');
    return false;
  }
  let units = selectedUnits().filter((u) => u.id !== necromancer.id);
  if (units.length === 0) {
    units = [...game.units.values()].filter((u) => isMine(u) && u.id !== necromancer.id);
  }
  if (units.length === 0) {
    if (showMarker) notice('You have no legion to gather.');
    return false;
  }
  game.send({ command: 'move', units: units.map((u) => u.id), x: necromancer.x, y: necromancer.y, attackMove: false });
  if (showMarker) marker(necromancer.x, necromancer.y, false);
  return true;
}

/** Pauses the world for everyone, or resumes it if a player or the host paused it. */
export function togglePause() {
  if (!game.paused) game.send({ command: 'pause' });
  else if (game.paused.reason !== 'story') game.send({ command: 'resume' });
}

/** Saves a solo campaign under a name; with force, replaces a save of that name. */
export function saveGame(name, force) {
  game.send({ command: 'save', name, force });
}

export function fuse() {
  const units = selectedUnits().filter((u) => u.type === 'SKELETON_WARRIOR').map((u) => u.id);
  if (units.length < game.rules.fuseCount) {
    notice(`Select at least ${game.rules.fuseCount} Skeleton Warriors to fuse.`);
    return;
  }
  game.send({ command: 'fuse', units });
}

const DISMISS_CONFIRM_MS = 3000;
let dismissArmed = null;  // {ids, until}: the first press, waiting for the second

/**
 * Lets the selected legion members go, freeing their slots. The first press asks, and a second within
 * three seconds, on the same selection, does it. The necromancer is never in the list.
 */
export function dismiss() {
  const necromancer = myNecromancer();
  const units = selectedUnits().filter((u) => u.id !== necromancer?.id).map((u) => u.id);
  if (units.length === 0) {
    notice('Select the legion members to dismiss.');
    return;
  }
  const key = units.slice().sort((a, b) => a - b).join(',');
  const now = performance.now();
  if (dismissArmed && dismissArmed.key === key && now < dismissArmed.until) {
    dismissArmed = null;
    game.send({ command: 'dismiss', units });
    for (const id of units) game.selection.delete(id);
    return;
  }
  dismissArmed = { key, until: now + DISMISS_CONFIRM_MS };
  const again = document.body.classList.contains('touch') ? 'Tap Dismiss again' : 'Press Delete again';
  notice(`${again} to dismiss ${units.length === 1 ? 'it' : `these ${units.length}`}. They crumble for good.`);
}

export function mend() {
  const units = selectedIds();
  if (units.length) game.send({ command: 'mend', units });
}

export function layTrap() {
  const units = selectedUnits().filter((u) => game.unitTypes[u.type]?.laysTraps).map((u) => u.id);
  if (units.length === 0) {
    notice('Select a Grave Warden to lay a trap.');
    return;
  }
  game.send({ command: 'trap', units });
}

/** Raises the corpse on or next to this tile. Returns whether there was one to raise. */
export function raiseAt(x, y) {
  const corpse = corpseNear(x, y);
  if (!corpse) {
    notice('There is no corpse there.');
    return false;
  }
  game.send({ command: 'raise', corpse: corpse.id });
  return true;
}

/** Detonates the corpse on or next to this tile. Returns whether there was one. */
export function explodeAt(x, y) {
  const corpse = corpseNear(x, y);
  if (!corpse) {
    notice('There is no corpse there.');
    return false;
  }
  game.send({ command: 'explode', corpse: corpse.id });
  return true;
}

export function beginExplode() {
  if (!myNecromancer()) {
    notice('The dead cannot detonate the dead.');
    return;
  }
  setMode(game.mode === 'explode' ? null : 'explode');
}

export function beginAttackMove() {
  if (game.selection.size === 0) {
    notice('Select units first.');
    return;
  }
  setMode('attack-move');
}

export function beginRaise() {
  if (!myNecromancer()) {
    notice('The dead cannot raise the dead.');
    return;
  }
  setMode(game.mode === 'raise' ? null : 'raise');
}

export function selectLegion() {
  game.selection.clear();
  for (const unit of game.units.values()) {
    if (isMine(unit) && unit.id !== game.you?.necromancerId) game.selection.add(unit.id);
  }
}

export function selectNecromancer() {
  const necromancer = myNecromancer();
  game.selection.clear();
  if (necromancer) game.selection.add(necromancer.id);
}

// ---------------------------------------------------------------- a commander's abilities

const NEEDS = { ENEMY: 'an enemy', ALLY: 'an ally', ENEMY_COMMANDER: 'an enemy commander' };

/** Why the commander can't use this ability right now, in the server's own words, or null if it can. */
export function refusal(ability) {
  const you = game.you;
  const commander = myNecromancer();
  if (!commander) return `The fallen cannot use ${ability.label}.`;
  if (commander.statuses?.some((s) => s.id === 'silenced')) return 'You are silenced.';
  const left = you.cooldowns?.[ability.id] ?? 0;
  if (left > 0) return `${ability.label} is ready in ${Math.ceil(left)}s.`;
  if (you.soul < ability.cost) return `Not enough ${game.commander.energy} for ${ability.label}.`;
  return null;
}

/** R, F or X: casts an ability that needs no target, or takes aim with one that does. */
export function useAbility(slot) {
  const ability = game.commander?.abilities?.find((a) => a.slot === slot);
  if (!ability) return;
  if (game.mode === `cast:${ability.id}`) {
    setMode(null);
    return;
  }
  const why = refusal(ability);
  if (why) {
    notice(why);
    return;
  }
  if (needsTarget(ability)) setMode(`cast:${ability.id}`);
  else game.send({ command: 'cast', ability: ability.id });
}

/** Casts the aimed ability at this tile, or at the unit on it. Returns whether it was cast. */
export function castAt(tx, ty) {
  const ability = aimedAbility();
  if (!ability) return false;
  if (ability.target === 'TILE') {
    game.send({ command: 'cast', ability: ability.id, x: tx, y: ty });
    marker(tx, ty, true);
    return true;
  }
  const unit = unitAtTile(tx, ty);
  if (!canTarget(ability, unit)) {
    notice(`${ability.label} needs ${NEEDS[ability.target]}.`);
    return false;
  }
  game.send({ command: 'cast', ability: ability.id, target: unit.id });
  marker(unit.x, unit.y, ability.target !== 'ALLY');
  return true;
}

/** Picks the commander to rise as next. */
export function pick(id) {
  game.send({ command: 'pick', commander: id });
}
