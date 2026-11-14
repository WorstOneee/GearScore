package br.com.serpens.gearscore.score;

/**
 * Outcome of one evaluation, including the per-item breakdown shown by the details command.
 * total = armorBase + armorEnchant + weaponBase + weaponEnchant.
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
    private final ItemScore[] armor;
    private final ItemScore weapon;
    private final Tier tier;
    private final int rulesVersion;

    public ScoreResult(int armorBase, int armorEnchant, int weaponBase, int weaponEnchant,
                       ItemScore[] armor, ItemScore weapon, RuleSet rules) {
        this.armorBase = armorBase;
        this.armorEnchant = armorEnchant;
        this.weaponBase = weaponBase;
        this.weaponEnchant = weaponEnchant;
        this.armor = armor;
        this.weapon = weapon;
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

    /** Helmet, chestplate, leggings, boots; entries are null for empty slots. */
    public ItemScore armor(int index) {
        return armor[index];
    }

    /** The single weapon that was counted, or null. */
    public ItemScore weapon() {
        return weapon;
    }

    public Tier tier() {
        return tier;
    }

    public int rulesVersion() {
        return rulesVersion;
    }
}
