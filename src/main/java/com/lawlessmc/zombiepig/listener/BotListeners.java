package com.lawlessmc.zombiepig.listener;

import com.lawlessmc.zombiepig.ZombiePigPlugin;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Locale;

public final class BotListeners implements Listener {

    private final ZombiePigPlugin plugin;

    public BotListeners(ZombiePigPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        plugin.bot().considerJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        plugin.bot().store().forget(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        plugin.bot().considerDeath(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> plugin.bot().considerChatHelp(player, text));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String raw = event.getMessage();
        if (raw.length() < 2 || raw.charAt(0) != '/') {
            return;
        }
        String body = raw.substring(1).trim();
        if (body.startsWith("minecraft:")) {
            body = body.substring("minecraft:".length());
        }
        String[] parts = body.split("\\s+", 3);
        if (parts.length == 0) {
            return;
        }
        String label = parts[0].toLowerCase(Locale.ROOT);
        Player player = event.getPlayer();
        if (isMsg(label)) {
            if (parts.length < 2) {
                return;
            }
            String target = parts[1];
            if (plugin.bot().isBotName(target)) {
                event.setCancelled(true);
                String text = parts.length >= 3 ? parts[2] : "";
                if (text.isBlank()) {
                    plugin.bot().sendPm(player, "yeah? /msg " + plugin.bot().botName() + " <what you want>");
                    return;
                }
                plugin.bot().incomingPm(player, text);
                player.sendMessage(net.kyori.adventure.text.Component.text("[me → ", net.kyori.adventure.text.format.NamedTextColor.GRAY)
                        .append(net.kyori.adventure.text.Component.text(plugin.bot().botName(), net.kyori.adventure.text.format.NamedTextColor.GOLD))
                        .append(net.kyori.adventure.text.Component.text("]: ", net.kyori.adventure.text.format.NamedTextColor.GRAY))
                        .append(net.kyori.adventure.text.Component.text(text, net.kyori.adventure.text.format.NamedTextColor.WHITE)));
                return;
            }
            plugin.bot().store().session(player.getUniqueId()).botIsLastPartner = false;
            return;
        }
        if (isReply(label) && plugin.bot().store().session(player.getUniqueId()).botIsLastPartner) {
            event.setCancelled(true);
            String text = parts.length >= 2 ? body.substring(label.length()).trim() : "";
            if (text.isBlank()) {
                plugin.bot().sendPm(player, "yeah? /r <message>");
                return;
            }
            plugin.bot().incomingPm(player, text);
            player.sendMessage(net.kyori.adventure.text.Component.text("[me → ", net.kyori.adventure.text.format.NamedTextColor.GRAY)
                    .append(net.kyori.adventure.text.Component.text(plugin.bot().botName(), net.kyori.adventure.text.format.NamedTextColor.GOLD))
                    .append(net.kyori.adventure.text.Component.text("]: ", net.kyori.adventure.text.format.NamedTextColor.GRAY))
                    .append(net.kyori.adventure.text.Component.text(text, net.kyori.adventure.text.format.NamedTextColor.WHITE)));
        }
    }

    private static boolean isMsg(String label) {
        return label.equals("msg") || label.equals("m") || label.equals("tell")
                || label.equals("whisper") || label.equals("w");
    }

    private static boolean isReply(String label) {
        return label.equals("r") || label.equals("reply");
    }
}
