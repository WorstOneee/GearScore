package br.com.serpens.gearscore.hook;

import br.com.serpens.gearscore.GearScorePlugin;
import br.com.serpens.gearscore.model.CachedScore;
import br.com.serpens.gearscore.model.PlayerRecord;
import br.com.serpens.gearscore.storage.LookupService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.util.Locale;

/**
 * PlaceholderAPI expansion. Every placeholder, the ranking position included, is answered
 * from memory: no SQL, no task, no inventory access, no recalculation, no waiting. The caches
 * behind them are refreshed on the plugin's own schedule, so how often TAB, holograms or
 * scoreboards ask has no effect on the database.
 *
 * <pre>
 * %gearscore_total%         total score
 * %gearscore_armor%         armor score
 * %gearscore_weapon%        weapon score
 * %gearscore_tier%          tier name, plain text
 * %gearscore_tier_colored%  tier name with legacy colour codes
 * %gearscore_tier_id%       tier id from config.yml
 * %gearscore_position%      ranking position
 * </pre>
 */
public final class GearScoreExpansion extends PlaceholderExpansion {

    private final GearScorePlugin plugin;

    public GearScoreExpansion(GearScorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "gearscore";
    }

    @Override
    public String getAuthor() {
        return "Zwo";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        String fallback = plugin.settings().fallback();
        if (player == null) {
            return fallback;
        }
        CachedScore cached = plugin.cached(player.getUniqueId());
        if (cached == null) {
            return fallback;
        }
        PlayerRecord record = cached.record();
        switch (params.toLowerCase(Locale.ROOT)) {
            case "total", "score" -> {
                return Integer.toString(record.score());
            }
            case "armor" -> {
                return Integer.toString(record.armorScore());
            }
            case "weapon" -> {
                return Integer.toString(record.weaponScore());
            }
            case "tier" -> {
                return plugin.tierView(record.tier()).plain();
            }
            case "tier_colored" -> {
                return plugin.tierView(record.tier()).legacy();
            }
            case "tier_id" -> {
                return record.tier();
            }
            case "position", "rank" -> {
                LookupService lookups = plugin.lookups();
                if (lookups == null) {
                    return fallback;
                }
                int position = lookups.position(record.uuid());
                return position > 0 ? Integer.toString(position) : fallback;
            }
            default -> {
                return null;
            }
        }
    }
}
