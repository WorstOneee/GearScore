package br.com.serpens.gearscore.score;

import java.util.Arrays;

/**
 * The equipment the formula looks at: four armor slots plus the weapon candidates
 * found in the hotbar and off hand. Doubles as the change-detection signature.
 */
public final class GearSnapshot {

    private static final ItemSnapshot[] NO_WEAPONS = new ItemSnapshot[0];

    private final ItemSnapshot[] armor;
    private final ItemSnapshot[] weapons;
    private final int hash;

    /**
     * @param armor   helmet, chestplate, leggings, boots; entries may be null
     * @param weapons weapon candidates, no null entries
     */
    public GearSnapshot(ItemSnapshot[] armor, ItemSnapshot[] weapons) {
        if (armor.length != 4) {
            throw new IllegalArgumentException("armor must have 4 entries");
        }
        this.armor = armor;
        this.weapons = weapons == null || weapons.length == 0 ? NO_WEAPONS : weapons;
        this.hash = 31 * Arrays.hashCode(this.armor) + Arrays.hashCode(this.weapons);
    }

    public ItemSnapshot armor(int index) {
        return armor[index];
    }

    public int weaponCount() {
        return weapons.length;
    }

    public ItemSnapshot weapon(int index) {
        return weapons[index];
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof GearSnapshot that)) {
            return false;
        }
        return hash == that.hash && Arrays.equals(armor, that.armor) && Arrays.equals(weapons, that.weapons);
    }

    @Override
    public int hashCode() {
        return hash;
    }
}
