package br.com.serpens.gearscore.config;

import br.com.serpens.gearscore.TestRules;
import br.com.serpens.gearscore.score.RuleSet;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Updating files left by earlier versions, using the real defaults those versions shipped.
 */
class FileUpdaterTest {

    private static final String CONFIG = TestRules.resource("config.yml");
    private static final String CONFIG_10 = TestRules.resource("legacy/config-1.0.yml");
    private static final String CONFIG_11 = TestRules.resource("legacy/config-1.1.yml");
    private static final String MESSAGES = TestRules.resource("messages.yml");
    private static final String CONFIG_20 = TestRules.resource("legacy/config-2.0.yml");
    private static final String MESSAGES_11 = TestRules.resource("legacy/messages-1.1.yml");
    private static final String MESSAGES_20 = TestRules.resource("legacy/messages-2.0.yml");
    private static final List<String> OLD_CONFIGS = List.of(CONFIG_10, CONFIG_11, CONFIG_20);
    private static final List<String> OLD_MESSAGES = List.of(MESSAGES_11, MESSAGES_20);

    private static YamlConfiguration yaml(String text) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        return yaml;
    }

    @Test
    void versionIsReadFromTheMarker() {
        assertEquals(1, FileUpdater.versionOf(CONFIG_10));
        assertEquals(1, FileUpdater.versionOf(CONFIG_11));
        assertEquals(2, FileUpdater.versionOf(CONFIG_20));
        assertEquals(3, FileUpdater.versionOf(CONFIG));
        assertEquals(1, FileUpdater.versionOf(MESSAGES_11));
        assertEquals(2, FileUpdater.versionOf(MESSAGES_20));
        assertEquals(3, FileUpdater.versionOf(MESSAGES));
    }

    @Test
    void untouchedOldConfigBecomesTheNewDefault() {
        for (String old : OLD_CONFIGS) {
            FileUpdater.Result result = FileUpdater.merge(old, CONFIG, OLD_CONFIGS);
            assertEquals(CONFIG, result.text());
            assertTrue(result.kept().isEmpty(), result.kept().toString());
            assertTrue(result.updated().contains("rules-version"));
            assertFalse(result.text().contains("details-max-items"), "obsolete untouched sections are dropped");
        }
    }

    @Test
    void currentFileIsLeftExactlyAsItIs() {
        FileUpdater.Result result = FileUpdater.merge(CONFIG, CONFIG, OLD_CONFIGS);
        assertEquals(CONFIG, result.text());
        assertTrue(result.updated().isEmpty() && result.kept().isEmpty() && result.added().isEmpty());
        assertEquals(MESSAGES, FileUpdater.merge(MESSAGES, MESSAGES, OLD_MESSAGES).text());
    }

    @Test
    void windowsLineEndingsDoNotCountAsCustomisation() {
        FileUpdater.Result result = FileUpdater.merge(CONFIG_11.replace("\n", "\r\n"), CONFIG, OLD_CONFIGS);
        assertTrue(result.kept().isEmpty(), result.kept().toString());
        assertEquals(CONFIG, result.text());
    }

    @Test
    void customisedSectionsSurviveAndTheRestIsUpdated() throws Exception {
        // A hub: different name, a tuned Protection table and a renamed tier.
        String mine = CONFIG_11
                .replace("  name: \"newera\"", "  name: \"hub\"   # este é o lobby")
                .replace("levels: [30, 65, 105, 150]", "levels: [40, 80, 120, 160]")
                .replace("<gold>Lendário", "<gold>Imortal");
        assertFalse(mine.equals(CONFIG_11));

        FileUpdater.Result result = FileUpdater.merge(mine, CONFIG, OLD_CONFIGS);
        assertEquals(List.of("server", "enchantments", "tiers"), result.kept());
        assertTrue(result.updated().containsAll(List.of("rules-version", "evaluation", "placeholders")));

        // What the administrator wrote is still there, comment included.
        assertTrue(result.text().contains("  name: \"hub\"   # este é o lobby"));
        assertTrue(result.text().contains("levels: [40, 80, 120, 160]"));
        assertTrue(result.text().contains("<gold>Imortal"));

        YamlConfiguration merged = yaml(result.text());
        assertEquals("hub", merged.getString("server.name"));
        assertEquals(3, merged.getInt("file-version"));
        assertEquals(3, merged.getInt("rules-version"));
        assertFalse(merged.contains("display"));
        assertEquals("—", merged.getString("placeholders.fallback"));
        assertFalse(merged.contains("evaluation.weapon-slots"));
        // The kept tiers are the administrator's, untouched.
        assertEquals("<gold>Imortal", merged.getMapList("tiers").get(5).get("name"));

        // The result is a working configuration.
        RuleParser parser = new RuleParser(merged, null);
        RuleSet rules = parser.parse();
        assertTrue(parser.warnings().isEmpty(), parser.warnings().toString());
        assertEquals(160.0, rules.enchantRules(br.com.serpens.gearscore.score.GearType.CHESTPLATE)
                .get("minecraft:protection").points(4, br.com.serpens.gearscore.score.EnchantRule.OverLimit.CAP));
        assertFalse(Settings.from(merged).official());
    }

    @Test
    void aSecondRunChangesNothing() {
        String mine = CONFIG_10.replace("  name: \"newera\"", "  name: \"eventos\"");
        String once = FileUpdater.merge(mine, CONFIG, OLD_CONFIGS).text();
        FileUpdater.Result twice = FileUpdater.merge(once, CONFIG, OLD_CONFIGS);
        assertEquals(once, twice.text());
        assertEquals(3, FileUpdater.versionOf(once));
    }

    @Test
    void aServerOnTheFullInventoryVersionGetsTheBestSetDefaults() throws Exception {
        // 2.0.0 file from a live server: only the name and the official list were edited.
        String mine = CONFIG_20.replace("  name: \"newera\"", "  name: \"survivalnewera\"")
                .replace("    - \"newera\"", "    - \"survivalnewera\"");
        assertFalse(mine.equals(CONFIG_20));
        FileUpdater.Result result = FileUpdater.merge(mine, CONFIG, OLD_CONFIGS);
        assertEquals(List.of("server"), result.kept());
        assertTrue(result.updated().containsAll(List.of("rules-version", "tiers")));

        YamlConfiguration merged = yaml(result.text());
        assertEquals("survivalnewera", merged.getString("server.name"));
        assertTrue(Settings.from(merged).official());
        assertEquals(3, merged.getInt("rules-version"));
        assertEquals(1400, ((Number) merged.getMapList("tiers").get(6).get("min")).intValue());
        assertFalse(merged.contains("display"));
        // Enchantment rules were already the scoped ones and stay as they are.
        assertFalse(result.updated().contains("enchantments"));
        assertFalse(result.kept().contains("enchantments"));
    }

    @Test
    void aManagedRulesVersionIsKeptAndThenRaised() throws Exception {
        String mine = CONFIG_11.replace("rules-version: 1", "rules-version: 7");
        FileUpdater.Result result = FileUpdater.merge(mine, CONFIG, OLD_CONFIGS);
        assertTrue(result.kept().contains("rules-version"));
        assertEquals(7, yaml(result.text()).getInt("rules-version"));
        String raised = FileUpdater.increment(result.text(), "rules-version");
        assertEquals(8, yaml(raised).getInt("rules-version"));
        assertEquals(result.text().length(), raised.length());
        assertEquals("a: 1\n", FileUpdater.increment("a: 1\n", "rules-version"));
    }

    @Test
    void sectionsAddedByTheAdministratorAreNotLost() throws Exception {
        String mine = CONFIG_11 + "\n# anotações da equipe\nminha-secao:\n  valor: 3\n";
        FileUpdater.Result result = FileUpdater.merge(mine, CONFIG, OLD_CONFIGS);
        assertTrue(result.kept().contains("minha-secao"));
        assertTrue(result.text().contains("# anotações da equipe\nminha-secao:\n  valor: 3\n"));
        assertEquals(3, yaml(result.text()).getInt("minha-secao.valor"));
    }

    @Test
    void untouchedMessagesGetTheNewTexts() throws Exception {
        assertEquals(MESSAGES, FileUpdater.merge(MESSAGES_20, MESSAGES, OLD_MESSAGES).text());
        FileUpdater.Result result = FileUpdater.merge(MESSAGES_11, MESSAGES, OLD_MESSAGES);
        assertEquals(MESSAGES, result.text());
        YamlConfiguration merged = yaml(result.text());
        assertEquals("Capacete", merged.getString("slot.helmet"));
        assertTrue(merged.getString("details.item").contains("<slot>"));
        assertFalse(merged.contains("details.more"));
        assertTrue(merged.getString("summary.line").contains("<score> GS"));
        assertFalse(merged.contains("summary.note"));
    }

    @Test
    void customisedMessagesAreKeptAndOnlyTheOthersChange() throws Exception {
        String mine = MESSAGES_11
                .replace("no-permission: \"<prefix><red>Você não tem permissão para isso.\"",
                        "no-permission: \"<prefix><red>Sem permissão, guerreiro.\"")
                .replace("prefix: \"<dark_gray>[<gold>GearScore<dark_gray>]<reset> \"",
                        "prefix: \"<aqua>[Serpens]<reset> \"");
        FileUpdater.Result result = FileUpdater.merge(mine, MESSAGES, OLD_MESSAGES);
        assertEquals(List.of("prefix", "no-permission"), result.kept());

        YamlConfiguration merged = yaml(result.text());
        assertEquals("<aqua>[Serpens]<reset> ", merged.getString("prefix"));
        assertEquals("<prefix><red>Sem permissão, guerreiro.", merged.getString("no-permission"));
        // Everything else moved to the short presentation.
        assertEquals(yaml(MESSAGES).getString("summary.line"), merged.getString("summary.line"));
        assertEquals(yaml(MESSAGES).getString("top.line"), merged.getString("top.line"));
        assertFalse(merged.contains("summary.updated"));
    }

    @Test
    void newDefaultMessagesAvoidDarkGreyAndInternalDetails() throws Exception {
        YamlConfiguration messages = yaml(MESSAGES);
        for (String key : messages.getKeys(true)) {
            if (!messages.isString(key)) {
                continue;
            }
            String text = messages.getString(key);
            assertFalse(text.contains("dark_gray"), key);
            assertFalse(text.contains("newera"), key);
        }
        // The normal answer carries only player, score and tier.
        String line = messages.getString("summary.line");
        for (String forbidden : List.of("<server>", "<updated>", "<base>", "<enchant>", "<slot>", "<item>",
                "<version>")) {
            assertFalse(line.contains(forbidden), forbidden);
        }
        for (String needed : List.of("<player>", "<score>", "<tier>")) {
            assertTrue(line.contains(needed), needed);
        }
        assertEquals(1, messages.getConfigurationSection("summary").getKeys(false).size());
        // The origin server only appears in the line reserved for administrators.
        for (String key : messages.getKeys(true)) {
            if (messages.isString(key) && messages.getString(key).contains("<server>")) {
                assertEquals("details.admin", key);
            }
        }
    }

    @Test
    void summaryRendersAsTheShortLine() throws Exception {
        Messages messages = new Messages(yaml(MESSAGES));
        net.kyori.adventure.text.Component line = messages.get("summary.line",
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("player", "zWorstOne"),
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("score", "1125"),
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component("tier",
                        Messages.mini("<light_purple>Elite")));
        assertEquals("GearScore » zWorstOne • 1125 GS • Elite",
                net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(line));
    }
}
