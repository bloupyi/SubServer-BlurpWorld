package com.stackmc.subserver.worldgen;

import java.util.Random;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;
import org.jetbrains.annotations.NotNull;

final class BlurpVoidGenerator extends ChunkGenerator {

    @Override
    public Location getFixedSpawnLocation(@NotNull World world, @NotNull Random random) {
        return new Location(world, 0.5D, 65.0D, 0.5D);
    }
}
