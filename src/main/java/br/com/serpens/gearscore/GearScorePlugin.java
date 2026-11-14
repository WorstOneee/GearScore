package br.com.serpens.gearscore;

import br.com.serpens.gearscore.bukkit.ConnectionListener;
import br.com.serpens.gearscore.bukkit.GearListener;
import br.com.serpens.gearscore.bukkit.SnapshotFactory;
import br.com.serpens.gearscore.command.GearScoreCommand;
import br.com.serpens.gearscore.config.DatabaseSettings;
import br.com.serpens.gearscore.config.Messages;
import br.com.serpens.gearscore.config.RuleParser;
import br.com.serpens.gearscore.config.Settings;
import br.com.serpens.gearscore.engine.Metrics;
import br.com.serpens.gearscore.engine.ScoreEngine;
import br.com.serpens.gearscore.hook.GearScoreExpansion;
import br.com.serpens.gearscore.model.CachedScore;
import br.com.serpens.gearscore.model.PlayerRecord;
import br.com.serpens.gearscore.score.GearSnapshot;
import br.com.serpens.gearscore.score.RuleSet;
import br.com.serpens.gearscore.score.Tier;
import br.com.serpens.gearscore.storage.Database;
import br.com.serpens.gearscore.storage.LookupService;
import br.com.serpens.gearscore.storage.PoolFactory;
import br.com.serpens.gearscore.storage.ScoreRepository;
import br.com.serpens.gearscore.storage.WriteQueue;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class GearScorePlugin extends JavaPlugin {

    /** A tier rendered once per configuration load. */
    public record TierView(Component component, String plain, String legacy) {
    }

    private final Metrics metrics = new Metrics();
    private final ConcurrentHashMap<UUID, CachedScore> remoteScores = new ConcurrentHashMap<>();

    private volatile Settings settings;
    private volatile Messages messages;
    private volatile RuleSet rules;
    private volatile Map<String, TierView> tierViews = Map.of();
    private volatile SnapshotFactory snapshots;

    private Database database;
    private WriteQueue writes;
    private LookupService lookups;
    private ScoreEngine engine;
    private BukkitTask tickTask;
    private long shutdownTimeoutMillis;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveIfMissing("database.yml");
        saveIfMissing("messages.yml");

        DatabaseSettings databaseSettings;
        try {
            RuleParser parser = new RuleParser(getConfig(), GearScorePlugin::vanillaMaxLevel);
            this.rules = parser.parse();
            this.settings = Settings.from(getConfig());
            this.messages = loadMessages();
            this.tierViews = renderTiers(rules);
            parser.warnings().forEach(warning -> getLogger().warning("config.yml: " + warning));
            databaseSettings = DatabaseSettings.from(
                    YamlConfiguration.loadConfiguration(new File(getDataFolder(), "database.yml")));
        } catch (RuntimeException e) {
            getLogger().log(Level.SEVERE, "Invalid configuration, disabling: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        if (databaseSettings.enabled()) {
            this.shutdownTimeoutMillis = databaseSettings.shutdownTimeoutMillis();
            this.database = PoolFactory.create(databaseSettings, getLogger(), metrics);
            ScoreRepository repository = new ScoreRepository(databaseSettings.tablePrefix());
            this.lookups = new LookupService(database, repository, metrics,
                    databaseSettings.readerThreads(), databaseSettings.maxQueuedReads(),
                    databaseSettings.offlineCacheMillis(), databaseSettings.offlineCacheSize(),
                    settings.maxEntries(), settings.topCacheMillis(), settings.positionCacheMillis());
            if (settings.official()) {
                this.writes = new WriteQueue(database, repository, metrics, getLogger(),
                        databaseSettings.flushIntervalMillis(), databaseSettings.batchSize(),
                        databaseSettings.maxPendingWrites());
            }
        } else {
            getLogger().warning("Database disabled in database.yml: scores are kept in memory only.");
        }

        if (settings.official()) {
            this.snapshots = new SnapshotFactory(rules);
            if (!snapshots.unknownMaterials().isEmpty()) {
                getLogger().info("Configured items not present in this version (ignored): "
                        + String.join(", ", snapshots.unknownMaterials()));
            }
            this.engine = new ScoreEngine(rules, settings.serverName(), this::capture, this::persist, metrics,
                    System::currentTimeMillis);
            getServer().getPluginManager().registerEvents(new GearListener(this), this);
        }
        getServer().getPluginManager().registerEvents(new ConnectionListener(this), this);

        GearScoreCommand command = new GearScoreCommand(this);
        PluginCommand registered = getCommand("gearscore");
        if (registered != null) {
            registered.setExecutor(command);
            registered.setTabCompleter(command);
        }

        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new GearScoreExpansion(this).register();
            getLogger().info("PlaceholderAPI expansion registered.");
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            handleJoin(player);
        }

        getLogger().info("Server \"" + settings.serverName() + "\" is "
                + (settings.official()
                ? "an official source: equipment is evaluated and persisted."
                : "not an official source: scores are only read from the database."));
    }

    @Override
    public void onDisable() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        if (engine != null) {
            // Plugins are disabled before players are disconnected, so finish what is pending.
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (engine.isPending(player.getUniqueId())) {
                    engine.evaluateNow(player.getUniqueId());
                }
            }
        }
        if (writes != null) {
            int lost = writes.shutdown(shutdownTimeoutMillis);
            if (lost > 0) {
                getLogger().warning(lost + " pending score update(s) could not be written before shutdown.");
            }
        }
        if (lookups != null) {
            lookups.shutdown();
        }
        if (database != null) {
            database.close();
        }
    }

    // ---- configuration -------------------------------------------------------------------

    /**
     * Reloads rules, tiers, messages and evaluation settings. Online players are queued for a
     * new evaluation and processed under the per-tick limit; offline players are untouched.
     *
     * @return configuration warnings
     */
    public List<String> reload() {
        reloadConfig();
        RuleParser parser = new RuleParser(getConfig(), GearScorePlugin::vanillaMaxLevel);
        RuleSet parsed = parser.parse();
        Settings loaded = Settings.from(getConfig());
        Messages loadedMessages = loadMessages();

        Settings current = this.settings;
        this.settings = new Settings(current.serverName(), current.official(), loaded.debounceTicks(),
                loaded.maxPerTick(), loaded.ignoredWorlds(), loaded.pageSize(), current.maxEntries(),
                current.topCacheMillis(), current.positionCacheMillis(), loaded.fallback());
        this.messages = loadedMessages;
        this.rules = parsed;
        this.tierViews = renderTiers(parsed);
        metrics.clearUnknownEnchantments();

        if (engine != null) {
            this.snapshots = new SnapshotFactory(parsed);
            engine.setRules(parsed);
            engine.requestAll(Bukkit.getCurrentTick(), settings.debounceTicks());
            ensureTicking();
        }
        return parser.warnings();
    }

    private Messages loadMessages() {
        YamlConfiguration file = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "messages.yml"));
        InputStream defaults = getResource("messages.yml");
        if (defaults != null) {
            file.setDefaults(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaults, StandardCharsets.UTF_8)));
            file.options().copyDefaults(true);
        }
        return new Messages(file);
    }

    private void saveIfMissing(String resource) {
        if (!new File(getDataFolder(), resource).exists()) {
            saveResource(resource, false);
        }
    }

    private static Map<String, TierView> renderTiers(RuleSet rules) {
        Map<String, TierView> views = new HashMap<>();
        for (Tier tier : rules.tiers()) {
            Component component = Messages.mini(tier.name());
            views.put(tier.id(), new TierView(component,
                    PlainTextComponentSerializer.plainText().serialize(component),
                    LegacyComponentSerializer.legacySection().serialize(component)));
        }
        return Map.copyOf(views);
    }

    private static int vanillaMaxLevel(String key) {
        NamespacedKey namespaced = NamespacedKey.fromString(key);
        if (namespaced == null) {
            return 0;
        }
        Enchantment enchantment = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(namespaced);
        return enchantment == null ? 0 : enchantment.getMaxLevel();
    }

    // ---- evaluation ----------------------------------------------------------------------

    private GearSnapshot capture(UUID id) {
        Player player = Bukkit.getPlayer(id);
        if (player == null || settings.ignoredWorlds().contains(player.getWorld().getName())) {
            return null;
        }
        return snapshots.capture(player.getInventory());
    }

    private void persist(PlayerRecord record) {
        if (writes != null) {
            writes.submit(record);
        }
    }

    /** Queues an evaluation for a tracked player. Constant time; safe to call from any event. */
    public void requestEvaluation(UUID id) {
        if (engine != null && engine.request(id, Bukkit.getCurrentTick(), settings.debounceTicks())) {
            ensureTicking();
        }
    }

    // The task only exists while something is queued; an idle server runs nothing.
    private void ensureTicking() {
        if (tickTask == null && engine.hasPending()) {
            tickTask = getServer().getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
        }
    }

    private void tick() {
        engine.tick(Bukkit.getCurrentTick(), settings.maxPerTick());
        if (!engine.hasPending() && tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
    }

    public void handleJoin(Player player) {
        UUID id = player.getUniqueId();
        if (engine != null) {
            engine.track(id, player.getName());
            requestEvaluation(id);
        }
        if (lookups == null) {
            return;
        }
        lookups.loadByUuid(id).whenComplete((record, error) -> {
            if (error != null || record == null || !isEnabled()) {
                return;
            }
            try {
                getServer().getScheduler().runTask(this, () -> {
                    if (engine != null) {
                        engine.seedPersisted(id, record);
                    } else if (Bukkit.getPlayer(id) != null) {
                        remoteScores.put(id, new CachedScore(record, null));
                    }
                });
            } catch (IllegalPluginAccessException ignored) {
                // plugin disabled while the query was running
            }
        });
    }

    public void handleQuit(Player player) {
        UUID id = player.getUniqueId();
        if (engine != null) {
            if (engine.isPending(id)) {
                engine.evaluateNow(id);
            }
            engine.forget(id);
        }
        remoteScores.remove(id);
        if (lookups != null) {
            lookups.forget(id);
        }
    }

    // ---- read access (memory only) -------------------------------------------------------

    /** Cached score of an online player. Never touches the database or an inventory. */
    public CachedScore cached(UUID id) {
        CachedScore score = engine != null ? engine.cached(id) : remoteScores.get(id);
        if (score != null) {
            metrics.cacheHits.increment();
        } else {
            metrics.cacheMisses.increment();
        }
        return score;
    }

    public TierView tierView(String id) {
        TierView view = tierViews.get(id);
        return view != null ? view : new TierView(Component.text(id), id, id);
    }

    public Settings settings() {
        return settings;
    }

    public Messages messages() {
        return messages;
    }

    public RuleSet rules() {
        return rules;
    }

    public Metrics metrics() {
        return metrics;
    }

    public SnapshotFactory snapshots() {
        return snapshots;
    }

    public ScoreEngine engine() {
        return engine;
    }

    public LookupService lookups() {
        return lookups;
    }

    public WriteQueue writes() {
        return writes;
    }

    public Database database() {
        return database;
    }
}
