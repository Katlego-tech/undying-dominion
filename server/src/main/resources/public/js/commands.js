// Every order the player can give, used by the keyboard, the mouse and the command bar alike.

import {
  game, selectedUnits, myNecromancer, corpseNear, isMine,
} from './state.js';
import { notice, showModeHint } from './hud.js';

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
    if (isMine(unit) && unit.type !== 'NECROMANCER') game.selection.add(unit.id);
  }
}

export function selectNecromancer() {
  const necromancer = myNecromancer();
  game.selection.clear();
  if (necromancer) game.selection.add(necromancer.id);
}
