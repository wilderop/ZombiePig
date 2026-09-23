package com.lawlessmc.zombiepig;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

public final class BotService {

    public enum Trigger { JOIN, DEATH, STUCK, CHAT, MSG, TEST }

    private static final Pattern COORDS = Pattern.compile(
            "(?i)\\b-?\\d{2,8}\\s*[,/ ]\\s*-?\\d{1,8}(?:\\s*[,/ ]\\s*-?\\d{2,8})?\\b");
    private static final Pattern BAIT = Pattern.compile(
            "(?i)\\b(come to (my |spawn)|follow me|drop your|give me your coords|free kit)\\b");
    private static final Pattern HTTP = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);

    private final ZombiePigPlugin plugin;
    private final PlayerStore store;
    private final GrokClient grok;
    private final CannedReplies canned;
    private final AtomicReference<String> personality = new AtomicReference<>("");
    private final List<Pattern> chatHelp = new ArrayList<>();
    private int stuckTask = -1;

    BotService(ZombiePigPlugin plugin) {
        this.plugin = plugin;
        this.store = new PlayerStore(plugin);
        this.grok = new GrokClient(plugin);
        this.canned = new CannedReplies(plugin);
        reload();
    }

    public PlayerStore store() {
        return store;
    }

    public GrokClient grok() {
        return grok;
    }

    public void start() {
        stuckTask = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::tickStuck, 20L * 15, 20L * 5);
    }

    public void shutdown() {
        if (stuckTask != -1) {
            Bukkit.getScheduler().cancelTask(stuckTask);
            stuckTask = -1;
        }
        store.save();
    }

    public void reload() {
        plugin.reloadConfig();
        chatHelp.clear();
        for (String raw : plugin.getConfig().getStringList("chat-help-patterns")) {
            try {
                chatHelp.add(Pattern.compile(raw));
            } catch (Exception e) {
                plugin.getLogger().warning("Bad chat-help pattern: " + raw);
            }
        }
        try {
            var path = plugin.getDataFolder().toPath().resolve("personality.txt");
            if (Files.exists(path)) {
                personality.set(Files.readString(path, StandardCharsets.UTF_8).trim());
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Could not read personality.txt: " + e.getMessage());
        }
        if (personality.get().isBlank()) {
            try (var in = plugin.getResource("personality.txt")) {
                if (in != null) {
                    personality.set(new String(in.readAllBytes(), StandardCharsets.UTF_8).trim());
                }
            } catch (IOException ignored) {
            }
        }
    }

    public String botName() {
        return plugin.getConfig().getString("bot-name", "ZombiePig");
    }

    public boolean isBotName(String name) {
        if (name == null) {
            return false;
        }
        String n = name.replace("_", "").replace("-", "");
        return n.equalsIgnoreCase(botName())
                || n.equalsIgnoreCase("zombiepig")
                || n.equalsIgnoreCase("zp");
    }

    public boolean looksLikeHelp(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (Pattern p : chatHelp) {
            if (p.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    public boolean nearSpawn(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return false;
        }
        if (loc.getWorld().getEnvironment() != World.Environment.NORMAL) {
            return false;
        }
        int r = plugin.getConfig().getInt("spawn-radius", 500);
        Location zero = new Location(loc.getWorld(), 0, loc.getY(), 0);
        Location spawn = loc.getWorld().getSpawnLocation();
        return loc.distanceSquared(zero) <= (double) r * r
                || loc.distanceSquared(spawn) <= (double) r * r;
    }

    public void considerJoin(Player player) {
        int delay = Math.max(1, plugin.getConfig().getInt("join-delay-seconds", 12));
        UUID id = player.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) {
                return;
            }
            maybeTalk(p, Trigger.JOIN, null, false);
        }, 20L * delay);
    }

    public void considerDeath(Player player) {
        if (!nearSpawn(player.getLocation())) {
            return;
        }
        maybeTalk(player, Trigger.DEATH, null, false);
    }

    public void considerChatHelp(Player player, String text) {
        if (!looksLikeHelp(text)) {
            return;
        }
        maybeTalk(player, Trigger.CHAT, text, false);
    }

    public void incomingPm(Player player, String text) {
        store.session(player.getUniqueId()).botIsLastPartner = true;
        maybeTalk(player, Trigger.MSG, text, true);
    }

    public void test(Player player) {
        maybeTalk(player, Trigger.TEST, null, true);
    }

    public void maybeTalk(Player player, Trigger trigger, String userText, boolean solicited) {
        if (trigger != Trigger.TEST && !solicited && !store.fresh(player)) {
            return;
        }
        if (trigger == Trigger.MSG && !store.fresh(player)) {
            sendPm(player, "i only nag people under 10 hours. you're past that. /zombiepig off if i ever bother you.");
            return;
        }
        long now = System.currentTimeMillis();
        PlayerStore.Session s = store.session(player.getUniqueId());
        if (!solicited && !cooled(s, trigger, now)) {
            return;
        }
        if (solicited && trigger == Trigger.MSG) {
            long gap = plugin.getConfig().getLong("cooldowns.reply-seconds", 2) * 1000L;
            if (now - s.lastReply < gap) {
                return;
            }
        }

        String situation = switch (trigger) {
            case JOIN -> "join";
            case DEATH -> "death";
            case STUCK -> "stuck";
            case CHAT -> "chat";
            default -> "reply";
        };
        String context = buildContext(player, trigger, userText);
        List<String[]> messages = historyFor(player, context);
        String system = personality.get();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            GrokClient.Result result = grok.chat(system, messages);
            String text;
            String source;
            if (result.grok()) {
                text = sanitize(result.text());
                source = "grok";
                if (text.isBlank()) {
                    text = canned.pick(situation, player);
                    source = "canned";
                }
            } else {
                text = canned.pick(situation, player);
                source = "canned:" + result.reason();
            }
            String send = text;
            String src = source;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                PlayerStore.Session sess = store.session(player.getUniqueId());
                long t = System.currentTimeMillis();
                sess.lastAny = t;
                if (!solicited) {
                    sess.lastUnsolicited = t;
                    if (trigger == Trigger.JOIN) {
                        sess.joinSent = true;
                    }
                    if (trigger == Trigger.DEATH) {
                        sess.lastDeath = t;
                    }
                    if (trigger == Trigger.STUCK) {
                        sess.lastStuck = t;
                    }
                    if (trigger == Trigger.CHAT) {
                        sess.lastChatHelp = t;
                    }
                } else {
                    sess.lastReply = t;
                    if (trigger == Trigger.TEST) {
                        sess.joinSent = true;
                    }
                }
                deliver(player, send, sess);
                store.remember(player.getUniqueId(), "user", context);
                store.remember(player.getUniqueId(), "assistant", send);
                plugin.getLogger().info("PM " + trigger + " -> " + player.getName() + " via " + src);
            });
        });
    }

    private boolean cooled(PlayerStore.Session s, Trigger trigger, long now) {
        long unsol = plugin.getConfig().getLong("cooldowns.unsolicited-seconds", 90) * 1000L;
        if (now - s.lastUnsolicited < unsol) {
            return false;
        }
        return switch (trigger) {
            case DEATH -> now - s.lastDeath >= plugin.getConfig().getLong("cooldowns.death-seconds", 600) * 1000L;
            case STUCK -> now - s.lastStuck >= plugin.getConfig().getLong("cooldowns.stuck-seconds", 1200) * 1000L;
            case CHAT -> now - s.lastChatHelp >= plugin.getConfig().getLong("cooldowns.chat-help-seconds", 180) * 1000L;
            case JOIN -> !s.joinSent;
            default -> true;
        };
    }

    private List<String[]> historyFor(Player player, String context) {
        List<String[]> out = new ArrayList<>(store.session(player.getUniqueId()).history);
        out.add(new String[]{"user", context});
        return out;
    }

    private String buildContext(Player player, Trigger trigger, String userText) {
        Location loc = player.getLocation();
        String where;
        if (loc.getWorld() == null) {
            where = "unknown world";
        } else if (loc.getWorld().getEnvironment() == World.Environment.NETHER) {
            where = "nether";
        } else if (loc.getWorld().getEnvironment() == World.Environment.THE_END) {
            where = "end";
        } else if (nearSpawn(loc)) {
            where = "overworld spawn area";
        } else {
            where = "overworld away from spawn";
        }
        int items = 0;
        boolean hasBed = false;
        boolean hasFood = false;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            items += stack.getAmount() > 0 ? 1 : 0;
            Material t = stack.getType();
            if (t.name().endsWith("_BED")) {
                hasBed = true;
            }
            if (t.isEdible()) {
                hasFood = true;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("trigger=").append(trigger.name().toLowerCase(Locale.ROOT));
        sb.append(" name=").append(player.getName());
        sb.append(String.format(Locale.US, " hours=%.1f", PlayerStore.hours(player)));
        sb.append(" deaths=").append(PlayerStore.deaths(player));
        sb.append(" where=").append(where);
        sb.append(" inv_stacks=").append(items);
        sb.append(" bed=").append(hasBed);
        sb.append(" food=").append(hasFood);
        sb.append(" health=").append((int) player.getHealth());
        if (userText != null && !userText.isBlank()) {
            sb.append("\nplayer said: ").append(userText.replace('\n', ' '));
        }
        return sb.toString();
    }

    private void deliver(Player player, String text, PlayerStore.Session sess) {
        if (!sess.disclaimerSent) {
            sess.disclaimerSent = true;
            sendPm(player, plugin.getConfig().getString("disclaimer",
                    "server bot, not a player. /msg ZombiePig for more. /zombiepig off to mute me."));
        }
        sendPm(player, text);
        sess.botIsLastPartner = true;
    }

    public void sendPm(Player player, String text) {
        String name = botName();
        Component msg = Component.text("[", NamedTextColor.GRAY)
                .append(Component.text(name, NamedTextColor.GOLD))
                .append(Component.text(" (bot) → me]: ", NamedTextColor.GRAY))
                .append(Component.text(text, NamedTextColor.WHITE));
        player.sendMessage(msg);
    }

    static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replace("\r", "").trim();
        s = s.replaceAll("(?s)```.*?```", " ");
        s = s.replace("*", "");
        s = HTTP.matcher(s).replaceAll("");
        s = COORDS.matcher(s).replaceAll("[coords]");
        if (BAIT.matcher(s).find()) {
            return "";
        }
        String[] lines = s.split("\n");
        StringBuilder out = new StringBuilder();
        int n = 0;
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (n > 0) {
                out.append('\n');
            }
            out.append(t);
            n++;
            if (n >= 3) {
                break;
            }
        }
        String done = out.toString();
        if (done.length() > 400) {
            done = done.substring(0, 397) + "...";
        }
        return done;
    }

    private void tickStuck() {
        int stillNeed = plugin.getConfig().getInt("stuck-seconds", 90);
        int maxItems = plugin.getConfig().getInt("stuck-max-items", 8);
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!store.fresh(player)) {
                continue;
            }
            Location loc = player.getLocation();
            if (!nearSpawn(loc)) {
                PlayerStore.Session s = store.session(player.getUniqueId());
                s.lastLoc = loc;
                s.stillSince = now;
                continue;
            }
            int stacks = 0;
            for (ItemStack stack : player.getInventory().getContents()) {
                if (stack != null && !stack.getType().isAir()) {
                    stacks++;
                }
            }
            if (stacks > maxItems) {
                continue;
            }
            PlayerStore.Session s = store.session(player.getUniqueId());
            if (s.lastLoc == null || s.lastLoc.getWorld() == null || loc.getWorld() == null
                    || !s.lastLoc.getWorld().equals(loc.getWorld())
                    || s.lastLoc.distanceSquared(loc) > 4) {
                s.lastLoc = loc.clone();
                s.stillSince = now;
                continue;
            }
            if (now - s.stillSince >= stillNeed * 1000L) {
                maybeTalk(player, Trigger.STUCK, null, false);
                s.stillSince = now;
            }
        }
    }
}
