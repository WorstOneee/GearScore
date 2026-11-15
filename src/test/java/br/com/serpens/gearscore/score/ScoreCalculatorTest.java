package br.com.serpens.gearscore.score;

import br.com.serpens.gearscore.TestRules;
import br.com.serpens.gearscore.score.ScoreResult.EnchantScore;
import br.com.serpens.gearscore.score.ScoreResult.EnchantStatus;
import br.com.serpens.gearscore.score.ScoreResult.ItemScore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static br.com.serpens.gearscore.TestRules.armorSet;
import static br.com.serpens.gearscore.TestRules.carrying;
import static br.com.serpens.gearscore.TestRules.gear;
import static br.com.serpens.gearscore.TestRules.item;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScoreCalculatorTest {

    private final RuleSet rules = TestRules.defaults();
    private final ScoreCalculator calculator = new ScoreCalculator(rules);

    private int total(GearSnapshot snapshot) {
        return calculator.calculate(snapshot).total();
    }

    private double enchantPoints(String material, String enchant, int level) {
        return calculator.score(item(rules, material, enchant, level)).enchant();
    }

    // ---- whole inventory -----------------------------------------------------------------

    @Test
    void emptyInventoryScoresZero() {
        ScoreResult result = calculator.calculate(GearSnapshot.EMPTY);
        assertEquals(0, result.total());
        assertEquals(0, result.itemCount());
        assertEquals("iniciante", result.tier().id());
    }

    @Test
    void everyValidItemIsSummed() {
        ScoreResult result = calculator.calculate(gear(
                armorSet(rules, "netherite", "protection", 4, "unbreaking", 3, "mending", 1),
                item(rules, "netherite_sword", "sharpness", 5, "fire_aspect", 2),
                item(rules, "netherite_axe", "sharpness", 5),
                item(rules, "bow", "power", 5),
                item(rules, "mace", "density", 5)));
        assertEquals(265, result.armorBase());
        assertEquals(4 * (150 + 15 + 12), result.armorEnchant());
        assertEquals(80 + 76 + 50 + 110, result.weaponBase());
        assertEquals((155 + 45) + 155 + 120 + 170, result.weaponEnchant());
        assertEquals(result.armorBase() + result.armorEnchant() + result.weaponBase() + result.weaponEnchant(),
                result.total());
        assertEquals(4, result.armorCount());
        assertEquals(4, result.weaponCount());
        assertEquals(8, result.itemCount());
    }

    @Test
    void equalItemsInDifferentSlotsEachContribute() {
        ItemSnapshot sword = item(rules, "netherite_sword", "sharpness", 5);
        int one = total(carrying(sword));
        assertEquals(80 + 155, one);
        assertEquals(2 * one, total(carrying(sword, item(rules, "netherite_sword", "sharpness", 5))));
        ItemSnapshot[] nine = new ItemSnapshot[9];
        for (int i = 0; i < 9; i++) {
            nine[i] = item(rules, "netherite_sword", "sharpness", 5);
        }
        assertEquals(9 * one, total(carrying(nine)));
    }

    @Test
    void eachSlotIsCountedExactlyOnce() {
        // The snapshot holds one entry per occupied slot; the calculator adds each entry once.
        GearSnapshot snapshot = gear(armorSet(rules, "diamond"), item(rules, "diamond_sword"));
        ScoreResult result = calculator.calculate(snapshot);
        assertEquals(snapshot.size(), result.itemCount());
        double sum = 0;
        for (int i = 0; i < result.itemCount(); i++) {
            sum += result.item(i).total();
        }
        assertEquals(220 + 65, result.total());
        assertEquals(result.total(), sum);
    }

    @Test
    void armorCarriedInTheInventoryAlsoScores() {
        int worn = total(gear(armorSet(rules, "netherite", "protection", 4)));
        int withSpare = total(gear(armorSet(rules, "netherite", "protection", 4),
                item(rules, "netherite_chestplate", "protection", 4),
                item(rules, "diamond_helmet")));
        assertEquals(worn + (90 + 150) + 45, withSpare);
    }

    @Test
    void removingAnItemLowersTheScoreByThatItem() {
        ItemSnapshot[] armor = armorSet(rules, "netherite", "protection", 4);
        ItemSnapshot sword = item(rules, "netherite_sword", "sharpness", 5);
        int full = total(gear(armor, sword));
        assertEquals(full - (80 + 155), total(gear(armor)));
        armor[1] = null;
        assertEquals(full - (90 + 150), total(gear(armor, sword)));
    }

    @Test
    void itemsAreListedFromHighestToLowest() {
        ScoreResult result = calculator.calculate(carrying(
                item(rules, "wooden_sword"),
                item(rules, "netherite_chestplate", "protection", 4),
                item(rules, "bow", "power", 5)));
        assertEquals("netherite_chestplate", result.item(0).item().def().material());
        assertEquals("bow", result.item(1).item().def().material());
        assertEquals("wooden_sword", result.item(2).item().def().material());
    }

    @Test
    void snapshotIgnoresWhereItemsSitButNotWhatTheyAre() {
        ItemSnapshot sword = item(rules, "netherite_sword", "sharpness", 5);
        ItemSnapshot bow = item(rules, "bow", "power", 5);
        ItemSnapshot helmet = item(rules, "diamond_helmet", "protection", 4);
        // Same items read in another slot order: same signature, nothing to recalculate.
        assertEquals(carrying(sword, bow, helmet), carrying(helmet, sword, bow));
        assertEquals(carrying(sword, bow, helmet).hashCode(), carrying(bow, helmet, sword).hashCode());
        // A different composition is a different signature.
        assertNotEquals(carrying(sword, bow), carrying(sword, bow, helmet));
        assertNotEquals(carrying(sword, bow), carrying(sword, sword));
        assertNotEquals(carrying(sword), carrying(item(rules, "netherite_sword", "sharpness", 4)));
        assertNotEquals(carrying(sword), carrying(item(rules, "netherite_sword", "sharpness", 5, "mending", 1)));
    }

    @Test
    void onlyConfiguredGearCanScore() {
        // Items are admitted by material. None of these has a definition, so the inventory
        // reader skips them whatever enchantments or glint they carry.
        for (String name : List.of("enchanted_book", "book", "diamond_pickaxe", "netherite_shovel", "stick",
                "golden_apple", "diamond_block", "shulker_box", "bundle", "shield", "fishing_rod", "diamond")) {
            assertNull(rules.gear().get(name), name);
        }
        for (GearDef def : rules.gear().values()) {
            assertTrue(def.base() >= 0, def.material());
        }
        assertEquals(GearType.CHESTPLATE, rules.gear().get("elytra").type());
        assertEquals(GearType.HELMET, rules.gear().get("turtle_helmet").type());
    }

    // ---- enchantments: levels, weights, applicability ------------------------------------

    @Test
    void enchantedGearIsWorthFarMoreThanPlainGear() {
        int plain = total(gear(armorSet(rules, "netherite")));
        int enchanted = total(gear(armorSet(rules, "netherite", "protection", 4, "unbreaking", 3, "mending", 1)));
        assertEquals(265, plain);
        assertTrue(enchanted >= plain * 3, "enchanted=" + enchanted + " plain=" + plain);

        int sword = total(carrying(item(rules, "netherite_sword")));
        int enchantedSword = total(carrying(item(rules, "netherite_sword",
                "sharpness", 5, "fire_aspect", 2, "knockback", 2, "unbreaking", 3, "mending", 1)));
        assertTrue(enchantedSword >= sword * 3);
    }

    @Test
    void everyLevelScoresMoreThanTheOneBelow() {
        Object[][] cases = {
                {"diamond_chestplate", "protection", 4}, {"diamond_chestplate", "blast_protection", 4},
                {"diamond_chestplate", "projectile_protection", 4}, {"diamond_chestplate", "fire_protection", 4},
                {"diamond_chestplate", "thorns", 3}, {"diamond_boots", "feather_falling", 4},
                {"diamond_sword", "sharpness", 5}, {"diamond_sword", "fire_aspect", 2},
                {"diamond_axe", "sharpness", 5}, {"mace", "density", 5}, {"mace", "breach", 4},
                {"mace", "wind_burst", 3}, {"bow", "power", 5}, {"crossbow", "quick_charge", 3},
                {"trident", "loyalty", 3}, {"trident", "riptide", 3}, {"diamond_sword", "smite", 5},
                {"diamond_chestplate", "unbreaking", 3}};
        for (Object[] entry : cases) {
            double previous = 0;
            for (int level = 1; level <= (Integer) entry[2]; level++) {
                double points = enchantPoints((String) entry[0], (String) entry[1], level);
                assertTrue(points > previous, entry[1] + " " + level + " on " + entry[0]);
                previous = points;
            }
        }
    }

    @Test
    void weightsComeFromTheConfigurationAndDiffer() {
        // Exact values read from the shipped config.yml through the whole pipeline.
        assertEquals(150.0, enchantPoints("netherite_chestplate", "protection", 4));
        assertEquals(75.0, enchantPoints("netherite_chestplate", "blast_protection", 4));
        assertEquals(75.0, enchantPoints("netherite_chestplate", "projectile_protection", 4));
        assertEquals(60.0, enchantPoints("netherite_chestplate", "fire_protection", 4));
        assertEquals(45.0, enchantPoints("netherite_chestplate", "thorns", 3));
        assertEquals(40.0, enchantPoints("netherite_boots", "feather_falling", 4));
        assertEquals(18.0, enchantPoints("netherite_boots", "depth_strider", 3));
        assertEquals(15.0, enchantPoints("netherite_chestplate", "unbreaking", 3));
        assertEquals(12.0, enchantPoints("netherite_chestplate", "mending", 1));
        assertEquals(155.0, enchantPoints("netherite_sword", "sharpness", 5));
        assertEquals(45.0, enchantPoints("netherite_sword", "fire_aspect", 2));
        assertEquals(30.0, enchantPoints("netherite_sword", "smite", 5));
        assertEquals(20.0, enchantPoints("netherite_sword", "bane_of_arthropods", 5));
        assertEquals(6.0, enchantPoints("netherite_sword", "looting", 3));
        assertEquals(170.0, enchantPoints("mace", "density", 5));
        assertEquals(120.0, enchantPoints("mace", "breach", 4));
        assertEquals(60.0, enchantPoints("mace", "wind_burst", 3));
        assertEquals(120.0, enchantPoints("bow", "power", 5));
        assertEquals(15.0, enchantPoints("bow", "infinity", 1));
        assertEquals(60.0, enchantPoints("crossbow", "quick_charge", 3));
        assertEquals(5.0, enchantPoints("netherite_axe", "efficiency", 5));
    }

    @Test
    void combatEnchantmentsOutweighUtilityAndMobOnes() {
        double protection = enchantPoints("diamond_chestplate", "protection", 4);
        // survival in combat first
        for (String key : List.of("blast_protection", "projectile_protection", "fire_protection", "thorns")) {
            double points = enchantPoints("diamond_chestplate", key, key.equals("thorns") ? 3 : 4);
            assertTrue(points > 0 && points < protection, key);
        }
        assertTrue(enchantPoints("diamond_boots", "feather_falling", 4) < protection);
        // durability far below
        assertTrue(enchantPoints("diamond_chestplate", "mending", 1) < protection / 5);
        assertTrue(enchantPoints("diamond_chestplate", "unbreaking", 3) < protection / 5);
        assertTrue(enchantPoints("diamond_helmet", "respiration", 3) < protection / 5);

        double sharpness = enchantPoints("diamond_sword", "sharpness", 5);
        assertTrue(enchantPoints("diamond_sword", "smite", 5) < sharpness / 3);
        assertTrue(enchantPoints("diamond_sword", "bane_of_arthropods", 5) < sharpness / 3);
        assertTrue(enchantPoints("diamond_sword", "looting", 3) < sharpness / 10);
        assertTrue(enchantPoints("diamond_axe", "efficiency", 5) < sharpness / 10);
        assertEquals(0.0, enchantPoints("diamond_axe", "fortune", 3));
        assertEquals(0.0, enchantPoints("diamond_axe", "silk_touch", 1));
        assertTrue(enchantPoints("trident", "impaling", 5) < enchantPoints("trident", "loyalty", 3));
        assertTrue(enchantPoints("bow", "infinity", 1) < enchantPoints("bow", "power", 2));
    }

    /** Enchantments each gear type can really hold in vanilla 1.21.11. */
    private static final List<String> EVERYTHING = List.of("unbreaking", "mending", "vanishing_curse");
    private static final List<String> ALL_ARMOR = List.of("protection", "blast_protection",
            "projectile_protection", "fire_protection", "thorns", "binding_curse");
    private static final java.util.Map<GearType, List<String>> OWN = java.util.Map.ofEntries(
            java.util.Map.entry(GearType.HELMET, List.of("respiration", "aqua_affinity")),
            java.util.Map.entry(GearType.CHESTPLATE, List.of()),
            java.util.Map.entry(GearType.LEGGINGS, List.of("swift_sneak")),
            java.util.Map.entry(GearType.BOOTS, List.of("feather_falling", "depth_strider", "frost_walker",
                    "soul_speed")),
            java.util.Map.entry(GearType.SWORD, List.of("sharpness", "smite", "bane_of_arthropods", "knockback",
                    "fire_aspect", "looting", "sweeping_edge")),
            java.util.Map.entry(GearType.AXE, List.of("sharpness", "smite", "bane_of_arthropods", "efficiency",
                    "silk_touch", "fortune")),
            java.util.Map.entry(GearType.MACE, List.of("density", "breach", "wind_burst", "smite",
                    "bane_of_arthropods", "fire_aspect")),
            java.util.Map.entry(GearType.TRIDENT, List.of("loyalty", "impaling", "riptide", "channeling")),
            java.util.Map.entry(GearType.BOW, List.of("power", "punch", "flame", "infinity")),
            java.util.Map.entry(GearType.CROSSBOW, List.of("quick_charge", "multishot", "piercing")),
            java.util.Map.entry(GearType.SPEAR, List.of("sharpness", "smite", "bane_of_arthropods", "knockback",
                    "fire_aspect", "looting", "lunge")));
    private static final List<String> VANILLA = List.of("protection", "fire_protection", "feather_falling",
            "blast_protection", "projectile_protection", "respiration", "aqua_affinity", "thorns", "depth_strider",
            "frost_walker", "binding_curse", "soul_speed", "swift_sneak", "sharpness", "smite",
            "bane_of_arthropods", "knockback", "fire_aspect", "looting", "sweeping_edge", "efficiency",
            "silk_touch", "unbreaking", "fortune", "power", "punch", "flame", "infinity", "luck_of_the_sea", "lure",
            "loyalty", "impaling", "riptide", "channeling", "multishot", "quick_charge", "piercing", "density",
            "breach", "wind_burst", "mending", "vanishing_curse", "lunge");

    private static String sample(GearType type) {
        return switch (type) {
            case HELMET -> "diamond_helmet";
            case CHESTPLATE -> "diamond_chestplate";
            case LEGGINGS -> "diamond_leggings";
            case BOOTS -> "diamond_boots";
            case SWORD -> "diamond_sword";
            case AXE -> "diamond_axe";
            case SPEAR -> "diamond_spear";
            default -> type.id();
        };
    }

    @Test
    void anEnchantmentOnlyScoresOnGearThatCanHoldIt() {
        for (GearType type : GearType.values()) {
            List<String> allowed = new java.util.ArrayList<>(EVERYTHING);
            allowed.addAll(OWN.get(type));
            if (type.isArmor()) {
                allowed.addAll(ALL_ARMOR);
            }
            for (String enchant : VANILLA) {
                ItemScore score = calculator.score(item(rules, sample(type), enchant, 1));
                EnchantScore result = score.enchants()[0];
                if (allowed.contains(enchant)) {
                    assertEquals(EnchantStatus.COUNTED, result.status(),
                            enchant + " must have a rule on " + type.id());
                } else {
                    // No generic rule hands out points where the enchantment does not belong.
                    assertEquals(EnchantStatus.UNKNOWN, result.status(), enchant + " on " + type.id());
                    assertEquals(0.0, result.points(), enchant + " on " + type.id());
                }
            }
        }
    }

    @Test
    void weaponTypesUseTheirOwnTables() {
        assertEquals(170.0, enchantPoints("mace", "density", 5));
        assertEquals(0.0, enchantPoints("diamond_sword", "density", 5));
        assertEquals(0.0, enchantPoints("mace", "sharpness", 5));
        assertEquals(0.0, enchantPoints("diamond_sword", "power", 5));
        assertEquals(0.0, enchantPoints("bow", "sharpness", 5));
        assertEquals(0.0, enchantPoints("diamond_chestplate", "sharpness", 5));
        assertEquals(0.0, enchantPoints("diamond_sword", "protection", 4));
        assertEquals(0.0, enchantPoints("diamond_helmet", "feather_falling", 4));
        assertEquals(0.0, enchantPoints("diamond_boots", "respiration", 3));
    }

    @Test
    void removingARuleFromTheFileMakesTheEnchantmentWorthNothing() {
        RuleSet trimmed = TestRules.with(config -> config.set("enchantments.rules.sword.minecraft:fire_aspect", null));
        assertEquals(0.0, new ScoreCalculator(trimmed).score(
                item(trimmed, "diamond_sword", "fire_aspect", 2)).enchant());
        assertEquals(45.0, enchantPoints("diamond_sword", "fire_aspect", 2));
    }

    @Test
    void aFileWithoutGearIsRejectedInsteadOfZeroingEveryScore() {
        assertThrows(IllegalArgumentException.class, () -> TestRules.with(config -> {
            config.set("armor", null);
            config.set("weapons", null);
        }));
    }

    @Test
    void incompatibleCombinationKeepsOnlyTheBestByDefault() {
        ItemScore score = calculator.score(item(rules, "diamond_chestplate",
                "protection", 4, "blast_protection", 4, "fire_protection", 4));
        assertEquals(150.0, score.enchant());
        long dropped = List.of(score.enchants()).stream()
                .filter(enchant -> enchant.status() == EnchantStatus.INCOMPATIBLE).count();
        assertEquals(2, dropped);

        assertEquals(170.0, calculator.score(item(rules, "mace", "density", 5, "breach", 4)).enchant());
        assertEquals(155.0, calculator.score(item(rules, "diamond_sword", "sharpness", 5, "smite", 5)).enchant());
        // Riptide excludes both Loyalty and Channeling; only the strongest of them stays.
        assertEquals(50.0, calculator.score(
                item(rules, "trident", "riptide", 3, "loyalty", 3, "channeling", 1)).enchant());
    }

    @Test
    void incompatibleCombinationModesAreConfigurable() {
        ItemSnapshot item = item(rules, "diamond_chestplate", "protection", 4, "blast_protection", 4);
        RuleSet sum = TestRules.with(config -> config.set("enchantments.incompatible.mode", "sum"));
        RuleSet zero = TestRules.with(config -> config.set("enchantments.incompatible.mode", "zero"));
        assertEquals(225.0, new ScoreCalculator(sum).score(item).enchant());
        assertEquals(0.0, new ScoreCalculator(zero).score(item).enchant());
    }

    @Test
    void levelsAboveTheLimitFollowTheConfiguredPolicy() {
        ItemSnapshot sharp = item(rules, "diamond_sword", "sharpness", 10);
        ItemSnapshot smite = item(rules, "diamond_sword", "smite", 8);
        ItemScore capped = calculator.score(sharp);
        assertEquals(155.0, capped.enchant());
        assertEquals(EnchantStatus.CAPPED, capped.enchants()[0].status());
        assertEquals(5 * 6.0, calculator.score(smite).enchant());

        RuleSet zero = TestRules.with(config -> config.set("enchantments.above-max-level", "zero"));
        RuleSet allow = TestRules.with(config -> {
            config.set("enchantments.above-max-level", "allow");
            config.set("enchantments.rules.sword.minecraft:sharpness.per-level", 20);
        });
        assertEquals(0.0, new ScoreCalculator(zero).score(sharp).enchant());
        assertEquals(155.0 + 5 * 20, new ScoreCalculator(allow).score(sharp).enchant());
        assertEquals(8 * 6.0, new ScoreCalculator(allow).score(smite).enchant());
    }

    @Test
    void unknownEnchantmentsAreWorthZero() {
        ItemScore score = calculator.score(item(rules, "diamond_sword",
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
        assertEquals(-10.0, enchantPoints("diamond_chestplate", "binding_curse", 1));
        RuleSet harsh = TestRules.with(config ->
                config.set("enchantments.rules.any.minecraft:vanishing_curse.levels", List.of(-500)));
        assertEquals(0.0, new ScoreCalculator(harsh).score(item(harsh, "leather_boots", "vanishing_curse", 1)).total());
        RuleSet lucky = TestRules.with(config ->
                config.set("enchantments.rules.any.minecraft:vanishing_curse.levels", List.of(7)));
        assertEquals(7.0, new ScoreCalculator(lucky).score(
                item(lucky, "leather_boots", "vanishing_curse", 1)).enchant());
    }

    @Test
    void multipliersApplyAndPartsAreRoundedOnce() {
        RuleSet tuned = TestRules.with(config -> {
            config.set("enchantments.category-multipliers.utility", 0.0);
            config.set("enchantments.category-multipliers.combat", 1.15);
            config.set("armor.slot-multipliers.chestplate", 1.5);
        });
        ScoreCalculator calc = new ScoreCalculator(tuned);
        ItemSnapshot chest = item(tuned, "netherite_chestplate", "protection", 4, "mending", 1, "unbreaking", 3);
        ItemScore single = calc.score(chest);
        assertEquals(135.0, single.base());
        assertEquals(172.5, single.enchant(), 1e-9);

        // Three of them: the fractional points are added first and rounded at the end.
        ScoreResult result = calc.calculate(carrying(chest,
                item(tuned, "netherite_chestplate", "protection", 4, "mending", 1, "unbreaking", 3),
                item(tuned, "netherite_chestplate", "protection", 4, "mending", 1, "unbreaking", 3)));
        assertEquals(405, result.armorBase());
        assertEquals(518, result.armorEnchant());
        assertEquals(923, result.total());
    }

    @Test
    void ignoredItemsAreNotGear() {
        RuleSet tuned = TestRules.with(config -> config.set("evaluation.ignored-items", List.of("wooden_sword")));
        assertNull(tuned.gear().get("wooden_sword"));
        assertFalse(tuned.gear().isEmpty());
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
        ItemSnapshot plain = item(custom, "netherite_sword", "sharpness", 5);
        ItemSnapshot special = new ItemSnapshot(plain.def(),
                new String[]{"minecraft:sharpness"}, new int[]{5}, rule);
        ScoreCalculator calc = new ScoreCalculator(custom);
        assertEquals(80 + 155.0, calc.score(plain).total());
        assertEquals((120 + 80 + 155) * 1.5, calc.score(special).total());
        assertNotEquals(plain, special);
        assertEquals((int) Math.round(235 + 532.5), calc.calculate(carrying(plain, special)).total());
    }

    @Test
    void tiersFollowTheConfiguredBands() {
        assertEquals(2, rules.version());
        assertEquals("iniciante", rules.tierFor(0).id());
        assertEquals("aprendiz", rules.tierFor(300).id());
        assertEquals("guerreiro", rules.tierFor(1199).id());
        assertEquals("veterano", rules.tierFor(1200).id());
        assertEquals("elite", rules.tierFor(1800).id());
        assertEquals("lendario", rules.tierFor(2400).id());
        assertEquals("mitico", rules.tierFor(9000).id());
    }

    // ---- worked examples for the documentation -------------------------------------------

    @Test
    void printBalanceExamples() {
        Object[] full = {"protection", 4, "unbreaking", 3, "mending", 1};
        ItemSnapshot[] best = armorSet(rules, "netherite", full);
        best[3] = item(rules, "netherite_boots", "protection", 4, "unbreaking", 3, "mending", 1,
                "feather_falling", 4, "depth_strider", 3, "soul_speed", 3);
        ItemSnapshot sword = item(rules, "netherite_sword", "sharpness", 5, "fire_aspect", 2,
                "knockback", 2, "sweeping_edge", 3, "looting", 3, "unbreaking", 3, "mending", 1);
        ItemSnapshot axe = item(rules, "netherite_axe", "sharpness", 5, "unbreaking", 3, "mending", 1);
        ItemSnapshot mace = item(rules, "mace", "density", 5, "wind_burst", 3, "unbreaking", 3, "mending", 1);
        ItemSnapshot bow = item(rules, "bow", "power", 5, "flame", 1, "punch", 2, "infinity", 1, "unbreaking", 3);
        ItemSnapshot crossbow = item(rules, "crossbow", "quick_charge", 3, "piercing", 4, "unbreaking", 3,
                "mending", 1);
        ItemSnapshot trident = item(rules, "trident", "loyalty", 3, "channeling", 1, "impaling", 5,
                "unbreaking", 3, "mending", 1);

        // Equivalent pieces, different enchantments.
        for (Object[] entry : new Object[][]{
                {"Peitoral de netherita sem encantamentos"},
                {"Peitoral de netherita + Protecao IV", "protection", 4},
                {"Peitoral de netherita + Protecao contra Explosoes IV", "blast_protection", 4},
                {"Peitoral de netherita + Protecao contra Projeteis IV", "projectile_protection", 4},
                {"Peitoral de netherita + Protecao contra Fogo IV", "fire_protection", 4},
                {"Peitoral de netherita + Espinhos III", "thorns", 3},
                {"Peitoral de netherita + Inquebravel III e Remendo", "unbreaking", 3, "mending", 1},
                {"Peitoral de netherita + Protecao IV, Espinhos III, Inquebravel III, Remendo",
                        "protection", 4, "thorns", 3, "unbreaking", 3, "mending", 1}}) {
            piece((String) entry[0], item(rules, "netherite_chestplate",
                    java.util.Arrays.copyOfRange(entry, 1, entry.length)));
        }
        for (Object[] entry : new Object[][]{
                {"Espada de netherita sem encantamentos"},
                {"Espada de netherita + Afiacao V", "sharpness", 5},
                {"Espada de netherita + Julgamento V", "smite", 5},
                {"Espada de netherita + Ruina dos Artropodes V", "bane_of_arthropods", 5},
                {"Espada de netherita + Aspecto Flamejante II", "fire_aspect", 2},
                {"Espada de netherita + Repulsao II", "knockback", 2},
                {"Espada de netherita + Saque III", "looting", 3},
                {"Espada de netherita + Inquebravel III e Remendo", "unbreaking", 3, "mending", 1}}) {
            piece((String) entry[0], item(rules, "netherite_sword",
                    java.util.Arrays.copyOfRange(entry, 1, entry.length)));
        }
        piece("Mace + Densidade V", item(rules, "mace", "density", 5));
        piece("Mace + Ruptura IV", item(rules, "mace", "breach", 4));
        piece("Mace + Rajada de Vento III", item(rules, "mace", "wind_burst", 3));
        piece("Arco + Forca V", item(rules, "bow", "power", 5));
        piece("Arco + Infinidade", item(rules, "bow", "infinity", 1));
        piece("Botas de netherita + Peso-Pena IV", item(rules, "netherite_boots", "feather_falling", 4));

        // Whole inventories.
        print("Ferro completo sem encantamentos + espada de ferro",
                gear(armorSet(rules, "iron"), item(rules, "iron_sword")));
        print("Diamante Protecao IV + espada de diamante Afiacao V",
                gear(armorSet(rules, "diamond", "protection", 4), item(rules, "diamond_sword", "sharpness", 5)));
        print("Netherita completa encantada, sem armas", gear(best));
        print("Netherita completa encantada + espada completa", gear(best, sword));
        print("Netherita completa encantada + espada, machado e arco", gear(best, sword, axe, bow));
        print("Netherita completa encantada + espada, machado, arco, besta, mace e tridente",
                gear(best, sword, axe, bow, crossbow, mace, trident));
        print("O anterior + segunda armadura de netherita Protecao IV no inventario",
                gear(best, sword, axe, bow, crossbow, mace, trident,
                        item(rules, "netherite_helmet", "protection", 4),
                        item(rules, "netherite_chestplate", "protection", 4),
                        item(rules, "netherite_leggings", "protection", 4),
                        item(rules, "netherite_boots", "protection", 4)));
    }

    private void piece(String label, ItemSnapshot item) {
        ItemScore score = calculator.score(item);
        System.out.printf("PIECE | %s | %.0f | %.0f | %.0f%n", label, score.base(), score.enchant(), score.total());
    }

    private void print(String label, GearSnapshot snapshot) {
        ScoreResult r = calculator.calculate(snapshot);
        System.out.printf("EXAMPLE | %s | %d | %d | %d | %d | %d | %s%n", label, r.armorScore(), r.weaponScore(),
                r.armorCount(), r.weaponCount(), r.total(), r.tier().id());
    }
}
