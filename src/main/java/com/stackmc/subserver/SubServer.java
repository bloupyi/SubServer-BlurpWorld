package com.stackmc.subserver;

import com.stackmc.subserver.commands.SubServerCommand;
import com.stackmc.subserver.instance.Instance;
import com.stackmc.subserver.instance.InstanceFactory;
import com.stackmc.subserver.listeners.EventListener;
import com.stackmc.subserver.listeners.InstanceListener;
import com.stackmc.subserver.worldgen.BlurpWorldRepository;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class SubServer extends JavaPlugin {

    private final List<Listener> listeners = new ArrayList<>();
    @Getter private final InstanceFactory instanceFactory = new InstanceFactory(this);
    @Getter private BlurpWorldRepository worldRepository;

    /** true = la tab list affiche les joueurs de toutes les instances (sinon seulement ceux de l'instance). */
    @Getter private boolean crossInstanceTab;
    /** true = le chat est global entre instances (sinon chat limite a l'instance). */
    @Getter private boolean crossInstanceChat;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // "visibility" est l'ancien nom de l'option : les instances ont leurs propres mondes, donc seule la tab list est concernee
        this.crossInstanceTab = getConfig().getBoolean("cross-instance.tab", getConfig().getBoolean("cross-instance.visibility", false));
        this.crossInstanceChat = getConfig().getBoolean("cross-instance.chat", false);
        this.worldRepository = new BlurpWorldRepository(this, Bukkit.getServer().getBlurpWorldManager());
        this.listeners.add(new InstanceListener(this));
        this.listeners.add(new EventListener(this));
        registerListeners();
        registerCommands();

        this.worldRepository.loadTemplates().whenComplete((count, error) -> Bukkit.getScheduler().runTask(this, () -> {
            if (error != null) {
                getLogger().severe("Impossible de charger les snapshots BlurpWorld : " + rootMessage(error));
            } else {
                getLogger().info(count + " archive(s) BlurpWorld indexée(s).");
            }
            instanceFactory.startLoop();
        }));
    }

    @Override
    public void onDisable() {
        instanceFactory.stopLoop();
        List<Instance> instancesSnapshot = new ArrayList<>(Instance.getInstances());
        if (this.worldRepository != null) {
            long started = System.nanoTime();
            CompletableFuture<?>[] saves = instancesSnapshot.stream()
                    .flatMap(instance -> instance.getWorlds().stream())
                    .filter(Instance.InstanciableWorld::isSavable)
                    .map(world -> this.worldRepository.persist(world.getTemplateName(), world.getWorld()))
                    .toArray(CompletableFuture[]::new);
            try {
                CompletableFuture.allOf(saves).join();
                if (saves.length > 0) {
                    getLogger().info(saves.length + " monde(s) persistant(s) sauvegardé(s) en "
                            + String.format(java.util.Locale.ROOT, "%.1f ms", (System.nanoTime() - started) / 1_000_000.0D));
                }
            } catch (CompletionException exception) {
                getLogger().severe("Impossible de sauvegarder tous les mondes persistants : " + rootMessage(exception));
            }
        }
        instancesSnapshot.forEach(instance -> instance.close(false));
        Instance.getInstances().clear();
        if (this.worldRepository != null) {
            this.worldRepository.shutdown();
        }
    }

    /** @deprecated remplace par {@link #isCrossInstanceTab()} */
    @Deprecated
    public boolean isCrossInstanceVisibility() {
        return this.crossInstanceTab;
    }

    public void setCrossInstanceChat(boolean enabled) {
        this.crossInstanceChat = enabled;
        getConfig().set("cross-instance.chat", enabled);
        saveConfig();
    }

    public void setCrossInstanceTab(boolean enabled) {
        this.crossInstanceTab = enabled;
        getConfig().set("cross-instance.tab", enabled);
        getConfig().set("cross-instance.visibility", null);
        saveConfig();
        refreshTabList();
    }

    /** Applique l'option de tab list a tous les joueurs connectes. */
    public void refreshTabList() {
        List<? extends Player> online = List.copyOf(Bukkit.getOnlinePlayers());
        Map<Player, Instance> membership = new HashMap<>();
        Instance.getInstances().forEach(instance -> instance.getPlayers().forEach(player -> membership.put(player, instance)));
        for (Player viewer : online) {
            Instance viewerInstance = membership.get(viewer);
            for (Player target : online) {
                if (viewer == target) {
                    continue;
                }
                if (this.crossInstanceTab || (viewerInstance != null && viewerInstance == membership.get(target))) {
                    viewer.showPlayer(this, target);
                } else {
                    viewer.hidePlayer(this, target);
                }
            }
        }
    }

    public void registerCommands(){
        this.getCommand("subserver").setExecutor(new SubServerCommand(this));
    }

    public void registerListeners(){
        this.listeners.forEach(listener -> Bukkit.getPluginManager().registerEvents(listener, this));
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
