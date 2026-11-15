package br.com.serpens.gearscore.hook;

import br.com.serpens.gearscore.model.CachedScore;
import br.com.serpens.gearscore.model.PlayerRecord;

import java.util.Locale;
import java.util.UUID;

/**
 * Turns a placeholder name into text using only what is already in memory.
 *
 * It never queries the database, reads an inventory, recalculates, schedules a task or
 * waits, whatever the caller (PlaceholderAPI, TAB through TAB-Bridge, holograms) and
 * however often it asks. While a value is not available it answers with the configured
 * fallback, never with an error or the raw placeholder.
 */
public final class PlaceholderResolver {

    /** Memory-only views the resolver reads from. */
    public interface Source {
        CachedScore cached(UUID id);

        String tierPlain(String tierId);

        String tierColored(String tierId);

        /** 1-based ranking position already held in memory, or a value below 1. */
        int position(UUID id);

        String fallback();
    }

    private final Source source;

    public PlaceholderResolver(Source source) {
        this.source = source;
    }

    /**
     * @return the text for a known placeholder, or null when the name is not one of ours
     */
    public String resolve(UUID id, String params) {
        String name = params.toLowerCase(Locale.ROOT);
        boolean known = switch (name) {
            case "score", "total", "armor", "weapon", "tier", "tier_colored", "tier_id", "position", "rank" -> true;
            default -> false;
        };
        if (!known) {
            return null;
        }
        CachedScore cached = id == null ? null : source.cached(id);
        if (cached == null) {
            return source.fallback();
        }
        PlayerRecord record = cached.record();
        return switch (name) {
            case "score", "total" -> Integer.toString(record.score());
            case "armor" -> Integer.toString(record.armorScore());
            case "weapon" -> Integer.toString(record.weaponScore());
            case "tier" -> source.tierPlain(record.tier());
            case "tier_colored" -> source.tierColored(record.tier());
            case "tier_id" -> record.tier();
            default -> {
                int position = source.position(record.uuid());
                yield position > 0 ? Integer.toString(position) : source.fallback();
            }
        };
    }
}
