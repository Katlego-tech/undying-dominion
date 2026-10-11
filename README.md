# Undying Dominion

A multiplayer necromancer RTS in the browser. Summon skeletons, raise everything that dies into your
own legion, fuse warriors into a Skeleton King, and outlast every rival necromancer in a fog-shrouded
barrow.

One authoritative Java server runs the world at 10 ticks a second and speaks JSON over WebSocket.
The browser client is plain JavaScript on a canvas, with no build step. Anything that speaks the
protocol can play, including bots.

> Early vertical slice. The core loop is playable; art, balance and persistence are still to come.

## Quick start

Requires JDK 21 and Maven 3.9+.

```bash
cd server
mvn package
java -jar target/undying-dominion-0.1.0-SNAPSHOT-jar-with-dependencies.jar
```

The terminal becomes the server dashboard. Open <http://localhost:5000>, pick a name and rise.
Open a second tab to fight yourself.

Run it from the `server/` directory and the client is served straight from
`src/main/resources/public`, so client edits show on a browser refresh without rebuilding.

### Server options

| Flag | Long form | Default | Meaning |
|---|---|---|---|
| `-p` | `--port` | `5000` | Listening port |
| `-m` | `--map` | the Sundered Barrow | Map file, or `classpath:/maps/<name>.properties` |
| `-mc` | `--max-clients` | `8` | Most necromancers at once |
| | `--seed` | random | Seed the world for a repeatable game |
| | `--no-wild` | off | No wild creatures |
| | `--plain` | off | Plain line console instead of the dashboard |
| | `--campaign` | none | Play a story campaign from a folder instead of a free-for-all (see Campaigns) |

## How to play

| Input | Action |
|---|---|
| `W` `A` `S` `D` | Walk your necromancer, diagonals included. The camera follows it |
| Left-drag, left-click | Select your units. Shift adds. Double-click selects every unit of that kind on screen |
| Right-click | Move, or attack the enemy under the cursor. Right-click the minimap to march there |
| `1` `2` `3` `4` | Summon a Skeleton Warrior, Grave Warden, Skeleton Archer or Lich beside your necromancer |
| `C` then click | Attack-move: march there, fighting anything met on the way |
| `R` then click | Raise the corpse there into your legion. Shift-click to keep raising |
| `F` | Fuse five selected Skeleton Warriors standing together into a Skeleton King |
| `X` then click | Corpse Explosion: detonate a corpse near your necromancer, hurting every enemy around it |
| `M` | Mend: the selected units stand still until they are whole again |
| `T` | Grave Wardens lay a hidden bone trap and step forward off it |
| `G` | Gather the selected units to your necromancer, or the whole legion when none are selected. Hold to keep them following |
| `H` | Stop |
| `Q` / `E` | Select your whole legion / your necromancer |
| `Space` | Return the camera to your necromancer and follow it again |
| Arrows, middle-drag, minimap | Look around freely (stops following). Mouse wheel zooms |

### The rules

- **Soul energy** pays for everything. It trickles back while your necromancer lives, and every kill
  pays out more.
- **Your legion** is capped at 8 + 2 per level. Kills earn experience, and each level raises the cap
  and your soul reserve.
- **Everything that dies leaves a corpse** for 30 seconds. Any necromancer within 6 tiles can raise
  it, whoever it belonged to. Raise a rival's Lich and it fights for you.
- **If your necromancer falls**, your whole legion crumbles into corpses for the others to take. You
  rise again elsewhere after six seconds with half your soul.
- **Mending and trap-laying are commitments.** A unit doing either ignores orders until it finishes,
  and a Grave Warden laying a trap takes double damage.
- **Traps are hidden.** Enemies only notice one within a quarter of their vision range. A trap hurts
  whoever steps on it, including its owner's own units. Your units route around your own traps, and a
  warden with no room to step off its trap springs it.
- **Fog of war.** You see what your units see, and ruins block sight. The server never sends you
  anything under your fog.

| Unit | Summon | Raise | Role |
|---|---|---|---|
| Skeleton Warrior | 20 | 12 | Melee line. Five fuse into a King |
| Grave Warden | 25 | 15 | No attack. Lays bone traps |
| Skeleton Archer | 30 | 18 | Range 5, fragile |
| Lich | 70 | 40 | Range 4, splash damage |
| Skeleton King | fusion | 150 | Huge melee with splash |
| Dire Wolf | wild | 10 | Fast wild melee that roams the map |
| Fallen Knight | wild | 25 | Tough wild melee |

