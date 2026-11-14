package br.com.serpens.gearscore.score;

/**
 * Classification band. {@code name} is a MiniMessage string.
 */
public record Tier(String id, String name, int min) {

    public static final Tier NONE = new Tier("none", "-", Integer.MIN_VALUE);
}
