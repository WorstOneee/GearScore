package br.com.serpens.gearscore.score;

/**
 * Scoring definition of one item material. {@code base} already includes the slot/type multiplier.
 */
public record GearDef(String material, GearType type, double base) {

    public String translationKey() {
        return "item.minecraft." + material;
    }
}