| Terrain | Movement | Sight and missiles |
|---|---|---|
| Ruins | blocked | blocked |
| Forest | normal | blocked |
| Swamp | half speed | clear |
| Abyss | blocked | clear |

## Server dashboard

Started in a terminal, the server takes over the window with a live dashboard:

- **World:** the whole battlefield without fog, two tiles per character cell, with every unit, corpse
  and trap.
- **Necromancers:** level, soul, legion, kills and deaths for each player.
- **Log:** joins, leaves, warnings and the output of your commands.
- **Header:** the port, the tick time, and a link other players on your network can open.

| Key | Action |
|---|---|
| `↑` `↓` | Select a necromancer |
| `p` | Purge the selected necromancer (asks to confirm): their legion crumbles and they are disconnected |
| `o` | Open the game in your browser |
| `:` | Type a console command: `players`, `dump`, `purge <name>`, `help` |
| `q` | Close the Dominion (asks to confirm) |

The dashboard needs a terminal of at least 94 x 26. When output isn't an interactive terminal (piped,
a service, a container without `-t`), or with `--plain`, the server runs a plain line console instead,
reading the same commands plus `quit` from standard input.

## Web API

`GET /api/world` returns the map and the scoreboard as JSON. Unit positions are deliberately left out
so the API can't be used to see through the fog.

## Protocol

Connect a WebSocket to `ws://<host>:<port>/ws`. Every client message is a JSON object with a
`command`:

```json
{ "command": "join", "name": "Vesper" }
{ "command": "summon", "unit": "SKELETON_WARRIOR" }
{ "command": "move", "units": [4, 7], "x": 20, "y": 11, "attackMove": true }
{ "command": "attack", "units": [4, 7], "target": 31 }
{ "command": "stop", "units": [4] }
{ "command": "raise", "corpse": 12 }
{ "command": "explode", "corpse": 12 }
{ "command": "fuse", "units": [4, 5, 6, 8, 9] }
{ "command": "mend", "units": [4] }
{ "command": "trap", "units": [10] }
```

The server sends three kinds of message:

- `welcome`, once, after a successful join: your `playerId`, the map, the rules and the stats of every
  unit type.
- `state`, every tick: your soul, level and notices (`you`), the units, corpses and traps you can
  see, what happened this tick (`events`), the scoreboard (`players`), and `fog`, a base64 bitmask of
  visible tiles, one bit per tile, row by row, lowest bit first. If a client falls behind, it skips
  stale states rather than queueing them.
- `story`, in a campaign: a chapter's `briefing`, a line said mid-chapter (`say`), `victory`,
  `defeat` or the `finale`, with the chapter's title, objective and `lines`. During a campaign every
  `state` also carries `story`: chapter, objective and progress. A second `welcome` means the
  campaign has moved to the next chapter's map.
- `error`, when a message can't be acted on: `Unsupported command`, `Could not parse arguments`,
  `Join the Dominion first`, `Too many of you in this world` (name taken) or
  `No more space in this world` (server full).

Coordinates are tiles, with `[0,0]` at the top-left.

## Maps

Maps are properties files. Obstacles are inclusive rectangles, `x1,y1,x2,y2`, and later keys paint
over earlier ones in sorted order:

```properties
map.width=48
map.height=32
obstacle.ruins.01=8,5,14,5
obstacle.swamp.1=10,22,16,27
obstacle.abyss.1=23,6,24,13
```

## Campaigns

A campaign tells a story as a series of chapters, each a map with its own creatures, timed waves,
scripted moments and goal. Start one with `--campaign <folder>`. Everyone connected plays the
chapter together, sees its briefing, and follows its objective in the HUD. When the chapter is won,
everyone moves on to the next map, keeping their level, record and legion. A chapter that runs out
of time starts again.

The folder holds a `campaign.json` and the map files it names:

