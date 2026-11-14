package br.com.serpens.gearscore.config;

import br.com.serpens.gearscore.score.CustomItemRule;
import br.com.serpens.gearscore.score.EnchantCategory;
import br.com.serpens.gearscore.score.EnchantRule;
import br.com.serpens.gearscore.score.GearDef;
import br.com.serpens.gearscore.score.GearType;
import br.com.serpens.gearscore.score.RuleSet;
import br.com.serpens.gearscore.score.Tier;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * Turns config.yml into a {@link RuleSet}. All text parsing happens here, once per load.
 */
public final class RuleParser {

    private final ConfigurationSection root;
    private final ToIntFunction<String> vanillaMaxLevel;
    private final List<String> warnings = new ArrayList<>();

    /**
     * @param vanillaMaxLevel returns the vanilla maximum level for an enchantment key, or 0 when unknown; may be null
     */
    public RuleParser(ConfigurationSection root, ToIntFunction<String> vanillaMaxLevel) {
        this.root = root;
        this.vanillaMaxLevel = vanillaMaxLevel;
    }

    public List<String> warnings() {
        return warnings;
    }

    public RuleSet parse() {
        Set<String> ignored = new HashSet<>();
        for (String item : root.getStringList("evaluation.ignored-items")) {
            ignored.add(item.trim().toLowerCase(Locale.ROOT));
        }

        Map<String, GearDef> gear = new HashMap<>();
        parseArmor(gear);
        parseWeapons(gear);
        gear.keySet().removeAll(ignored);

        return new RuleSet(
                root.getInt("rules-version", 1),
                gear,
                parseEnchantRules(),
                parseCategoryMultipliers(),
                parseEnum(EnchantRule.OverLimit.class, "enchantments.above-max-level", EnchantRule.OverLimit.CAP),
                parseEnum(RuleSet.Incompatible.class, "enchantments.incompatible.mode", RuleSet.Incompatible.HIGHEST),
                parseGroups(),
                parseTiers(),
                parseCustomItems(),
                root.getBoolean("evaluation.weapon-slots.hotbar", true),
                root.getBoolean("evaluation.weapon-slots.offhand", true));
    }

    private void parseArmor(Map<String, GearDef> gear) {
        ConfigurationSection materials = root.getConfigurationSection("armor.materials");
        if (materials != null) {
            for (String material : materials.getKeys(false)) {
                ConfigurationSection slots = materials.getConfigurationSection(material);
                if (slots == null) {
                    warnings.add("armor.materials." + material + " must be a section");
                    continue;
                }
                for (String slot : slots.getKeys(false)) {
                    GearType type = GearType.byId(slot);
                    if (type == null || !type.isArmor()) {
                        warnings.add("armor.materials." + material + "." + slot + ": unknown armor slot");
                        continue;
                    }
                    String name = lower(material) + "_" + type.id();
                    gear.put(name, new GearDef(name, type, slots.getDouble(slot) * armorMultiplier(type)));
                }
            }
        }
        ConfigurationSection special = root.getConfigurationSection("armor.special-items");
        if (special != null) {
            for (String item : special.getKeys(false)) {
                ConfigurationSection section = special.getConfigurationSection(item);
                GearType type = section == null ? null : GearType.byId(section.getString("slot"));
                if (type == null || !type.isArmor()) {
                    warnings.add("armor.special-items." + item + ": missing or invalid slot");
                    continue;
                }
                String name = lower(item);
                gear.put(name, new GearDef(name, type, section.getDouble("base") * armorMultiplier(type)));
            }
        }
    }

    private double armorMultiplier(GearType type) {
        return root.getDouble("armor.slot-multipliers." + type.id(), 1.0);
    }

