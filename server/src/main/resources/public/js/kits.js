// What a commander's kit says, in words and marks: role marks, status families, and one plain line per
// ability, written from the kit's own effects (docs/design/characters.md §6 Client). No DOM here.

/** Role order in the picker, with each role's name and mark. Marks are 24×24 SVG paths, filled even-odd. */
export const ROLES = [
  { id: 'FRONT_LINE', name: 'Front line', mark: 'M3 19h18l-1.6-11-4.6 4.2L12 4.5 9.2 12.2 4.6 8z' },
  { id: 'FIGHTER', name: 'Fighters', mark: 'M21 3v3.5L11.2 16.3 7.7 12.8 17.5 3zM4.6 12.4l7 7-1.7 1.7-2-2-2.6 2.6-2.4-2.4 2.6-2.6-2-2z' },
  { id: 'ASSASSIN', name: 'Assassins', mark: 'M5 3h14c-.6 8.5-3.2 14.4-7 18.5C10.4 14 8.4 8.6 5 3z' },
  { id: 'CASTER', name: 'Casters', mark: 'M12 2l2.4 7.6L22 12l-7.6 2.4L12 22l-2.4-7.6L2 12l7.6-2.4z' },
  { id: 'SUMMONER', name: 'Summoners', mark: 'M12 2a10 10 0 1 1 0 20 10 10 0 1 1 0-20zm0 2.6a7.4 7.4 0 1 0 0 14.8 7.4 7.4 0 1 0 0-14.8zM12 6.6l4.8 8.4H7.2z' },
  { id: 'SUPPORT', name: 'Support', mark: 'M20.5 3.5C9.8 3.2 3.6 9.4 4.6 19.4c.2.1.4.1.6.1L13.4 11l1 1-8.2 8.4c9.7.5 14.6-6.3 14.3-16.9z' },
];

export const roleOf = (id) => ROLES.find((r) => r.id === id) ?? ROLES[0];

/** Keys by slot: the necromancer's raise, fuse and explode keys, so the hand never moves. */
export const SLOT_KEYS = { BASIC: 'R', UTILITY: 'F', SIGNATURE: 'X' };
export const SLOTS = ['BASIC', 'UTILITY', 'SIGNATURE'];

const CONTROL = new Set(['SLOW', 'WEAKENED', 'EXPOSED', 'ROOTED', 'SILENCED', 'SEALED', 'TAUNTED']);

/** buff (gold), control (violet), damage over time (blood) or stealth (a dashed outline, no pip). */
export function familyOf(status) {
  if (status === 'STEALTHED') return 'stealth';
  if (status === 'BURNING') return 'dot';
  return CONTROL.has(status) ? 'control' : 'buff';
}
export const FAMILY_COLORS = { buff: '#fbbf24', control: '#a78bfa', dot: '#f87171' };

const STATUS_NAMES = {
  MIGHT: 'Might', WARD: 'Ward', HASTE: 'Haste', SLOW: 'Slow', WEAKENED: 'Weakened', EXPOSED: 'Exposed',
  ROOTED: 'Rooted', SILENCED: 'Silenced', SEALED: 'Sealed', STEALTHED: 'Stealth', TAUNTED: 'Taunted',
  UNSTOPPABLE: 'Unstoppable', EMPOWERED: 'Empowered', REGEN: 'Regen', BURNING: 'Burning',
  CLEAVING: 'Cleaving', EVADING: 'Evading',
};
export const statusName = (id) => STATUS_NAMES[id.toUpperCase()] ?? id;

/** What one status does at magnitude m, as a short phrase. */
function statusPhrase(status, m) {
  switch (status) {
    case 'MIGHT': return `+${m} % damage`;
    case 'WARD': return `${m} % less damage taken`;
    case 'HASTE': return `${m} % faster`;
    case 'SLOW': return `slowed ${m} %`;
    case 'WEAKENED': return `weakened ${m} %`;
    case 'EXPOSED': return `exposed ${m} %`;
    case 'ROOTED': return 'rooted';
    case 'SILENCED': return 'silenced';
    case 'SEALED': return 'sealed, no summons';
    case 'STEALTHED': return 'stealthed';
    case 'TAUNTED': return 'taunted';
    case 'UNSTOPPABLE': return 'unstoppable';
    case 'EMPOWERED': return `next strike ${m} %`;
    case 'REGEN': return `heal ${m} hp/s`;
    case 'BURNING': return `burn ${m} hp/s`;
    case 'CLEAVING': return 'strikes cleave';
    case 'EVADING': return `evade ${m} % of strikes`;
    default: return status.toLowerCase();
  }
}

const plural = (n, word) => `${n} ${word}${n === 1 ? '' : 's'}`;

