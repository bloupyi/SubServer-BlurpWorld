package com.stackmc.subserver.commands.subs;

import com.stackmc.subserver.SubServer;
import lombok.RequiredArgsConstructor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import java.util.List;
import java.util.Locale;

/** /subserver global <chat|tab> [on|off] : active ou desactive le chat et la tab list globaux. */
@RequiredArgsConstructor
public class GlobalSubCommand implements TabExecutor {
    private static final List<String> OPTIONS = List.of("chat", "tab");
    private static final List<String> STATES = List.of("on", "off");

    private final SubServer plugin;

    @Override
    public boolean onCommand(CommandSender sender, Command rootCommand, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Chat global : " + state(plugin.isCrossInstanceChat()) + "§r, tab global : " + state(plugin.isCrossInstanceTab()));
            return true;
        }
        if (!OPTIONS.contains(args[0].toLowerCase(Locale.ROOT))) {
            sender.sendMessage("§cUsage : /subserver global <chat|tab> [on|off]");
            return false;
        }
        boolean chat = args[0].equalsIgnoreCase("chat");
        String name = chat ? "Chat global" : "Tab global";
        if (args.length == 1) {
            sender.sendMessage(name + " : " + state(chat ? plugin.isCrossInstanceChat() : plugin.isCrossInstanceTab()));
            return true;
        }
        String value = args[1].toLowerCase(Locale.ROOT);
        if (!STATES.contains(value)) {
            sender.sendMessage("§cValeur attendue : on ou off.");
            return false;
        }
        boolean enabled = value.equals("on");
        if (chat) {
            plugin.setCrossInstanceChat(enabled);
        } else {
            plugin.setCrossInstanceTab(enabled);
        }
        sender.sendMessage(name + " : " + state(enabled));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command rootCommand, String label, String[] args) {
        List<String> values = switch (args.length) {
            case 1 -> OPTIONS;
            case 2 -> STATES;
            default -> List.of();
        };
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.startsWith(prefix)).toList();
    }

    private static String state(boolean enabled) {
        return enabled ? "§aactivé" : "§cdésactivé";
    }
}
