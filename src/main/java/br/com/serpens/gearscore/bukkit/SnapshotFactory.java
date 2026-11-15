package br.com.serpens.gearscore.bukkit;

import br.com.serpens.gearscore.score.CustomItemRule;
import br.com.serpens.gearscore.score.GearDef;
import br.com.serpens.gearscore.score.GearSnapshot;
import br.com.serpens.gearscore.score.ItemSnapshot;
import br.com.serpens.gearscore.score.RuleSet;
import io.papermc.paper.persistence.PersistentDataContainerView;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads a player's own inventory into a {@link GearSnapshot}: the 36 storage slots (hotbar
 * included), the 4 armor slots and the off hand, each slot exactly once. The main hand is
 * a hotbar slot and is not read a second time. Ender chest, open containers, the cursor,
 * the crafting grid and the contents of shulker boxes or bundles are never looked at.
 *
 * Server thread only. Per slot it reads the material and, for configured gear only, the
 * real enchantments and the configured PDC keys; never names, lore, model data, durability
 * or amounts. Enchanted books, blocks, food and other items are skipped by material, no
 * matter what enchantments or glint they carry.
 */
public final class SnapshotFactory {

    /** Bukkit slot indexes: 0-8 hotbar, 9-35 storage, 36-39 armor, 40 off hand. */
    public static final int SLOT_COUNT = 41;

    private record Matcher(CustomItemRule rule, NamespacedKey key, int kind, String text, long number) {
    }

    private static final int KIND_ANY = 0;
    private static final int KIND_STRING = 1;
    private static final int KIND_INTEGER = 2;
    private static final int KIND_BYTE = 3;

    private final EnumMap<Material, GearDef> defs = new EnumMap<>(Material.class);
    private final Matcher[] matchers;
    private final Map<String, GearDef> customDefs = new HashMap<>();
    private final Map<Enchantment, String> enchantKeys = new IdentityHashMap<>();
    private final ItemSnapshot[] scratch = new ItemSnapshot[SLOT_COUNT];
    private final List<String> unknownMaterials = new ArrayList<>();

    public SnapshotFactory(RuleSet rules) {
        for (GearDef def : rules.gear().values()) {
            Material material = Material.getMaterial(def.material().toUpperCase(Locale.ROOT));
            if (material == null || !material.isItem()) {
                unknownMaterials.add(def.material());
                continue;
            }
            defs.put(material, def);
        }
        List<Matcher> compiled = new ArrayList<>();
        for (CustomItemRule rule : rules.customItems()) {
            NamespacedKey key = NamespacedKey.fromString(rule.pdcKey());
            if (key == null) {
                continue;
            }
            int kind = switch (rule.pdcType()) {
                case "string" -> KIND_STRING;
                case "integer" -> KIND_INTEGER;
                case "byte" -> KIND_BYTE;
                default -> KIND_ANY;
            };
            long number = 0L;
            if (kind == KIND_INTEGER || kind == KIND_BYTE) {
                try {
                    number = Long.parseLong(rule.pdcValue().trim());
                } catch (NumberFormatException e) {
                    continue;
                }
            }
            compiled.add(new Matcher(rule, key, kind, rule.pdcValue(), number));
        }
        this.matchers = compiled.toArray(new Matcher[0]);
    }

    /** Configured materials that do not exist in this server version. */
    public List<String> unknownMaterials() {
        return unknownMaterials;
    }

    /**
     * Constant-time test used by the listener: can replacing {@code before} with
     * {@code after} in a slot change the set of scored items? False when neither is gear,
     * or when only the amount or durability changed.
     */
    public boolean changesGear(ItemStack before, ItemStack after) {
        boolean gearBefore = mayScore(before);
        boolean gearAfter = mayScore(after);
        if (!gearBefore && !gearAfter) {
            return false;
        }
        if (gearBefore != gearAfter || before.getType() != after.getType()) {
            return true;
        }
        if (matchers.length > 0
                && !(before.getPersistentDataContainer().isEmpty() && after.getPersistentDataContainer().isEmpty())) {
            // A configured PDC key may differ; let the snapshot comparison decide.
            return true;
        }
        return !before.getEnchantments().equals(after.getEnchantments());
    }

