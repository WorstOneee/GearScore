package br.com.serpens.gearscore.hook;

import br.com.serpens.gearscore.GearScorePlugin;
import br.com.serpens.gearscore.model.CachedScore;
import br.com.serpens.gearscore.storage.LookupService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.util.UUID;

/**
 * PlaceholderAPI expansion. With TAB on the proxy, TAB-Bridge on this server resolves these
 * through PlaceholderAPI and forwards the values, so the same placeholders work in the
 * proxy's sidebar. All of them only read memory; see {@link PlaceholderResolver}.
 *
 * <pre>
 * %gearscore_score%         total score (also %gearscore_total%)
 * %gearscore_tier%          tier name, plain text
 * %gearscore_tier_colored%  tier name with legacy colour codes
 * %gearscore_tier_id%       tier id from config.yml
 * %gearscore_armor%         armor part of the score
 * %gearscore_weapon%        weapon part of the score
 * %gearscore_position%      ranking position
 * </pre>
 */
public final class GearScoreExpansion extends PlaceholderExpansion {

    private final GearScorePlugin plugin;
    private final PlaceholderResolver resolver;

    public GearScoreExpansion(GearScorePlugin plugin) {
        this.plugin = plugin;
        this.resolver = new PlaceholderResolver(new PlaceholderResolver.Source() {
            @Override
            public CachedScore cached(UUID id) {
                return plugin.cached(id);
            }

            @Override
            public String tierPlain(String tierId) {
                return plugin.tierView(tierId).plain();
            }

            @Override
            public String tierColored(String tierId) {
                return plugin.tierView(tierId).legacy();
            }

            @Override
            public int position(UUID id) {
                LookupService lookups = plugin.lookups();
                return lookups == null ? -1 : lookups.position(id);
            }

            @Override
            public String fallback() {
                return plugin.settings().fallback();
            }
        });
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
        return resolver.resolve(player == null ? null : player.getUniqueId(), params);
    }
}
