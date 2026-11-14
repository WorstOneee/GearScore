package br.com.serpens.gearscore.command;

import br.com.serpens.gearscore.GearScorePlugin;
import br.com.serpens.gearscore.bukkit.SnapshotFactory;
import br.com.serpens.gearscore.config.Messages;
import br.com.serpens.gearscore.engine.Metrics;
import br.com.serpens.gearscore.engine.ScoreEngine;
import br.com.serpens.gearscore.model.CachedScore;
import br.com.serpens.gearscore.model.PlayerRecord;
import br.com.serpens.gearscore.score.GearType;
import br.com.serpens.gearscore.score.ScoreResult;
import br.com.serpens.gearscore.score.ScoreResult.EnchantScore;
import br.com.serpens.gearscore.score.ScoreResult.ItemScore;
import br.com.serpens.gearscore.storage.Database;
import br.com.serpens.gearscore.storage.LookupService;
import br.com.serpens.gearscore.storage.WriteQueue;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionException;

public final class GearScoreCommand implements CommandExecutor, TabCompleter {

    private static final String PERM_USE = "gearscore.use";
    private static final String PERM_OTHERS = "gearscore.view.others";
    private static final String PERM_DETAILS = "gearscore.details";
    private static final String PERM_DETAILS_OTHERS = "gearscore.details.others";
    private static final String PERM_TOP = "gearscore.top";
    private static final String PERM_RELOAD = "gearscore.admin.reload";
    private static final String PERM_DIAG = "gearscore.admin.diag";

    private final GearScorePlugin plugin;