/** One effect on its recipients, as a phrase, with its duration kept apart so runs can share it. */
function effectPhrase(e) {
  switch (e.kind) {
    case 'damage': return `${e.amount} damage${e.stealthBonus ? ` (+${e.stealthBonus} % from stealth)` : ''}`;
    case 'heal': return `heal ${e.amount}`;
    case 'shield': return { text: `shield ${e.amount}`, secs: e.seconds };
    case 'status': {
      const after = e.afterSeconds ? ` after ${e.afterSeconds} s` : '';
      return { text: `${statusPhrase(e.status, e.magnitude)}${after}`, secs: e.seconds };
    }
    case 'push': return `pushed ${plural(e.tiles, 'tile')}`;
    case 'taunt': return { text: 'taunted', secs: e.seconds };
    case 'reveal': return { text: 'revealed', secs: e.seconds };
    case 'cleanse': return 'control removed';
    default: return e.kind;
  }
}

/** "a, b and c for 5 s, d": phrases that share a duration say it once, after the last of them. */
function joinPhrases(list) {
  const out = [];
  for (let i = 0; i < list.length; i++) {
    const p = typeof list[i] === 'string' ? { text: list[i] } : list[i];
    const run = [p.text];
    while (p.secs && i + 1 < list.length && list[i + 1]?.secs === p.secs) run.push(list[++i].text);
    const words = run.length > 1 ? `${run.slice(0, -1).join(', ')} and ${run.at(-1)}` : run[0];
    out.push(p.secs ? `${words} for ${p.secs} s` : words);
  }
  return out.join(', ');
}

/** Who an effect lands on, in words: its own {@code affects}, else the ability's, at the ability's target. */
function recipient(ability, e, dashed) {
  const affects = e.affects ?? ability.affects;
  if (affects === 'CASTER' || ability.target === 'SELF') return 'You';
  const r = ability.radius ?? 0;
  const where = ability.target === 'AROUND_SELF' ? 'you' : dashed ? 'where you land' : 'the tile';
  const side = affects === 'OWN' || affects === 'ALLIES' ? 'Allies' : 'Enemies';
  if (ability.target === 'TILE' || ability.target === 'AROUND_SELF') {
    if (affects === 'OWN' && dashed && !r) return 'You';
    return r ? `${side} within ${r} of ${where}` : dashed ? 'Enemies beside where you land' : 'The tile';
  }
  if (ability.target === 'ALLY') return 'An ally';
  if (ability.target === 'ENEMY_COMMANDER') return 'An enemy commander';
  return 'An enemy';
}

const ARTICLE = { ENEMY: 'an enemy', ALLY: 'an ally', ENEMY_COMMANDER: 'an enemy commander', TILE: 'a tile' };

/**
 * One line per ability, written from its effects: "Enemies within 2 of the tile: weakened 30 % and
 * slowed 25 % for 5 s." A dash, a summon or a cooldown reset leads as its own sentence.
 * {@code types} names summoned units that aren't in the retinue (welcome.unitTypes).
 */
export function describe(ability, kit, types = {}) {
  const lead = [];
  const groups = new Map();
  const dash = ability.effects.find((e) => e.kind === 'dash');
  for (const e of ability.effects) {
    if (e.kind === 'dash') {
      const to = ARTICLE[ability.target] ?? 'a tile';
      lead.push(`Dash up to ${plural(e.tiles, 'tile')} to ${to}${e.overTerrain ? ', over walls' : ''}`
        + (e.damage ? `, ${e.damage} damage to each enemy passed` : ''));
    } else if (e.kind === 'summon') {
      const name = kit.retinue?.find((r) => r.name === e.unit)?.label ?? types[e.unit]?.label ?? e.unit;
      lead.push(`Call ${e.count} ${e.count === 1 ? name : name.replace(/([^aeiou])y$/, '$1ie') + 's'} for ${e.seconds} s`);
    } else if (e.kind === 'resetCooldowns') {
      lead.push('Reset your other cooldowns');
    } else {
      const who = recipient(ability, e, Boolean(dash));
      if (!groups.has(who)) groups.set(who, []);
      groups.get(who).push(effectPhrase(e));
    }
  }
  const sentences = [...lead, ...[...groups].map(([who, list]) => `${who}: ${joinPhrases(list)}`)];
  return sentences.map((x) => `${x}.`).join(' ');
}

/** The passive in one line. */
export function describePassive(p) {
  const status = p.status ? statusPhrase(p.status, p.magnitude) : '';
  switch (p.kind) {
    case 'aura': return `Allies within ${p.radius}: ${status}.`;
    case 'self': return `Always: ${status}.`;
    case 'seesStealth': return `Sees stealthed enemies within ${p.radius}.`;
    case 'energyRegen': return `Energy returns ${p.magnitude / 10} a second faster.`;
    case 'energyOnKill': return `+${p.magnitude} energy for every kill.`;
    case 'immune': return `Immune to ${statusName(p.status).toLowerCase()}.`;
    case 'bonusVs': {
      const who = p.who === 'OWN' ? 'You and your retinue deal' : 'You deal';
      return `${who} +${p.magnitude} % damage to ${p.against === 'COMMANDER' ? 'commanders' : 'retinue units'}.`;
    }
    default: return '';
  }
}

/** Whether the ability waits for a target: a unit or a tile, picked on the field. */
export const needsTarget = (ability) => ['ENEMY', 'ALLY', 'ENEMY_COMMANDER', 'TILE'].includes(ability.target);
