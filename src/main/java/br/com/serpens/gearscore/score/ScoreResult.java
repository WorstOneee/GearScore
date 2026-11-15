package br.com.serpens.gearscore.score;

/**
 * Outcome of one evaluation, including the per-item breakdown shown by the details command.
 * total = armorBase + armorEnchant + weaponBase + weaponEnchant, summed over every valid item.
 */
public final class ScoreResult {

    public enum EnchantStatus {
        COUNTED,
        /** Level above the configured limit, scored as the limit. */
        CAPPED,
        /** Level above the configured limit, scored as zero. */
        OVER_LIMIT,
        /** Dropped because it conflicts with a better enchantment on the same item. */
        INCOMPATIBLE,
        /** No rule configured for this enchantment on this gear type. */
        UNKNOWN
    }

    public record EnchantScore(String key, int level, double points, EnchantStatus status) {
    }

    public record ItemScore(ItemSnapshot item, double base, double enchant, EnchantScore[] enchants) {

        public double total() {
            return base + enchant;
        }
    }

    private final int armorBase;
    private final int armorEnchant;
    private final int weaponBase;
    private final int weaponEnchant;
    private final int armorCount;
    private final int weaponCount;
    private final ItemScore[] items;
    private final Tier tier;
    private final int rulesVersion;

    /**
     * @param items every scored item, highest score first
     */
    public ScoreResult(int armorBase, int armorEnchant, int weaponBase, int weaponEnchant,
                       int armorCount, int weaponCount, ItemScore[] items, RuleSet rules) {
        this.armorBase = armorBase;
        this.armorEnchant = armorEnchant;
        this.weaponBase = weaponBase;
        this.weaponEnchant = weaponEnchant;
        this.armorCount = armorCount;
        this.weaponCount = weaponCount;
        this.items = items;
        this.tier = rules.tierFor(total());
        this.rulesVersion = rules.version();
    }

    public int total() {
        return armorBase + armorEnchant + weaponBase + weaponEnchant;
    }

    public int armorBase() {
        return armorBase;
    }

    public int armorEnchant() {
        return armorEnchant;
    }

    public int armorScore() {
        return armorBase + armorEnchant;
    }

    public int weaponBase() {
        return weaponBase;
    }

    public int weaponEnchant() {
        return weaponEnchant;
    }

    public int weaponScore() {
        return weaponBase + weaponEnchant;
    }

    /** Armor pieces counted, worn or carried. */
    public int armorCount() {
        return armorCount;
    }

    public int weaponCount() {
        return weaponCount;
    }

    public int itemCount() {
        return items.length;
    }

    /** Scored items, highest score first. */
    public ItemScore item(int index) {
        return items[index];
    }

    public Tier tier() {
        return tier;
    }

    public int rulesVersion() {
        return rulesVersion;
    }
}
