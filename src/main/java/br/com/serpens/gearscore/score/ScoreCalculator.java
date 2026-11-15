package br.com.serpens.gearscore.score;

import br.com.serpens.gearscore.score.ScoreResult.EnchantScore;
import br.com.serpens.gearscore.score.ScoreResult.EnchantStatus;
import br.com.serpens.gearscore.score.ScoreResult.ItemScore;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;

/**
 * Pure scoring function over a snapshot. Holds no state besides the rules, touches no
 * server objects and can run on any thread.
 */
public final class ScoreCalculator {

    private static final EnchantScore[] NO_ENCHANTS = new EnchantScore[0];
    private static final Comparator<ItemScore> BY_SCORE = (a, b) -> {
        int order = Double.compare(b.total(), a.total());
        return order != 0 ? order : a.item().compareTo(b.item());
    };

    private final RuleSet rules;

    public ScoreCalculator(RuleSet rules) {
        this.rules = rules;
    }

    public RuleSet rules() {
        return rules;
    }

    public ScoreResult calculate(GearSnapshot snapshot) {
        int count = snapshot.size();
        ItemScore[] items = new ItemScore[count];
        double armorBase = 0.0;
        double armorEnchant = 0.0;
        double weaponBase = 0.0;
        double weaponEnchant = 0.0;
        int armorCount = 0;
        // One entry per occupied slot, so each item is counted exactly once and equal items
        // in different slots each contribute.
        for (int i = 0; i < count; i++) {
            ItemScore score = score(snapshot.item(i));
            items[i] = score;
            if (score.item().def().type().isArmor()) {
                armorBase += score.base();
                armorEnchant += score.enchant();
                armorCount++;
            } else {
                weaponBase += score.base();
                weaponEnchant += score.enchant();
            }
        }
        if (count > 1) {
            Arrays.sort(items, BY_SCORE);
        }
        return new ScoreResult(round(armorBase), round(armorEnchant), round(weaponBase), round(weaponEnchant),
                armorCount, count - armorCount, items, rules);
    }

    public ItemScore score(ItemSnapshot item) {
        GearDef def = item.def();
        CustomItemRule custom = item.custom();
        double base = def.base();
        if (custom != null) {
            if (custom.overridesBase()) {
                base = custom.base();
            }
            base += custom.bonus();
        }

        int count = item.enchantCount();
        EnchantScore[] enchants = NO_ENCHANTS;
        double enchant = 0.0;
        if (count > 0) {
            Map<String, EnchantRule> table = rules.enchantRules(def.type());
            EnchantRule.OverLimit mode = rules.overLimit();
            double[] points = new double[count];
            EnchantStatus[] status = new EnchantStatus[count];
            for (int i = 0; i < count; i++) {
                EnchantRule rule = table.get(item.enchantKey(i));
                if (rule == null) {
                    status[i] = EnchantStatus.UNKNOWN;
                    continue;
                }
                int level = item.enchantLevel(i);
                points[i] = rule.points(level, mode) * rules.categoryMultiplier(rule.category());
                if (!rule.exceedsLimit(level) || mode == EnchantRule.OverLimit.ALLOW) {
                    status[i] = EnchantStatus.COUNTED;
                } else {
                    status[i] = mode == EnchantRule.OverLimit.CAP ? EnchantStatus.CAPPED : EnchantStatus.OVER_LIMIT;
                }
            }
            if (count > 1 && rules.incompatible() != RuleSet.Incompatible.SUM) {
                resolveConflicts(item, points, status);
            }
            enchants = new EnchantScore[count];
            for (int i = 0; i < count; i++) {
                double value = status[i] == EnchantStatus.INCOMPATIBLE ? 0.0 : points[i];
                enchants[i] = new EnchantScore(item.enchantKey(i), item.enchantLevel(i), value, status[i]);
                enchant += value;
            }
        }

        if (custom != null && custom.multiplier() != 1.0) {
            base *= custom.multiplier();
            enchant *= custom.multiplier();
        }
        // Curses can lower an item but never push it below zero.
        if (base < 0.0) {
            base = 0.0;
        }
        if (base + enchant < 0.0) {
            enchant = -base;
        }
        return new ItemScore(item, base, enchant, enchants);
    }

    private void resolveConflicts(ItemSnapshot item, double[] points, EnchantStatus[] status) {
        boolean keepBest = rules.incompatible() == RuleSet.Incompatible.HIGHEST;
        int count = points.length;
        for (Set<String> group : rules.incompatibleGroups()) {
            int members = 0;
            int best = -1;
            for (int i = 0; i < count; i++) {
                if (status[i] == EnchantStatus.UNKNOWN || !group.contains(item.enchantKey(i))) {
                    continue;
                }
                members++;
                if (best < 0 || points[i] > points[best]) {
                    best = i;
                }
            }
            if (members < 2) {
                continue;
            }
            for (int i = 0; i < count; i++) {
                if (status[i] == EnchantStatus.UNKNOWN || !group.contains(item.enchantKey(i))) {
                    continue;
                }
                if (!keepBest || i != best) {
                    status[i] = EnchantStatus.INCOMPATIBLE;
                }
            }
        }
    }

    private static int round(double value) {
        return (int) Math.round(value);
    }
}