    public GearScoreCommand(GearScorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            self(sender, false);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "ver", "view" -> {
                if (args.length < 2) {
                    self(sender, false);
                } else if (allowed(sender, PERM_OTHERS)) {
                    other(sender, args[1], false);
                }
            }
            case "detalhes", "details" -> {
                if (args.length < 2) {
                    self(sender, true);
                } else if (allowed(sender, PERM_DETAILS_OTHERS)) {
                    other(sender, args[1], true);
                }
            }
            case "top", "ranking" -> {
                if (allowed(sender, PERM_TOP)) {
                    top(sender, args.length > 1 ? args[1] : "1");
                }
            }
            case "reload" -> {
                if (allowed(sender, PERM_RELOAD)) {
                    reload(sender);
                }
            }
            case "diag", "diagnostico" -> {
                if (allowed(sender, PERM_DIAG)) {
                    diagnostics(sender);
                }
            }
            case "ajuda", "help" -> help(sender);
            default -> send(sender, "unknown-command");
        }
        return true;
    }

    // ---- queries -------------------------------------------------------------------------

    private void self(CommandSender sender, boolean details) {
        if (!(sender instanceof Player player)) {
            send(sender, "players-only");
            return;
        }
        if (!allowed(sender, details ? PERM_DETAILS : PERM_USE)) {
            return;
        }
        CachedScore score = plugin.cached(player.getUniqueId());
        if (score == null) {
            send(sender, "not-ready");
        } else if (details) {
            details(sender, score);
        } else {
            summary(sender, score.record(), score.detail());
        }
    }

    private void other(CommandSender sender, String name, boolean details) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            CachedScore score = plugin.cached(online.getUniqueId());
            if (score == null) {
                send(sender, "not-ready");
            } else if (details) {
                details(sender, score);
            } else {
                summary(sender, score.record(), score.detail());
            }
            return;
        }
        if (details) {
            send(sender, "details.unavailable");
            return;
        }
        LookupService lookups = plugin.lookups();
        if (lookups == null) {
            send(sender, "db-disabled");
            return;
        }
        lookups.lookupByName(name).whenComplete((found, error) -> sync(() -> {
            if (error != null) {
                send(sender, errorKey(error));
            } else if (found.isEmpty()) {
                send(sender, "not-found", Placeholder.unparsed("player", name));
            } else {
                summary(sender, found.get(), null);
            }
        }));
    }

    private void summary(CommandSender sender, PlayerRecord record, ScoreResult detail) {
        Messages messages = plugin.messages();
        send(sender, "summary.header",
                Placeholder.unparsed("player", record.name()),
                number("score", record.score()),
                Placeholder.component("tier", plugin.tierView(record.tier()).component()));
        if (detail != null) {
            send(sender, "summary.armor",
                    number("armor", detail.armorScore()),
                    number("armor_base", detail.armorBase()),
                    number("armor_enchant", detail.armorEnchant()));
            ItemScore weapon = detail.weapon();
            if (weapon == null) {
                send(sender, "summary.weapon-none");
            } else {
                send(sender, "summary.weapon",
                        number("weapon", detail.weaponScore()),
                        number("weapon_base", detail.weaponBase()),
                        number("weapon_enchant", detail.weaponEnchant()),
                        Placeholder.component("item", itemName(weapon)),
                        Placeholder.unparsed("slot", slotName(weapon.item().slot())));
            }
        } else {
            send(sender, "summary.armor-simple", number("armor", record.armorScore()));
            send(sender, "summary.weapon-simple", number("weapon", record.weaponScore()));
        }
        send(sender, "summary.updated",
                Placeholder.unparsed("updated", messages.ago(record.updatedAt(), System.currentTimeMillis())),
                Placeholder.unparsed("server", record.server()),
                number("version", record.rulesVersion()));
        send(sender, "summary.note");
    }

    private void details(CommandSender sender, CachedScore score) {
        ScoreResult detail = score.detail();
        if (detail == null) {
            send(sender, "details.unavailable");
            return;
        }
        send(sender, "details.header",
                Placeholder.unparsed("player", score.record().name()),
                number("score", detail.total()));
        for (int i = 0; i < 4; i++) {
            item(sender, plugin.messages().raw("slot." + GearType.ARMOR_SLOTS[i].id()), detail.armor(i));
        }
        ItemScore weapon = detail.weapon();
        String weaponSlot = plugin.messages().raw("slot.weapon");
        if (weapon != null) {
            weaponSlot += " [" + slotName(weapon.item().slot()) + "]";
        }
        item(sender, weaponSlot, weapon);
        send(sender, "details.weapon-note");
    }

    private void item(CommandSender sender, String slot, ItemScore item) {
        if (item == null) {
            send(sender, "details.empty", Placeholder.unparsed("slot", slot));
            return;
        }
        send(sender, "details.item",
                Placeholder.unparsed("slot", slot),
                Placeholder.component("item", itemName(item)),
                decimal("total", item.total()),
                decimal("base", item.base()),
                decimal("enchant", item.enchant()));
        for (EnchantScore enchant : item.enchants()) {
            String note = switch (enchant.status()) {
                case COUNTED -> "";
                case CAPPED -> plugin.messages().raw("note.capped");
                case OVER_LIMIT -> plugin.messages().raw("note.over-limit");
                case INCOMPATIBLE -> plugin.messages().raw("note.incompatible");
                case UNKNOWN -> plugin.messages().raw("note.unknown");
            };
            send(sender, "details.enchant",
                    Placeholder.component("name", Component.translatable(
                            "enchantment." + enchant.key().replace(':', '.'), enchant.key())),
                    Placeholder.component("level", level(enchant.level())),
                    decimal("points", enchant.points()),
                    Placeholder.unparsed("note", note));
        }
    }

    private void top(CommandSender sender, String pageArgument) {
        LookupService lookups = plugin.lookups();
        if (lookups == null) {
            send(sender, "db-disabled");
            return;
        }
        int requested;
        try {
            requested = Integer.parseInt(pageArgument);
        } catch (NumberFormatException e) {
            send(sender, "invalid-page");
            return;
        }
        if (requested < 1) {
            send(sender, "invalid-page");
            return;
        }
        lookups.top().whenComplete((snapshot, error) -> sync(() -> {
            if (error != null) {
                send(sender, errorKey(error));
                return;
            }
            List<PlayerRecord> rows = snapshot.rows();
            if (rows.isEmpty()) {
                send(sender, "top.empty");
                return;
            }
            Messages messages = plugin.messages();
            int pageSize = plugin.settings().pageSize();
            int pages = (rows.size() + pageSize - 1) / pageSize;
            int page = Math.min(requested, pages);
            long now = System.currentTimeMillis();
            send(sender, "top.header", number("page", page), number("pages", pages),
                    Placeholder.unparsed("age", messages.ago(snapshot.loadedAt(), now)));
            for (int i = (page - 1) * pageSize, end = Math.min(rows.size(), page * pageSize); i < end; i++) {
                PlayerRecord row = rows.get(i);
                send(sender, "top.line",
                        number("position", i + 1),
                        Placeholder.unparsed("player", row.name()),
                        number("score", row.score()),
                        Placeholder.component("tier", plugin.tierView(row.tier()).component()),
                        Placeholder.unparsed("updated", messages.ago(row.updatedAt(), now)));
            }
            send(sender, "top.footer");
        }));
    }

    // ---- administration ------------------------------------------------------------------

    private void reload(CommandSender sender) {
        try {
            List<String> warnings = plugin.reload();
            for (String warning : warnings) {
                send(sender, "reload.warning", Placeholder.unparsed("warning", warning));
            }
            send(sender, "reload.ok",
                    number("version", plugin.rules().version()),
                    number("players", plugin.engine() == null ? 0 : plugin.engine().trackedCount()));
            send(sender, "reload.restart");
        } catch (RuntimeException e) {
            send(sender, "reload.failed", Placeholder.unparsed("error", String.valueOf(e.getMessage())));
        }
    }

    private void diagnostics(CommandSender sender) {
        Metrics m = plugin.metrics();
        ScoreEngine engine = plugin.engine();
        Database database = plugin.database();
        WriteQueue writes = plugin.writes();
        LookupService lookups = plugin.lookups();

        line(sender, "<gold>GearScore <gray>- diagnóstico <dark_gray>(" + plugin.settings().serverName() + ", "
                + (plugin.settings().official() ? "origem oficial" : "somente consulta") + ", regras v"
                + plugin.rules().version() + ")");
        if (engine != null) {
            long captures = m.captures.sum();
            double average = captures == 0 ? 0.0 : m.evaluationNanos.sum() / (double) captures / 1000.0;
            line(sender, " <gray>Jogadores acompanhados: <white>" + engine.trackedCount()
                    + "<gray>, avaliações na fila: <white>" + engine.pendingEvaluations());
            line(sender, " <gray>Eventos filtrados no listener: <white>" + m.filteredEvents.sum());
            line(sender, " <gray>Pedidos de avaliação: <white>" + m.requests.sum()
                    + "<gray>, agrupados (debounce): <white>" + m.coalesced.sum());
            line(sender, " <gray>Leituras de equipamento: <white>" + captures
                    + "<gray>, descartadas sem mudança: <white>" + m.unchanged.sum()
                    + "<gray>, recalculadas: <white>" + m.evaluations.sum());
            line(sender, " <gray>Tempo por leitura+cálculo: média <white>" + String.format(Locale.ROOT, "%.1f", average)
                    + " µs<gray>, máximo <white>"
                    + String.format(Locale.ROOT, "%.1f", m.maxEvaluationNanos.get() / 1000.0) + " µs");
        }
        line(sender, " <gray>Cache: <white>" + m.cacheHits.sum() + " <gray>acertos, <white>" + m.cacheMisses.sum()
                + " <gray>faltas");
        if (database == null) {
            line(sender, " <gray>Banco: <yellow>desativado");
        } else {
            line(sender, " <gray>Banco: " + (database.isAvailable()
                    ? "<green>disponível"
                    : "<red>indisponível <gray>(nova tentativa em " + database.retryInMillis() / 1000 + "s, "
                    + database.consecutiveFailures() + " falha(s) seguidas)")
                    + "<gray>, falhas totais: <white>" + m.databaseFailures.sum());
            if (writes != null) {
                line(sender, " <gray>Gravações: pendentes <white>" + writes.pendingCount()
                        + "<gray>, enfileiradas <white>" + m.writesQueued.sum()
                        + "<gray>, consolidadas <white>" + m.writesMerged.sum()
                        + "<gray>, descartadas <white>" + m.writesDropped.sum());
                line(sender, " <gray>Lotes enviados: <white>" + m.writeBatches.sum()
                        + "<gray>, linhas gravadas: <white>" + m.rowsWritten.sum());
            }
            line(sender, " <gray>Leituras SQL: <white>" + m.readQueries.sum()
                    + "<gray>, na fila: <white>" + lookups.queuedReads()
                    + "<gray>, recusadas: <white>" + m.readsRejected.sum());
        }
        if (!m.unknownEnchantments().isEmpty()) {
            line(sender, " <gray>Encantamentos sem regra (valem 0): <white>"
                    + String.join("<gray>, <white>", m.unknownEnchantments()));
        }
    }

    private void help(CommandSender sender) {
        send(sender, "help.header");
        send(sender, "help.self");
        send(sender, "help.view");
        send(sender, "help.details");
        send(sender, "help.top");
        if (sender.hasPermission(PERM_RELOAD)) {
            send(sender, "help.reload");
        }
        if (sender.hasPermission(PERM_DIAG)) {
            send(sender, "help.diag");
        }
    }

    // ---- helpers -------------------------------------------------------------------------

    private boolean allowed(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        send(sender, "no-permission");
        return false;
    }

    private void send(CommandSender sender, String key, TagResolver... resolvers) {
        sender.sendMessage(plugin.messages().get(key, resolvers));
    }

    private static void line(CommandSender sender, String miniMessage) {
        sender.sendMessage(Messages.mini(miniMessage));
    }

    /** Database callbacks arrive on a reader thread; replies go back to the server thread. */
    private void sync(Runnable action) {
        if (Bukkit.isPrimaryThread()) {
            action.run();
            return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, action);
        } catch (IllegalPluginAccessException ignored) {
            // plugin disabled while the query was running
        }
    }

    private static String errorKey(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        return cause instanceof LookupService.BusyException ? "db-busy" : "db-unavailable";
    }

    private static TagResolver number(String name, int value) {
        return Placeholder.unparsed(name, Integer.toString(value));
    }

    private static TagResolver decimal(String name, double value) {
        long rounded = Math.round(value);
        String text = Math.abs(value - rounded) < 0.05
                ? Long.toString(rounded)
                : String.format(Locale.ROOT, "%.1f", value);
        return Placeholder.unparsed(name, text);
    }

    private static Component itemName(ItemScore item) {
        return Component.translatable(item.item().def().translationKey(), item.item().def().material());
    }

    private static Component level(int level) {
        return level >= 1 && level <= 10
                ? Component.translatable("enchantment.level." + level, Integer.toString(level))
                : Component.text(level);
    }

    private String slotName(int slot) {
        if (slot == SnapshotFactory.SLOT_OFFHAND) {
            return plugin.messages().raw("slot.offhand");
        }
        if (slot >= 0 && slot <= 8) {
            return plugin.messages().raw("slot.hotbar").replace("{n}", Integer.toString(slot + 1));
        }
        return plugin.messages().raw("slot.weapon");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            if (sender.hasPermission(PERM_OTHERS)) {
                options.add("ver");
            }
            if (sender.hasPermission(PERM_DETAILS)) {
                options.add("detalhes");
            }
            if (sender.hasPermission(PERM_TOP)) {
                options.add("top");
            }
            if (sender.hasPermission(PERM_RELOAD)) {
                options.add("reload");
            }
            if (sender.hasPermission(PERM_DIAG)) {
                options.add("diag");
            }
            options.add("ajuda");
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            boolean view = (sub.equals("ver") || sub.equals("view")) && sender.hasPermission(PERM_OTHERS);
            boolean details = (sub.equals("detalhes") || sub.equals("details"))
                    && sender.hasPermission(PERM_DETAILS_OTHERS);
            if (view || details) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    options.add(player.getName());
                }
            }
        }
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(option -> !option.toLowerCase(Locale.ROOT).startsWith(typed));
        return options;
    }
}
