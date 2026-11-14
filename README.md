# GearScore

Equipment strength score for the Serpens Minecraft network, by **Zwo**.

GearScore rates what a player is wearing and wielding — armor, weapon, materials
and, above all, enchantments and their levels — and turns it into a single
number with a tier, a ranking and PlaceholderAPI placeholders.

The number is an **estimate of gear strength**. It does not measure skill,
potions or positioning and is not a prediction of who wins a fight.

* Paper 1.21.11 · Java 21 · MariaDB/MySQL · works behind Velocity
* Optional: PlaceholderAPI

## How the score is built

```
GearScore = armor base + armor enchantments + best weapon base + best weapon enchantments
```

* Armor: the four equipped pieces.
* Weapon: the single best one found in the hotbar or off hand. Carrying several
  copies of the same sword or mace never raises the score, and the weapon that
  was counted is shown to the player.
* Every enchantment has its own points per level and per equipment type, as a
  table or a linear value. Enchantments are grouped by relevance (combat,
  conditional, mob-only, mobility, utility, curses), each with a global
  multiplier.
* Enchantments are matched by namespaced key. Unknown ones are worth zero until
  configured.
* Levels above the vanilla limit, incompatible combinations and curses each
  have a configurable policy.
* Custom items are recognised only through configured PersistentDataContainer
  keys. Names, lore, CustomModelData and a visual glint never score.

The plugin only reads items. It never changes attributes, enchantments or game
mechanics.

With the default configuration a full netherite set goes from 265 points
unenchanted to 973 with Protection IV, Unbreaking III and Mending.

## Network behaviour

Each server declares its name and the list of official sources:

```yaml
server:
  name: "newera"
  official-sources: ["newera"]
```

Official sources evaluate equipment and persist the result. Every other server
(hub, events) never reads inventories and never writes; it only reads the
official score from the database. Temporary kits elsewhere cannot overwrite it.
Nothing is installed on the proxy.

The ranking reflects the last evaluated equipment of each player, with its
update time. It is not an all-time best.

## Commands

| Command | Description | Permission |
|---|---|---|
| `/gearscore`, `/gs` | Your score | `gearscore.use` |
| `/gs ver <player>` | Another player's score | `gearscore.view.others` |
| `/gs detalhes [player]` | Per-item and per-enchantment breakdown | `gearscore.details`, `gearscore.details.others` |
| `/gs top [page]` | Paginated ranking | `gearscore.top` |
| `/gs reload` | Reload configuration | `gearscore.admin.reload` |
| `/gs diag` | Performance counters | `gearscore.admin.diag` |

## Placeholders

`%gearscore_total%`, `%gearscore_armor%`, `%gearscore_weapon%`,
`%gearscore_tier%`, `%gearscore_tier_colored%`, `%gearscore_tier_id%`,
`%gearscore_position%`

Placeholders are served from memory. The plugin supplies values to TAB and
hologram plugins and does not drive those displays itself.

## Performance design

* No repeating scan of players or inventories, no movement events.
* Change events are filtered in constant time (amount and durability changes
  are dropped), debounced per player and processed under a per-tick limit by a
  task that only exists while work is queued.
* Only 4 armor slots, the hotbar and the off hand are read, on the server
  thread, into a minimal snapshot that doubles as a change signature.
* Rules are pre-processed at load time.
* Online lookups and placeholders never touch SQL or an inventory.
* Writes are consolidated per player and batched on one dedicated thread, with
  a bounded queue, exponential backoff and rate-limited logging.
* Bounded, cached reads for offline lookups and the ranking.

## Build

```bash
./mvnw clean package
```

The plugin JAR is written to `target/GearScore-1.0.0.jar`.

```bash
./mvnw test
```

```bash
./mvnw test -Pbench
```

The second command runs the simulated load test.

## Testing status

Automated tests cover the scoring rules, the evaluation pipeline and the
persistence layer (against H2 in MySQL compatibility mode), and a simulated
load test covers 50, 100 and 200 players. The plugin has **not** been measured
on a live Paper server or against a real MariaDB instance; see the performance
document for what the numbers do and do not show.

## Documentation

The server documentation is written in Portuguese.

* [Instalação](docs/INSTALACAO.md) — setup, commands, permissions, placeholders
* [Fórmula e balanceamento](docs/FORMULA.md) — formula, default tables, worked examples
* [Banco de dados](docs/BANCO.md) — schema, migrations, queries, failure handling
* [Desempenho](docs/DESEMPENHO.md) — design, measurements, how to measure on a server
