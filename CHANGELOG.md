# Changelog

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