    private boolean mayScore(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (defs.containsKey(stack.getType())) {
            return true;
        }
        // Without custom rules only configured materials score; with them, an item needs
        // data in its container to be a candidate at all.
        return matchers.length > 0 && !stack.getPersistentDataContainer().isEmpty();
    }

    public GearSnapshot capture(PlayerInventory inventory) {
        int count = 0;
        // getItem covers storage, armor and off hand by index, one slot at a time.
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            ItemSnapshot item = read(inventory.getItem(slot));
            if (item != null) {
                scratch[count++] = item;
            }
        }
        if (count == 0) {
            return GearSnapshot.EMPTY;
        }
        GearSnapshot snapshot = new GearSnapshot(scratch, count);
        java.util.Arrays.fill(scratch, 0, count, null);
        return snapshot;
    }

    private ItemSnapshot read(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        Material material = stack.getType();
        GearDef def = defs.get(material);
        CustomItemRule custom = null;
        if (matchers.length > 0) {
            custom = match(stack);
            if (custom != null && custom.gearType() != null) {
                def = customDef(material, custom, def);
            }
        }
        if (def == null) {
            return null;
        }

        Map<Enchantment, Integer> enchantments = stack.getEnchantments();
        int size = enchantments.size();
        if (size == 0) {
            return new ItemSnapshot(def, ItemSnapshot.NO_KEYS, ItemSnapshot.NO_LEVELS, custom);
        }
        String[] keys = new String[size];
        int[] levels = new int[size];
        int filled = 0;
        for (Map.Entry<Enchantment, Integer> entry : enchantments.entrySet()) {
            String key = keyOf(entry.getKey());
            int level = entry.getValue();
            // Insertion sort keeps the arrays ordered by key, so equal items compare equal.
            int position = filled;
            while (position > 0 && keys[position - 1].compareTo(key) > 0) {
                keys[position] = keys[position - 1];
                levels[position] = levels[position - 1];
                position--;
            }
            keys[position] = key;
            levels[position] = level;
            filled++;
        }
        return new ItemSnapshot(def, keys, levels, custom);
    }

    private String keyOf(Enchantment enchantment) {
        String key = enchantKeys.get(enchantment);
        if (key == null) {
            key = enchantment.getKey().toString();
            enchantKeys.put(enchantment, key);
        }
        return key;
    }

    private CustomItemRule match(ItemStack stack) {
        PersistentDataContainerView container = stack.getPersistentDataContainer();
        if (container.isEmpty()) {
            return null;
        }
        for (Matcher matcher : matchers) {
            switch (matcher.kind()) {
                case KIND_STRING -> {
                    String value = container.has(matcher.key(), PersistentDataType.STRING)
                            ? container.get(matcher.key(), PersistentDataType.STRING) : null;
                    if (matcher.text().equals(value)) {
                        return matcher.rule();
                    }
                }
                case KIND_INTEGER -> {
                    Integer value = container.has(matcher.key(), PersistentDataType.INTEGER)
                            ? container.get(matcher.key(), PersistentDataType.INTEGER) : null;
                    if (value != null && value == matcher.number()) {
                        return matcher.rule();
                    }
                }
                case KIND_BYTE -> {
                    Byte value = container.has(matcher.key(), PersistentDataType.BYTE)
                            ? container.get(matcher.key(), PersistentDataType.BYTE) : null;
                    if (value != null && value == matcher.number()) {
                        return matcher.rule();
                    }
                }
                default -> {
                    if (container.has(matcher.key())) {
                        return matcher.rule();
                    }
                }
            }
        }
        return null;
    }

    private GearDef customDef(Material material, CustomItemRule rule, GearDef fallback) {
        if (fallback != null && fallback.type() == rule.gearType()) {
            return fallback;
        }
        String name = material.getKey().getKey();
        return customDefs.computeIfAbsent(rule.id() + '|' + name,
                key -> new GearDef(name, rule.gearType(), 0.0));
    }
}
