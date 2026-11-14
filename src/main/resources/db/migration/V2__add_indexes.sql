-- Ranking: ORDER BY score DESC, updated_at ASC LIMIT n, and COUNT(*) WHERE score > ?.
CREATE INDEX ${prefix}idx_score ON ${prefix}players (score DESC, updated_at);

-- Offline lookup by last known name.
CREATE INDEX ${prefix}idx_name ON ${prefix}players (last_name);
