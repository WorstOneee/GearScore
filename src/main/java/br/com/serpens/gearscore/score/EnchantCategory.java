package br.com.serpens.gearscore.score;

import java.util.Locale;

public enum EnchantCategory {
    COMBAT,
    CONDITIONAL,
    MOB,
    MOBILITY,
    UTILITY,
    CURSE;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static EnchantCategory byId(String id) {
        if (id == null) {
            return null;
        }
        try {
            return valueOf(id.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
