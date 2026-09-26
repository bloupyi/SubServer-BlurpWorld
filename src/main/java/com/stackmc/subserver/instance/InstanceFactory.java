package com.stackmc.subserver.instance;

import com.stackmc.subserver.SubServer;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@RequiredArgsConstructor
public class InstanceFactory {
    private final SubServer plugin;

    @Getter private final Set<InstanceType> instanceTypes = new HashSet<>();
    private final Map<InstanceType, Set<Instance>> instances = new HashMap<>();
    @Getter @Setter public Instance autoJoinInstance = null;

    private final AtomicInteger nameCounter = new AtomicInteger();

    private BukkitTask task;

    public Set<Instance> getInstances(InstanceType type) {
        return instances.getOrDefault(type, new HashSet<>());
    }

    public void removeInstance(Instance instance) {
        Set<Instance> typeInstances = instances.get(instance.getType());
        if (typeInstances != null) {
            typeInstances.remove(instance);
            if (typeInstances.isEmpty()) {
                instances.remove(instance.getType());
            }
        }

        if (autoJoinInstance == instance) {
            autoJoinInstance = null;
        }
    }

    public void registerType(InstanceType type) {
        instanceTypes.add(type);
    }

    public void unregisterType(InstanceType type) {
        instanceTypes.remove(type);
    }

    public void startLoop() {
        if (task != null) {
            return;
        }

        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            generateInstances();
            closeEmptyInstances();
        }, 20, 20);
    }

    public void stopLoop() {
        if (task == null) {
            return;
        }

        task.cancel();
        task = null;
    }

    public void generateInstances() {
        for (InstanceType type : instanceTypes) {
            if (!type.isPreGenerated()) {
                continue;
            }

            Set<Instance> typeInstances = this.instances.computeIfAbsent(type, key -> new HashSet<>());
            int missing = type.getMaxInstancesCount() - typeInstances.size();
            for (int i = 0; i < missing; i++) {
                Instance instance = open(type, null, null, null);
                if (type.isAutoJoin()) autoJoinInstance = instance;
            }
        }
    }

    @Nullable
    public Instance createInstance(InstanceType type, @Nullable Consumer<Instance> onReady) {
        return createInstance(type, null, onReady);
    }

    /**
     * Cree une instance en remplacant les mondes du type par ceux passes en argument.
     *
     * <p>Un type decrit des mondes fixes, ce qui suffit a un lobby mais pas a un editeur ou a
     * une partie, dont le monde change a chaque fois. Plutot que d'enregistrer un type par
     * monde — ils sont globaux et plafonnes — le type sert de gabarit (capacite, fermeture
     * automatique) et les mondes sont donnes ici.</p>
     */
    @Nullable
    public Instance createInstance(InstanceType type, @Nullable List<InstanceType.InstanciableWorld> worlds,
                                   @Nullable Consumer<Instance> onReady) {
        return createInstance(type, worlds, onReady, null);
    }

    /**
     * Pareil, en prevenant {@code onFailure} si un monde ne se charge pas.
     *
     * <p>L'instance est alors fermee et {@code onReady} n'est jamais appele : sans ce rappel,
     * celui qui attendait l'instance ne l'apprenait pas, et le joueur restait sans nouvelles.</p>
     */
    @Nullable
    public Instance createInstance(InstanceType type, @Nullable List<InstanceType.InstanciableWorld> worlds,
                                   @Nullable Consumer<Instance> onReady, @Nullable Runnable onFailure) {
        Set<Instance> typeInstances = this.instances.computeIfAbsent(type, key -> new HashSet<>());
        if (typeInstances.size() >= InstanceType.MAX_INSTANCES_LIMIT) {
            return null;
        }
        return open(type, worlds, onReady, onFailure);
    }

    /** Nombre d'instances ouvertes de ce type. */
    public int countInstances(InstanceType type) {
        return getInstances(type).size();
    }

    private Instance open(InstanceType type, @Nullable List<InstanceType.InstanciableWorld> worlds,
                          @Nullable Consumer<Instance> onReady, @Nullable Runnable onFailure) {
        Instance instance = new Instance(type.getName() + "_" + nameCounter.incrementAndGet(), plugin, type);
        this.instances.computeIfAbsent(type, key -> new HashSet<>()).add(instance);
        instance.register();
        generateWorlds(type, instance, worlds == null ? type.getWorlds() : worlds, onReady, onFailure);
        return instance;
    }

    public void closeEmptyInstances() {
        long now = System.currentTimeMillis();

        for (Instance instance : new ArrayList<>(Instance.getInstances())) {
            InstanceType type = instance.getType();
            if (type == null || !type.isCloseWhenEmpty() || instance.isClosed()) {
                continue;
            }

            if (instance.getState() == InstanceState.INIT) {
                continue;
            }

            if (!instance.isEmpty()) {
                instance.setEmptySince(0);
                continue;
            }

            if (instance.getEmptySince() == 0) {
                instance.setEmptySince(now);
                continue;
            }

            if (now - instance.getEmptySince() >= type.getEmptyGraceSeconds() * 1000L) {
                instance.close();
            }
        }
    }

    private void generateWorlds(InstanceType type, Instance instance,
                                List<InstanceType.InstanciableWorld> worlds,
                                @Nullable Consumer<Instance> onReady, @Nullable Runnable onFailure) {
        int max = worlds.size();
        if (max == 0) {
            instance.setState(InstanceState.CLOSED);
            type.getPostInitRunnable().accept(instance);
            if (onReady != null) {
                onReady.accept(instance);
            }
            return;
        }

        AtomicInteger loaded = new AtomicInteger();

        AtomicBoolean aborted = new AtomicBoolean();

        for (InstanceType.InstanciableWorld world : worlds) {
            instance.loadWorld(world.getWorldName(), world.isSavable(), str -> {
                if (loaded.incrementAndGet() != max) {
                    return;
                }

                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (aborted.get() || instance.isClosed()) {
                        return;
                    }
                    instance.setState(InstanceState.CLOSED);
                    type.getPostInitRunnable().accept(instance);
                    if (onReady != null) {
                        onReady.accept(instance);
                    }
                });
            }, () -> {
                if (aborted.getAndSet(true)) {
                    return;
                }
                Bukkit.getLogger().warning("Instance " + instance.getName()
                        + " : monde " + world.getWorldName() + " non charge, l'instance est abandonnee.");
                instance.close();
                if (onFailure != null) {
                    onFailure.run();
                }
            });
        }
    }
}
