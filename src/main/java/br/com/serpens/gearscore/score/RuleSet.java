package br.com.serpens.gearscore.score;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable, pre-processed scoring rules. Built once per configuration load so that
 * a calculation is only array and hash lookups.
 */
public final class RuleSet {

    public enum Incompatible {
        /** Only the best enchantment of a conflicting group counts. */
        HIGHEST,
        /** Every enchantment counts, even when vanilla would not allow the combination. */
        SUM,
        /** No enchantment of a conflicting group counts. */
        ZERO
    }

    private final int version;
    private final Map<String, GearDef> gear;
    private final Map<String, EnchantRule>[] enchantRules;
    private final double[] categoryMultipliers;
    private final EnchantRule.OverLimit overLimit;
    private final Incompatible incompatible;
    private final Set<String>[] incompatibleGroups;
    private final Tier[] tiers;
    private final List<CustomItemRule> customItems;
    private final boolean hotbarWeapons;
    private final boolean offhandWeapon;

    public RuleSet(int version,
                   Map<String, GearDef> gear,
                   Map<String, EnchantRule>[] enchantRules,
                   double[] categoryMultipliers,
                   EnchantRule.OverLimit overLimit,
                   Incompatible incompatible,
                   Set<String>[] incompatibleGroups,
                   Tier[] tiers,
                   List<CustomItemRule> customItems,
                   boolean hotbarWeapons,
                   boolean offhandWeapon) {
        this.version = version;
        this.gear = Map.copyOf(gear);
        this.enchantRules = enchantRules;
        this.categoryMultipliers = categoryMultipliers;
        this.overLimit = overLimit;
        this.incompatible = incompatible;
        this.incompatibleGroups = incompatibleGroups;
        this.tiers = tiers;
        this.customItems = List.copyOf(customItems);
        this.hotbarWeapons = hotbarWeapons;
        this.offhandWeapon = offhandWeapon;
    }

    public int version() {
        return version;
    }

    /** Gear definitions keyed by lower-case material name. */
    public Map<String, GearDef> gear() {
        return gear;
    }

    public Map<String, EnchantRule> enchantRules(GearType type) {
        return enchantRules[type.ordinal()];
    }

    public double categoryMultiplier(EnchantCategory category) {
        return categoryMultipliers[category.ordinal()];
    }

    public EnchantRule.OverLimit overLimit() {
        return overLimit;
    }

    public Incompatible incompatible() {
        return incompatible;
    }

    public Set<String>[] incompatibleGroups() {
        return incompatibleGroups;
    }

    public List<CustomItemRule> customItems() {
        return customItems;
    }

    public boolean hotbarWeapons() {
        return hotbarWeapons;
    }

    public boolean offhandWeapon() {
        return offhandWeapon;
    }

    public Tier[] tiers() {
        return tiers;
    }

    /** Tiers are sorted by descending minimum. */
    public Tier tierFor(int score) {
        for (Tier tier : tiers) {
            if (score >= tier.min()) {
                return tier;
            }
        }
        return Tier.NONE;
    }

    public Tier tierById(String id) {
        for (Tier tier : tiers) {
            if (tier.id().equals(id)) {
                return tier;
            }
        }
        return null;
    }
}
