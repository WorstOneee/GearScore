package br.com.serpens.gearscore.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.ConfigurationSection;

import java.util.HashMap;
import java.util.Map;

/**
 * MiniMessage texts from messages.yml.
 */
public final class Messages {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final Map<String, String> texts = new HashMap<>();
    private final TagResolver prefix;

    public Messages(ConfigurationSection root) {
        for (String key : root.getKeys(true)) {
            if (root.isString(key)) {
                texts.put(key, root.getString(key));
            }
        }
        this.prefix = Placeholder.parsed("prefix", texts.getOrDefault("prefix", ""));
    }

    public Component get(String key, TagResolver... resolvers) {
        String text = texts.get(key);
        if (text == null) {
            return Component.text(key);
        }
        TagResolver.Builder builder = TagResolver.builder().resolver(prefix);
        for (TagResolver resolver : resolvers) {
            builder.resolver(resolver);
        }
        return MINI.deserialize(text, builder.build());
    }

    public String raw(String key) {
        return texts.getOrDefault(key, key);
    }

    public static Component mini(String text) {
        return MINI.deserialize(text);
    }

    /** Relative time such as "há 5 min". */
    public String ago(long epochMillis, long now) {
        long seconds = Math.max(0L, (now - epochMillis) / 1000L);
        if (seconds < 5) {
            return raw("time.now");
        }
        if (seconds < 60) {
            return raw("time.seconds").replace("{n}", Long.toString(seconds));
        }
        if (seconds < 3600) {
            return raw("time.minutes").replace("{n}", Long.toString(seconds / 60));
        }
        if (seconds < 86_400) {
            return raw("time.hours").replace("{n}", Long.toString(seconds / 3600));
        }
        return raw("time.days").replace("{n}", Long.toString(seconds / 86_400));
    }
}
