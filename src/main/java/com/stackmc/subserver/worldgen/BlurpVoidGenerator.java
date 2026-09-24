package com.stackmc.subserver.worldgen;

import java.util.List;
import java.util.Random;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

final class BlurpVoidGenerator extends ChunkGenerator {

    @Override
    public BiomeProvider getDefaultBiomeProvider(@NotNull WorldInfo worldInfo) {
        // A fixed biome keeps generation of the empty chunks around a map cheap; stored chunks keep their own biomes
        return VoidBiomeProvider.INSTANCE;
    }

    @Override
    public Location getFixedSpawnLocation(@NotNull World world, @NotNull Random random) {
        return new Location(world, 0.5D, 65.0D, 0.5D);
    }

    private static final class VoidBiomeProvider extends BiomeProvider {

        private static final VoidBiomeProvider INSTANCE = new VoidBiomeProvider();

        @Override
        public @NotNull Biome getBiome(@NotNull WorldInfo worldInfo, int x, int y, int z) {
            return Biome.PLAINS;
        }

        @Override
        public @NotNull List<Biome> getBiomes(@NotNull WorldInfo worldInfo) {
            return List.of(Biome.PLAINS);
        }
    }
}
