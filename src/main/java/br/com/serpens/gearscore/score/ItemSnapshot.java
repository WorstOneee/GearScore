package br.com.serpens.gearscore.score;

import java.util.Arrays;

/**
 * Minimal immutable view of one item: only what the rules read.
 * Slot, amount, durability, name and lore are deliberately absent, so moving an item around
 * or wearing it down does not make it a different item.
 */
public final class ItemSnapshot implements Comparable<ItemSnapshot> {

    public static final String[] NO_KEYS = new String[0];
    public static final int[] NO_LEVELS = new int[0];

    private final GearDef def;
    private final String[] enchantKeys;
    private final int[] enchantLevels;
    private final CustomItemRule custom;
    private final int hash;

    /**
     * @param enchantKeys   namespaced keys sorted ascending
     * @param enchantLevels levels aligned with {@code enchantKeys}
     */
    public ItemSnapshot(GearDef def, String[] enchantKeys, int[] enchantLevels, CustomItemRule custom) {
        this.def = def;
        this.enchantKeys = enchantKeys;
        this.enchantLevels = enchantLevels;
        this.custom = custom;
        int result = def.hashCode();
        result = 31 * result + Arrays.hashCode(enchantKeys);
        result = 31 * result + Arrays.hashCode(enchantLevels);
        result = 31 * result + (custom == null ? 0 : custom.id().hashCode());
        this.hash = result;
    }

    public GearDef def() {
        return def;
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
        return hash == that.hash
                && custom == that.custom
                && def.equals(that.def)
                && Arrays.equals(enchantLevels, that.enchantLevels)
                && Arrays.equals(enchantKeys, that.enchantKeys);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    /** Total order consistent with equals, used to give an inventory a canonical form. */
    @Override
    public int compareTo(ItemSnapshot that) {
        if (this == that) {
            return 0;
        }
        int order = Integer.compare(hash, that.hash);
        if (order != 0) {
            return order;
        }
        order = def.material().compareTo(that.def.material());
        if (order != 0) {
            return order;
        }
        order = def.type().compareTo(that.def.type());
        if (order != 0) {
            return order;
        }
        order = Double.compare(def.base(), that.def.base());
        if (order != 0) {
            return order;
        }
        order = Arrays.compare(enchantKeys, that.enchantKeys);
        if (order != 0) {
            return order;
        }
        order = Arrays.compare(enchantLevels, that.enchantLevels);
        if (order != 0) {
            return order;
        }
        String mine = custom == null ? "" : custom.id();
        String theirs = that.custom == null ? "" : that.custom.id();
        return mine.compareTo(theirs);
    }
}
