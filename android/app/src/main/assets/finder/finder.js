// The finder: Dominions heard on this network, the last one played, and a typed address
// (docs/design/android.md §6). The app listens and navigates; the page asks it through window.Dominion,
// a message channel the app opens only to this page's origin.
'use strict';

const $ = (id) => document.getElementById(id);
const MODES = { 'free-for-all': 'Free-for-all', campaign: 'Campaign' };
const NOTHING_AFTER_MS = 8000;
// window.Dominion is missing on an old system WebView: joining then goes by a link the app catches.
const bridge = { send: (message) => window.Dominion?.postMessage(JSON.stringify(message)) };
const channel = typeof window.Dominion?.postMessage === 'function';
const params = new URLSearchParams(location.search);
let lookedLongEnough = false;

/** host, or host:port with a port in range; null for anything else. */
function parseAddress(text) {
  const m = /^\s*([A-Za-z0-9.-]+)(?::(\d{1,5}))?\s*$/.exec(text);
  if (!m) return null;
  const port = m[2] ? Number(m[2]) : 5000;
  return port >= 1 && port <= 65535 ? `${m[1]}:${port}` : null;
}

function play(address) {
  if (channel) bridge.send({ do: 'play', address });
  else location.href = `/play?address=${encodeURIComponent(address)}`;
}

function row(server) {
  const li = document.createElement('li');
  const b = document.createElement('button');
  b.type = 'button';
  b.className = 'server';
  const name = document.createElement('b');
  name.textContent = server.name;
  const mode = document.createElement('span');
  mode.className = 'mode';
  // A hub offers several modes: Free-for-all · Campaign (docs/design/modes.md §6 Beacon).
  mode.textContent = (server.modes ?? [server.mode]).map((m) => MODES[m] ?? m).join(' · ');
  const where = document.createElement('span');
  where.className = 'where';
  where.textContent = `${server.host}:${server.port}`;
  const count = document.createElement('span');
  count.className = 'count';
  count.innerHTML = `<b></b> / ${Number(server.max)}`;
  count.firstChild.textContent = String(Number(server.players));
  b.append(name, mode, where, count);
  b.addEventListener('click', () => play(`${server.host}:${server.port}`));
  li.append(b);
  return li;
}

/** Called by the app about twice a second with every server heard lately, newest first. */
window.finder = {
  found(servers) {
    $('servers').replaceChildren(...servers.map(row));
    $('none').hidden = servers.length > 0 || !lookedLongEnough;
    $('status').classList.toggle('looking', servers.length === 0);
    $('status').textContent = servers.length
      ? `${servers.length === 1 ? 'One Dominion' : `${servers.length} Dominions`} on this network`
      : lookedLongEnough ? 'Still listening on this network' : 'Looking for Dominions on this network…';
  },
  /** The app can't listen on this phone (another app holds the port): say so, and leave typing. */
  unavailable() {
    $('status').classList.remove('looking');
    $('status').textContent = "This phone can't listen for Dominions right now. Type the address shown on the server's screen.";
  },
};

setTimeout(() => {
  lookedLongEnough = true;
  if (!$('servers').children.length) {
    $('none').hidden = false;
    $('status').textContent = 'Still listening on this network';
  }
}, NOTHING_AFTER_MS);

// While typing, the form sits at the top, above the keyboard. Leaving it waits a moment, so a tap on
// Join lands before the layout moves back; a tap anywhere else closes the keyboard, since a Back
// gesture can hide it without the field ever losing focus.
$('address').addEventListener('focus', () => document.body.classList.add('typing'));
$('address').addEventListener('blur', () => setTimeout(() => document.body.classList.remove('typing'), 250));
document.addEventListener('pointerdown', (e) => {
  if (document.body.classList.contains('typing') && !$('manual').contains(e.target)) $('address').blur();
});

$('manual').addEventListener('submit', (e) => {
  e.preventDefault();
  const address = parseAddress($('address').value);
  if (!address) {
    $('error').textContent = "That isn't an address. It looks like 10.1.1.140:5000.";
    return;
  }
  $('error').textContent = '';
  play(address);
});

const last = params.get('last') ?? '';
if (last) {
  $('rejoin').hidden = false;
  // The address in the body face: Cinzel's numerals read 1 as I.
  $('rejoin').append('Rejoin ');
  const address = document.createElement('span');
  address.className = 'address';
  address.textContent = last;
  $('rejoin').append(address);
  $('rejoin').addEventListener('click', () => play(last));
}
// A failed connection comes back here with ?unreachable=<address>.
const unreachable = params.get('unreachable');
if (unreachable) $('error').textContent = `${unreachable} didn't answer. Is the server running, and on this network?`;

if (channel) {
  $('status').classList.add('looking');
  bridge.send({ do: 'scan' });
} else {
  $('status').textContent = "Finding Dominions needs Android System WebView updated. You can still type the address.";
}
