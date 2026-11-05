package com.exemplo.veintreeminer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public final class VeinTreeMiner extends JavaPlugin implements CommandExecutor {

    private NamespacedKey disabledKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.disabledKey = new NamespacedKey(this, "disabled");

        getServer().getPluginManager().registerEvents(new MinerListener(this), this);

        PluginCommand command = getCommand("veinminer");
        if (command != null) {
            command.setExecutor(this);
        }
    }

    /** O jogador pode desligar o recurso para si mesmo com /veinminer. */
    public boolean isEnabledFor(Player player) {
        return !player.getPersistentDataContainer().has(disabledKey, PersistentDataType.BYTE);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Apenas jogadores podem usar este comando."));
            return true;
        }

        PersistentDataContainer data = player.getPersistentDataContainer();
        if (data.has(disabledKey, PersistentDataType.BYTE)) {
            data.remove(disabledKey);
            player.sendMessage(Component.text("Vein miner e tree miner ", NamedTextColor.GRAY)
                    .append(Component.text("ligados", NamedTextColor.GREEN))
                    .append(Component.text(".", NamedTextColor.GRAY)));
        } else {
            data.set(disabledKey, PersistentDataType.BYTE, (byte) 1);
            player.sendMessage(Component.text("Vein miner e tree miner ", NamedTextColor.GRAY)
                    .append(Component.text("desligados", NamedTextColor.RED))
                    .append(Component.text(".", NamedTextColor.GRAY)));
        }
        return true;
    }
}
