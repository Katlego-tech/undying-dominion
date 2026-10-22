// The roster picker and kit card: the join form's two columns, and "Rise as another" after a fall
// (docs/design/characters.md §6 Client, mockups/characters-picker.png and characters-rise-as.png).

import { ROLES, roleOf, SLOTS, SLOT_KEYS, describe, describePassive } from './kits.js';

const $ = (id) => document.getElementById(id);
const svg = (d) => `<svg class="mark" viewBox="0 0 24 24" aria-hidden="true"><path d="${d}"/></svg>`;
const DICE = 'M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2zm3 3.5a1.5 1.5 0 1 0 0 3 1.5 1.5 0 1 0 0-3zm8 8a1.5 1.5 0 1 0 0 3 1.5 1.5 0 1 0 0-3zm-4-4a1.5 1.5 0 1 0 0 3 1.5 1.5 0 1 0 0-3z';
const TAGLINE = 'Pick a commander. Lead their retinue. Outlast every rival.';

let roster = [];
let unitTypes = {};
let chosen = null;
let shownBefore = null;  // the join screen's title and tagline, restored when a rise-as closes

const short = (title) => title.replace(/^the /, '');
const escape = (text) => String(text).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);

/** Fills the roster from the server's kits. Returns whether there is one to pick from (none in a campaign). */
export function initPicker(kits, types) {
  if (roster.length) return true;
  roster = kits ?? [];
  unitTypes = types ?? {};
  if (!roster.length) return false;
  $('random').innerHTML = `${svg(DICE)}<b>Random</b>`;
  $('roster').innerHTML = ROLES.map((role) => {
    const members = roster.filter((k) => k.role === role.id);
    if (!members.length) return '';
    return `<section class="role">
      <h3>${svg(role.mark)}${role.name}</h3>
      <ul>${members.map((k) => `
        <li><button type="button" class="kit-tile" data-id="${k.id}" aria-pressed="false">
          ${svg(role.mark)}<b>${escape(k.name)}</b><small>${escape(short(k.title))}</small></button></li>`).join('')}
      </ul>
    </section>`;
  }).join('');
  $('roster').addEventListener('click', (e) => {
    const tile = e.target.closest('.kit-tile');
    if (tile) select(tile.dataset.id);
  });
  $('random').addEventListener('click', () => select(roster[Math.floor(Math.random() * roster.length)].id));
  return true;
}

/** The commander picked on the card, or null when there's no roster. */
export function chosenKit() {
  return roster.length ? chosen : null;
}

/** Turns the join form into the two-column picker. */
export function showPicker() {
  if (!roster.length) return;
  $('join').classList.add('picking');
  $('join-form').classList.replace('join-box', 'pick-stage');
  $('roster-picker').hidden = false;
  $('kit-card').hidden = false;
  $('join-button').hidden = true;
  $('name-label').textContent = 'Your name';
  $('join-tagline').textContent = TAGLINE;
  select(chosen ?? roster[0].id);
}

/** Opens the picker over the field after a fall: no name, a blood-tinted field, and Esc keeps the fallen one. */
export function openRiseAs(fallen, seconds) {
  if (!roster.length) return;
  showPicker();
  if (fallen && roster.some((k) => k.id === fallen.id)) select(fallen.id);
  shownBefore ??= { title: $('join-title').textContent, tagline: $('join-tagline').innerHTML };
  $('join').classList.add('rise-mode');
  // The name box is hidden here, and an empty required box would block the pick.
  $('name').required = false;
  $('join-title').textContent = 'Rise as another';
  $('join-tagline').innerHTML = fallen
    ? `You fell as <b>${escape(fallen.name)}, ${escape(fallen.title)}</b>. Whoever you pick rises in ${seconds} s; Esc keeps ${escape(fallen.name)}.`
    : TAGLINE;
  $('join').hidden = false;
  document.querySelector('.kit-tile[aria-pressed="true"]')?.focus();
}

export function closeRiseAs() {
  $('join').hidden = true;
  $('join').classList.remove('rise-mode');
  $('name').required = true;
  if (shownBefore) {
    $('join-title').textContent = shownBefore.title;
    $('join-tagline').innerHTML = shownBefore.tagline;
  }
}

export function risingAs() {
  return !$('join').hidden && $('join').classList.contains('rise-mode');
}

function select(id) {
  const kit = roster.find((k) => k.id === id) ?? roster[0];
  chosen = kit.id;
  for (const tile of document.querySelectorAll('.kit-tile[data-id]')) {
    tile.setAttribute('aria-pressed', String(tile.dataset.id === kit.id));
  }
  const error = $('join-error');
  $('kit-card').innerHTML = card(kit);
  $('kit-card').append(error);
}

/** Everything a pick changes, in the order you'll use it. */
function card(kit) {
  const role = roleOf(kit.role);
  const energy = kit.energy.toLowerCase();
  const retinue = kit.retinue.map((r, i) => {
    const t = unitTypes[r.name] ?? { label: r.label, maxHp: '—', summonCost: '—' };
    return `<li><kbd>${i + 1}</kbd>${escape(t.label)}<span class="value">${t.maxHp} hp</span><span class="cost">${t.summonCost} ${energy}</span></li>`;
  }).join('');
  const abilities = SLOTS.map((slot) => {
    const a = kit.abilities.find((x) => x.slot === slot);
    if (!a) return '';
    const meta = [`${a.cost} ${energy}`, `${a.cooldown} s`, a.range ? `range ${a.range}` : null].filter(Boolean).join(' · ');
    return `<li class="${slot.toLowerCase()}"><kbd>${SLOT_KEYS[slot]}</kbd>
      <div class="name">${escape(a.label)}<span>${slot.toLowerCase()}</span></div>
      <div class="meta">${meta}</div><div class="does">${escape(describe(a, kit, unitTypes))}</div></li>`;
  }).join('');
  const passive = kit.passive ? `<li class="passive"><kbd>·</kbd><div class="name">${escape(kit.passive.label)}<span>passive</span></div>
      <div class="meta"></div><div class="does">${escape(describePassive(kit.passive))}</div></li>` : '';
  const u = kit.unit;
  return `
    <span class="kit-role">${svg(role.mark)}${role.name.replace(/s$/, '')}</span>
    <h2>${escape(kit.name)}</h2>
    <p class="kit-title">${escape(kit.title)} · ${escape(kit.energy)}</p>
    <p class="kit-blurb">${escape(kit.blurb)}</p>
    <dl class="kit-stats">
      <div><dt>Health</dt><dd>${u.hp}</dd></div><div><dt>Damage</dt><dd>${u.damage}</dd></div>
      <div><dt>Range</dt><dd>${u.range}</dd></div><div><dt>Sight</dt><dd>${u.vision}</dd></div>
    </dl>
    <h4>Retinue · ${escape(kit.verb.toLowerCase())} with 1–${kit.retinue.length}</h4>
    <ul class="kit-retinue">${retinue}</ul>
    <h4>Abilities</h4>
    <ul class="kit-abilities">${abilities}${passive}</ul>
    <button type="submit" class="rise">Rise as ${escape(kit.name)}</button>`;
}
