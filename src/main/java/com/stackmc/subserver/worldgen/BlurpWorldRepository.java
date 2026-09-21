package com.stackmc.subserver.worldgen;

import com.stackmc.subserver.SubServer;
import io.papermc.paper.blurpworld.BlurpWorldManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
    private final ConcurrentHashMap<String, Path> templates = new ConcurrentHashMap<>();
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
        }, this.ioExecutor).thenApply(files -> {
            files.forEach(path -> {
                String fileName = path.getFileName().toString();
                String templateName = fileName.substring(0, fileName.length() - SNAPSHOT_EXTENSION.length());
                validateTemplateName(templateName);
                this.templates.put(normalize(templateName), path);
            });
            return files.size();
        });
    }

    public Set<String> templateNames() {
        return Set.copyOf(this.templates.keySet());
    }

    public CompletableFuture<World> loadWorld(String templateName, String destinationName) {
        Path archive = this.templates.get(normalize(templateName));
        CompletableFuture<?> prepared;
        if (archive != null) {
            prepared = CompletableFuture.supplyAsync(() -> readArchive(archive), this.ioExecutor)
                .thenCompose(data -> this.worlds.prepareFromArchiveAsync(destinationName, data));
        } else {
            UUID snapshotId = this.resolveSnapshot(templateName);
            if (snapshotId == null) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Snapshot inconnu : " + templateName));
            }
            prepared = this.worlds.prepareAsync(destinationName, snapshotId);
        }
        return prepared.thenCompose(ignored -> this.onMain(() -> {
            World world = Bukkit.createWorld(new WorldCreator(destinationName)
                .generator(new BlurpVoidGenerator()));
            if (world == null) {
                this.worlds.discard(destinationName);
                throw new IllegalStateException("Paper n'a pas pu charger " + destinationName);
            }
            world.setAutoSave(false);
            return world;
        })).thenCompose(world -> world.getChunkAtAsync(world.getSpawnLocation(), true).handle((ignored, error) -> {
            if (error != null) {
                this.plugin.getLogger().warning("Préchargement du spawn de " + destinationName + " impossible : " + rootMessage(error));
            }
            return world;
        }));
    }

    public CompletableFuture<Void> release(String templateName, World world, boolean save) {
        CompletableFuture<?> saved = save ? this.persist(templateName, world) : CompletableFuture.completedFuture(null);
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
        this.ioExecutor.close();
    }

    public CompletableFuture<Void> persist(String templateName, World world) {
        validateTemplateName(templateName);
        Path target = this.mapsDirectory.resolve(templateName + SNAPSHOT_EXTENSION);
        return this.onMain(() -> {
            world.save(true);
            return null;
        }).thenCompose(ignored -> this.worlds.exportWorldAsync(world, templateName))
            .thenCompose(archive -> CompletableFuture.runAsync(() -> writeArchive(target, archive), this.ioExecutor))
            .thenRun(() -> this.templates.put(normalize(templateName), target));
    }

    private UUID resolveSnapshot(String templateName) {
        return this.worlds.snapshots().stream()
            .filter(snapshot -> snapshot.label().equalsIgnoreCase(templateName) || snapshot.sourceWorld().equalsIgnoreCase(templateName))
            .findFirst()
            .map(snapshot -> snapshot.id())
            .orElse(null);
    }

    private static byte[] readArchive(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static void writeArchive(Path target, byte[] archive) {
        Path temporary = null;
        try {
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), target.getFileName().toString() + ".", ".tmp");
            Files.write(temporary, archive, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
            }
        }
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

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