    private void parseWeapons(Map<String, GearDef> gear) {
        ConfigurationSection weapons = root.getConfigurationSection("weapons");
        if (weapons == null) {
            return;
        }
        for (GearType type : GearType.values()) {
            if (type.isArmor() || !weapons.contains(type.id())) {
                continue;
            }
            double multiplier = weapons.getDouble("type-multipliers." + type.id(), 1.0);
            ConfigurationSection materials = weapons.getConfigurationSection(type.id());
            if (materials == null) {
                gear.put(type.id(), new GearDef(type.id(), type, weapons.getDouble(type.id()) * multiplier));
                continue;
            }
            for (String material : materials.getKeys(false)) {
                String name = lower(material) + "_" + type.id();
                gear.put(name, new GearDef(name, type, materials.getDouble(material) * multiplier));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, EnchantRule>[] parseEnchantRules() {
        ConfigurationSection rules = root.getConfigurationSection("enchantments.rules");
        Map<String, EnchantRule>[] byType = new Map[GearType.values().length];
        Map<String, Map<String, EnchantRule>> groups = new HashMap<>();
        if (rules != null) {
            for (String group : rules.getKeys(false)) {
                String id = lower(group);
                if (!id.equals("any") && !id.equals("armor") && !id.equals("weapon") && GearType.byId(id) == null) {
                    warnings.add("enchantments.rules." + group + ": unknown equipment group");
                    continue;
                }
                groups.put(id, parseGroupRules(rules.getConfigurationSection(group), group));
            }
        }
        for (GearType type : GearType.values()) {
            Map<String, EnchantRule> merged = new HashMap<>();
            merge(merged, groups.get("any"));
            merge(merged, groups.get(type.isArmor() ? "armor" : "weapon"));
            merge(merged, groups.get(type.id()));
            byType[type.ordinal()] = merged;
        }
        return byType;
    }

    private static void merge(Map<String, EnchantRule> target, Map<String, EnchantRule> source) {
        if (source != null) {
            target.putAll(source);
        }
    }

    private Map<String, EnchantRule> parseGroupRules(ConfigurationSection section, String group) {
        Map<String, EnchantRule> result = new HashMap<>();
        if (section == null) {
            return result;
        }
        for (String rawKey : section.getKeys(false)) {
            String key = enchantKey(rawKey);
            String path = "enchantments.rules." + group + "." + rawKey;
            Object value = section.get(rawKey);
            if (value instanceof Number number) {
                result.put(key, new EnchantRule(key, EnchantCategory.COMBAT, null, number.doubleValue(), maxLevel(key, 0, null)));
                continue;
            }
            if (!(value instanceof ConfigurationSection rule)) {
                warnings.add(path + ": expected a number or a section");
                continue;
            }
            EnchantCategory category = EnchantCategory.byId(rule.getString("category", "combat"));
            if (category == null) {
                warnings.add(path + ".category: unknown category, using combat");
                category = EnchantCategory.COMBAT;
            }
            double[] levels = null;
            List<?> table = rule.getList("levels");
            if (table != null && !table.isEmpty()) {
                levels = new double[table.size()];
                for (int i = 0; i < levels.length; i++) {
                    if (table.get(i) instanceof Number number) {
                        levels[i] = number.doubleValue();
                    } else {
                        warnings.add(path + ".levels: entry " + (i + 1) + " is not a number");
                    }
                }
            }
            double perLevel = rule.getDouble("per-level", 0.0);
            result.put(key, new EnchantRule(key, category, levels, perLevel,
                    maxLevel(key, rule.getInt("max-level", 0), levels)));
        }
        return result;
    }

    private int maxLevel(String key, int configured, double[] levels) {
        if (configured > 0) {
            return configured;
        }
        if (levels != null) {
            return levels.length;
        }
        return vanillaMaxLevel == null ? 0 : Math.max(0, vanillaMaxLevel.applyAsInt(key));
    }

    private double[] parseCategoryMultipliers() {
        double[] multipliers = new double[EnchantCategory.values().length];
        for (EnchantCategory category : EnchantCategory.values()) {
            multipliers[category.ordinal()] =
                    root.getDouble("enchantments.category-multipliers." + category.id(), 1.0);
        }
        return multipliers;
    }

    @SuppressWarnings("unchecked")
    private Set<String>[] parseGroups() {
        List<Set<String>> groups = new ArrayList<>();
        List<?> raw = root.getList("enchantments.incompatible.groups");
        if (raw != null) {
            for (Object entry : raw) {
                if (!(entry instanceof List<?> list)) {
                    warnings.add("enchantments.incompatible.groups: every group must be a list");
                    continue;
                }
                Set<String> group = new HashSet<>();
                for (Object key : list) {
                    group.add(enchantKey(String.valueOf(key)));
                }
                if (group.size() > 1) {
                    groups.add(Set.copyOf(group));
                }
            }
        }
        return groups.toArray(new Set[0]);
    }

    private Tier[] parseTiers() {
        List<Tier> tiers = new ArrayList<>();
        for (Map<?, ?> entry : root.getMapList("tiers")) {
            Object id = entry.get("id");
            Object min = entry.get("min");
            if (id == null || !(min instanceof Number number)) {
                warnings.add("tiers: every tier needs an id and a numeric min");
                continue;
            }
            Object name = entry.get("name");
            tiers.add(new Tier(String.valueOf(id), name == null ? String.valueOf(id) : String.valueOf(name),
                    number.intValue()));
        }
        tiers.sort(Comparator.comparingInt(Tier::min).reversed());
        return tiers.toArray(new Tier[0]);
    }

    private List<CustomItemRule> parseCustomItems() {
        List<CustomItemRule> result = new ArrayList<>();
        if (!root.getBoolean("custom-items.enabled", false)) {
            return result;
        }
        ConfigurationSection rules = root.getConfigurationSection("custom-items.rules");
        if (rules == null) {
            return result;
        }
        for (String id : rules.getKeys(false)) {
            ConfigurationSection rule = rules.getConfigurationSection(id);
            String path = "custom-items.rules." + id;
            if (rule == null) {
                continue;
            }
            String key = rule.getString("key", "");
            if (key.indexOf(':') <= 0) {
                warnings.add(path + ".key: a namespaced key such as \"plugin:id\" is required");
                continue;
            }
            String type = lower(rule.getString("type", "any"));
            if (!Set.of("string", "integer", "byte", "any").contains(type)) {
                warnings.add(path + ".type: expected string, integer, byte or any");
                continue;
            }
            GearType gearType = null;
            if (rule.contains("gear-type")) {
                gearType = GearType.byId(rule.getString("gear-type"));
                if (gearType == null) {
                    warnings.add(path + ".gear-type: unknown gear type");
                    continue;
                }
            }
            result.add(new CustomItemRule(id, lower(key), type, rule.getString("value", ""), gearType,
                    rule.contains("base") ? rule.getDouble("base") : Double.NaN,
                    rule.getDouble("bonus", 0.0),
                    rule.getDouble("multiplier", 1.0)));
        }
        return result;
    }

    private <E extends Enum<E>> E parseEnum(Class<E> type, String path, E fallback) {
        String value = root.getString(path);
        if (value == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            warnings.add(path + ": unknown value \"" + value + "\", using " + fallback.name().toLowerCase(Locale.ROOT));
            return fallback;
        }
    }

    private static String enchantKey(String raw) {
        String key = lower(raw);
        return key.indexOf(':') < 0 ? "minecraft:" + key : key;
    }

    private static String lower(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
