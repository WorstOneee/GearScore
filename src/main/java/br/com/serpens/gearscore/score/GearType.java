package br.com.serpens.gearscore.score;

import java.util.Locale;

public enum GearType {
    HELMET(true),
    CHESTPLATE(true),
    LEGGINGS(true),
    BOOTS(true),
    SWORD(false),
    AXE(false),
    MACE(false),
    TRIDENT(false),
    BOW(false),
    CROSSBOW(false),
    SPEAR(false);

    public static final GearType[] ARMOR_SLOTS = {HELMET, CHESTPLATE, LEGGINGS, BOOTS};

    private final boolean armor;
    private final String id;

    GearType(boolean armor) {
        this.armor = armor;
        this.id = name().toLowerCase(Locale.ROOT);
    }

    public boolean isArmor() {
        return armor;
    }

    public String id() {
        return id;
    }

    public static GearType byId(String id) {
        if (id == null) {
            return null;
        }
        String wanted = id.trim().toLowerCase(Locale.ROOT);
        for (GearType type : values()) {
            if (type.id.equals(wanted)) {
                return type;
            }
        }
        return null;
    }
}
