package br.com.serpens.gearscore.score;

import java.util.Arrays;

/**
 * Minimal immutable view of one item: only what the rules read.
 * Amount, durability, name and lore are deliberately absent.
 */
public final class ItemSnapshot {

    public static final String[] NO_KEYS = new String[0];
    public static final int[] NO_LEVELS = new int[0];

    private final GearDef def;
    private final int slot;
    private final String[] enchantKeys;
    private final int[] enchantLevels;
    private final CustomItemRule custom;

    /**
     * @param enchantKeys   namespaced keys sorted ascending
     * @param enchantLevels levels aligned with {@code enchantKeys}
     */
    public ItemSnapshot(GearDef def, int slot, String[] enchantKeys, int[] enchantLevels, CustomItemRule custom) {
        this.def = def;
        this.slot = slot;
        this.enchantKeys = enchantKeys;
        this.enchantLevels = enchantLevels;
        this.custom = custom;
    }

    public GearDef def() {
        return def;
    }

    /** Bukkit inventory slot the item was read from. */
    public int slot() {
        return slot;
    }

    public int enchantCount() {
        return enchantKeys.length;
    }

    public String enchantKey(int index) {
        return enchantKeys[index];
    }

    public int enchantLevel(int index) {
        return enchantLevels[index];
    }

    public CustomItemRule custom() {
        return custom;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ItemSnapshot that)) {
            return false;
        }
        return slot == that.slot
                && custom == that.custom
                && def.equals(that.def)
                && Arrays.equals(enchantLevels, that.enchantLevels)
                && Arrays.equals(enchantKeys, that.enchantKeys);
    }

    @Override
    public int hashCode() {
        int result = def.hashCode();
        result = 31 * result + slot;
        result = 31 * result + Arrays.hashCode(enchantKeys);
        result = 31 * result + Arrays.hashCode(enchantLevels);
        result = 31 * result + (custom == null ? 0 : custom.id().hashCode());
        return result;
    }
}
