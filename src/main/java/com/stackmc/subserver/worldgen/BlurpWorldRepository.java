package com.stackmc.subserver.worldgen;

import com.stackmc.subserver.SubServer;
import io.papermc.paper.blurpworld.BlurpWorldManager;
import io.papermc.paper.blurpworld.BlurpWorldSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;

public final class BlurpWorldRepository {

    private static final String SNAPSHOT_EXTENSION = ".bws";

    private final SubServer plugin;
    private final BlurpWorldManager worlds;
    private final Path mapsDirectory;
    private final ConcurrentHashMap<String, UUID> templates = new ConcurrentHashMap<>();
    private final ExecutorService ioExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public BlurpWorldRepository(SubServer plugin, BlurpWorldManager worlds) {
        this.plugin = plugin;
        this.worlds = worlds;
        this.mapsDirectory = plugin.getDataFolder().toPath().resolve("maps");
    }

    public CompletableFuture<Integer> loadTemplates() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Files.createDirectories(this.mapsDirectory);
                try (Stream<Path> files = Files.list(this.mapsDirectory)) {
                    return files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(SNAPSHOT_EXTENSION))
                        .toList();
                }
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }, this.ioExecutor).thenCompose(files -> {
            CompletableFuture<?>[] imports = files.stream().map(this::importTemplate).toArray(CompletableFuture[]::new);
            return CompletableFuture.allOf(imports).thenApply(ignored -> files.size());
        });
    }

    public Set<String> templateNames() {
        return Set.copyOf(this.templates.keySet());
    }

    public CompletableFuture<World> loadWorld(String templateName, String destinationName) {
        UUID snapshotId = this.resolve(templateName);
        if (snapshotId == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Snapshot inconnu : " + templateName));
        }
        return this.worlds.prepareAsync(destinationName, snapshotId).thenCompose(ignored -> this.onMain(() -> {
            World world = Bukkit.createWorld(new WorldCreator(destinationName)
                .generator(new BlurpVoidGenerator()));
            if (world == null) {
                this.worlds.discard(destinationName);
                throw new IllegalStateException("Paper n'a pas pu charger " + destinationName);
            }
            world.setAutoSave(false);
            return world;
        }));
    }

    public CompletableFuture<Void> release(String templateName, World world, boolean save) {
        CompletableFuture<?> saved = save ? this.saveTemplate(templateName, world) : CompletableFuture.completedFuture(null);
        return saved.thenCompose(ignored -> this.onMain(() -> {
            if (!Bukkit.unloadWorld(world, false)) {
                throw new IllegalStateException("Impossible de décharger " + world.getName());
            }
            this.worlds.discard(world.getName());
            return null;
        }));
    }

    public void discard(String worldName) {
        this.worlds.discard(worldName);
    }

    public void shutdown() {
        this.ioExecutor.shutdown();
    }

    private CompletableFuture<BlurpWorldSnapshot> saveTemplate(String templateName, World world) {
        validateTemplateName(templateName);
        return this.worlds.snapshot(world, templateName).thenCompose(snapshot ->
            this.worlds.exportSnapshotAsync(snapshot.id()).thenCompose(archive -> CompletableFuture.supplyAsync(() -> {
                Path target = this.mapsDirectory.resolve(templateName + SNAPSHOT_EXTENSION);
                try {
                    Files.createDirectories(this.mapsDirectory);
                    Files.write(target, archive, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                } catch (IOException exception) {
                    throw new CompletionException(exception);
                }
                this.templates.put(normalize(templateName), snapshot.id());
                return snapshot;
            }, this.ioExecutor))
        );
    }

    private CompletableFuture<Void> importTemplate(Path path) {
        String fileName = path.getFileName().toString();
        String templateName = fileName.substring(0, fileName.length() - SNAPSHOT_EXTENSION.length());
        validateTemplateName(templateName);
        return CompletableFuture.supplyAsync(() -> {
            try {
                return Files.readAllBytes(path);
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }, this.ioExecutor).thenCompose(this.worlds::importSnapshotAsync).thenAccept(snapshot ->
            this.templates.put(normalize(templateName), snapshot.id())
        );
    }

    private UUID resolve(String templateName) {
        UUID registered = this.templates.get(normalize(templateName));
        if (registered != null) {
            return registered;
        }
        return this.worlds.snapshots().stream()
            .filter(snapshot -> snapshot.label().equalsIgnoreCase(templateName) || snapshot.sourceWorld().equalsIgnoreCase(templateName))
            .findFirst()
            .map(snapshot -> {
                this.templates.put(normalize(templateName), snapshot.id());
                return snapshot.id();
            })
            .orElse(null);
    }

    private <T> CompletableFuture<T> onMain(Supplier<T> action) {
        if (Bukkit.isPrimaryThread()) {
            try {
                return CompletableFuture.completedFuture(action.get());
            } catch (Throwable throwable) {
                return CompletableFuture.failedFuture(throwable);
            }
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            try {
                result.complete(action.get());
            } catch (Throwable throwable) {
                result.completeExceptionally(throwable);
            }
        });
        return result;
    }

    private static void validateTemplateName(String templateName) {
        if (!templateName.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("Nom de snapshot invalide : " + templateName);
        }
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
