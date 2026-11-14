package br.com.serpens.gearscore.score;

/**
 * Optional rule for items identified by a PersistentDataContainer entry.
 *
 * @param id         rule name from the configuration
 * @param pdcKey     namespaced key that must be present on the item
 * @param pdcType    "string", "integer", "byte" or "any"
 * @param pdcValue   expected value, ignored when the type is "any"
 * @param gearType   forces the gear type, allowing non-gear materials; may be null
 * @param base       replaces the material base score when not NaN
 * @param bonus      flat points added to the base score
 * @param multiplier applied to the final item score
 */
public record CustomItemRule(String id, String pdcKey, String pdcType, String pdcValue,
                             GearType gearType, double base, double bonus, double multiplier) {

    public boolean overridesBase() {
        return !Double.isNaN(base);
    }
}
