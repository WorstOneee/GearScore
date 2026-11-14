package br.com.serpens.gearscore.bukkit;

import br.com.serpens.gearscore.score.CustomItemRule;
import br.com.serpens.gearscore.score.GearDef;
import br.com.serpens.gearscore.score.GearSnapshot;
import br.com.serpens.gearscore.score.GearType;
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
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the 4 armor slots, the hotbar and the off hand into a {@link GearSnapshot}.
 * Server thread only. Reads material, real enchantments and the configured PDC keys;
 * never names, lore, model data, durability or amounts.
 */
public final class SnapshotFactory {

    public static final int SLOT_BOOTS = 36;
    public static final int SLOT_LEGGINGS = 37;
    public static final int SLOT_CHESTPLATE = 38;
    public static final int SLOT_HELMET = 39;
    public static final int SLOT_OFFHAND = 40;

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
    private final boolean hotbar;
    private final boolean offhand;
    private final ItemSnapshot[] scratch = new ItemSnapshot[10];
    private final List<String> unknownMaterials = new ArrayList<>();

    public SnapshotFactory(RuleSet rules) {
        for (GearDef def : rules.gear().values()) {
            Material material = Material.getMaterial(def.material().toUpperCase(java.util.Locale.ROOT));
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
        this.hotbar = rules.hotbarWeapons();
        this.offhand = rules.offhandWeapon();
    }

    /** Configured materials that do not exist in this server version. */
    public List<String> unknownMaterials() {
        return unknownMaterials;
    }

    public boolean hasCustomRules() {
        return matchers.length > 0;
    }

    public boolean isGear(Material material) {
        return defs.containsKey(material);
    }

    public GearSnapshot capture(PlayerInventory inventory) {
        ItemSnapshot[] armor = {
                read(inventory.getHelmet(), SLOT_HELMET, GearType.HELMET),
                read(inventory.getChestplate(), SLOT_CHESTPLATE, GearType.CHESTPLATE),
                read(inventory.getLeggings(), SLOT_LEGGINGS, GearType.LEGGINGS),
                read(inventory.getBoots(), SLOT_BOOTS, GearType.BOOTS)
        };
        int count = 0;
        if (hotbar) {
            for (int slot = 0; slot < 9; slot++) {
                ItemSnapshot item = read(inventory.getItem(slot), slot, null);
                if (item != null) {
                    scratch[count++] = item;
                }
            }
        }
        if (offhand) {
            ItemSnapshot item = read(inventory.getItemInOffHand(), SLOT_OFFHAND, null);
            if (item != null) {
                scratch[count++] = item;
            }
        }
        ItemSnapshot[] weapons = count == 0 ? null : Arrays.copyOf(scratch, count);
        Arrays.fill(scratch, 0, count, null);
        return new GearSnapshot(armor, weapons);
    }

    /**
     * @param armorSlot the armor type this slot accepts, or null for a weapon candidate
     */
    private ItemSnapshot read(ItemStack stack, int slot, GearType armorSlot) {
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
        if (armorSlot != null ? def.type() != armorSlot : def.type().isArmor()) {
            return null;
        }

        Map<Enchantment, Integer> enchantments = stack.getEnchantments();
        int size = enchantments.size();
        if (size == 0) {
            return new ItemSnapshot(def, slot, ItemSnapshot.NO_KEYS, ItemSnapshot.NO_LEVELS, custom);
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
        return new ItemSnapshot(def, slot, keys, levels, custom);
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
