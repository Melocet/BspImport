package com.melocet.bspimport.convert;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.block.data.type.Light;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.Wall;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.BitSet;
import java.util.EnumMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntConsumer;

/**
 * Sets the grid's blocks into a world, a batch per tick so the server keeps running. Remembers
 * which blocks it set and what was there before, so the import can be undone.
 */
public final class Placer extends BukkitRunnable {

    private final VoxelGrid grid;
    private final BlockPalette.Style[] styles;
    /** Per texture, the styles of its pattern variants 1, 2, ...; entries may be null. */
    private final BlockPalette.Style[][] variants;
    /** Scoreboard tag on every display this import spawns, so they can be found again. */
    private final String tag;
    private final List<UUID> spawned = new ArrayList<>();
    private int displayCursor;
    private final BlockPalette.Style fill;
    private final Material sky;
    private final Material woodDoor;
    private final Material metalDoor;
    private final World world;
    private final int ox, oy, oz;
    private final int perTick;
    private final IntConsumer progress;
    private final Runnable done;
    private final Map<Material, BlockData> data = new EnumMap<>(Material.class);
    private final BitSet placedMask = new BitSet();
    /** Whatever stood in a block before the import, when it wasn't air. */
    private final Map<Integer, BlockData> previous = new HashMap<>();
    private int cursor;
    private int lastPercent;
    private int placed;

    public Placer(VoxelGrid grid, BlockPalette.Style[] styles, BlockPalette.Style[][] variants, BlockPalette.Style fill, Material sky,
                  Material woodDoor, Material metalDoor, World world, int ox, int oy, int oz, int perTick, String tag,
                  IntConsumer progress, Runnable done) {
        this.grid = grid;
        this.styles = styles;
        this.variants = variants;
        this.tag = tag;
        this.fill = fill;
        this.sky = sky;
        this.woodDoor = woodDoor;
        this.metalDoor = metalDoor;
        this.world = world;
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.perTick = perTick;
        this.progress = progress;
        this.done = done;
    }

    @Override
    public void run() {
        int total = grid.kind.length;
        int budget = perTick;
        while (cursor < total && budget > 0) {
            int i = cursor++;
            byte k = grid.kind[i];
            if (k == VoxelGrid.AIR) continue;
            int x = i % grid.nx;
            int z = (i / grid.nx) % grid.nz;
            int y = i / (grid.nx * grid.nz);
            BlockData bd = blockFor(k, i, x, y, z);
            if (bd == null) continue;
            Block block = world.getBlockAt(ox + x, oy + y, oz + z);
            if (!block.getType().isAir()) previous.put(i, block.getBlockData());
            block.setBlockData(bd, false);
            placedMask.set(i);
            placed++;
            budget--;
        }
        // Blocks first, then the small props' displays, a few hundred per tick.
        if (cursor >= total) {
            int limit = Math.max(50, perTick / 50);
            while (displayCursor < grid.displays.size() && limit-- > 0) spawn(grid.displays.get(displayCursor++));
        }
        long all = (long) total + grid.displays.size();
        int percent = (int) ((cursor + displayCursor) * 100 / all);
        if (percent / 10 != lastPercent / 10) progress.accept(percent);
        lastPercent = percent;
        if (cursor >= total && displayCursor >= grid.displays.size()) {
            cancel();
            done.run();
        }
    }

