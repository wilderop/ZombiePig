package com.lawlessmc.zombiepig;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

final class CannedReplies {

    private final ZombiePigPlugin plugin;

    CannedReplies(ZombiePigPlugin plugin) {
        this.plugin = plugin;
    }

    String pick(String situation, Player player) {
        List<String> lines = plugin.getConfig().getStringList("canned." + situation);
        if (lines.isEmpty()) {
            lines = plugin.getConfig().getStringList("canned.reply");
        }
        if (lines.isEmpty()) {
            return "leave spawn, hide a stash, do not share coords. no /tpa, no /sethome.";
        }
        int salt = player.getUniqueId().hashCode();
        int idx = Math.floorMod(salt + ThreadLocalRandom.current().nextInt(3), lines.size());
        return lines.get(idx);
    }
}
