package br.com.serpens.gearscore.score;

import br.com.serpens.gearscore.score.ScoreResult.EnchantScore;
import br.com.serpens.gearscore.score.ScoreResult.EnchantStatus;
import br.com.serpens.gearscore.score.ScoreResult.ItemScore;

import java.util.Map;
import java.util.Set;

/**
 * Pure scoring function over a snapshot. Holds no state besides the rules, touches no
 * server objects and can run on any thread.
 */
public final class ScoreCalculator {

    private static final EnchantScore[] NO_ENCHANTS = new EnchantScore[0];

    private final RuleSet rules;

    public ScoreCalculator(RuleSet rules) {
        this.rules = rules;
    }

    public RuleSet rules() {
        return rules;
    }

    public ScoreResult calculate(GearSnapshot snapshot) {
        ItemScore[] armor = new ItemScore[4];
        double armorBase = 0.0;
        double armorEnchant = 0.0;
        for (int i = 0; i < 4; i++) {
            ItemSnapshot item = snapshot.armor(i);
            if (item == null) {
                continue;
            }
            ItemScore score = score(item);
            armor[i] = score;
            armorBase += score.base();
            armorEnchant += score.enchant();
        }

        // Only the single best candidate counts, so duplicates never add up.
        ItemScore weapon = null;
        for (int i = 0, n = snapshot.weaponCount(); i < n; i++) {
            ItemScore score = score(snapshot.weapon(i));
            if (weapon == null || score.total() > weapon.total()) {
                weapon = score;
            }
        }

        return new ScoreResult(
                round(armorBase), round(armorEnchant),
                weapon == null ? 0 : round(weapon.base()),
                weapon == null ? 0 : round(weapon.enchant()),
                armor, weapon, rules);
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
