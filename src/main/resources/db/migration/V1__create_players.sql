-- One row per player: the last equipment evaluated by an official source server.
-- updated_at is epoch milliseconds (UTC).
CREATE TABLE IF NOT EXISTS ${prefix}players (
    uuid          CHAR(36)    NOT NULL,
    last_name     VARCHAR(32) NOT NULL,
    score         INT         NOT NULL DEFAULT 0,
    armor_score   INT         NOT NULL DEFAULT 0,
    weapon_score  INT         NOT NULL DEFAULT 0,
    tier          VARCHAR(32) NOT NULL DEFAULT '',
    source_server VARCHAR(32) NOT NULL DEFAULT '',
    rules_version INT         NOT NULL DEFAULT 0,
    updated_at    BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
