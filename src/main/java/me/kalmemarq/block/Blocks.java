package me.kalmemarq.block;

public class Blocks {
    public static final Block[] blocks = new Block[6];

    public static final Block AIR = new Block(0, new int[0]);
    public static final Block STONE = new Block(1, new int[]{5, 5, 5, 5, 5, 5});
    public static final Block GRASS = new GrassBlock(2, new int[]{2, 0, 3, 3, 3, 3});
    public static final Block DIRT = new Block(3, new int[]{2, 2, 2, 2, 2, 2});
    public static final Block COBBLESTONE = new Block(4, new int[]{1, 1, 1, 1, 1, 1});
    public static final Block PLANKS = new Block(5, new int[]{4, 4, 4, 4, 4, 4});
}
