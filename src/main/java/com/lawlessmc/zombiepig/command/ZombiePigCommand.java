package com.lawlessmc.zombiepig.command;

import com.lawlessmc.zombiepig.GrokClient;
import com.lawlessmc.zombiepig.ZombiePigPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class ZombiePigCommand implements TabExecutor {

    private final ZombiePigPlugin plugin;

    public ZombiePigCommand(ZombiePigPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) {
                boolean off = plugin.bot().store().optedOut(player.getUniqueId());
                sender.sendMessage(Component.text(
                        "ZombiePig is " + (off ? "muted" : "on") + " for you. /" + label + " off|on",
                        NamedTextColor.GRAY));
            } else {
                sender.sendMessage("ZombiePig. /zombiepig status");
            }
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "off" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Players only.");
                    return true;
                }
                plugin.bot().store().setOptedOut(player.getUniqueId(), true);
                sender.sendMessage(Component.text("ZombiePig will leave you alone.", NamedTextColor.GRAY));
            }
            case "on" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Players only.");
                    return true;
                }
                plugin.bot().store().setOptedOut(player.getUniqueId(), false);
                sender.sendMessage(Component.text("ZombiePig can PM you again if you are under 10 hours.", NamedTextColor.GRAY));
            }
            case "status" -> status(sender);
            case "reload" -> {
                if (!sender.hasPermission("zombiepig.admin")) {
                    sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
                    return true;
                }
                plugin.bot().reload();
                sender.sendMessage(Component.text("ZombiePig reloaded.", NamedTextColor.GREEN));
            }
            case "test" -> {
                if (!sender.hasPermission("zombiepig.admin")) {
                    sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
                    return true;
                }
                Player target;
                if (args.length >= 2) {
                    target = Bukkit.getPlayer(args[1]);
                } else if (sender instanceof Player player) {
                    target = player;
                } else {
                    sender.sendMessage("Need a player.");
                    return true;
                }
                if (target == null) {
                    sender.sendMessage("Player not online.");
                    return true;
                }
                plugin.bot().test(target);
                sender.sendMessage(Component.text("Test PM queued for " + target.getName(), NamedTextColor.GREEN));
            }
            default -> sender.sendMessage("Usage: /" + label + " [off|on|status|reload|test]");
        }
        return true;
    }

    private void status(CommandSender sender) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            GrokClient.Result c = plugin.bot().grok().credits();
            String line = String.format(
                    Locale.US,
                    "ZombiePig sidecar: %s remaining=%.1f%% reason=%s",
                    c.source(),
                    c.remainingPercent(),
                    c.reason());
            Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(Component.text(line, NamedTextColor.GRAY)));
        });
        if (sender instanceof Player player) {
            boolean off = plugin.bot().store().optedOut(player.getUniqueId());
            sender.sendMessage(Component.text(
                    "you: " + (off ? "muted" : "on") + String.format(Locale.US, "  hours=%.1f",
                            com.lawlessmc.zombiepig.PlayerStore.hours(player)),
                    NamedTextColor.GRAY));
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            Stream<String> opts = Stream.of("off", "on", "status");
            if (sender.hasPermission("zombiepig.admin")) {
                opts = Stream.concat(opts, Stream.of("reload", "test"));
            }
            String p = args[0].toLowerCase(Locale.ROOT);
            return opts.filter(s -> s.startsWith(p)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("test") && sender.hasPermission("zombiepig.admin")) {
            String p = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(p))
                    .toList();
        }
        return List.of();
    }
}
