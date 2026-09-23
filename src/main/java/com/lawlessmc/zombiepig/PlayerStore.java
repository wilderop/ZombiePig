package com.lawlessmc.zombiepig;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerStore {

    public static final class Session {
        public final Deque<String[]> history = new ArrayDeque<>();
        public boolean disclaimerSent;
        public boolean joinSent;
        public boolean botIsLastPartner;
        public long lastUnsolicited;
        public long lastDeath;
        public long lastStuck;
        public long lastChatHelp;
        public long lastReply;
        public long lastAny;
        public org.bukkit.Location lastLoc;
        public long stillSince;
    }

    private final ZombiePigPlugin plugin;
    private final Set<UUID> optedOut = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final File file;

    PlayerStore(ZombiePigPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "players.yml");
        load();
    }

    public Session session(UUID uuid) {
        return sessions.computeIfAbsent(uuid, k -> new Session());
    }

    public void forget(UUID uuid) {
        sessions.remove(uuid);
    }

    public boolean optedOut(UUID uuid) {
        return optedOut.contains(uuid);
    }

    public void setOptedOut(UUID uuid, boolean off) {
        if (off) {
            optedOut.add(uuid);
        } else {
            optedOut.remove(uuid);
        }
        save();
    }

    void remember(UUID uuid, String role, String text) {
        Session s = session(uuid);
        s.history.addLast(new String[]{role, text});
        while (s.history.size() > 8) {
            s.history.removeFirst();
        }
    }

    public boolean fresh(Player player) {
        if (player.hasPermission("zombiepig.bypass")) {
            return false;
        }
        if (optedOut(player.getUniqueId())) {
            return false;
        }
        return hours(player) < plugin.getConfig().getDouble("hours-limit", 10.0);
    }

    public static double hours(Player player) {
        int ticks = player.getStatistic(org.bukkit.Statistic.PLAY_ONE_MINUTE);
        return ticks / 72000.0;
    }

    static int deaths(Player player) {
        return player.getStatistic(org.bukkit.Statistic.DEATHS);
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String id : yaml.getStringList("opted-out")) {
            try {
                optedOut.add(UUID.fromString(id));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    synchronized void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("opted-out", optedOut.stream().map(UUID::toString).toList());
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save players.yml: " + e.getMessage());
        }
    }
}
