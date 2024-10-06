package me.kalmemarq;

import me.kalmemarq.block.Block;
import me.kalmemarq.block.Blocks;
import me.kalmemarq.util.BlockHitResult;
import me.kalmemarq.util.Box;
import me.kalmemarq.util.PerlinNoiseFilter;
import org.joml.Vector3d;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class World {
    public static final int CHUNK_SIZE = 32;
    public final int width;
    public final int height;
    public final int depth;
    private final byte[] blocks;
    private final short[] heightmap;
    private WorldStateListener stateListener;

    private final Random random = new Random();
    private int unprocessed;

    public World(int width, int height, int depth) {
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.blocks = new byte[width * height * depth];
        this.heightmap = new short[width * height];

        if (!this.load()) {
            this.generate();
        }

        this.calculateHeightMap();
    }

    private void generate() {
        PerlinNoiseFilter filter = new PerlinNoiseFilter();
        int[] heightmap1 = filter.read(this.width, this.height, 0);
        int[] heightmap2 = filter.read(this.width, this.height, 0);
        int[] controlFilter = filter.read(this.width, this.height, 1);
        int[] rockMap = filter.read(this.width, this.height, 1);

        for (int y = 0; y < this.depth; ++y) {
            for (int z = 0; z < this.height; ++z) {
                for (int x = 0; x < this.width; ++x) {
                    int dh1 = heightmap1[x + z * this.width];
                    int dh2 = heightmap2[x + z * this.width];
                    int cfh = controlFilter[x + z * this.width];
                    if (cfh < 128) {
                        dh2 = dh1;
                    }

                    int dh = Math.max(dh2, dh1);

                    dh = dh / 8 + this.depth / 3;
                    int rh = rockMap[x + z * this.width] / 8 + this.depth / 3;
                    if (rh > dh - 2) {
                        rh = dh - 2;
                    }

                    int i = (y * this.height + z) * this.width + x;
                    int id = 0;

                    if (y == dh) {
                        id = Blocks.GRASS.numericId;
                    } else if (y < dh) {
                        id = Blocks.DIRT.numericId;
                    }

                    if (y <= rh) {
                        id = Blocks.STONE.numericId;
                    }

                    this.blocks[i] = (byte)id;
                }
            }
        }
    }

    public boolean load() {
        try (DataInputStream e = new DataInputStream(new GZIPInputStream(Files.newInputStream(Path.of("level.dat"))))) {
            e.readFully(this.blocks);
        } catch (Exception ignored) {
            return false;
        }

        return true;
    }

    public void save() {
        try (DataOutputStream e = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(Path.of("level.dat"))))) {
            e.write(this.blocks);
        } catch (Exception ignored) {
        }
    }

    public void tick() {
        this.unprocessed += this.width * this.height * this.depth;
        int ticks = this.unprocessed / 400;
        this.unprocessed -= ticks * 400;

        for(int i = 0; i < ticks; ++i) {
            int x = this.random.nextInt(this.width);
            int y = this.random.nextInt(this.depth);
            int z = this.random.nextInt(this.height);
            Block tile = this.getBlock(x, y, z);
            if (tile.isTickable()) {
                tile.tick(this, x, y, z, this.random);
            }
        }
    }

    public void setStateListener(WorldStateListener stateListener) {
        this.stateListener = stateListener;
    }

    public void calculateHeightMap() {
        this.calculateHeightMap(0, 0, this.width, this.height);
    }

    public void calculateHeightMap(int minX, int minZ, int maxX, int maxZ) {
        for (int x = minX; x < maxX; ++x) {
            for (int z = minZ; z < maxZ; ++z) {
                int y = this.depth - 1;
                for (; y >= 0; --y) {
                    if (this.getBlockId(x, y, z) != 0) {
                        break;
                    }
                }
                this.heightmap[z * this.width + x] = (short) y;
            }
        }
    }

    private void notifyChangesOfBlock(int x, int y, int z) {
        this.calculateHeightMap(x, z, x + 1, z + 1);
        if (this.stateListener != null) {
            int chunkX = x / CHUNK_SIZE;
            int chunkY = y / CHUNK_SIZE;
            int chunkZ = z / CHUNK_SIZE;
            int localX = x % CHUNK_SIZE;
            int localY = y % CHUNK_SIZE;
            int localZ = z % CHUNK_SIZE;
            this.stateListener.onChunkModified(chunkX, chunkY, chunkZ);
            if (localX == 0) {
                this.stateListener.onChunkModified(chunkX - 1, chunkY, chunkZ);
            } else if (localX == CHUNK_SIZE - 1) {
                this.stateListener.onChunkModified(chunkX + 1, chunkY, chunkZ);
            }
            if (localZ == 0) {
                this.stateListener.onChunkModified(chunkX, chunkY, chunkZ - 1);
            } else if (localZ == CHUNK_SIZE - 1) {
                this.stateListener.onChunkModified(chunkX, chunkY, chunkZ + 1);
            }
            if (localY == 0) {
                this.stateListener.onChunkModified(chunkX, chunkY - 1, chunkZ);
            } else if (localY == CHUNK_SIZE - 1) {
                this.stateListener.onChunkModified(chunkX, chunkY + 1, chunkZ);
            }
        }
    }

    public boolean isOutOfBounds(int x, int y, int z) {
        return x < 0 || y < 0 || z < 0 || x >= this.width || y >= this.depth || z >= this.height;
    }

    public void setBlockId(int x, int y, int z, int id) {
        if (this.isOutOfBounds(x, y, z)) return;
        this.blocks[(y * this.height + z) * this.width + x] = (byte) id;
        this.notifyChangesOfBlock(x, y, z);
    }

    public int getBlockId(int x, int y, int z) {
        if (this.isOutOfBounds(x, y, z)) return 0;
        return this.blocks[(y * this.height + z) * this.width + x];
    }

    public Block getBlock(int x, int y, int z) {
        return Blocks.blocks[this.getBlockId(x, y, z)];
    }

    public boolean isLit(int x, int y, int z) {
        if (this.isOutOfBounds(x, y, z)) return true;
        return y >= this.heightmap[x + z * this.width];
    }

    public float getBrigthness(int x, int y, int z) {
        if (this.isOutOfBounds(x, y, z) || this.heightmap[z * this.width + x] <= y) return 1f;
        return 0.5f;
    }

    public List<Box> getCubes(Box box) {
        List<Box> boxes = new ArrayList<>();
        int x0 = (int) Math.clamp(box.minX, 0, this.width);
        int x1 = (int) Math.clamp(box.maxX + 1.0f, 0, this.width);
        int y0 = (int) Math.clamp(box.minY, 0, this.depth);
        int y1 = (int) Math.clamp(box.maxY + 1.0f, 0, this.depth);
        int z0 = (int) Math.clamp(box.minZ, 0, this.height);
        int z1 = (int) Math.clamp(box.maxZ + 1.0f, 0, this.height);

        for (int y = y0; y < y1; ++y) {
            for (int z = z0; z < z1; ++z) {
                for (int x = x0; x < x1; ++x) {
                    if (this.getBlockId(x, y, z) != 0) {
                        boxes.add(new Box(x, y, z, x + 1, y + 1, z + 1));
                    }
                }
            }
        }

        return boxes;
    }

    public BlockHitResult raytraceBlock(double x0, double y0, double z0, double x1, double y1, double z1) {
        int endX = (int) Math.floor(x1);
        int endY = (int) Math.floor(y1);
        int endZ = (int) Math.floor(z1);
        int x = (int) Math.floor(x0);
        int y = (int) Math.floor(y0);
        int z = (int) Math.floor(z0);

        int attempts = 100;

        while (attempts-- >= 0) {
            if (Double.isNaN(x0) || Double.isNaN(y0) || Double.isNaN(z0)) {
                return null;
            }

            Block block = this.getBlock(x, y, z);
            if (block != Blocks.AIR) {
                BlockHitResult result = block.raytrace(x, y, z, new Vector3d(x0 - x, y0 - y, z0 - z), new Vector3d(x1 - x, y1 - y, z1 - z));
                if (result != null) {
                    return result;
                }
            }

            if (x == endX && y == endY && z == endZ) {
                return null;
            }

            double nx = endX > x ? x + 1D : x;
            double ny = endY > y ? y + 1D : y;
            double nz = endZ > z ? z + 1D : z;

            double distX = x1 - x0;
            double distY = y1 - y0;
            double distZ = z1 - z0;

            double stepX = endX != x ? (nx - x0) / distX : 999D;
            double stepY = endY != y ? (ny - y0) / distY : 999D;
            double stepZ = endZ != z ? (nz - z0) / distZ : 999D;

            int dir;
            if (stepX < stepY && stepX < stepZ) {
                dir = endX > x ? 4 : 5;
                x0 = nx;
                y0 += distY * stepX;
                z0 += distZ * stepX;
            } else if (stepY < stepZ) {
                dir = endY > y ? 0 : 1;
                x0 += distX * stepY;
                y0 = ny;
                z0 += distZ * stepY;
            } else {
                dir = endZ > z ? 2 : 3;
                x0 += distX * stepZ;
                y0 += distY * stepZ;
                z0 = nz;
            }

            x = (int) Math.floor(x0);
            if (dir == 5) --x;

            y = (int) Math.floor(y0);
            if (dir == 1) --y;

            z = (int) Math.floor(z0);
            if (dir == 3) --z;
        }

        return null;
    }

    public interface WorldStateListener {
        void onChunkModified(int chunkX, int chunkY, int chunkZ);
    }
}
