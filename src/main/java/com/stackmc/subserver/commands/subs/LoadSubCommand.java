package com.stackmc.subserver.commands.subs;

import com.stackmc.subserver.SubServer;
import com.stackmc.subserver.instance.Instance;
import lombok.RequiredArgsConstructor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import java.util.Collections;
import java.util.List;

@RequiredArgsConstructor
public class LoadSubCommand implements TabExecutor {

    private final SubServer plugin;

    @Override
    public boolean onCommand(CommandSender sender, Command rootCommand, String label, String[] args) {
        boolean isSavable = false;
        if(args.length == 0) {
            sender.sendMessage("§cVous devez préciser un nom d'instance.");
            return false;
        }
        if(args.length < 2) {
            sender.sendMessage("§cVous devez préciser au moins un monde à charger.");
            return false;
        }
        if(args.length > 2) {
            isSavable = true;
        }

        String instanceName = args[0];

        if (Instance.getInstances().stream().anyMatch(instance -> instance.getName().equals(instanceName))) {
            sender.sendMessage(String.format("§cUne instance du même nom (%s) existe déjà.", instanceName));
            return false;
        }

        Instance instance = new Instance(instanceName, plugin, null);

        for (int i = 1; i < args.length; i++) {
            instance.loadWorld(args[i], isSavable, sender::sendMessage);
        }

        instance.register();
        sender.sendMessage("Instance créée.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command rootCommand, String label, String[] args) {
        if (args.length == 1) {
            return Collections.emptyList();
        }
        String prefix = args[args.length - 1].toLowerCase();
        return plugin.getWorldRepository().templateNames().stream()
                .filter(name -> name.toLowerCase().startsWith(prefix))
                .sorted()
                .toList();
    }
}
