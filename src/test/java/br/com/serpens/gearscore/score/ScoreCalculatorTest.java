package br.com.serpens.gearscore.score;

import br.com.serpens.gearscore.TestRules;
import br.com.serpens.gearscore.score.ScoreResult.EnchantScore;
import br.com.serpens.gearscore.score.ScoreResult.EnchantStatus;
import br.com.serpens.gearscore.score.ScoreResult.ItemScore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static br.com.serpens.gearscore.TestRules.armorSet;
import static br.com.serpens.gearscore.TestRules.gear;
import static br.com.serpens.gearscore.TestRules.item;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScoreCalculatorTest {

    private final RuleSet rules = TestRules.defaults();
    private final ScoreCalculator calculator = new ScoreCalculator(rules);

    private int total(ItemSnapshot[] armor, ItemSnapshot... weapons) {
        return calculator.calculate(gear(armor, weapons)).total();
    }

    private double enchantPoints(String material, String enchant, int level) {
        return calculator.score(item(rules, material, 0, enchant, level)).enchant();
    }

    @Test
    void emptyEquipmentScoresZero() {
        ScoreResult result = calculator.calculate(gear(null));
        assertEquals(0, result.total());
        assertNull(result.weapon());
        assertEquals("iniciante", result.tier().id());
    }

    @Test
    void totalIsTheSumOfTheFourComponents() {
        ScoreResult result = calculator.calculate(gear(
                armorSet(rules, "netherite", "protection", 4, "unbreaking", 3, "mending", 1),
                item(rules, "netherite_sword", 0, "sharpness", 5, "fire_aspect", 2)));
        assertEquals(265, result.armorBase());
        assertEquals(4 * (150 + 15 + 12), result.armorEnchant());
        assertEquals(80, result.weaponBase());
        assertEquals(155 + 45, result.weaponEnchant());
        assertEquals(result.armorBase() + result.armorEnchant() + result.weaponBase() + result.weaponEnchant(),
                result.total());
    }

    @Test
    void enchantedArmorIsWorthFarMoreThanPlainArmor() {
        int plain = total(armorSet(rules, "netherite"));
        int enchanted = total(armorSet(rules, "netherite", "protection", 4, "unbreaking", 3, "mending", 1));
        assertEquals(265, plain);
        assertTrue(enchanted >= plain * 3, "enchanted=" + enchanted + " plain=" + plain);
    }

    @Test
    void enchantedWeaponIsWorthFarMoreThanPlainWeapon() {
        int plain = total(null, item(rules, "netherite_sword", 0));
        int enchanted = total(null, item(rules, "netherite_sword", 0,
                "sharpness", 5, "fire_aspect", 2, "knockback", 2, "unbreaking", 3, "mending", 1));
        assertTrue(enchanted >= plain * 3, "enchanted=" + enchanted + " plain=" + plain);
    }

    @Test
    void higherProtectionLevelsScoreMore() {
        double previous = 0;
        for (int level = 1; level <= 4; level++) {
            double points = enchantPoints("diamond_chestplate", "protection", level);
            assertTrue(points > previous, "protection " + level);
            previous = points;
        }
    }

    @Test
    void higherSharpnessLevelsScoreMore() {
        double previous = 0;
        for (int level = 1; level <= 5; level++) {
            double points = enchantPoints("diamond_sword", "sharpness", level);
            assertTrue(points > previous, "sharpness " + level);
            previous = points;
        }
    }

    @Test
    void durabilityEnchantmentsWeighLessThanDefensiveOnes() {
        double protection = enchantPoints("diamond_chestplate", "protection", 4);
        assertTrue(enchantPoints("diamond_chestplate", "mending", 1) < protection / 5);
        assertTrue(enchantPoints("diamond_chestplate", "unbreaking", 3) < protection / 5);
    }

    @Test
    void specialisedProtectionsWeighLessThanGeneralProtection() {
        double protection = enchantPoints("diamond_chestplate", "protection", 4);
        for (String key : List.of("blast_protection", "projectile_protection", "fire_protection")) {
            double points = enchantPoints("diamond_chestplate", key, 4);
            assertTrue(points > 0 && points < protection, key);
        }
    }

    @Test
    void mobOnlyEnchantmentsWeighLessThanPlayerOnes() {
        double sharpness = enchantPoints("diamond_sword", "sharpness", 5);
        assertTrue(enchantPoints("diamond_sword", "smite", 5) < sharpness / 3);
        assertTrue(enchantPoints("diamond_sword", "bane_of_arthropods", 5) < sharpness / 3);
        assertTrue(enchantPoints("trident", "impaling", 5) < enchantPoints("trident", "loyalty", 3));
    }

    @Test
    void weaponTypesUseTheirOwnTables() {
        // Density only exists for the mace; on a sword it has no rule and is worth nothing.
        assertEquals(170.0, enchantPoints("mace", "density", 5));
        assertEquals(0.0, enchantPoints("diamond_sword", "density", 5));
        assertEquals(120.0, enchantPoints("mace", "breach", 4));
        assertEquals(60.0, enchantPoints("mace", "wind_burst", 3));
        assertEquals(155.0, enchantPoints("diamond_axe", "sharpness", 5));
        assertEquals(10.0, enchantPoints("diamond_axe", "efficiency", 5));
        assertEquals(0.0, enchantPoints("diamond_sword", "efficiency", 5));
        assertEquals(120.0, enchantPoints("bow", "power", 5));
        assertEquals(60.0, enchantPoints("crossbow", "quick_charge", 3));
        assertEquals(50.0, enchantPoints("trident", "riptide", 3));
    }

    @Test
    void swordAxeAndMaceWithDifferentEnchantmentsScoreDifferently() {
        int sword = total(null, item(rules, "netherite_sword", 0, "sharpness", 5, "fire_aspect", 2));
        int axe = total(null, item(rules, "netherite_axe", 0, "sharpness", 5, "efficiency", 5));
        int mace = total(null, item(rules, "mace", 0, "density", 5, "wind_burst", 3));
        assertEquals(80 + 155 + 45, sword);
        assertEquals(76 + 155 + 10, axe);
        assertEquals(110 + 170 + 60, mace);
    }

    @Test
    void duplicatedWeaponsDoNotInflateTheScore() {
        ItemSnapshot[] nine = new ItemSnapshot[9];
        for (int slot = 0; slot < 9; slot++) {
            nine[slot] = item(rules, "netherite_sword", slot, "sharpness", 5);
        }
        int one = total(null, item(rules, "netherite_sword", 0, "sharpness", 5));
        assertEquals(one, total(null, nine));

        ItemSnapshot[] maces = {item(rules, "mace", 0, "density", 5), item(rules, "mace", 1, "density", 5),
                item(rules, "mace", 40, "density", 5)};
        assertEquals(total(null, maces[0]), total(null, maces));
    }

    @Test
    void onlyTheBestWeaponCountsAndItIsReported() {
        ScoreResult result = calculator.calculate(gear(null,
                item(rules, "iron_sword", 0),
                item(rules, "bow", 3, "power", 5),
                item(rules, "netherite_axe", 6, "sharpness", 5),
                item(rules, "diamond_sword", 40, "sharpness", 1)));
        ItemScore weapon = result.weapon();
        assertNotNull(weapon);
        assertEquals("netherite_axe", weapon.item().def().material());
        assertEquals(6, weapon.item().slot());
        assertEquals(76 + 155, result.weaponScore());
        assertEquals(result.weaponScore(), result.total());
    }

    @Test
    void incompatibleCombinationKeepsOnlyTheBestByDefault() {
        ItemScore score = calculator.score(item(rules, "diamond_chestplate", 38,
                "protection", 4, "blast_protection", 4, "fire_protection", 4));
        assertEquals(150.0, score.enchant());
        long dropped = List.of(score.enchants()).stream()
                .filter(enchant -> enchant.status() == EnchantStatus.INCOMPATIBLE).count();
        assertEquals(2, dropped);
    }

    @Test
    void incompatibleCombinationModesAreConfigurable() {
        ItemSnapshot item = item(rules, "diamond_chestplate", 38, "protection", 4, "blast_protection", 4);
        RuleSet sum = TestRules.with(config -> config.set("enchantments.incompatible.mode", "sum"));
        RuleSet zero = TestRules.with(config -> config.set("enchantments.incompatible.mode", "zero"));
        assertEquals(225.0, new ScoreCalculator(sum).score(item).enchant());
        assertEquals(0.0, new ScoreCalculator(zero).score(item).enchant());
    }

    @Test
    void levelsAboveTheLimitAreCappedByDefault() {
        ItemScore score = calculator.score(item(rules, "diamond_sword", 0, "sharpness", 10));
        assertEquals(155.0, score.enchant());
        assertEquals(EnchantStatus.CAPPED, score.enchants()[0].status());
    }

    @Test
    void levelsAboveTheLimitCanBeZeroedOrAllowed() {
        ItemSnapshot sharp = item(rules, "diamond_sword", 0, "sharpness", 10);
        ItemSnapshot smite = item(rules, "diamond_sword", 0, "smite", 8);
        RuleSet zero = TestRules.with(config -> config.set("enchantments.above-max-level", "zero"));
        RuleSet allow = TestRules.with(config -> {
            config.set("enchantments.above-max-level", "allow");
            config.set("enchantments.rules.sword.minecraft:sharpness.per-level", 20);
        });
        assertEquals(0.0, new ScoreCalculator(zero).score(sharp).enchant());
        assertEquals(155.0 + 5 * 20, new ScoreCalculator(allow).score(sharp).enchant());
        assertEquals(8 * 6.0, new ScoreCalculator(allow).score(smite).enchant());
        assertEquals(5 * 6.0, calculator.score(smite).enchant());
    }

    @Test
    void unknownEnchantmentsAreWorthZero() {
        ItemScore score = calculator.score(item(rules, "diamond_sword", 0,
                "sharpness", 3, "customplugin:vampirism", 5));
        assertEquals(80.0, score.enchant());
        EnchantScore unknown = score.enchants()[0];
        assertEquals("customplugin:vampirism", unknown.key());
        assertEquals(EnchantStatus.UNKNOWN, unknown.status());
        assertEquals(0.0, unknown.points());
    }

    @Test
    void cursesAreConfigurableAndNeverPushAnItemBelowZero() {
        assertEquals(-10.0, enchantPoints("diamond_chestplate", "vanishing_curse", 1));
        RuleSet harsh = TestRules.with(config ->
                config.set("enchantments.rules.any.minecraft:vanishing_curse.levels", List.of(-500)));
        ItemScore cursed = new ScoreCalculator(harsh).score(item(harsh, "leather_boots", 36, "vanishing_curse", 1));
        assertEquals(0.0, cursed.total());
        RuleSet lucky = TestRules.with(config ->
                config.set("enchantments.rules.any.minecraft:vanishing_curse.levels", List.of(7)));
        assertEquals(7.0, new ScoreCalculator(lucky).score(
                item(lucky, "leather_boots", 36, "vanishing_curse", 1)).enchant());
    }

    @Test
    void categoryMultipliersScaleWholeClasses() {
        RuleSet tuned = TestRules.with(config -> {
            config.set("enchantments.category-multipliers.utility", 0.0);
            config.set("enchantments.category-multipliers.combat", 2.0);
        });
        ItemScore score = new ScoreCalculator(tuned).score(
                item(tuned, "diamond_chestplate", 38, "protection", 4, "mending", 1, "unbreaking", 3));
        assertEquals(300.0, score.enchant());
    }

    @Test
    void slotMultipliersAndIgnoredItemsApply() {
        RuleSet tuned = TestRules.with(config -> {
            config.set("armor.slot-multipliers.chestplate", 2.0);
            config.set("evaluation.ignored-items", List.of("wooden_sword"));
        });
        assertEquals(180.0, tuned.gear().get("netherite_chestplate").base());
        assertNull(tuned.gear().get("wooden_sword"));
    }

    @Test
    void customItemRuleAddsBonusAndMultiplier() {
        RuleSet custom = TestRules.with(config -> {
            config.set("custom-items.enabled", true);
            config.set("custom-items.rules.king.key", "serpensitems:id");
            config.set("custom-items.rules.king.type", "string");
            config.set("custom-items.rules.king.value", "king_sword");
            config.set("custom-items.rules.king.base", 120);
            config.set("custom-items.rules.king.bonus", 80);
            config.set("custom-items.rules.king.multiplier", 1.5);
        });
        CustomItemRule rule = custom.customItems().get(0);
        ItemSnapshot plain = item(custom, "netherite_sword", 0, "sharpness", 5);
        ItemSnapshot special = new ItemSnapshot(plain.def(), 0,
                new String[]{"minecraft:sharpness"}, new int[]{5}, rule);
        ScoreCalculator calc = new ScoreCalculator(custom);
        assertEquals(80 + 155.0, calc.score(plain).total());
        assertEquals((120 + 80 + 155) * 1.5, calc.score(special).total());
    }

    @Test
    void tiersFollowTheConfiguredBands() {
        assertEquals("iniciante", rules.tierFor(0).id());
        assertEquals("aprendiz", rules.tierFor(150).id());
        assertEquals("guerreiro", rules.tierFor(599).id());
        assertEquals("elite", rules.tierFor(900).id());
        assertEquals("mitico", rules.tierFor(5000).id());
    }

    /** Prints the worked examples used in the documentation. */
    @Test
    void printBalanceExamples() {
        Object[] full = {"protection", 4, "unbreaking", 3, "mending", 1};
        ItemSnapshot[] best = armorSet(rules, "netherite", full);
        best[3] = item(rules, "netherite_boots", 36, "protection", 4, "unbreaking", 3, "mending", 1,
                "feather_falling", 4, "depth_strider", 3, "soul_speed", 3);
        ItemSnapshot godSword = item(rules, "netherite_sword", 0, "sharpness", 5, "fire_aspect", 2,
                "knockback", 2, "sweeping_edge", 3, "looting", 3, "unbreaking", 3, "mending", 1);

        print("Netherite set, no enchantments, no weapon", gear(armorSet(rules, "netherite")));
        print("Iron set, no enchantments + iron sword", gear(armorSet(rules, "iron"), item(rules, "iron_sword", 0)));
        print("Diamond set Protection II + diamond sword Sharpness II",
                gear(armorSet(rules, "diamond", "protection", 2), item(rules, "diamond_sword", 0, "sharpness", 2)));
        print("Diamond set Protection IV + diamond sword Sharpness V",
                gear(armorSet(rules, "diamond", "protection", 4), item(rules, "diamond_sword", 0, "sharpness", 5)));
        print("Netherite set Prot IV/Unb III/Mending + plain netherite sword",
                gear(armorSet(rules, "netherite", full), item(rules, "netherite_sword", 0)));
        print("Netherite full enchant + full sword", gear(best, godSword));
        print("Netherite full enchant + mace Density V/Wind Burst III/Unb III/Mending",
                gear(best, item(rules, "mace", 0, "density", 5, "wind_burst", 3, "unbreaking", 3, "mending", 1)));
        print("Netherite full enchant + bow Power V/Flame/Punch II/Infinity/Unb III",
                gear(best, item(rules, "bow", 0, "power", 5, "flame", 1, "punch", 2, "infinity", 1, "unbreaking", 3)));
    }

    private void print(String label, GearSnapshot snapshot) {
        ScoreResult r = calculator.calculate(snapshot);
        System.out.printf("EXAMPLE | %s | %d | %d | %d | %d | %d | %s%n", label, r.armorBase(), r.armorEnchant(),
                r.weaponBase(), r.weaponEnchant(), r.total(), r.tier().id());
    }
}
