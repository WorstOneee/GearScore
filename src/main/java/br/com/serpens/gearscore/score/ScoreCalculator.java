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

    /**
     * Picks the best helmet, chestplate, leggings, boots and weapon among everything the
     * player carries, each by its full score (material, enchantments, custom rule), and adds
     * those five. Other items of the same slot, and every other weapon, add nothing, so
     * carrying duplicates or spare gear cannot raise the result.
     */
    public ScoreResult calculate(GearSnapshot snapshot) {
        ItemScore[] best = new ItemScore[ScoreResult.SET_SIZE];
        int count = snapshot.size();
        for (int i = 0; i < count; i++) {
            ItemScore score = score(snapshot.item(i));
            GearType type = score.item().def().type();
            // Armor slot types are the first four enum constants; every weapon type
            // competes for the single weapon place.
            int place = type.isArmor() ? type.ordinal() : ScoreResult.WEAPON;
            // Strictly greater: on a tie the first in the snapshot's canonical order stays,
            // so the choice does not depend on where items sit.
            if (best[place] == null || score.total() > best[place].total()) {
                best[place] = score;
            }
        }
        double armorBase = 0.0;
        double armorEnchant = 0.0;
        for (int place = 0; place < ScoreResult.WEAPON; place++) {
            if (best[place] != null) {
                armorBase += best[place].base();
                armorEnchant += best[place].enchant();
            }
        }
        ItemScore weapon = best[ScoreResult.WEAPON];
        return new ScoreResult(round(armorBase), round(armorEnchant),
                weapon == null ? 0 : round(weapon.base()), weapon == null ? 0 : round(weapon.enchant()),
                best, count, rules);
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
