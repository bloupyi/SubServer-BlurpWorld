package com.stackmc.subserver.listeners;

import com.stackmc.subserver.SubServer;
import com.stackmc.subserver.events.InstanceChatEvent;
import com.stackmc.subserver.events.InstanceDeathEvent;
import com.stackmc.subserver.events.InstanceQuitEvent;
import com.stackmc.subserver.events.InstanceRespawnEvent;
import com.stackmc.subserver.instance.Instance;
import com.stackmc.subserver.instance.InstanceType;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class InstanceListener implements Listener {
    private final SubServer plugin;

    public InstanceListener(SubServer plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.isCrossInstanceVisibility()) {
            Bukkit.getOnlinePlayers().forEach(target -> {
                event.getPlayer().hidePlayer(plugin, target);
                target.hidePlayer(plugin, event.getPlayer());
            });
        }
        event.joinMessage(null);

        List<InstanceType> autospawnTypes = this.plugin.getInstanceFactory().getInstanceTypes().stream()
                .filter(InstanceType::isAutoJoin)
                .collect(Collectors.toList());

        if (autospawnTypes.isEmpty()) {
            Bukkit.getLogger().severe("No instance type is set to auto-join.");
            return;
        }

        if (autospawnTypes.size() > 1) {
            Bukkit.getLogger().severe("More than one instance type is set to auto-join.");
            return;
        }

        InstanceType type = autospawnTypes.get(0);

        Instance instance = this.plugin.getInstanceFactory().getInstances(type).stream()
                .filter(inst -> !inst.isClosed() && !inst.getWorlds().isEmpty())
                .filter(inst -> type.hasRoomFor(inst.getPlayers().size()))
                .findAny()
                .orElse(null);

        if (instance == null) {
            Bukkit.getLogger().severe("No instance are open for auto-join.");
            event.getPlayer().kick(Component.text("Serveur plein, reessaie plus tard.", NamedTextColor.RED));
            return;
        }

        instance.dispatchEvent(event);
        instance.sendMessage(event.joinMessage());
        instance.joinInstance(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        event.quitMessage(null);

        Instance instance = Instance.getInstance(event.getPlayer().getWorld());
        if (instance == null) return;

        instance.dispatchEvent(event);
        event.quitMessage(event.quitMessage());
        instance.quitInstance(event.getPlayer());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity().getPlayer();
        if (player == null) return;
        Instance instance = Instance.getInstance(player.getWorld());

        InstanceDeathEvent deathEvent = new InstanceDeathEvent(instance, player, event.getDamageSource(), event.deathMessage());
        Bukkit.getPluginManager().callEvent(deathEvent);

        if (deathEvent.isCancelled()) {
            event.setCancelled(true);
            return;
        }

        if (instance == null) return;

        event.deathMessage(null);
        instance.dispatchEvent(event);
        instance.sendMessage(event.deathMessage());
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Instance instance = Instance.getInstance(event.getPlayer().getWorld());

        Set<Audience> audience = event.viewers();

        if (!plugin.isCrossInstanceChat()) {
            audience.clear();
            if (instance == null) {
                audience.addAll(event.getPlayer().getWorld().getPlayers());
            } else {
                audience.addAll(instance.getPlayers());
            }
            audience.add(Bukkit.getConsoleSender());
        }

        InstanceChatEvent chatEvent = new InstanceChatEvent(instance, event.getPlayer(), event.message(), audience);
        Bukkit.getScheduler().runTask(plugin,() -> Bukkit.getPluginManager().callEvent(chatEvent));

        if (chatEvent.isCancelled()) {
            event.setCancelled(true);
            return;
        }

        if (instance != null) {
            instance.dispatchEvent(event);
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Instance instance = Instance.getInstance(event.getPlayer().getWorld());

        InstanceRespawnEvent respawnEvent = new InstanceRespawnEvent(instance, event.getPlayer(), event.getRespawnFlags(), event.getRespawnLocation(), event.getRespawnReason(), event.isAnchorSpawn(), event.isBedSpawn(), event.isMissingRespawnBlock());
        Bukkit.getPluginManager().callEvent(respawnEvent);

        if (respawnEvent.isCancelled()) {
            return;
        }

        if (instance != null) {
            event.setRespawnLocation(instance.getWorlds().get(0).getWorld().getSpawnLocation());
        }
    }
}