```json
{
  "title": "The Barrow Wars",
  "creatures": {
    "CAVE_SPIDER": {"label": "Cave Spider", "level": 3, "hp": 30, "damage": 4, "look": "BEAST"},
    "SPIDER_QUEEN": {"label": "Spider Queen", "level": 6, "hp": 600, "damage": 15, "splash": 1,
                     "look": "BEAST", "boss": true}
  },
  "chapters": [{
    "id": "nest", "title": "The Nest", "source": "Chapter one", "map": "maps/nest.properties",
    "start": {"area": [2, 2, 5, 5], "soul": 100},
    "briefing": ["Something is breeding under the hill."],
    "objective": {"kind": "KILL_BOSS", "unit": "SPIDER_QUEEN", "text": "Slay the Spider Queen"},
    "spawns": [{"unit": "CAVE_SPIDER", "area": [10, 2, 30, 20], "count": 12},
               {"unit": "SPIDER_QUEEN", "at": [[28, 18]]}],
    "triggers": [{"when": "BOSS_HP_BELOW", "unit": "SPIDER_QUEEN", "percent": 50,
                  "say": ["The queen shrieks. The walls start to move."],
                  "spawns": [{"unit": "CAVE_SPIDER", "area": [20, 10, 30, 20], "count": 8}]}],
    "victory": ["The nest is silent."],
    "timeLimitSeconds": 600
  }]
}
```

- **Creatures** need a `label` and `hp`. Optional: `level`, `damage`, `range`, `attackCooldown` and
  `moveCooldown` (ticks), `aggro`, `vision`, `splash`, `soul`, `xp`, `raise` (cost, -1 for never),
  `look` (`BEAST`, `GOBLIN`, `PLANT`, `HUMANOID`, `BEASTMAN`, `DEMON`, `UNDEAD`), `boss`, `stationary`.
- **Objectives:** `CLEAR_ALL`, `KILL_BOSS` (`unit` may list several types: `"A,B,C"`), `KILL_COUNT`
  (`count`) or `SURVIVE` (`seconds`).
- **Triggers** fire once: `AT_SECOND` (`seconds`), `BOSS_HP_BELOW` (`unit`, `percent`) or
  `TYPE_CLEARED` (`unit`, after it has appeared and every one has fallen). Each can `say` lines of
  story and bring more `spawns`.
- **Spawns** are scattered across an `area` (kept clear of the start) or placed `at` exact tiles.
  Loading fails on a creature placed inside a wall, so mistakes surface before anyone plays.

## Drawing maps

The jar can draw any map, or every chapter of a campaign with its start and creatures marked, to PNG:

```bash
java -cp target/undying-dominion-0.1.0-SNAPSHOT-jar-with-dependencies.jar \
     com.undyingdominion.tools.MapRenderer src/main/resources/maps/default.properties barrow.png
java -cp target/undying-dominion-0.1.0-SNAPSHOT-jar-with-dependencies.jar \
     com.undyingdominion.tools.MapRenderer --campaign path/to/campaign renders/
```

## Development

```bash
cd server
mvn test
```

This runs the unit tests and the acceptance tests, which start real servers on free ports and play
scenarios over real WebSockets.

```text
server/src/main/java/com/undyingdominion/
├── Main.java, ServerOptions.java   startup and command-line options
├── Dominion.java                   one running world: map, engine and server together
├── world/      the map: terrain, line of sight, A* pathfinding, map files
├── game/       the simulation: engine, units, players, commands, fog of war
├── net/        Javalin server, WebSocket protocol, snapshots, admin console
├── story/      campaigns: chapters, triggers, objectives, the director that runs them
├── tools/      the map renderer
└── tui/        the terminal dashboard (Lanterna)
server/src/main/resources/
├── maps/       map files
└── public/     the browser client (index.html, style.css, js/)
```

The engine is single-threaded and owned by the game loop. Network threads only queue commands for
the next tick. Each player gets their own fog-filtered snapshot, sent from a per-connection virtual
thread so a slow client never stalls the world.

## Origins

Undying Dominion grew out of a Robot Worlds client/server project: robots launched into a shared grid
world over a JSON protocol. The grid, the rectangular obstacle format, the command-line options, the
console commands and the protocol's error messages all carried over. Repair and mines became mending
and bone traps, keeping their rules: a robot couldn't move while it repaired, set mines blind, and
spotted mines only within a quarter of its visibility.
