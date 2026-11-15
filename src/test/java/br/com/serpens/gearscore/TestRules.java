package br.com.serpens.gearscore;

import br.com.serpens.gearscore.config.RuleParser;
import br.com.serpens.gearscore.score.GearDef;
import br.com.serpens.gearscore.score.GearSnapshot;
import br.com.serpens.gearscore.score.ItemSnapshot;
import br.com.serpens.gearscore.score.RuleSet;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Builds rule sets from the shipped config.yml and snapshots without a running server.
 */
public final class TestRules {

    private TestRules() {
    }

    public static String resource(String name) {
        try (InputStream in = TestRules.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static YamlConfiguration defaultConfig() {
        try (Reader reader = new InputStreamReader(
                TestRules.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static RuleSet defaults() {
        return with(config -> {
        });
    }

    public static RuleSet with(Consumer<YamlConfiguration> changes) {
        YamlConfiguration config = defaultConfig();
        changes.accept(config);
        RuleParser parser = new RuleParser(config, null);
        RuleSet rules = parser.parse();
        if (!parser.warnings().isEmpty()) {
            throw new IllegalStateException("config warnings: " + parser.warnings());
        }
        return rules;
    }

    /**
     * @param enchants alternating enchantment name and level, e.g. "protection", 4
     */
    public static ItemSnapshot item(RuleSet rules, String material, Object... enchants) {
        GearDef def = rules.gear().get(material);
        if (def == null) {
            throw new IllegalArgumentException("unknown gear " + material);
        }
        Map<String, Integer> sorted = new TreeMap<>();
        for (int i = 0; i < enchants.length; i += 2) {
            String key = (String) enchants[i];
            sorted.put(key.indexOf(':') < 0 ? "minecraft:" + key : key, (Integer) enchants[i + 1]);
        }
        String[] keys = sorted.keySet().toArray(new String[0]);
        int[] levels = sorted.values().stream().mapToInt(Integer::intValue).toArray();
        return new ItemSnapshot(def, keys, levels, null);
    }

    /** Full armor set of one material with the same enchantments on every piece. */
    public static ItemSnapshot[] armorSet(RuleSet rules, String material, Object... enchants) {
        return new ItemSnapshot[]{
                item(rules, material + "_helmet", enchants),
                item(rules, material + "_chestplate", enchants),
                item(rules, material + "_leggings", enchants),
                item(rules, material + "_boots", enchants)
        };
    }

    /** An inventory holding the given items; null entries stand for empty slots. */
    public static GearSnapshot gear(ItemSnapshot[] set, ItemSnapshot... more) {
        List<ItemSnapshot> items = new ArrayList<>();
        if (set != null) {
            for (ItemSnapshot item : set) {
                if (item != null) {
                    items.add(item);
                }
            }
        }
        for (ItemSnapshot item : more) {
            if (item != null) {
                items.add(item);
            }
        }
        return new GearSnapshot(items.toArray(new ItemSnapshot[0]), items.size());
    }

    public static GearSnapshot carrying(ItemSnapshot... items) {
        return gear(null, items);
    }
}
