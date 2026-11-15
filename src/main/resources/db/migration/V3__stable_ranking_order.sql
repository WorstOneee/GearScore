-- Ranking order: score DESC, updated_at ASC, uuid ASC.
-- rank_score is the negated score, so the whole order is ascending and one plain index
-- serves it on every MariaDB/MySQL version (descending index parts are ignored before
-- MariaDB 10.8). The column is generated: it can never disagree with score.
ALTER TABLE ${prefix}players ADD COLUMN rank_score INT GENERATED ALWAYS AS (-score);

CREATE INDEX ${prefix}idx_rank ON ${prefix}players (rank_score, updated_at, uuid);

-- Superseded by idx_rank.
DROP INDEX ${prefix}idx_score ON ${prefix}players;
