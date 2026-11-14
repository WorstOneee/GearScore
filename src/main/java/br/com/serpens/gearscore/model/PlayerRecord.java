package br.com.serpens.gearscore.model;

import java.util.UUID;

/**
 * The persisted state of one player: the last evaluated equipment, not a historical best.
 *
 * @param updatedAt epoch milliseconds of the evaluation that produced this result
 */
public record PlayerRecord(UUID uuid, String name, int score, int armorScore, int weaponScore,
                           String tier, String server, int rulesVersion, long updatedAt) {

    public boolean sameResult(String otherName, int otherScore, int otherArmor, int otherWeapon,
                              String otherTier, String otherServer, int otherRulesVersion) {
        return score == otherScore
                && armorScore == otherArmor
                && weaponScore == otherWeapon
                && rulesVersion == otherRulesVersion
                && tier.equals(otherTier)
                && server.equals(otherServer)
                && name.equals(otherName);
    }
}
