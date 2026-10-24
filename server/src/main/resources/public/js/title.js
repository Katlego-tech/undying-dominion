// The title screen on a hub (docs/design/modes.md, mockups/modes-*.png): the name once, then
// Campaign, Free-for-all or Match. A single-mode server has no title screen; main.js goes straight
// to its join form.

const $ = (id) => document.getElementById(id);

const MARKS = {
  campaign: 'M5 3h9a5 5 0 0 1 5 5v13H8a3 3 0 0 1-3-3zm2 2v13a1 1 0 0 0 1 1h9V8a3 3 0 0 0-3-3zm2 3h6v2H9zm0 4h6v2H9z',
  'free-for-all': 'M4 3l7 7-1.5 1.5L3 5V3zm16 0v2l-6.5 6.5L12 10l7-7zM8 13l3 3-4 4-2 1-1-1 1-2zm8 0l3 4 1 2-1 1-2-1-4-4z',
  match: 'M7 3h10v3a5 5 0 0 1-4 4.9V14h3v3H8v-3h3v-3.1A5 5 0 0 1 7 6zM4 4h2v2a3 3 0 0 0 1 2.2L5.6 9.6A5 5 0 0 1 4 6zm16 0v2a5 5 0 0 1-1.6 3.6L17 8.2A3 3 0 0 0 18 6V4zM6 19h12v2H6z',
};

/** What the server offers, or null for a single-mode server (or one too old to say). */
export async function loadModes() {
  try {
    const response = await fetch('/modes');
    if (!response.ok) return null;
    const { modes } = await response.json();
    return Array.isArray(modes) && modes.length > 1 ? modes : null;
  } catch {
    return null;
  }
}

/** "5 hours ago", "yesterday", "3 days ago". */
function ago(iso) {
  const minutes = Math.max(0, Math.round((Date.now() - Date.parse(iso)) / 60000));
  if (minutes < 2) return 'just now';
  if (minutes < 60) return `${minutes} minutes ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours} hour${hours === 1 ? '' : 's'} ago`;
  const days = Math.round(hours / 24);
  return days === 1 ? 'yesterday' : `${days} days ago`;
}

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text != null) node.textContent = text;
  return node;
}

function mark(id) {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('class', 'mark');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('aria-hidden', 'true');
  const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
  path.setAttribute('d', MARKS[id]);
  svg.append(path);
  return svg;
}

/**
 * Shows the title screen. {@code onFreeForAll(name)} goes on to the commander picker;
 * {@code onCampaign(name, save)} joins a campaign, new ({@code save} null) or continued.
 */
export function showTitle(modes, { onFreeForAll, onCampaign }) {
  const byId = Object.fromEntries(modes.map((m) => [m.id, m]));
  let campaignOpen = false;

  /** The name, or null with the reason shown. */
  const name = () => {
    const value = $('title-name').value.trim();
    if (!value) {
      $('title-error').textContent = 'Enter your name first.';
      $('title-name').focus();
      return null;
    }
    $('title-error').textContent = '';
    return value;
  };

  function campaignTile() {
    const c = byId.campaign;
    const tile = el('div', `mode campaign${campaignOpen ? ' open' : ''}${c.open ? '' : ' closed'}`);
    tile.tabIndex = 0;
    tile.setAttribute('role', 'button');
    tile.setAttribute('aria-expanded', String(campaignOpen));
    tile.append(mark('campaign'), el('h2', null, 'Campaign'));
    if (!c.open) {
      tile.append(el('span', 'sub', c.reason));
      return tile;
    }
    tile.append(el('span', 'sub', `${c.title} · ${c.chapters} chapters`));
    const saves = [...(c.saves ?? [])].sort((a, b) => Date.parse(b.savedAt) - Date.parse(a.savedAt));
    if (!campaignOpen) {
      tile.append(el('span', 'sub', 'The story, alone, chapter by chapter.'),
        el('span', 'meta', saves.length ? `Continue · ${saves.length} save${saves.length === 1 ? '' : 's'} on this server` : 'New campaign'));
      // The list is fetched again on opening: a save written as the player left may be newer than the page.
      const open = async () => {
        const fresh = await loadModes();
        if (fresh) fresh.forEach((m) => { byId[m.id] = m; });
        campaignOpen = true;
        render();
      };
      tile.addEventListener('click', open);
      tile.addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(); } });
      return tile;
    }
    const list = el('ul', 'saves');
    for (const s of saves) {
      const row = el('button', 'save');
      row.type = 'button';
      row.append(el('b', null, s.name), el('span', 'when', ago(s.savedAt)), el('span', 'where', `Chapter ${s.chapter}: ${s.chapterTitle}`));
      row.addEventListener('click', () => {
        const who = name();
        if (who) onCampaign(who, s.name);
      });
      const item = el('li');
      item.append(row);
      list.append(item);
    }
    const actions = el('div', 'campaign-actions');
    const fresh = el('button', 'new', 'New campaign');
    fresh.type = 'button';
    fresh.addEventListener('click', () => {
      const who = name();
      if (who) onCampaign(who, null);
    });
    actions.append(fresh);
    if (saves.length) tile.append(list);
    tile.append(actions);
    return tile;
  }

  function freeForAllTile() {
    const f = byId['free-for-all'];
    const tile = el('button', 'mode ffa');
    tile.type = 'button';
    tile.append(mark('free-for-all'), el('h2', null, 'Free-for-all'), el('span', 'sub', 'Every commander for themselves.'),
      el('span', 'meta', `${f.players} playing`));
    tile.addEventListener('click', () => {
      const who = name();
      if (who) onFreeForAll(who);
    });
    return tile;
  }

  function matchTile() {
    const m = byId.match;
    const tile = el('div', 'mode match');
    tile.setAttribute('aria-disabled', 'true');
    tile.append(mark('match'), el('span', 'soon', 'Coming'), el('h2', null, 'Match'), el('span', 'sub', `${m.reason ?? 'Matches are coming'}.`));
    return tile;
  }

  function render() {
    const tiles = [];
    if (byId.campaign) tiles.push(campaignTile());
    if (byId['free-for-all']) tiles.push(freeForAllTile());
    if (byId.match && !campaignOpen) tiles.push(matchTile());
    $('modes').replaceChildren(...tiles);
  }

  render();
  $('join').hidden = true;
  $('title').hidden = false;
  $('title-name').focus();
}

/** Shows why a title-screen join didn't take. */
export function titleError(text) {
  $('title-error').textContent = text;
}

export function hideTitle() {
  $('title').hidden = true;
}
