package com.stackmc.subserver.commands.subs;

import com.stackmc.subserver.SubServer;
import com.stackmc.subserver.instance.Instance;
import lombok.RequiredArgsConstructor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import java.util.List;

@RequiredArgsConstructor
public class CloseSubCommand implements TabExecutor {
    private final SubServer plugin;

    @Override
    public boolean onCommand(CommandSender sender, Command rootCommand, String label, String[] args) {
        if(args.length == 0) {
            sender.sendMessage("§cVous devez préciser un nom d'instance.");
            return false;
        }
        Instance instance = Instance.findInstance(args[0]);
        if (instance == null) {
            sender.sendMessage("§cAucune instance ne porte ce nom (ou plusieurs correspondent).");
            return false;
        }
        String name = instance.getName();

        instance.close();
        sender.sendMessage(String.format("L'instance %s est maintenant fermée.", name));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command rootCommand, String label, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase();
        return Instance.getInstances().stream()
                .map(Instance::getName)
                .filter(name -> name.toLowerCase().startsWith(prefix))
                .sorted()
                .toList();
    }
}
