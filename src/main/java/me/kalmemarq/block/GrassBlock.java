package me.kalmemarq.block;

import me.kalmemarq.World;

import java.util.Random;

public class GrassBlock extends Block {
    public GrassBlock(int numericId, int[] sideTextures) {
        super(numericId, sideTextures);
    }

    @Override
    public boolean isTickable() {
        return true;
    }

    @Override
    public void tick(World world, int x, int y, int z, Random random) {
        if (world.isLit(x, y, z)) {
            for (int i = 0; i < 4; ++i) {
                int xt = x + random.nextInt(3) - 1;
                int yt = y + random.nextInt(5) - 3;
                int zt = z + random.nextInt(3) - 1;
                if (world.getBlockId(xt, yt, zt) == Blocks.DIRT.numericId && world.isLit(xt, yt, zt)) {
                    world.setBlockId(xt, yt, zt, Blocks.GRASS.numericId);
                }
            }
        } else {
            world.setBlockId(x, y, z, Blocks.DIRT.numericId);
        }
    }
}
