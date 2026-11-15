# Changelog

## 3.0.1

### Fixed

* **Lost writes when the queue was full.** The engine treated a result as
  persisted as soon as it was handed to the write queue. If the queue then
  dropped it, a player whose equipment did not change was never written again,
  leaving the database, the ranking and the query servers stale.

  A result is now tracked as calculated, queued or confirmed, and only counts
  as persisted after its transaction commits. A full queue refuses results of
  online players instead of dropping them, the engine is told about every
  entry that is given up, and owed results are submitted again from memory by
  a bounded once-a-second step, with no inventory read and no new event
  needed. Sequence numbers keep an old confirmation, or one from an earlier
  session, from settling a newer result.
* Players who leave with a refused or pending result are still written.
* The upsert ignores a result older than the stored row, so a late retry or a
  restored batch can never undo a newer write.

### Added

* Unconfirmed writes are saved to `pending-writes.dat` while the database is
  down and at shutdown, and are written on the next start.
* `/gs diag` shows refused, dropped, recovered and lost writes and the state of
  the pending file.

Scoring, balance, public messages, the TAB integration, configuration files
and the database schema are unchanged.

## 3.0.0

### Changed

* **The score is the best set the player can assemble.** The plugin still looks
  at the 36 inventory slots, the 4 armor slots and the off hand, but now keeps
  only the best helmet, chestplate, leggings, boots and weapon, each chosen by
  its full score (material, enchantments, custom rule), and adds those five. A
  piece kept in the inventory can beat the worn one; the best weapon is chosen
  across all weapon types. Items that are not selected add nothing, so
  duplicates and spare gear no longer raise the score.
* `/gs detalhes` shows the five selected pieces with their points.
* **Tiers** return to the single-set scale (0 / 150 / 350 / 600 / 900 / 1200 /
  1400) and `rules-version` is now 3.
* The `display` section (`details-max-items`) was removed.

Enchantment weights, base values and the remaining messages are unchanged from
2.0.0.

### Upgrading

Replace the JAR on every server and restart. `config.yml` and `messages.yml`
are updated on first start with a backup; customised sections are kept, so
review `tiers` if the console lists it. See `docs/INSTALACAO.md`.

## 2.0.0

### Changed

* **Scoring covers the whole inventory.** The score is now the sum of every
  valid piece of gear in the 36 inventory slots, the 4 armor slots and the off
  hand, each slot once. The "single best weapon" rule is gone; equal items in
  different slots each count and armor kept in the inventory scores. Ender
  chest, open containers, cursor, crafting grid and container contents are not
  read. Snapshots no longer depend on where items sit, so moving or equipping
  gear causes neither a recalculation nor a write.
* **Enchantments only score where they belong.** Slot-specific enchantments
  moved to their own groups (`helmet`, `leggings`, `boots`), so an enchantment
  forced onto gear that cannot hold it is worth zero. Scoring rules are read
  from the file as written, without bundled defaults filling in removed
  entries. Looting, Efficiency and Infinity weigh less.
* **Tiers** were rescaled for the larger totals and `rules-version` is now 2.
* **Short messages.** `/gs` and the new `/gs <player>` answer with one line:
  player, score and tier. Breakdowns moved to `/gs detalhes`, which lists the
  highest-scoring items with per-enchantment hover text. The origin server and
  rules version are shown to administrators only. Default colours avoid dark
  grey.
* The placeholder fallback is now `—`.

### Added

* `config.yml` and `messages.yml` are updated automatically on first start:
  untouched sections take the new defaults, customised sections are kept as
  written, and a timestamped backup is saved first.
* `docs/TAB.md`: the proxy TAB sidebar through TAB-Bridge.

### Upgrading

Replace the JAR on every server and restart; review the sections the console
reports as kept. Scores of players who have not joined the official server
since the update stay on the old scale until they do. See
`docs/INSTALACAO.md`.

## 1.1.0

### Fixed

* **Stale score on query servers.** Servers that are not an official source
  loaded a player's score only on join, often before the origin server had
  flushed its write, and kept that value for the whole session. They now read
  again shortly after the join and then on a shared interval, in grouped
  primary-key queries. Missing rows and failed reads are picked up later, late
  answers never replace newer data or repopulate an ended session, and reads
  resume by themselves after a database outage.
* **Ranking ties.** A tied player could be 2nd in the cached ranking and 1st by
  the individual query. There is now a single order — score descending, earlier
  update first, then UUID — used by `/gs top`, the ranking cache, the
  individual position and the position placeholder.
* **Placeholders starting queries.** `%gearscore_position%` could start an
  asynchronous query when requested. Every placeholder now only reads memory;
  positions are refreshed by a separate clocked, batched step that runs only
  while the placeholder is in use.
* **Evaluation queue.** Removing a player left an entry behind that could fire
  a later request for the same player before its debounce elapsed. Each request
  now carries its own token, and entries are ordered by due tick, so a changed
  debounce cannot hold back or reorder evaluations.
* **Write queue bound.** A failed batch put back in the queue could exceed
  `writes.max-pending`. The limit now holds on that path too, the restored rows
  never replace a newer state of the same player, and the oldest data is what
  gets dropped.
* **Test `reloadReevaluatesOnlinePlayersGradually`** depended on which entries
  were taken first because one tracked player had no snapshot.

### Changed

* Ranking reads use a generated `rank_score` column and the `idx_rank` index,
  which serve the ordering on every MariaDB version. On MariaDB before 10.8 the
  previous query sorted the whole table.
* The `driver` option in `database.yml` was removed. Its `mysql` value relied
  on a driver that is not part of the plugin JAR. MariaDB Connector/J, shaded
  into the JAR, is always used.
* `ranking.position-cache-seconds` was replaced by
  `ranking.position-refresh-seconds`.
* New `query-refresh` section in `config.yml`.

### Upgrading

Replace the JAR on every server and restart. Database migration 3 runs on the
first connection and needs the `ALTER` and `INDEX` privileges. Existing
configuration files keep working. See `docs/INSTALACAO.md`.

## 1.0.0

First release.
