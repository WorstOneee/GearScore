package br.com.serpens.gearscore;

import br.com.serpens.gearscore.bukkit.ConnectionListener;
import br.com.serpens.gearscore.bukkit.GearListener;
import br.com.serpens.gearscore.bukkit.SnapshotFactory;
import br.com.serpens.gearscore.command.GearScoreCommand;
import br.com.serpens.gearscore.config.DatabaseSettings;
import br.com.serpens.gearscore.config.FileUpdater;
import br.com.serpens.gearscore.config.Messages;
import br.com.serpens.gearscore.config.RuleParser;
import br.com.serpens.gearscore.config.Settings;
import br.com.serpens.gearscore.engine.Metrics;
import br.com.serpens.gearscore.engine.RemoteScores;
import br.com.serpens.gearscore.engine.ScoreEngine;
import br.com.serpens.gearscore.hook.GearScoreExpansion;
import br.com.serpens.gearscore.model.CachedScore;
import br.com.serpens.gearscore.model.PlayerRecord;
import br.com.serpens.gearscore.score.GearSnapshot;
import br.com.serpens.gearscore.score.RuleSet;
import br.com.serpens.gearscore.score.Tier;
import br.com.serpens.gearscore.storage.Database;
import br.com.serpens.gearscore.storage.LookupService;
import br.com.serpens.gearscore.storage.PendingSpool;
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
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public final class GearScorePlugin extends JavaPlugin {

    /** A tier rendered once per configuration load. */
    public record TierView(Component component, String plain, String legacy) {
    }

    /** Owed results submitted again per second. */
    private static final int RECOVERY_BATCH = 50;
    /** Minimum time between rewrites of the pending-writes file while the database is down. */
    private static final long SPOOL_INTERVAL_MILLIS = 30_000L;

    private final Metrics metrics = new Metrics();
    private final Set<UUID> online = new HashSet<>();

    private volatile Settings settings;
    private volatile Messages messages;
    private volatile RuleSet rules;
    private volatile Map<String, TierView> tierViews = Map.of();
    private volatile SnapshotFactory snapshots;

    private Database database;
    private WriteQueue writes;
    private LookupService lookups;
    private ScoreEngine engine;
    private RemoteScores remote;
    private boolean positionsEnabled;
    private BukkitTask tickTask;
    private BukkitTask maintenanceTask;
    private long shutdownTimeoutMillis;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveIfMissing("database.yml");
        saveIfMissing("messages.yml");
        updateFile("config.yml",
                List.of("legacy/config-1.0.yml", "legacy/config-1.1.yml", "legacy/config-2.0.yml"), true);
        updateFile("messages.yml", List.of("legacy/messages-1.1.yml", "legacy/messages-2.0.yml"), false);
        reloadConfig();

        DatabaseSettings databaseSettings;
        try {
            RuleParser parser = new RuleParser(rulesFile(), GearScorePlugin::vanillaMaxLevel);
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
                    settings.maxEntries(), settings.topCacheMillis(), settings.positionRefreshMillis());
            if (settings.official()) {
                this.writes = new WriteQueue(database, repository, metrics, getLogger(),
                        databaseSettings.flushIntervalMillis(), databaseSettings.batchSize(),
                        databaseSettings.maxPendingWrites(),
                        new PendingSpool(new File(getDataFolder(), "pending-writes.dat").toPath()),
                        SPOOL_INTERVAL_MILLIS);
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
            this.engine = new ScoreEngine(rules, settings.serverName(), this::capture, new ScoreEngine.Sink() {
                @Override
                public boolean submit(PlayerRecord record, long sequence, boolean recoverable) {
                    return persist(record, sequence, recoverable);
                }

                @Override
                public boolean hasRoom() {
                    return writes == null || writes.hasRoom();
                }
            }, metrics, System::currentTimeMillis);
            if (writes != null) {
                writes.setListener(engine);
                // Entries a previous run could not confirm; loaded off the server thread.
                writes.recoverSpool();
            }
            getServer().getPluginManager().registerEvents(new GearListener(this), this);
        } else if (lookups != null) {
            Database gate = database;
            LookupService reader = lookups;
            this.remote = new RemoteScores(new RemoteScores.Loader() {
                @Override
                public boolean available() {
                    return gate.isAvailable();
                }

                @Override
                public CompletableFuture<Map<UUID, PlayerRecord>> load(List<UUID> ids) {
                    return reader.loadByUuids(ids);
                }
            }, metrics, settings.remoteRecheckMillis(), settings.remoteIntervalMillis(),
                    settings.remoteBatchSize(), settings.remoteMaxBatches());
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
            this.positionsEnabled = lookups != null;
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
        if (maintenanceTask != null) {
            maintenanceTask.cancel();
            maintenanceTask = null;
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
            if (engine != null) {
                // Results the queue had refused: from here on nobody else will resend them.
                engine.handOver();
            }
            WriteQueue.ShutdownResult result = writes.shutdown(shutdownTimeoutMillis);
            if (result.unconfirmed() > 0) {
                getLogger().warning(result.unconfirmed() + " score update(s) were not confirmed by the database "
                        + "before shutdown; " + result.savedToDisk() + " saved to pending-writes.dat and will be "
                        + "written on the next start.");
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
        RuleParser parser = new RuleParser(rulesFile(), GearScorePlugin::vanillaMaxLevel);
        RuleSet parsed = parser.parse();
        Settings loaded = Settings.from(getConfig());
        Messages loadedMessages = loadMessages();

        Settings current = this.settings;
        this.settings = new Settings(current.serverName(), current.official(), loaded.debounceTicks(),
                loaded.maxPerTick(), loaded.ignoredWorlds(), loaded.pageSize(), current.maxEntries(),
                current.topCacheMillis(), current.positionRefreshMillis(), loaded.fallback(),
                current.remoteIntervalMillis(), current.remoteRecheckMillis(), current.remoteBatchSize(),
                current.remoteMaxBatches());
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

    // Scoring rules come from the file exactly as written, without the bundled defaults
    // filling gaps: an enchantment the administrator removed must be worth zero, not come
    // back with its default value.
    private YamlConfiguration rulesFile() {
        return YamlConfiguration.loadConfiguration(new File(getDataFolder(), "config.yml"));
    }

    /**
     * Updates a file written by an older version. Sections the administrator never changed
     * take the new defaults, edited sections are kept, and the previous file is saved next
     * to it before anything is written.
     */
    private void updateFile(String name, List<String> legacyResources, boolean scoringRules) {
        File file = new File(getDataFolder(), name);
        try {
            String existing = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            String current = resourceText(name);
            if (FileUpdater.versionOf(existing) >= FileUpdater.versionOf(current)) {
                return;
            }
            List<String> legacy = new java.util.ArrayList<>();
            for (String resource : legacyResources) {
                legacy.add(resourceText(resource));
            }
            FileUpdater.Result result = FileUpdater.merge(existing, current, legacy);
            String text = result.text();
            if (scoringRules && result.kept().contains("rules-version")) {
                // The formula changed, so the stored results must be told apart even when
                // the administrator manages this number.
                text = FileUpdater.increment(text, "rules-version");
            }
            String stamp = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            File backup = new File(getDataFolder(), name + ".bak-" + stamp);
            Files.copy(file.toPath(), backup.toPath());
            Files.writeString(file.toPath(), text, StandardCharsets.UTF_8);

            getLogger().info(name + " updated for this version; previous file saved as " + backup.getName() + ".");
            if (!result.updated().isEmpty() || !result.added().isEmpty()) {
                List<String> changed = new java.util.ArrayList<>(result.updated());
                changed.addAll(result.added());
                getLogger().info(name + ": sections set to the new defaults: " + String.join(", ", changed));
            }
            if (!result.kept().isEmpty()) {
                getLogger().warning(name + ": customised sections kept as they were, please review them: "
                        + String.join(", ", result.kept()));
            }
        } catch (IOException | RuntimeException e) {
            getLogger().log(Level.WARNING, "Could not update " + name + ", keeping it as it is: " + e.getMessage());
        }
    }

    private String resourceText(String name) throws IOException {
        try (InputStream in = getResource(name)) {
            if (in == null) {
                throw new IOException("missing resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
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

    // Without a database there is nothing to wait for: the result counts as delivered.
    private boolean persist(PlayerRecord record, long sequence, boolean recoverable) {
        if (writes == null) {
            return true;
        }
        boolean accepted = writes.submit(record, sequence, recoverable);
        ensureMaintenance();
        return accepted;
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
        online.add(id);
        if (engine != null) {
            long session = engine.track(id, player.getName());
            requestEvaluation(id);
            if (lookups != null) {
                seedFromDatabase(id, session);
            }
        } else if (remote != null) {
            long now = System.currentTimeMillis();
            remote.open(id, now);
            remote.poll(now);
        }
        if (positionsEnabled) {
            lookups.trackPosition(id);
        }
        ensureMaintenance();
    }

    // Official source: learn what is stored so an identical result is not written again.
    // The score itself comes from the local evaluation, so a failed read costs at most one
    // redundant write.
    private void seedFromDatabase(UUID id, long session) {
        lookups.loadByUuid(id).whenComplete((record, error) -> {
            if (error != null || record == null || !isEnabled()) {
                return;
            }
            try {
                getServer().getScheduler().runTask(this, () -> engine.seedPersisted(id, session, record));
            } catch (IllegalPluginAccessException ignored) {
                // plugin disabled while the query was running
            }
        });
    }

    public void handleQuit(Player player) {
        UUID id = player.getUniqueId();
        online.remove(id);
        if (engine != null) {
            if (engine.isPending(id)) {
                engine.evaluateNow(id);
            }
            engine.forget(id);
        }
        if (remote != null) {
            remote.close(id);
        }
        if (lookups != null) {
            lookups.forget(id);
        }
    }

    // ---- periodic database refreshes -----------------------------------------------------

    // One task, once per second, alive only while players are online and there is something
    // to refresh: official scores on a query server, or ranking positions for placeholders.
    // It never looks at inventories; the reads it starts run on the database threads.
    private void ensureMaintenance() {
        if (maintenanceTask == null && needsMaintenance()) {
            maintenanceTask = getServer().getScheduler().runTaskTimer(this, this::maintain, 20L, 20L);
        }
    }

    private boolean needsMaintenance() {
        if (online.isEmpty()) {
            return false;
        }
        // On an official source the task only lives while a write is unconfirmed or owed.
        return remote != null || positionsEnabled
                || (writes != null && engine != null
                && (engine.hasOwed() || writes.pendingCount() > 0 || writes.backlogCount() > 0));
    }

    private void maintain() {
        if (!needsMaintenance()) {
            maintenanceTask.cancel();
            maintenanceTask = null;
            return;
        }
        long now = System.currentTimeMillis();
        if (remote != null) {
            remote.poll(now);
        }
        if (positionsEnabled) {
            lookups.refreshPositions(now);
        }
        if (engine != null && writes != null && engine.hasOwed()) {
            // Results the write queue refused or dropped go back in, a few per second and only
            // while there is room. They come from memory: no inventory is read again.
            engine.recover(RECOVERY_BATCH);
        }
    }

    // ---- read access (memory only) -------------------------------------------------------

    /** Cached score of an online player. Never touches the database or an inventory. */
    public CachedScore cached(UUID id) {
        CachedScore score = engine != null ? engine.cached(id) : remote != null ? remote.get(id) : null;
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

    public RemoteScores remote() {
        return remote;
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
