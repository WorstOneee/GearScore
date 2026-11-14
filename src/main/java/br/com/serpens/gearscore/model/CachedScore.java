package br.com.serpens.gearscore.model;

import br.com.serpens.gearscore.score.ScoreResult;

/**
 * What commands and placeholders read for an online player.
 *
 * @param detail per-item breakdown; null when the score was only loaded from the database
 */
public record CachedScore(PlayerRecord record, ScoreResult detail) {
}
