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

export function fuse() {
  const units = selectedUnits().filter((u) => u.type === 'SKELETON_WARRIOR').map((u) => u.id);
  if (units.length < game.rules.fuseCount) {
    notice(`Select at least ${game.rules.fuseCount} Skeleton Warriors to fuse.`);
    return;
  }
  game.send({ command: 'fuse', units });
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
