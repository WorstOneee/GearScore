package br.com.serpens.gearscore.score;

import java.util.Arrays;

/**
 * Every valid piece of gear a player carries: the 36 inventory slots, the 4 armor slots and
 * the off hand, one entry per occupied slot. Entries are kept in a canonical order, so two
 * snapshots are equal exactly when they hold the same items, wherever those items sit.
 * Doubles as the change-detection signature.
 */
public final class GearSnapshot {

    public static final GearSnapshot EMPTY = new GearSnapshot(new ItemSnapshot[0], 0);

    private final ItemSnapshot[] items;
    private final int hash;

    /** Takes the first {@code count} entries of {@code source}; the array is not kept. */
    public GearSnapshot(ItemSnapshot[] source, int count) {
        this.items = Arrays.copyOf(source, count);
        if (count > 1) {
            Arrays.sort(this.items);
        }
        this.hash = Arrays.hashCode(this.items);
    }

    public int size() {
        return items.length;
    }

    public ItemSnapshot item(int index) {
        return items[index];
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof GearSnapshot that)) {
            return false;
        }
        return hash == that.hash && Arrays.equals(items, that.items);
    }

    @Override
    public int hashCode() {
        return hash;
    }
}
