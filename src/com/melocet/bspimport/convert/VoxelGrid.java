package com.melocet.bspimport.convert;

import java.util.ArrayList;
import java.util.List;

/** The map cut into blocks. Index = (y * nz + z) * nx + x, all in block units from the grid's corner. */
public final class VoxelGrid {

    public static final byte AIR = 0;
    public static final byte SOLID = 1;
    public static final byte WATER = 2;
    public static final byte SKY = 3;
    public static final byte LADDER = 4;
    /** Solid but meant to be unseen (clip walls, invisible func_walls). */
    public static final byte INVISIBLE = 5;
    /** Half of a door; details in {@link #doors}. */
    public static final byte DOOR = 6;
    /** An invisible light block; its level (1..15) is kept in {@link #shape}. */
    public static final byte LIGHT = 7;

    /** A spawn inside the grid, feet position, Minecraft yaw. */
    public record Spawn(double x, double y, double z, float yaw) {}

    /**
     * One door half. alongX: the door panel runs along x (so it closes a gap in a wall running along x).
     */
    public record Door(boolean upper, boolean alongX, boolean rightHinge, boolean metal) {}

    public final java.util.Map<Integer, Door> doors = new java.util.HashMap<>();

    /**
     * A small prop part shown as a scaled block display instead of blocks: min corner and size in
     * blocks from the grid's corner, and the texture index that picks its block.
     */
    public record Display(double x, double y, double z, double sx, double sy, double sz, int texture) {}

    public final List<Display> displays = new ArrayList<>();
    /** Models of the props built as displays because people walk through them. */
    public final List<String> walkThrough = new ArrayList<>();

    public final int nx;
    public final int ny;
    public final int nz;
    /** Map coordinates of the grid's (0, 0, 0) block: min x, max y (grid z runs toward -y), min z. */
    public double mapX, mapY, mapZ;
    public double scale;
    public final byte[] kind;
    /** Texture index per block, -1 when none reached it. */
    public final int[] texture;
    /**
     * Which eighths of a solid block are filled: bit (sx + 2*sy + 4*sz) for sx, sy, sz in {0, 1},
     * sy up, sz south. 0xFF (-1 as a byte) is a full block. Picks slabs, stairs and thin blocks.
     */
    public final byte[] shape;
    /**
     * Which of its texture's pattern blocks a solid block uses: 0 the texture's own block, n the
     * n-th variant (a darker grout line, a stripe, a sign).
     */
    public final byte[] variant;
    public final List<Spawn> ctSpawns = new ArrayList<>();
    public final List<Spawn> tSpawns = new ArrayList<>();

    VoxelGrid(int nx, int ny, int nz) {
        this.nx = nx;
        this.ny = ny;
        this.nz = nz;
        this.kind = new byte[nx * ny * nz];
        this.texture = new int[nx * ny * nz];
        java.util.Arrays.fill(texture, -1);
        this.shape = new byte[nx * ny * nz];
        java.util.Arrays.fill(shape, (byte) -1);
        this.variant = new byte[nx * ny * nz];
    }

    public int index(int x, int y, int z) {
        return (y * nz + z) * nx + x;
    }

    public boolean inside(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < nx && y < ny && z < nz;
    }

    public byte at(int x, int y, int z) {
        return inside(x, y, z) ? kind[index(x, y, z)] : AIR;
    }

    public static boolean open(byte k) {
        return k == AIR || k == WATER || k == LADDER || k == DOOR || k == LIGHT;
    }

    public int count(byte k) {
        int n = 0;
        for (byte b : kind) if (b == k) n++;
        return n;
    }
}
