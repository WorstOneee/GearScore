package br.com.serpens.gearscore.score;

/**
 * Points awarded by one enchantment on one gear type.
 * Either a per-level table, a linear per-level value, or a table extended linearly.
 */
public final class EnchantRule {

    public enum OverLimit {
        /** Levels above the limit score as the limit. */
        CAP,
        /** Enchantments above the limit score nothing. */
        ZERO,
        /** Levels above the limit keep scaling. */
        ALLOW
    }

    private final String key;
    private final EnchantCategory category;
    private final double[] levels;
    private final double perLevel;
    private final int maxLevel;

    public EnchantRule(String key, EnchantCategory category, double[] levels, double perLevel, int maxLevel) {
        this.key = key;
        this.category = category;
        this.levels = levels == null || levels.length == 0 ? null : levels.clone();
        this.perLevel = perLevel;
        this.maxLevel = Math.max(0, maxLevel);
    }

    public String key() {
        return key;
    }

    public EnchantCategory category() {
        return category;
    }

    /** 0 means no limit. */
    public int maxLevel() {
        return maxLevel;
    }

    public boolean exceedsLimit(int level) {
        return maxLevel > 0 && level > maxLevel;
    }

    /** Raw points for a level, before the category multiplier. */
    public double points(int level, OverLimit mode) {
        if (level <= 0) {
            return 0.0;
        }
        int effective = level;
        if (exceedsLimit(level)) {
            if (mode == OverLimit.ZERO) {
                return 0.0;
            }
            if (mode == OverLimit.CAP) {
                effective = maxLevel;
            }
        }
        if (levels == null) {
            return perLevel * effective;
        }
        if (effective <= levels.length) {
            return levels[effective - 1];
        }
        return levels[levels.length - 1] + perLevel * (effective - levels.length);
    }
}
