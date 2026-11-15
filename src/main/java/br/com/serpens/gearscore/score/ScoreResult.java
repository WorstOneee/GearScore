package br.com.serpens.gearscore.score;

/**
 * Outcome of one evaluation: the best set the player can assemble from what they carry.
 * total = best helmet + best chestplate + best leggings + best boots + best weapon,
 * each with its base and enchantment points.
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

    /** Number of pieces in a set: four armor slots and one weapon. */
    public static final int SET_SIZE = 5;
    /** Index of the weapon in {@link #piece(int)}; armor uses the ordinals of its slot type. */
    public static final int WEAPON = 4;

    private final int armorBase;
    private final int armorEnchant;
    private final int weaponBase;
    private final int weaponEnchant;
    private final ItemScore[] pieces;
    private final int candidates;
    private final Tier tier;
    private final int rulesVersion;

    /**
     * @param pieces     the selected helmet, chestplate, leggings, boots and weapon; null where
     *                   the player carries none
     * @param candidates valid items that competed for those five places
     */
    public ScoreResult(int armorBase, int armorEnchant, int weaponBase, int weaponEnchant,
                       ItemScore[] pieces, int candidates, RuleSet rules) {
        this.armorBase = armorBase;
        this.armorEnchant = armorEnchant;
        this.weaponBase = weaponBase;
        this.weaponEnchant = weaponEnchant;
        this.pieces = pieces;
        this.candidates = candidates;
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

    /**
     * Selected piece: 0 helmet, 1 chestplate, 2 leggings, 3 boots, {@link #WEAPON} weapon.
     *
     * @return null when the player carries nothing for that place
     */
    public ItemScore piece(int index) {
        return pieces[index];
    }

    public ItemScore weapon() {
        return pieces[WEAPON];
    }

    /** Valid items found in the inventory, selected or not. */
    public int candidates() {
        return candidates;
    }

    public Tier tier() {
        return tier;
    }

    public int rulesVersion() {
        return rulesVersion;
    }
}
