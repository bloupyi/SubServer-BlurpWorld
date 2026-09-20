package com.stackmc.subserver;

import com.stackmc.subserver.commands.SubServerCommand;
import com.stackmc.subserver.instance.Instance;
import com.stackmc.subserver.instance.InstanceFactory;
import com.stackmc.subserver.listeners.EventListener;
import com.stackmc.subserver.listeners.InstanceListener;
import com.stackmc.subserver.worldgen.BlurpWorldRepository;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public final class SubServer extends JavaPlugin {

    private final List<Listener> listeners = new ArrayList<>();
    @Getter private final InstanceFactory instanceFactory = new InstanceFactory(this);
    @Getter private BlurpWorldRepository worldRepository;

    /** true = les joueurs se voient d'une instance a l'autre (sinon isolation par instance). */
    @Getter private boolean crossInstanceVisibility;
    /** true = le chat est global entre instances (sinon chat limite a l'instance). */
    @Getter private boolean crossInstanceChat;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.crossInstanceVisibility = getConfig().getBoolean("cross-instance.visibility", false);
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
                getLogger().info(count + " snapshot(s) BlurpWorld chargé(s).");
            }
            instanceFactory.startLoop();
        }));
    }

    @Override
    public void onDisable() {
        instanceFactory.stopLoop();
        List<Instance> instancesSnapshot = new ArrayList<>(Instance.getInstances());
        instancesSnapshot.forEach(instance -> instance.close(false));
        Instance.getInstances().clear();
        if (this.worldRepository != null) {
            this.worldRepository.shutdown();
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
