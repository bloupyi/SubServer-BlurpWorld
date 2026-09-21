package com.stackmc.subserver.commands.subs;

import com.stackmc.subserver.SubServer;
import com.stackmc.subserver.instance.Instance;
import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;

@RequiredArgsConstructor
public class TpSubCommand implements TabExecutor {
    private final SubServer plugin;

    @Override
    public boolean onCommand(CommandSender sender, Command rootCommand, String label, String[] args) {
        if(!(sender instanceof Player)) return false;
        if(args.length == 0) {
            sender.sendMessage("§cVous devez préciser un nom de monde.");
            return false;
        }
        World world = Bukkit.getWorld(args[0]);
        if (world == null) {
            sender.sendMessage("§cCe monde n'existe pas.");
            return false;
        }

        Instance instance = Instance.getInstance(world);
        if (instance == null) {
            sender.sendMessage("§cCe monde n'appartient à aucune instance.");
            return false;
        }

        if (!instance.joinInstance((Player) sender, world)) {
            sender.sendMessage("§cImpossible de rejoindre cette instance (fermée ou pleine).");
            return false;
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command rootCommand, String label, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase();
        return Instance.getInstances().stream()
                .flatMap(instance -> instance.getWorlds().stream())
                .map(Instance.InstanciableWorld::getWorld)
                .map(World::getName)
                .filter(name -> name.toLowerCase().startsWith(prefix))
                .sorted()
                .toList();
    }
}
