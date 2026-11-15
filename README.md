# GearScore

Equipment strength score for the Serpens Minecraft network, by **Zwo**.

GearScore rates what a player is wearing and wielding — armor, weapon, materials
and, above all, enchantments and their levels — and turns it into a single
number with a tier, a ranking and PlaceholderAPI placeholders.

The number is an **estimate of gear strength**. It does not measure skill,
potions or positioning and is not a prediction of who wins a fight.

* Paper 1.21.11 · Java 21 · MariaDB · works behind Velocity
* Optional: PlaceholderAPI

## How the score is built

```
GearScore = best helmet + best chestplate + best leggings + best boots + best weapon
piece     = base (material and type) + enchantments
```

* The plugin looks at everything the player carries — the 36 inventory slots
  (hotbar included), the 4 armor slots and the off hand, each slot once — and
  keeps, for each of the five places, the item with the highest full score.
* A piece kept in the inventory can beat the one that is worn, and the best
  weapon is chosen across every configured weapon type.
* Items that are not selected add nothing: ten swords, or a spare armor set,
  score the same as the best ones alone.
* Not read: ender chest, open containers, the cursor, the crafting grid and the
  contents of shulker boxes or other containers.
* Only configured materials (and custom items recognised by PDC) are gear.
  Blocks, food, resources and enchanted books never score, whatever
  enchantments or glint they carry.
* Where an item sits does not matter: moving or equipping gear changes nothing.
* Every enchantment has its own points per level and per equipment type, as a
  table or a linear value. Enchantments are grouped by relevance (combat,
  conditional, mob-only, mobility, utility, curses), each with a global
  multiplier.
* Enchantments are matched by namespaced key and only score on gear that can
  actually hold them. Unknown ones are worth zero until configured.
* Levels above the vanilla limit, incompatible combinations and curses each
  have a configurable policy.
* Custom items are recognised only through configured PersistentDataContainer
  keys. Names, lore, CustomModelData and a visual glint never score.

The plugin only reads items. It never changes attributes, enchantments or game
mechanics.

With the default configuration a netherite chestplate is worth 90 points
plain, 240 with Protection IV and 117 with only Unbreaking III and Mending.

## Network behaviour

Each server declares its name and the list of official sources:

```yaml
server:
  name: "newera"
  official-sources: ["newera"]
```

Official sources evaluate equipment and persist the result. Every other server
(hub, events) never reads inventories and never writes, so temporary kits
elsewhere cannot overwrite the official score.

Those servers keep the official score of their online players fresh from the
database: on join, again a few seconds later (the origin server may still be
flushing its write when the player arrives), and then on a slow shared
interval. Reads are grouped by primary key and capped per cycle; late answers
never replace newer data or repopulate a session that ended. The shared
database is the only link between servers: nothing is installed on the proxy
and no extra service is needed.

The ranking reflects the last evaluated equipment of each player, with its
update time. It is not an all-time best. One ordering is used everywhere —
score descending, then the earlier update, then UUID — so the list and each
player's individual position always agree.

## Commands

| Command | Description | Permission |
|---|---|---|
| `/gearscore`, `/gs` | Your score | `gearscore.use` |
| `/gs <player>` | Another player's score | `gearscore.view.others` |
| `/gs detalhes [player]` | Per-item and per-enchantment breakdown | `gearscore.details`, `gearscore.details.others` |
| `/gs top [page]` | Paginated ranking | `gearscore.top` |
| `/gs reload` | Reload configuration | `gearscore.admin.reload` |
| `/gs diag` | Performance counters | `gearscore.admin.diag` |

`/gs` and `/gs <player>` answer with one line — player, score and tier:

```
GearScore » zWorstOne • 1125 GS • Elite
```

`/gs detalhes` shows the five selected pieces; technical data stays in `/gs diag`. The plugin
never sends a message when a score changes. All texts are configurable.

## Placeholders and the TAB sidebar

`%gearscore_score%`, `%gearscore_tier%`, `%gearscore_tier_colored%`,
`%gearscore_tier_id%`, `%gearscore_armor%`, `%gearscore_weapon%`,
`%gearscore_position%`

With TAB on the proxy, TAB-Bridge on each backend resolves them through
PlaceholderAPI, so a sidebar line such as `⚔ GearScore: %gearscore_score%`
needs no extra code. See [docs/TAB.md](docs/TAB.md).

Every placeholder, the ranking position included, only reads memory: no SQL, no
task, no inventory access, no waiting. Positions are refreshed by a separate
clocked step, so a faster TAB or hologram refresh never means more queries.

## Performance design

* No inventory scans, no movement events, no per-player tasks.
* Change events are filtered in constant time (amount and durability changes
  are dropped), debounced per player and processed in due order under a
  per-tick limit by a task that only exists while work is queued.
* The player's 41 slots are read on the server thread into a minimal,
  position-independent snapshot that doubles as a change signature.
* Rules are pre-processed at load time.
* Writes are consolidated per player and batched on one dedicated thread, with
  a hard memory bound, exponential backoff and rate-limited logging.
* Bounded, cached, grouped reads; the ranking is read in index order.

## Build

```bash
./mvnw clean package
```

The plugin JAR is written to `target/GearScore-3.0.0.jar`. MariaDB Connector/J
and the connection pool are shaded into it.

```bash
./mvnw test
```

```bash
./mvnw test -Pbench
```

```bash
./mvnw test -Pmariadb "-Dgearscore.test.jdbc=jdbc:mariadb://127.0.0.1:3306/gstest?user=USER&password=PASSWORD"
```

The second command runs the simulated load test; the third runs the whole suite
plus query-plan and timing checks against a real MariaDB test database.

## Testing status

* Automated tests cover the scoring rules, enchantment applicability, the
  evaluation queue and pipeline, the query-server refresh, placeholders, the
  configuration updater and the persistence layer (on H2 in MySQL mode).
* Version 1.1.0's persistence layer, unchanged since, also passed on a real
  MariaDB 10.4.32, including the schema upgrade and query plans.
* A simulated load test covers 50, 100 and 200 players.
* The plugin has **not** been run on a live Paper server, and the TAB sidebar
  has **not** been verified on a real proxy. See the performance and TAB
  documents for what was and was not checked.

## Documentation

The server documentation is written in Portuguese.

* [Instalação](docs/INSTALACAO.md) — setup, commands, permissions, placeholders, upgrading
* [Sidebar do TAB](docs/TAB.md) — exact TAB configuration for the proxy sidebar
* [Fórmula e balanceamento](docs/FORMULA.md) — formula, default tables, worked examples
* [Banco de dados](docs/BANCO.md) — schema, migrations, ranking order, query plans
* [Desempenho](docs/DESEMPENHO.md) — design, measurements, how to measure on a server
* [Changelog](CHANGELOG.md)
