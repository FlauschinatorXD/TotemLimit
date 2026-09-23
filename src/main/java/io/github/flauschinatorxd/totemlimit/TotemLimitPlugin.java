package io.github.flauschinatorxd.totemlimit;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public final class TotemLimitPlugin extends JavaPlugin {

    private final MiniMessage mm = MiniMessage.miniMessage();
    private final Set<UUID> pendingChecks = new HashSet<>();
    private final Map<UUID, Long> lastMessage = new HashMap<>();

    private int limit;
    private boolean countContainers;
    private boolean ignoreCreative;
    private boolean dropExcess;
    private long messageCooldown;
    private int periodicTaskId = -1;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        getServer().getPluginManager().registerEvents(new TotemListener(this), this);
        registerCommand("totemlimit", "TotemLimit admin command", List.of("tl"), new LimitCommand());
    }

    @Override
    public void onDisable() {
        pendingChecks.clear();
        lastMessage.clear();
    }

    void loadSettings() {
        reloadConfig();
        limit = Math.max(0, getConfig().getInt("max-totems", 1));
        countContainers = getConfig().getBoolean("count-in-containers", true);
        ignoreCreative = getConfig().getBoolean("ignore-creative", true);
        dropExcess = !"DELETE".equalsIgnoreCase(getConfig().getString("excess-action", "DROP"));
        messageCooldown = Math.max(0, getConfig().getLong("message-cooldown-ms", 2000));

        if (periodicTaskId != -1) Bukkit.getScheduler().cancelTask(periodicTaskId);
        int interval = Math.max(1, getConfig().getInt("check-interval-ticks", 20));
        periodicTaskId = Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) enforce(p);
        }, interval, interval).getTaskId();
    }

    int limit() {
        return limit;
    }

    boolean countContainers() {
        return countContainers;
    }

    boolean isExempt(Player player) {
        if (player.hasPermission("totemlimit.bypass")) return true;
        return ignoreCreative && (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR);
    }

    /** Schedule a check for the next tick (after all inventory events have fully completed). */
    void scheduleCheck(Player player) {
        if (!pendingChecks.add(player.getUniqueId())) return;
        Bukkit.getScheduler().runTask(this, () -> {
            pendingChecks.remove(player.getUniqueId());
            enforce(player);
        });
    }

    /**
     * Enforces the limit. Always runs synchronously on the main thread and never inside
     * an inventory event. Totems are removed from the inventory FIRST, then recounted -
     * items are only dropped if the difference matches exactly.
     */
    void enforce(Player player) {
        if (!player.isOnline() || player.isDead() || isExempt(player)) return;

        int before = TotemCounter.countPlayer(player, countContainers);
        int excess = before - limit;
        if (excess <= 0) return;

        List<ItemStack> removed = TotemCounter.removeExcess(player, excess, countContainers);
        int after = TotemCounter.countPlayer(player, countContainers);
        int actuallyRemoved = before - after;
        int collected = TotemCounter.sum(removed);
        player.updateInventory();

        if (actuallyRemoved <= 0) return;

        if (actuallyRemoved != collected) {
            // Should never happen. Better to drop nothing than to risk a dupe.
            getLogger().severe("Totem mismatch for " + player.getName() + ": removed=" + actuallyRemoved
                    + ", collected=" + collected + ". Nothing will be dropped.");
            return;
        }

        if (dropExcess) {
            for (ItemStack stack : removed) {
                player.getWorld().dropItem(player.getLocation(), stack, (Item item) -> {
                    item.setPickupDelay(40);
                    item.setThrower(player.getUniqueId());
                });
            }
        }

        sendMessage(player, dropExcess ? "over-limit-drop" : "over-limit-delete", true,
                Placeholder.unparsed("removed", String.valueOf(actuallyRemoved)));
    }

    void sendMessage(CommandSender target, String key, boolean cooldown, TagResolver... extra) {
        if (cooldown && target instanceof Player p && messageCooldown > 0) {
            long now = System.currentTimeMillis();
            Long last = lastMessage.get(p.getUniqueId());
            if (last != null && now - last < messageCooldown) return;
            lastMessage.put(p.getUniqueId(), now);
        }
        String raw = getConfig().getString("messages." + key, "");
        if (raw.isEmpty()) return;
        String prefix = getConfig().getString("messages.prefix", "");
        TagResolver resolver = TagResolver.resolver(
                TagResolver.resolver(extra),
                Placeholder.unparsed("limit", String.valueOf(limit)));
        target.sendMessage(mm.deserialize(prefix + raw, resolver));
    }

    void forget(Player player) {
        lastMessage.remove(player.getUniqueId());
    }

    private final class LimitCommand implements BasicCommand {

        @Override
        public void execute(CommandSourceStack source, String[] args) {
            CommandSender sender = source.getSender();
            if (args.length == 0) {
                sendMessage(sender, "usage", false);
                return;
            }
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "reload" -> {
                    loadSettings();
                    sendMessage(sender, "reloaded", false);
                    Bukkit.getOnlinePlayers().forEach(TotemLimitPlugin.this::enforce);
                }
                case "set" -> {
                    if (args.length < 2) {
                        sendMessage(sender, "usage", false);
                        return;
                    }
                    int value;
                    try {
                        value = Integer.parseInt(args[1]);
                    } catch (NumberFormatException e) {
                        sendMessage(sender, "usage", false);
                        return;
                    }
                    getConfig().set("max-totems", Math.max(0, value));
                    saveConfig();
                    loadSettings();
                    sendMessage(sender, "limit-set", false);
                    Bukkit.getOnlinePlayers().forEach(TotemLimitPlugin.this::enforce);
                }
                case "check" -> {
                    Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : null;
                    if (target == null) {
                        sendMessage(sender, "player-not-found", false);
                        return;
                    }
                    sendMessage(sender, "check", false,
                            Placeholder.unparsed("player", target.getName()),
                            Placeholder.unparsed("amount", String.valueOf(TotemCounter.countPlayer(target, countContainers))));
                }
                default -> sendMessage(sender, "usage", false);
            }
        }

        @Override
        public Collection<String> suggest(CommandSourceStack source, String[] args) {
            if (args.length <= 1) {
                String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
                return List.of("reload", "set", "check").stream().filter(s -> s.startsWith(prefix)).toList();
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("check")) {
                return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                        .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
            }
            return List.of();
        }

        @Override
        public String permission() {
            return "totemlimit.admin";
        }
    }
}
