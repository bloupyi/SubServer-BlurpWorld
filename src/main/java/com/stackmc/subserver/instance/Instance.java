package com.stackmc.subserver.instance;

import com.stackmc.subserver.SubServer;
import com.stackmc.subserver.events.InstanceChatEvent;
import com.stackmc.subserver.events.InstanceJoinEvent;
import com.stackmc.subserver.events.InstanceQuitEvent;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Getter
public class Instance {
    @Getter private static final Set<Instance> instances = new HashSet<>();

    public static Instance getInstance(World world) {
        return instances.stream()
                .filter(instance -> instance.getWorlds()
                        .stream()
                        .anyMatch(instanciableWorld -> instanciableWorld.getWorld().equals(world)))
                .findAny()
                .orElse(null);
    }

    public static Instance getInstance(String name) {
        return instances.stream().filter(instance -> instance.getName().equals(name)).findAny().orElse(null);
    }

    public static Instance findInstance(String name) {
        Instance exact = getInstance(name);
        if (exact != null) {
            return exact;
        }

        List<Instance> matches = instances.stream()
                .filter(instance -> instance.getName().startsWith(name))
                .limit(2)
                .collect(Collectors.toList());
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private final String name;
    private final SubServer plugin;
    private final InstanceType type;
    private final List<InstanciableWorld> worlds = new ArrayList<>();
    private final Set<OfflinePlayer> offlinePlayers = new HashSet<>();
    private final UUID uniqueId = UUID.randomUUID();
    @Setter private InstanceState state = InstanceState.INIT;

    @Setter private long emptySince;

    /** Empeche une double fermeture : {@code close()} est appelable depuis plusieurs endroits. */
    private boolean closed;

    @Getter
    @RequiredArgsConstructor
    public static class InstanciableWorld {
        private final World world;
        private final boolean savable;
        private final String templateName;
    }

    public InstanciableWorld getInstanciableWorld(String worldName) {
        return worlds.stream()
                .filter(instanciableWorld -> instanciableWorld.getWorld().getName().equals(worldName))
                .findAny()
                .orElse(null);
    }

    private final EventDispatcher eventDispatcher = new EventDispatcher();

    public void register() {
        instances.add(this);
    }

    public void registerListener(Listener listener) {
        eventDispatcher.registerListener(listener);
    }

    public void unregisterListener(Listener listener) {
        eventDispatcher.unregisterListener(listener);
    }

    public void dispatchEvent(Event event) {
        eventDispatcher.dispatchEvent(event);
    }

    public void close() {
        this.close(true);
    }

    public void close(boolean persistSavableWorlds) {
        if (closed) {
            return;
        }
        closed = true;
        state = InstanceState.FINISHED;

        Location fallback = Bukkit.getWorlds().get(0).getSpawnLocation();

        worlds.forEach(world -> {
            World bukkitWorld = world.getWorld();
            String worldName = bukkitWorld.getName();

            new ArrayList<>(bukkitWorld.getPlayers()).forEach(player -> player.teleport(fallback));

            if (!persistSavableWorlds) {
                if (!Bukkit.unloadWorld(bukkitWorld, false)) {
                    Bukkit.getLogger().warning("Déchargement du monde " + worldName + " impossible pendant l'arrêt.");
                } else {
                    plugin.getWorldRepository().discard(worldName);
                }
            } else {
                plugin.getWorldRepository().release(world.getTemplateName(), bukkitWorld, world.isSavable())
                        .exceptionally(error -> {
                            Bukkit.getLogger().warning("Libération du monde " + worldName
                                    + " impossible : " + rootMessage(error));
                            return null;
                        });
            }
        });

        worlds.clear();
        offlinePlayers.clear();
        instances.remove(this);
        plugin.getInstanceFactory().removeInstance(this);
    }

    /**
     * Decharge un seul monde de l'instance, qui reste ouverte.
     *
     * <p>Ce qu'il faut pour une instance qui garde plusieurs mondes charges autour du joueur
     * et se debarrasse de ceux qui se sont eloignes : sans cela, decharger un monde imposait
     * de fermer toute l'instance.</p>
     *
     * <p>Un monde ou se trouve encore quelqu'un n'est jamais decharge : c'est a l'appelant de
     * deplacer les joueurs d'abord.</p>
     *
     * @return {@code true} si le monde a bien ete decharge
     */
    public boolean unloadWorld(String worldName) {
        InstanciableWorld target = getInstanciableWorld(worldName);
        if (target == null) {
            return false;
        }

        World bukkitWorld = target.getWorld();
        if (!bukkitWorld.getPlayers().isEmpty()) {
            return false;
        }

        plugin.getWorldRepository().release(target.getTemplateName(), bukkitWorld, target.isSavable())
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        Bukkit.getLogger().warning("Déchargement du monde " + worldName
                                + " impossible : " + rootMessage(error));
                        return;
                    }
                    worlds.remove(target);
                });
        return true;
    }

    public void loadWorld(String worldName, boolean isSavable, @Nullable Consumer<String> callback) {
        loadWorld(worldName, isSavable, callback, null);
    }

    public void loadWorld(String worldName, boolean isSavable, @Nullable Consumer<String> callback,
                          @Nullable Runnable onFailure) {
        final Consumer<String> finalCallback = (callback == null ? (s -> {}) : callback);
        final Runnable finalFailure = (onFailure == null ? () -> {} : onFailure);

        String destWorldName = isSavable ? worldName : getUniqueId() + "_" + worldName;

        long startTime = System.currentTimeMillis();
        plugin.getWorldRepository().loadWorld(worldName, destWorldName).whenComplete((world, error) -> {
            if (error != null) {
                fail(finalCallback, finalFailure,
                        "§cChargement du snapshot " + worldName + " impossible : " + rootMessage(error));
                return;
            }

            addWorld(world, isSavable, worldName);
            long totalTime = System.currentTimeMillis() - startTime;
            finalCallback.accept("Monde " + destWorldName + " chargé en " + totalTime + "ms ou "
                    + ((float) totalTime / 50f) + " ticks .");
        });
    }

    /** Signale l'echec de chargement sur le thread serveur. */
    private void fail(Consumer<String> callback, Runnable onFailure, String message) {
        Runnable report = () -> {
            callback.accept(message);
            onFailure.run();
        };
        if (Bukkit.isPrimaryThread()) {
            report.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, report);
        }
    }

    public void addWorld(World world, boolean isSavable, String templateName) {
        worlds.add(new InstanciableWorld(world, isSavable, templateName));
    }

    public boolean joinInstance(Player player) {
        return joinInstance(player, worlds.isEmpty() ? null : worlds.get(0).getWorld());
    }

    public boolean joinInstance(Player player, World world) {
        if (closed || worlds.isEmpty()) {
            return false;
        }

        InstanciableWorld target = world == null ? worlds.get(0) : getInstanciableWorld(world.getName());
        if (target == null) {
            return false;
        }

        if (type != null && !offlinePlayers.contains(player) && !type.hasRoomFor(getPlayers().size())) {
            return false;
        }

        InstanceJoinEvent event = new InstanceJoinEvent(this, player);
        Bukkit.getPluginManager().callEvent(event);

        if (event.isCancelled()) {
            return false;
        }

        Instance oldInstance = Instance.getInstance(player.getWorld());
        if (oldInstance != null && oldInstance != this) oldInstance.quitInstance(player);
        if (!plugin.isCrossInstanceVisibility()) {
            getPlayers().forEach(target2 -> {
                player.showPlayer(plugin, target2);
                target2.showPlayer(plugin, player);
            });
        }
        offlinePlayers.add(player);
        emptySince = 0;
        player.teleport(target.getWorld().getSpawnLocation());
        return true;
    }

    /** {@code true} si plus aucun joueur connecte n'est dans cette instance. */
    public boolean isEmpty() {
        return getPlayers().isEmpty();
    }

    public boolean isClosed() {
        return closed;
    }

    public void quitInstance(Player player) {
        InstanceQuitEvent event = new InstanceQuitEvent(this, player);
        Bukkit.getPluginManager().callEvent(event);

        if (event.isCancelled()) {
            return;
        }

        if (!plugin.isCrossInstanceVisibility()) {
            getPlayers().forEach(target -> {
                player.hidePlayer(plugin, target);
                target.hidePlayer(plugin, player);
            });
        }
        offlinePlayers.remove(player);
    }

    public void sendMessage(String message) {
        if (message == null) return;
        getPlayers().forEach(receiver -> receiver.sendMessage(message));
    }

    public void sendMessage(Component message) {
        if (message == null) return;
        getPlayers().forEach(receiver -> receiver.sendMessage(message));
    }

    public List<Player> getPlayers() {
        return offlinePlayers.stream().filter(OfflinePlayer::isOnline).map(OfflinePlayer::getPlayer).collect(Collectors.toList());
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
