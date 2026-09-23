package com.lawlessmc.zombiepig;

import com.lawlessmc.zombiepig.command.ZombiePigCommand;
import com.lawlessmc.zombiepig.listener.BotListeners;
import org.bukkit.plugin.java.JavaPlugin;

public final class ZombiePigPlugin extends JavaPlugin {

    private BotService bot;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("personality.txt", false);
        this.bot = new BotService(this);
        this.bot.start();

        var cmd = new ZombiePigCommand(this);
        var pluginCommand = getCommand("zombiepig");
        if (pluginCommand != null) {
            pluginCommand.setExecutor(cmd);
            pluginCommand.setTabCompleter(cmd);
        }

        getServer().getPluginManager().registerEvents(new BotListeners(this), this);
        getLogger().info("ZombiePig enabled. Sidecar " + getConfig().getString("sidecar-url"));
    }

    @Override
    public void onDisable() {
        if (bot != null) {
            bot.shutdown();
        }
        getLogger().info("ZombiePig disabled.");
    }

    public BotService bot() {
        return bot;
    }
}