    private void spawn(VoxelGrid.Display d) {
        BlockPalette.Style st = d.texture() >= 0 && d.texture() < styles.length ? styles[d.texture()] : null;
        if (st == null || st.full().isAir()) st = fill;
        BlockData bd = cached(st.full());
        // A display is lit by the block its position is in. The corner of a prop often sits inside the
        // floor (props sink a little into it), which renders the whole part black; the middle of the
        // box, or the first open block above it, is lit like the room.
        double cx = ox + d.x() + d.sx() / 2, cy = oy + d.y() + d.sy() / 2, cz = oz + d.z() + d.sz() / 2;
        // Slabs and stairs count as taken too: the light inside them is 0 as well. The nearest open
        // block (upward first) is where the display stands; the box is shifted back into place.
        int bx = (int) Math.floor(cx), by = (int) Math.floor(cy), bz = (int) Math.floor(cz);
        double px = cx, py = cy, pz = cz;
        search:
        for (int r = 0; r <= 2; r++) {
            for (int dy = r; dy >= -r; dy--) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) != r) continue;
                        Material in = world.getBlockAt(bx + dx, by + dy, bz + dz).getType();
                        if (!in.isAir() && in != Material.LIGHT) continue;
                        if (r > 0) {
                            px = bx + dx + 0.5;
                            py = by + dy + 0.5;
                            pz = bz + dz + 0.5;
                        }
                        break search;
                    }
                }
            }
        }
        org.bukkit.Location at = new org.bukkit.Location(world, px, py, pz);
        Vector3f offset = new Vector3f((float) (cx - px - d.sx() / 2), (float) (cy - py - d.sy() / 2), (float) (cz - pz - d.sz() / 2));
        BlockDisplay e = world.spawn(at, BlockDisplay.class, b -> {
            b.setBlock(bd);
            b.setTransformation(new Transformation(offset, new Quaternionf(),
                    new Vector3f((float) d.sx(), (float) d.sy(), (float) d.sz()), new Quaternionf()));
            b.setPersistent(true);
            b.addScoreboardTag("bspimport");
            b.addScoreboardTag(tag);
        });
        spawned.add(e.getUniqueId());
    }

    public int displays() {
        return spawned.size();
    }

    public String tag() {
        return tag;
    }

    /** A task that puts back what this import replaced, in batches, then runs `after`. */
    public BukkitRunnable undo(IntConsumer undoProgress, Runnable after) {
        BlockData air = Material.AIR.createBlockData();
        return new BukkitRunnable() {
            private int next = placedMask.nextSetBit(0);
            private int doneCount;

            @Override
            public void run() {
                int budget = perTick;
                while (next >= 0 && budget-- > 0) {
                    int x = next % grid.nx;
                    int z = (next / grid.nx) % grid.nz;
                    int y = next / (grid.nx * grid.nz);
                    world.getBlockAt(ox + x, oy + y, oz + z).setBlockData(previous.getOrDefault(next, air), false);
                    doneCount++;
                    next = placedMask.nextSetBit(next + 1);
                }
                if (next < 0) {
                    for (UUID id : spawned) {
                        Entity e = world.getEntity(id);
                        if (e != null) e.remove();
                    }
                    cancel();
                    after.run();
                } else if (placed > 0) {
                    undoProgress.accept(doneCount * 100 / placed);
                }
            }
        };
    }

    private BlockData blockFor(byte k, int i, int x, int y, int z) {
        return switch (k) {
            case VoxelGrid.SOLID -> {
                int t = grid.texture[i];
                BlockPalette.Style st = t >= 0 && t < styles.length ? styles[t] : null;
                int v = grid.variant[i];
                if (v > 0 && t >= 0 && t < variants.length && variants[t] != null && v <= variants[t].length) st = variants[t][v - 1];
                if (st == null || st.full().isAir()) st = fill;
                yield shaped(st, grid.shape[i] & 0xFF, x, y, z);
            }
            case VoxelGrid.WATER -> cached(Material.WATER);
            case VoxelGrid.SKY -> cached(sky);
            case VoxelGrid.INVISIBLE -> cached(Material.BARRIER);
            case VoxelGrid.LADDER -> ladder(x, y, z);
            case VoxelGrid.DOOR -> door(grid.doors.get(i));
            case VoxelGrid.LIGHT -> {
                Light light = (Light) Material.LIGHT.createBlockData();
                light.setLevel(Math.max(1, Math.min(15, grid.shape[i])));
                yield light;
            }
            default -> null;
        };
    }

    // ---------------------------------------------------------------- partial blocks

    private static final int BOTTOM = 0x33;
    private static final int TOP = 0xCC;
    private static final int[] VERTICAL_HALVES = {0x55, 0xAA, 0x0F, 0xF0};

    /**
     * Picks the block that matches which eighths are filled: whole, a slab, stairs facing the tall
     * side, or a thin block (pane, bars, fence, wall) for slivers. Falls back to the whole block when
     * the texture has no such variant.
     */
    private BlockData shaped(BlockPalette.Style st, int mask, int x, int y, int z) {
        int count = Integer.bitCount(mask);
        boolean seeThrough = st.full() == Material.IRON_BARS || st.thin() != null && st.thin().name().endsWith("_PANE");
        if (st.full() == Material.IRON_BARS) return thin(Material.IRON_BARS, x, y, z);
        if (count >= 7) return cached(st.full());
        int bottom = mask & BOTTOM, top = mask & TOP;
        if (bottom == BOTTOM && top == 0 && st.slab() != null) return slab(st.slab(), Slab.Type.BOTTOM);
        if (top == TOP && bottom == 0 && st.slab() != null) return slab(st.slab(), Slab.Type.TOP);
        if (bottom == BOTTOM && Integer.bitCount(top) == 2 && st.stairs() != null) {
            BlockFace side = sideOf(top >> 2 & 0x3 | (top >> 4 & 0xC));
            if (side != null) return stairs(st.stairs(), side, Bisected.Half.BOTTOM);
        }
        if (top == TOP && Integer.bitCount(bottom) == 2 && st.stairs() != null) {
            BlockFace side = sideOf(bottom & 0x3 | (bottom >> 2 & 0xC));
            if (side != null) return stairs(st.stairs(), side, Bisected.Half.TOP);
        }
        boolean verticalHalf = false;
        for (int h : VERTICAL_HALVES) if (mask == h) verticalHalf = true;
        if (st.thin() != null && (count <= 2 || verticalHalf && seeThrough)) return thin(st.thin(), x, y, z);
        if (count > 0 && top == 0 && st.slab() != null) return slab(st.slab(), Slab.Type.BOTTOM);
        if (count > 0 && bottom == 0 && st.slab() != null) return slab(st.slab(), Slab.Type.TOP);
        return cached(st.full());
    }

    /**
     * Which side two eighths in one layer sit on, as a 4-bit layer mask (bit = sx + 2*sz):
     * the tall back of a stair faces that way.
     */
    private static BlockFace sideOf(int layer) {
        return switch (layer) {
            case 0b0011 -> BlockFace.NORTH; // sz = 0
            case 0b1100 -> BlockFace.SOUTH; // sz = 1
            case 0b0101 -> BlockFace.WEST;  // sx = 0
            case 0b1010 -> BlockFace.EAST;  // sx = 1
            default -> null;
        };
    }

    private BlockData slab(Material m, Slab.Type type) {
        if (!(m.createBlockData() instanceof Slab slab)) return cached(m);
        slab.setType(type);
        return slab;
    }

    private BlockData stairs(Material m, BlockFace facing, Bisected.Half half) {
        if (!(m.createBlockData() instanceof Stairs stairs)) return cached(m);
        stairs.setFacing(facing);
        stairs.setHalf(half);
        stairs.setShape(Stairs.Shape.STRAIGHT);
        return stairs;
    }

    /** Panes, bars, fences and walls reach out to solid neighbours, as they would when placed by hand. */
    private BlockData thin(Material m, int x, int y, int z) {
        BlockData bd = m.createBlockData();
        BlockFace[] faces = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};
        int[][] step = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
        boolean[] link = new boolean[4];
        int links = 0;
        for (int f = 0; f < 4; f++) {
            byte k = grid.at(x + step[f][0], y, z + step[f][1]);
            link[f] = k == VoxelGrid.SOLID || k == VoxelGrid.INVISIBLE;
            if (link[f]) links++;
        }
        if (bd instanceof Wall wall) {
            for (int f = 0; f < 4; f++) wall.setHeight(faces[f], link[f] ? Wall.Height.LOW : Wall.Height.NONE);
            boolean straight = links == 2 && (link[0] && link[2] || link[1] && link[3]);
            wall.setUp(!straight);
        } else if (bd instanceof MultipleFacing mf) {
            for (int f = 0; f < 4; f++) {
                if (mf.getAllowedFaces().contains(faces[f])) mf.setFace(faces[f], link[f]);
            }
        }
        return bd;
    }

    private BlockData door(VoxelGrid.Door d) {
        if (d == null) return null;
        Material m = d.metal() ? metalDoor : woodDoor;
        if (!(m.createBlockData() instanceof Door door)) return null;
        door.setHalf(d.upper() ? Bisected.Half.TOP : Bisected.Half.BOTTOM);
        // A panel running along x closes a north-south passage, so it faces south; along z, east.
        door.setFacing(d.alongX() ? BlockFace.SOUTH : BlockFace.EAST);
        door.setHinge(d.rightHinge() ? Door.Hinge.RIGHT : Door.Hinge.LEFT);
        door.setOpen(false);
        return door;
    }

    /** A ladder hangs on the wall next to it, facing away from it. No wall, no ladder. */
    private BlockData ladder(int x, int y, int z) {
        BlockFace facing = null;
        if (solid(x + 1, y, z)) facing = BlockFace.WEST;
        else if (solid(x - 1, y, z)) facing = BlockFace.EAST;
        else if (solid(x, y, z + 1)) facing = BlockFace.NORTH;
        else if (solid(x, y, z - 1)) facing = BlockFace.SOUTH;
        if (facing == null) return null;
        Directional d = (Directional) Material.LADDER.createBlockData();
        d.setFacing(facing);
        return d;
    }

    private boolean solid(int x, int y, int z) {
        byte k = grid.at(x, y, z);
        return k == VoxelGrid.SOLID;
    }

    private BlockData cached(Material m) {
        return data.computeIfAbsent(m, mat -> {
            BlockData bd = mat.createBlockData();
            // leaves with no log nearby would decay away
            if (bd instanceof Leaves leaves) leaves.setPersistent(true);
            return bd;
        });
    }

    public int placed() {
        return placed;
    }

    public World world() {
        return world;
    }
}
