package br.com.serpens.gearscore.command;

import br.com.serpens.gearscore.GearScorePlugin;
import br.com.serpens.gearscore.config.Messages;
import br.com.serpens.gearscore.engine.Metrics;
import br.com.serpens.gearscore.engine.RemoteScores;
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
import net.kyori.adventure.text.event.HoverEvent;
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
            default -> {
                // /gs <player>
                if (args.length == 1 && args[0].matches("[A-Za-z0-9_.*]{1,32}")) {
                    if (allowed(sender, PERM_OTHERS)) {
                        other(sender, args[0], false);
                    }
                } else {
                    send(sender, "unknown-command");
                }
            }
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
        show(sender, plugin.cached(player.getUniqueId()), details);
    }

    private void other(CommandSender sender, String name, boolean details) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            show(sender, plugin.cached(online.getUniqueId()), details);
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
                summary(sender, found.get());
            }
        }));
    }

    private void show(CommandSender sender, CachedScore score, boolean details) {
        if (score == null) {
            send(sender, "not-ready");
        } else if (details) {
            details(sender, score);
        } else {
            summary(sender, score.record());
        }
    }

    /** The whole public answer: player, total and tier. */
    private void summary(CommandSender sender, PlayerRecord record) {
        send(sender, "summary.line", identity(record));
    }

    private TagResolver identity(PlayerRecord record) {
        return TagResolver.resolver(
                Placeholder.unparsed("player", record.name()),
                number("score", record.score()),
                Placeholder.component("tier", plugin.tierView(record.tier()).component()));
    }

    private void details(CommandSender sender, CachedScore score) {
        ScoreResult detail = score.detail();
        if (detail == null) {
            send(sender, "details.unavailable");
            return;
        }
        PlayerRecord record = score.record();
        Messages messages = plugin.messages();
        send(sender, "details.header", identity(record));
        for (int i = 0; i < ScoreResult.SET_SIZE; i++) {
            String slot = messages.raw(i == ScoreResult.WEAPON ? "slot.weapon" : "slot." + GearType.ARMOR_SLOTS[i].id());
            ItemScore piece = detail.piece(i);
            if (piece == null) {
                send(sender, "details.empty", Placeholder.unparsed("slot", slot));
            } else {
                item(sender, slot, piece);
            }
        }
        send(sender, "details.updated",
                Placeholder.unparsed("updated", messages.ago(record.updatedAt(), System.currentTimeMillis())));
        if (sender.hasPermission(PERM_DIAG)) {
            send(sender, "details.admin", Placeholder.unparsed("server", record.server()),
                    number("version", record.rulesVersion()));
        }
    }

    private void item(CommandSender sender, String slot, ItemScore item) {
        Messages messages = plugin.messages();
        Component hover = messages.get("details.hover-base", decimal("base", item.base()));
        if (item.enchants().length == 0) {
            hover = hover.appendNewline().append(messages.get("details.hover-none"));
        }
        for (EnchantScore enchant : item.enchants()) {
            String note = switch (enchant.status()) {
                case COUNTED -> "";
                case CAPPED -> messages.raw("note.capped");
                case OVER_LIMIT -> messages.raw("note.over-limit");
                case INCOMPATIBLE -> messages.raw("note.incompatible");
                case UNKNOWN -> messages.raw("note.unknown");
            };
            hover = hover.appendNewline().append(messages.get("details.hover-enchant",
                    Placeholder.component("name", Component.translatable(
                            "enchantment." + enchant.key().replace(':', '.'), enchant.key())),
                    Placeholder.component("level", level(enchant.level())),
                    decimal("points", enchant.points()),
                    Placeholder.unparsed("note", note)));
        }
        Component line = messages.get("details.item",
                Placeholder.unparsed("slot", slot),
                Placeholder.component("item", Component.translatable(
                        item.item().def().translationKey(), item.item().def().material())),
                decimal("total", item.total()),
                decimal("base", item.base()),
                decimal("enchant", item.enchant()));
        sender.sendMessage(line.hoverEvent(HoverEvent.showText(hover)));
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
            int pageSize = plugin.settings().pageSize();
            int pages = (rows.size() + pageSize - 1) / pageSize;
            int page = Math.min(requested, pages);
            send(sender, "top.header", number("page", page), number("pages", pages));
            for (int i = (page - 1) * pageSize, end = Math.min(rows.size(), page * pageSize); i < end; i++) {
                send(sender, "top.line", number("position", i + 1), identity(rows.get(i)));
            }
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

    /** Technical view for administrators; the only place that names servers and internals. */
    private void diagnostics(CommandSender sender) {
        Metrics m = plugin.metrics();
        ScoreEngine engine = plugin.engine();
        Database database = plugin.database();
        WriteQueue writes = plugin.writes();
        LookupService lookups = plugin.lookups();

        line(sender, "<gold>GearScore <gray>» <white>diagnóstico");
        line(sender, " <gray>Servidor: <white>" + plugin.settings().serverName() + " <gray>("
                + (plugin.settings().official() ? "origem oficial" : "somente consulta")
                + ") • regras v<white>" + plugin.rules().version());
        if (engine != null) {
            long captures = m.captures.sum();
            double average = captures == 0 ? 0.0 : m.evaluationNanos.sum() / (double) captures / 1000.0;
            line(sender, " <gray>Jogadores acompanhados: <white>" + engine.trackedCount()
                    + "<gray>, avaliações na fila: <white>" + engine.pendingEvaluations());
            line(sender, " <gray>Eventos filtrados no listener: <white>" + m.filteredEvents.sum());
            line(sender, " <gray>Pedidos de avaliação: <white>" + m.requests.sum()
                    + "<gray>, agrupados (debounce): <white>" + m.coalesced.sum());
            line(sender, " <gray>Leituras de inventário: <white>" + captures
                    + "<gray>, sem mudança: <white>" + m.unchanged.sum()
                    + "<gray>, recalculadas: <white>" + m.evaluations.sum());
            line(sender, " <gray>Tempo por leitura+cálculo: média <white>" + String.format(Locale.ROOT, "%.1f", average)
                    + " µs<gray>, máximo <white>"
                    + String.format(Locale.ROOT, "%.1f", m.maxEvaluationNanos.get() / 1000.0) + " µs");
        }
        RemoteScores remote = plugin.remote();
        if (remote != null) {
            line(sender, " <gray>Jogadores com pontuação oficial em cache: <white>" + remote.size());
            line(sender, " <gray>Atualizações do banco: <white>" + m.remoteRefreshes.sum()
                    + " <gray>consultas agrupadas, <white>" + m.remoteUpdates.sum()
                    + " <gray>aplicadas, <white>" + m.remoteDiscarded.sum() + " <gray>descartadas (atrasadas)");
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
                line(sender, " <gray>Gravações: na fila <white>" + writes.pendingCount()
                        + "<gray>, aguardando reenvio <white>" + (engine == null ? 0 : engine.owedCount())
                        + "<gray>, enfileiradas <white>" + m.writesQueued.sum()
                        + "<gray>, consolidadas <white>" + m.writesMerged.sum());
                line(sender, " <gray>Fila cheia: recusadas <white>" + m.writesRejected.sum()
                        + "<gray>, retiradas <white>" + m.writesDropped.sum()
                        + "<gray>, reenviadas <white>" + m.writesRecovered.sum()
                        + "<gray>, perdidas <white>" + m.writesLost.sum());
                line(sender, " <gray>Arquivo de pendências: salvo <white>" + m.spoolSaves.sum()
                        + "<gray> vez(es), aguardando lugar <white>" + writes.backlogCount()
                        + "<gray>, recuperadas <white>" + m.spoolRecovered.sum()
                        + "<gray>, erros <white>" + m.spoolErrors.sum());
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
        if (sender.hasPermission(PERM_OTHERS)) {
            send(sender, "help.view");
        }
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

    private static Component level(int level) {
        return level >= 1 && level <= 10
                ? Component.translatable("enchantment.level." + level, Integer.toString(level))
                : Component.text(level);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
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
            if (sender.hasPermission(PERM_OTHERS)) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    options.add(player.getName());
                }
            }
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
