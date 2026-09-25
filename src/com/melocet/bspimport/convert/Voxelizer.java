package com.melocet.bspimport.convert;

import com.melocet.bspimport.bsp.MapData;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;

/**
 * Cuts a map into blocks. Map coordinates are z-up; Minecraft is y-up, so map x becomes x, map z
 * becomes y, and map y becomes -z (keeps the map the right way round instead of mirrored).
 */
public final class Voxelizer {

    /**
     * skyHeadroom: blocks of open air kept above the highest thing in the map. Open maps (a flat
     * field under a huge sky box) are mostly sky; above this the sky is cut off and roofed with the
     * sky block. 0 keeps the whole sky.
     */
    public record Options(double scale, int samples, double threshold, int shell, int terrainFill,
                          Set<String> solidEntities, Set<String> waterEntities, Set<String> ladderEntities, int skyHeadroom) {
        public Options(double scale, int samples, double threshold, int shell, int terrainFill,
                       Set<String> solidEntities, Set<String> waterEntities, Set<String> ladderEntities) {
            this(scale, samples, threshold, shell, terrainFill, solidEntities, waterEntities, ladderEntities, 24);
        }
    }

    /** Grids bigger than this many blocks don't fit in a server's memory. */
    private static final long MAX_CELLS = 150_000_000L;

    private record BrushEntity(int model, double ox, double oy, double oz, byte kind,
                               double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        boolean contains(double x, double y, double z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }

        boolean overlaps(double x0, double y0, double z0, double x1, double y1, double z1) {
            return x1 >= minX && x0 <= maxX && y1 >= minY && y0 <= maxY && z1 >= minZ && z0 <= maxZ;
        }
    }

    private final MapData map;
    private final Options o;
    private final double minX, minY, minZ, maxX, maxY, maxZ;
    /** Whether the top was cut off below the map's sky, so it needs a roof. */
    private final boolean capped;
    private final List<BrushEntity> brushes = new ArrayList<>();

    public Voxelizer(MapData map, Options options) {
        this.map = map;
        this.o = options;
        // Mappers build on a 16/32/64 unit grid; snapping the block grid to it puts walls on block edges.
        double s = options.scale();
        double[] b = map.bounds();
        this.minX = Math.floor(b[0] / s) * s;
        this.minY = Math.floor(b[1] / s) * s;
        // A 3D skybox (a small copy of the scenery the sky shows) can sit inside the playable box,
        // high above or below the map; the height range of what's built around the spawns skips it.
        double[] range = options.skyHeadroom() > 0 ? contentRange(map, b) : null;
        double bottom = Math.floor(b[2] / s) * s;
        this.minZ = range != null && range[2] > 0 ? Math.max(bottom, Math.floor((range[0] - 4 * s) / s) * s) : bottom;
        this.maxX = Math.ceil(b[3] / s) * s;
        this.maxY = Math.ceil(b[4] / s) * s;
        double top = Math.ceil(b[5] / s) * s;
        double cut = range == null ? top : Math.ceil((range[1] + options.skyHeadroom() * s) / s) * s;
        this.capped = cut < top;
        this.maxZ = capped ? cut : top;
    }

    /**
     * The height range of what's built around the spawns: brush faces that aren't sky or tool
     * textures, terrain and the spawns themselves, inside the map's area, merged into bands wherever
     * they overlap or nearly touch. The bands holding a spawn count; a band holding only the sky
     * camera (the 3D skybox) doesn't. Returns {bottom, top, 1 if a skybox band was left out}, or null.
     */
    private static double[] contentRange(MapData map, double[] b) {
        List<double[]> spans = new ArrayList<>();
        String[] names = map.textureNames();
        for (int f = map.firstFace(0); f < map.firstFace(0) + map.numFaces(0); f++) {
            int t = map.faceTexture(f);
            if (t < 0 || map.isSky(t) || names[t].startsWith("tools/")) continue;
            double[] v = map.faceVertices(f);
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            boolean in = false;
            for (int i = 0; i < v.length; i += 3) {
                lo = Math.min(lo, v[i + 2]);
                hi = Math.max(hi, v[i + 2]);
                in |= v[i] >= b[0] && v[i] <= b[3] && v[i + 1] >= b[1] && v[i + 1] <= b[4];
            }
            if (in) spans.add(new double[]{lo, hi});
        }
        for (MapData.Triangle t : map.extraSurfaces()) {
            double lo = Math.min(t.a()[2], Math.min(t.b()[2], t.c()[2])), hi = Math.max(t.a()[2], Math.max(t.b()[2], t.c()[2]));
            spans.add(new double[]{lo, hi});
        }
        List<Double> spawns = new ArrayList<>();
        Double camera = null;
        for (Map<String, String> e : map.entities()) {
            String cls = e.getOrDefault("classname", "");
            double z = vector(e.get("origin"))[2];
            if (cls.equals("sky_camera")) camera = z;
            if (!map.humanSpawnClasses().contains(cls) && !map.zombieSpawnClasses().contains(cls)) continue;
            spawns.add(z);
            spans.add(new double[]{z, z + 72});
        }
        if (spans.isEmpty()) return null;
        spans.sort((x, y) -> Double.compare(x[0], y[0]));
        List<double[]> bands = new ArrayList<>();
        for (double[] sp : spans) {
            double[] last = bands.isEmpty() ? null : bands.get(bands.size() - 1);
            if (last != null && sp[0] <= last[1] + 512) last[1] = Math.max(last[1], sp[1]);
            else bands.add(sp.clone());
        }
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        boolean skipped = false;
        for (double[] band : bands) {
            boolean spawn = spawns.isEmpty();
            for (double z : spawns) spawn |= z >= band[0] - 1 && z <= band[1] + 1;
            boolean sky = camera != null && camera >= band[0] - 1 && camera <= band[1] + 1;
            if (sky && !spawn) {
                skipped = true;
                continue;
            }
            lo = Math.min(lo, band[0]);
            hi = Math.max(hi, band[1]);
        }
        if (lo > hi) return null;
        return new double[]{lo, hi, skipped ? 1 : 0};
    }

    /** Grid size in blocks before trimming: x, y (height), z. */
    public int[] size() {
        double s = o.scale();
        return new int[]{(int) Math.round((maxX - minX) / s), (int) Math.round((maxZ - minZ) / s), (int) Math.round((maxY - minY) / s)};
    }

    /** How props and doors go in: which doors are metal, how wide a door may be before it's left open. */
    /**
     * What goes in besides the map's own brushes: props and doors, which brush entities to keep
     * (the map's logic may switch some on and off), and which textures are see-through (grates,
     * fences, glass), whose surfaces are built however thin they are.
     */
    public record Extras(PropSet props, java.util.regex.Pattern metalDoors, int doorMaxWidth,
                         java.util.function.Predicate<Map<String, String>> keep, java.util.BitSet seeThrough,
                         Patterns patterns, Lights lights, Displays displays) {
        public Extras(PropSet props, java.util.regex.Pattern metalDoors, int doorMaxWidth,
                      java.util.function.Predicate<Map<String, String>> keep, java.util.BitSet seeThrough) {
            this(props, metalDoors, doorMaxWidth, keep, seeThrough, null, null, null);
        }
    }

    /** Picks a block variant for one spot of a texture, from the texels the block covers. */
    public interface Patterns {
        /** Whether this texture has pixels to read and a look that may vary. */
        boolean has(int texture);

        /** 0 for the texture's own block, else a variant number; s, t is the middle, foot* the size in texels. */
        int variant(int texture, double s, double t, double footS, double footT);
    }

    /**
     * Light blocks: at the map's light entities when entities is on, and in front of faces whose
     * texture name matches textures (glowing panels, GoldSrc "~" textures) at textureLevel.
     */
    public record Lights(boolean entities, java.util.regex.Pattern textures, int textureLevel) {}

    /**
     * Props no bigger than maxSize blocks become up to parts scaled block displays each, limit in
     * total; props smaller than minSize units that don't get displays are left out.
     */
    public record Displays(double maxSize, int parts, int limit, double minSize) {}

    /** @param progress receives 0..100 */
    public VoxelGrid run(IntConsumer progress) {
        return run(progress, new Extras(PropSet.empty(), java.util.regex.Pattern.compile("$^"), 2, e -> true, new java.util.BitSet()));
    }

    /** @param progress receives 0..100 */
    public VoxelGrid run(IntConsumer progress, Extras extras) {
        collectBrushEntities(extras.keep());
        int[] n = size();
        if ((long) n[0] * n[1] * n[2] > MAX_CELLS) {
            int suggest = (int) Math.ceil(o.scale() * Math.cbrt((double) n[0] * n[1] * n[2] / MAX_CELLS) / 8) * 8;
            throw new IllegalStateException("the map is " + n[0] + " x " + n[2] + " blocks and " + n[1]
                    + " tall at this scale, too big to build. Try a scale of " + suggest + " or more.");
        }
        VoxelGrid g = new VoxelGrid(n[0], n[1], n[2]);
        g.mapX = minX;
        g.mapY = maxY;
        g.mapZ = minZ;
        g.scale = o.scale();
        AtomicInteger done = new AtomicInteger();
        IntStream.range(0, g.ny).parallel().forEach(y -> {
            classifyLayer(g, y);
            progress.accept(done.incrementAndGet() * 70 / g.ny);
        });
        int[] votes = new int[g.kind.length];
        terrain(g, votes);
        if (capped) roof(g);
        progress.accept(75);
        // Open space as the map has it. Trimming below turns far-away solid into air too, and that
        // air must not count as a place anyone can stand and look from.
        boolean[] open = new boolean[g.kind.length];
        for (int i = 0; i < open.length; i++) open[i] = VoxelGrid.open(g.kind[i]);
        keepShell(g);
        progress.accept(80);
        paint(g, votes);
        progress.accept(92);
        skyFromTextures(g);
        dropHidden(g, open);
        placeDoors(g, extras);
        placeSeeThroughFaces(g, extras.seeThrough());
        placePatterns(g, extras.patterns());
        placeProps(g, extras.props(), extras.displays());
        placeLights(g, extras);
        clipDisplays(g);
        findSpawns(g);
        progress.accept(96);
        VoxelGrid trimmed = trim(g);
        progress.accept(100);
        return trimmed;
    }

    /** The sky was cut off above the map: open blocks in the top layer become sky, so nobody climbs out. */
    private static void roof(VoxelGrid g) {
        int y = g.ny - 1;
        for (int z = 0; z < g.nz; z++) {
            for (int x = 0; x < g.nx; x++) {
                int i = g.index(x, y, z);
                if (g.kind[i] == VoxelGrid.AIR) g.kind[i] = VoxelGrid.SKY;
            }
        }
    }

    // ---------------------------------------------------------------- props and doors

    /**
     * Prop triangles become blocks in open space only (never inside a wall), colored by the
     * prop's own materials, which come after the map's textures in the texture table.
     */
    private void placeProps(VoxelGrid g, PropSet props, Displays displays) {
        int base = map.textureNames().length;
        double s = o.scale();
        double step = s / 3;
        int shown = 0, walked = 0;
        for (PropSet.Instance p : props.instances) {
            float[] t = p.triangles();
            if (displays != null && t.length >= 9) {
                double size = extent(t);
                if (size <= displays.maxSize() * s && shown < displays.limit()) {
                    int parts = display(g, p, base, displays.parts(), false);
                    if (parts > 0) {
                        shown += parts;
                        continue;
                    }
                } else if (walked < displays.limit() && !KEEP_SOLID.matcher(p.model()).find()) {
                    // Bigger props people walk through (metal detectors, arches) would close up as
                    // blocks; as displays the way through stays open.
                    int parts = display(g, p, base, Math.max(10, displays.parts()), true);
                    if (parts > 0) {
                        walked += parts;
                        g.walkThrough.add(p.model());
                        continue;
                    }
                }
                if (size < displays.minSize()) continue;
            }
            for (int tri = 0; tri < t.length / 9; tri++) {
                int o9 = tri * 9;
                double ax = t[o9], ay = t[o9 + 1], az = t[o9 + 2];
                double bx = t[o9 + 3] - ax, by = t[o9 + 4] - ay, bz = t[o9 + 5] - az;
                double cxv = t[o9 + 6] - ax, cyv = t[o9 + 7] - ay, czv = t[o9 + 8] - az;
                double longest = Math.max(Math.sqrt(bx * bx + by * by + bz * bz), Math.sqrt(cxv * cxv + cyv * cyv + czv * czv));
                int steps = (int) Math.ceil(longest / step) + 1;
                int texture = base + p.material()[tri];
                for (int i = 0; i <= steps; i++) {
                    double u = (double) i / steps;
                    for (int j = 0; i + j <= steps; j++) {
                        double v = (double) j / steps;
                        double x = ax + bx * u + cxv * v, y = ay + by * u + cyv * v, z = az + bz * u + czv * v;
                        double fx = (x - minX) / s, fy = (z - minZ) / s, fz = (maxY - y) / s;
                        int gx = (int) Math.floor(fx), gy = (int) Math.floor(fy), gz = (int) Math.floor(fz);
                        if (!g.inside(gx, gy, gz)) continue;
                        int idx = g.index(gx, gy, gz);
                        // Prop blocks record which eighths their surface touches: a table top in the
                        // upper half becomes a top slab, a leg only a sliver becomes a fence.
                        int bit = 1 << ((fx - gx >= 0.5 ? 1 : 0) + 2 * (fy - gy >= 0.5 ? 1 : 0) + 4 * (fz - gz >= 0.5 ? 1 : 0));
                        if (g.kind[idx] == VoxelGrid.AIR) {
                            g.kind[idx] = VoxelGrid.SOLID;
                            g.texture[idx] = texture;
                            g.shape[idx] = (byte) bit;
                        } else if (g.kind[idx] == VoxelGrid.SOLID && g.texture[idx] >= base) {
                            g.shape[idx] = (byte) (g.shape[idx] | bit);
                        }
                    }
                }
            }
        }
    }

    /**
     * Display parts may reach into walls and floors (props sink into them, frames sit in the wall),
     * and two things drawn in the same place flicker. The part of each box inside a filled block (or
     * the filled half of a slab or stair) is cut away and what's left is boxed again.
     */
    private static void clipDisplays(VoxelGrid g) {
        List<VoxelGrid.Display> out = new ArrayList<>();
        for (VoxelGrid.Display d : g.displays) {
            int x0 = (int) Math.round(d.x() * 4), y0 = (int) Math.round(d.y() * 4), z0 = (int) Math.round(d.z() * 4);
            int nx = Math.max(1, (int) Math.round(d.sx() * 4)), ny = Math.max(1, (int) Math.round(d.sy() * 4)), nz = Math.max(1, (int) Math.round(d.sz() * 4));
            boolean[] free = new boolean[nx * ny * nz];
            int kept = 0;
            for (int y = 0; y < ny; y++) {
                for (int z = 0; z < nz; z++) {
                    for (int x = 0; x < nx; x++) {
                        boolean ok = !taken(g, x0 + x, y0 + y, z0 + z);
                        free[(y * nz + z) * nx + x] = ok;
                        if (ok) kept++;
                    }
                }
            }
            if (kept == free.length) {
                out.add(d);
                continue;
            }
            if (kept == 0) continue;
            // box what's left, biggest first; slivers of one cell are dropped
            boolean[] used = new boolean[free.length];
            for (int y = 0; y < ny; y++) {
                for (int z = 0; z < nz; z++) {
                    for (int x = 0; x < nx; x++) {
                        int i = (y * nz + z) * nx + x;
                        if (!free[i] || used[i]) continue;
                        int w = 1;
                        while (x + w < nx && free[i + w] && !used[i + w]) w++;
                        int dd = 1;
                        grow:
                        while (z + dd < nz) {
                            for (int k = 0; k < w; k++) {
                                int j = (y * nz + z + dd) * nx + x + k;
                                if (!free[j] || used[j]) break grow;
                            }
                            dd++;
                        }
                        int h = 1;
                        up:
                        while (y + h < ny) {
                            for (int c = 0; c < dd; c++) {
                                for (int k = 0; k < w; k++) {
                                    int j = ((y + h) * nz + z + c) * nx + x + k;
                                    if (!free[j] || used[j]) break up;
                                }
                            }
                            h++;
                        }
                        for (int yy = y; yy < y + h; yy++) for (int zz = z; zz < z + dd; zz++) for (int xx = x; xx < x + w; xx++) used[(yy * nz + zz) * nx + xx] = true;
                        if (w * h * dd < 2) continue;
                        out.add(new VoxelGrid.Display((x0 + x) / 4.0, (y0 + y) / 4.0, (z0 + z) / 4.0, w / 4.0, h / 4.0, dd / 4.0, d.texture()));
                    }
                }
            }
        }
        g.displays.clear();
        g.displays.addAll(out);
    }

    /** Whether the quarter-block cell is inside something built: a solid block's filled eighth, a door, a barrier. */
    private static boolean taken(VoxelGrid g, int cx, int cy, int cz) {
        int x = Math.floorDiv(cx, 4), y = Math.floorDiv(cy, 4), z = Math.floorDiv(cz, 4);
        if (!g.inside(x, y, z)) return false;
        int i = g.index(x, y, z);
        byte k = g.kind[i];
        if (k == VoxelGrid.DOOR || k == VoxelGrid.INVISIBLE || k == VoxelGrid.SKY) return true;
        if (k != VoxelGrid.SOLID) return false;
        int bit = (Math.floorMod(cx, 4) >= 2 ? 1 : 0) + 2 * (Math.floorMod(cy, 4) >= 2 ? 1 : 0) + 4 * (Math.floorMod(cz, 4) >= 2 ? 1 : 0);
        // no half filled enough to say (mask 0) is still built as a whole block
        return g.shape[i] == 0 || (g.shape[i] & (1 << bit)) != 0;
    }

    /** Props with a way through that still have to be walked on or blocked: stairs, shafts, stall walls. */
    private static final java.util.regex.Pattern KEEP_SOLID = java.util.regex.Pattern.compile("stair|step|ladder|ramp|elevator|shaft|stall|scaffold|catwalk|bridge");

    /** The longest side of a prop's box, in map units. */
    private static double extent(float[] t) {
        double[] lo = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE}, hi = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (int i = 0; i < t.length; i++) {
            lo[i % 3] = Math.min(lo[i % 3], t[i]);
            hi[i % 3] = Math.max(hi[i % 3], t[i]);
        }
        return Math.max(hi[0] - lo[0], Math.max(hi[1] - lo[1], hi[2] - lo[2]));
    }

    /**
     * A small prop as a few boxes: its surface is cut into quarter-block cells, the inside filled,
     * and the cells merged into as few boxes as fit (a table top and four legs). Each box becomes one
     * scaled block display; the biggest `maxParts` are kept. Returns how many, 0 when none fit.
     */
    private int display(VoxelGrid g, PropSet.Instance p, int base, int maxParts, boolean onlyWalkThrough) {
        double c = o.scale() / 4;
        float[] t = p.triangles();
        double lx = Double.MAX_VALUE, ly = Double.MAX_VALUE, lz = Double.MAX_VALUE, hx = -Double.MAX_VALUE, hy = -Double.MAX_VALUE, hz = -Double.MAX_VALUE;
        for (int i = 0; i < t.length; i += 3) {
            double fx = (t[i] - minX) / c, fy = (t[i + 2] - minZ) / c, fz = (maxY - t[i + 1]) / c;
            lx = Math.min(lx, fx); ly = Math.min(ly, fy); lz = Math.min(lz, fz);
            hx = Math.max(hx, fx); hy = Math.max(hy, fy); hz = Math.max(hz, fz);
        }
        int x0 = (int) Math.floor(lx) - 1, y0 = (int) Math.floor(ly) - 1, z0 = (int) Math.floor(lz) - 1;
        int nx = (int) Math.floor(hx) - x0 + 2, ny = (int) Math.floor(hy) - y0 + 2, nz = (int) Math.floor(hz) - z0 + 2;
        if ((long) nx * ny * nz > 60000) return 0;
        int[] cell = new int[nx * ny * nz]; // texture + 1, 0 empty
        double step = c / 3;
        for (int tri = 0; tri < t.length / 9; tri++) {
            int o9 = tri * 9;
            double ax = t[o9], ay = t[o9 + 1], az = t[o9 + 2];
            double bx = t[o9 + 3] - ax, by = t[o9 + 4] - ay, bz = t[o9 + 5] - az;
            double cxv = t[o9 + 6] - ax, cyv = t[o9 + 7] - ay, czv = t[o9 + 8] - az;
            double longest = Math.max(Math.sqrt(bx * bx + by * by + bz * bz), Math.sqrt(cxv * cxv + cyv * cyv + czv * czv));
            int steps = (int) Math.ceil(longest / step) + 1;
            int texture = base + p.material()[tri];
            for (int i = 0; i <= steps; i++) {
                double u = (double) i / steps;
                for (int j = 0; i + j <= steps; j++) {
                    double v = (double) j / steps;
                    double x = ax + bx * u + cxv * v, y = ay + by * u + cyv * v, z = az + bz * u + czv * v;
                    int cx = (int) Math.floor((x - minX) / c) - x0, cy = (int) Math.floor((z - minZ) / c) - y0, cz = (int) Math.floor((maxY - y) / c) - z0;
                    if (cx < 0 || cy < 0 || cz < 0 || cx >= nx || cy >= ny || cz >= nz) continue;
                    cell[(cy * nz + cz) * nx + cx] = texture + 1;
                }
            }
        }
        // Whatever the outside can't reach is inside the prop: filled with its most common material.
        boolean[] outside = new boolean[cell.length];
        int[] queue = new int[cell.length];
        int head = 0, tail = 0;
        queue[tail++] = 0;
        outside[0] = true;
        java.util.Map<Integer, Integer> counts = new java.util.HashMap<>();
        for (int v : cell) if (v > 0) counts.merge(v, 1, Integer::sum);
        if (counts.isEmpty()) return 0;
        int common = java.util.Collections.max(counts.entrySet(), Map.Entry.comparingByValue()).getKey();
        while (head < tail) {
            int i = queue[head++];
            int x = i % nx, z = i / nx % nz, y = i / (nx * nz);
            int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
            for (int[] d : dirs) {
                int xx = x + d[0], yy = y + d[1], zz = z + d[2];
                if (xx < 0 || yy < 0 || zz < 0 || xx >= nx || yy >= ny || zz >= nz) continue;
                int j = (yy * nz + zz) * nx + xx;
                if (outside[j] || cell[j] != 0) continue;
                outside[j] = true;
                queue[tail++] = j;
            }
        }
        for (int i = 0; i < cell.length; i++) if (cell[i] == 0 && !outside[i]) cell[i] = common;
        if (onlyWalkThrough && !walkThrough(cell, nx, ny, nz)) return 0;
        // Greedy boxes: grow along x, then z, then y while every cell is filled and unused.
        boolean[] used = new boolean[cell.length];
        List<int[]> boxes = new ArrayList<>();
        for (int y = 0; y < ny; y++) {
            for (int z = 0; z < nz; z++) {
                for (int x = 0; x < nx; x++) {
                    int i = (y * nz + z) * nx + x;
                    if (cell[i] == 0 || used[i]) continue;
                    int w = 1;
                    while (x + w < nx && free(cell, used, nx, nz, x + w, y, z)) w++;
                    int d = 1;
                    grow:
                    while (z + d < nz) {
                        for (int k = 0; k < w; k++) if (!free(cell, used, nx, nz, x + k, y, z + d)) break grow;
                        d++;
                    }
                    int h = 1;
                    up:
                    while (y + h < ny) {
                        for (int dz = 0; dz < d; dz++) for (int k = 0; k < w; k++) if (!free(cell, used, nx, nz, x + k, y + h, z + dz)) break up;
                        h++;
                    }
                    java.util.Map<Integer, Integer> mats = new java.util.HashMap<>();
                    for (int yy = y; yy < y + h; yy++) {
                        for (int zz = z; zz < z + d; zz++) {
                            for (int xx = x; xx < x + w; xx++) {
                                int j = (yy * nz + zz) * nx + xx;
                                used[j] = true;
                                mats.merge(cell[j], 1, Integer::sum);
                            }
                        }
                    }
                    int mat = java.util.Collections.max(mats.entrySet(), Map.Entry.comparingByValue()).getKey();
                    boxes.add(new int[]{x, y, z, w, h, d, mat - 1});
                }
            }
        }
        boxes.sort((a, b) -> Integer.compare(b[3] * b[4] * b[5], a[3] * a[4] * a[5]));
        int kept = Math.min(maxParts, boxes.size());
        for (int k = 0; k < kept; k++) {
            int[] b = boxes.get(k);
            g.displays.add(new VoxelGrid.Display((x0 + b[0]) / 4.0, (y0 + b[1]) / 4.0, (z0 + b[2]) / 4.0, b[3] / 4.0, b[4] / 4.0, b[5] / 4.0, b[6]));
        }
        return kept;
    }

    /**
     * Whether a person fits through the prop: a gap at least 0.75 blocks wide and 1.75 tall, starting
     * at its bottom, running all the way through along x or z, with the prop on both sides of it.
     * Cells are quarter blocks; the grid has one empty cell of padding all round.
     */
    private static boolean walkThrough(int[] cell, int nx, int ny, int nz) {
        int bottom = -1;
        for (int y = 1; y < ny - 1 && bottom < 0; y++) {
            for (int i = y * nz * nx; i < (y + 1) * nz * nx; i++) if (cell[i] != 0) { bottom = y; break; }
        }
        if (bottom < 0) return false;
        for (int axis = 0; axis < 2; axis++) {
            // along x: the gap is measured across z; along z: across x
            int across = axis == 0 ? nz : nx, along = axis == 0 ? nx : nz;
            boolean[] clear = new boolean[ny * across];
            boolean[] side = new boolean[across];
            for (int y = 1; y < ny - 1; y++) {
                for (int a = 1; a < across - 1; a++) {
                    boolean empty = true;
                    for (int b = 1; b < along - 1; b++) {
                        int x = axis == 0 ? b : a, z = axis == 0 ? a : b;
                        if (cell[(y * nz + z) * nx + x] != 0) {
                            empty = false;
                            side[a] = true;
                        }
                    }
                    clear[y * across + a] = empty;
                }
            }
            for (int start = bottom; start <= bottom + 1; start++) {
                if (start + 7 > ny - 1) continue;
                int run = 0;
                for (int a = 1; a < across - 1; a++) {
                    boolean ok = true;
                    for (int y = start; y < start + 7 && ok; y++) ok = clear[y * across + a];
                    run = ok ? run + 1 : 0;
                    if (run < 3) continue;
                    int first = a - run + 1;
                    boolean left = false, right = false;
                    for (int k = 1; k < first; k++) left |= side[k];
                    for (int k = a + 1; k < across - 1; k++) right |= side[k];
                    if (left && right) return true;
                }
            }
        }
        return false;
    }

    private static boolean free(int[] cell, boolean[] used, int nx, int nz, int x, int y, int z) {
        int i = (y * nz + z) * nx + x;
        return cell[i] != 0 && !used[i];
    }

    // ---------------------------------------------------------------- lights

    /**
     * Light blocks where the map has lights, so rooms are lit where the map lights them and dark
     * where it doesn't. Brightness 200 (the editors' default) gives level 11.
     */
    private void placeLights(VoxelGrid g, Extras ex) {
        Lights l = ex.lights();
        if (l == null) return;
        double s = o.scale();
        if (l.entities()) {
            for (Map<String, String> e : map.entities()) {
                String cls = e.getOrDefault("classname", "").toLowerCase(Locale.ROOT);
                if (!cls.equals("light") && !cls.equals("light_spot") && !cls.equals("light_dynamic")) continue;
                // spawnflag 1: starts switched off
                if ((parseInt(e.get("spawnflags")) & 1) != 0 || !ex.keep().test(e)) continue;
                double[] c = numbers(e.getOrDefault("_light", ""), 4);
                double strength = c[3] > 0 ? c[3] : 200;
                if (cls.equals("light_dynamic")) strength = Math.max(100, numbers(e.getOrDefault("distance", "200"), 1)[0]);
                double tint = Math.max(c[0], Math.max(c[1], c[2]));
                if (tint > 0) strength = strength * Math.max(0.4, tint / 255);
                int level = (int) Math.round(Math.max(6, Math.min(15, 6 + strength / 40)));
                double[] at = vector(e.get("origin"));
                putLight(g, at[0], at[1], at[2], level);
            }
        }
        if (l.textures() == null) return;
        String[] names = map.textureNames();
        for (double[] mm : solidModels()) {
            int model = (int) mm[0];
            for (int f = map.firstFace(model); f < map.firstFace(model) + map.numFaces(model); f++) {
                int tex = map.faceTexture(f);
                if (tex < 0 || !l.textures().matcher(names[tex]).find()) continue;
                double[] v = moved(map.faceVertices(f), mm);
                if (v.length < 9) continue;
                walkFace(v, map.faceNormal(f), s * 3, -s * 0.5, (x, y, z) -> putLight(g, x, y, z, l.textureLevel()));
            }
        }
    }

    /** A light in the nearest free block around a point: lights hang inside lamps and right under ceilings. */
    private void putLight(VoxelGrid g, double x, double y, double z, int level) {
        double s = o.scale();
        int gx = (int) Math.floor((x - minX) / s), gy = (int) Math.floor((z - minZ) / s), gz = (int) Math.floor((maxY - y) / s);
        for (int r = 0; r <= 2; r++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    for (int dx = -r; dx <= r; dx++) {
                        if (Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) != r) continue;
                        int xx = gx + dx, yy = gy + dy, zz = gz + dz;
                        if (!g.inside(xx, yy, zz)) continue;
                        int i = g.index(xx, yy, zz);
                        if (g.kind[i] == VoxelGrid.LIGHT) {
                            g.shape[i] = (byte) Math.max(g.shape[i], level);
                            return;
                        }
                        if (g.kind[i] == VoxelGrid.AIR) {
                            g.kind[i] = VoxelGrid.LIGHT;
                            g.shape[i] = (byte) level;
                            return;
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- patterns

    /**
     * Gives each visible wall block the block that matches the part of its texture it shows: the
     * texels the block covers are averaged, and a spot that differs enough from the texture's
     * average (grout, stripes, trim) gets its own block.
     */
    private void placePatterns(VoxelGrid g, Patterns p) {
        if (p == null) return;
        double s = o.scale();
        java.util.BitSet done = new java.util.BitSet(g.kind.length);
        for (double[] mm : solidModels()) {
            int model = (int) mm[0];
            double ox = mm[1], oy = mm[2], oz = mm[3];
            for (int f = map.firstFace(model); f < map.firstFace(model) + map.numFaces(model); f++) {
                int tex = map.faceTexture(f);
                if (tex < 0 || !p.has(tex)) continue;
                float[] ax = map.faceTexAxes(f);
                if (ax == null) continue;
                double[] v = moved(map.faceVertices(f), mm);
                if (v.length < 9) continue;
                double[] n = map.faceNormal(f);
                double footS = s * Math.sqrt(ax[0] * ax[0] + ax[1] * ax[1] + ax[2] * ax[2]);
                double footT = s * Math.sqrt(ax[4] * ax[4] + ax[5] * ax[5] + ax[6] * ax[6]);
                walkFace(v, n, s / 2.5, s * 0.35, (x, y, z) -> {
                    int gx = (int) Math.floor((x - minX) / s), gy = (int) Math.floor((z - minZ) / s), gz = (int) Math.floor((maxY - y) / s);
                    if (!g.inside(gx, gy, gz)) return;
                    int i = g.index(gx, gy, gz);
                    if (g.kind[i] != VoxelGrid.SOLID || g.texture[i] != tex || done.get(i)) return;
                    done.set(i);
                    // the block's middle, moved onto the face, in the brush's own coordinates
                    double mx = minX + (gx + 0.5) * s, my = maxY - (gz + 0.5) * s, mz = minZ + (gy + 0.5) * s;
                    double d = (mx - v[0]) * n[0] + (my - v[1]) * n[1] + (mz - v[2]) * n[2];
                    double px = mx - n[0] * d - ox, py = my - n[1] * d - oy, pz = mz - n[2] * d - oz;
                    double ts = ax[0] * px + ax[1] * py + ax[2] * pz + ax[3];
                    double tt = ax[4] * px + ax[5] * py + ax[6] * pz + ax[7];
                    g.variant[i] = (byte) p.variant(tex, ts, tt, footS, footT);
                });
            }
        }
    }

    /** The world (model 0) and the brush entities built as solid, as {model, origin x, y, z}. */
    private List<double[]> solidModels() {
        List<double[]> models = new ArrayList<>();
        models.add(new double[]{0, 0, 0, 0});
        for (BrushEntity e : brushes) if (e.kind == VoxelGrid.SOLID) models.add(new double[]{e.model, e.ox, e.oy, e.oz});
        return models;
    }

    private static double[] moved(double[] v, double[] model) {
        for (int i = 0; i < v.length; i += 3) {
            v[i] += model[1];
            v[i + 1] += model[2];
            v[i + 2] += model[3];
        }
        return v;
    }

    private interface FacePoint {
        void at(double x, double y, double z);
    }

    /**
     * Points spread over a convex face every `step` units, plus its middle, each pushed `push` units
     * against the normal (into the solid; negative pushes out into the open).
     */
    private static void walkFace(double[] v, double[] n, double step, double push, FacePoint out) {
        int nv = v.length / 3;
        if (nv < 3) return;
        double[] u = {v[3] - v[0], v[4] - v[1], v[5] - v[2]};
        double ul = Math.sqrt(u[0] * u[0] + u[1] * u[1] + u[2] * u[2]);
        if (ul < 1e-6) return;
        u[0] /= ul;
        u[1] /= ul;
        u[2] /= ul;
        double[] w = {n[1] * u[2] - n[2] * u[1], n[2] * u[0] - n[0] * u[2], n[0] * u[1] - n[1] * u[0]};
        double[] pu = new double[nv];
        double[] pw = new double[nv];
        double lo1 = Double.MAX_VALUE, hi1 = -Double.MAX_VALUE, lo2 = Double.MAX_VALUE, hi2 = -Double.MAX_VALUE;
        double cu = 0, cw = 0;
        for (int i = 0; i < nv; i++) {
            double dx = v[i * 3] - v[0], dy = v[i * 3 + 1] - v[1], dz = v[i * 3 + 2] - v[2];
            pu[i] = dx * u[0] + dy * u[1] + dz * u[2];
            pw[i] = dx * w[0] + dy * w[1] + dz * w[2];
            lo1 = Math.min(lo1, pu[i]);
            hi1 = Math.max(hi1, pu[i]);
            lo2 = Math.min(lo2, pw[i]);
            hi2 = Math.max(hi2, pw[i]);
            cu += pu[i];
            cw += pw[i];
        }
        facePoint(v, u, w, n, cu / nv, cw / nv, push, out);
        for (double a = lo1 + step / 2; a < hi1; a += step) {
            for (double b = lo2 + step / 2; b < hi2; b += step) {
                if (insideConvex(pu, pw, a, b)) facePoint(v, u, w, n, a, b, push, out);
            }
        }
    }

    private static void facePoint(double[] v, double[] u, double[] w, double[] n, double a, double b, double push, FacePoint out) {
        out.at(v[0] + a * u[0] + b * w[0] - n[0] * push,
                v[1] + a * u[1] + b * w[1] - n[1] * push,
                v[2] + a * u[2] + b * w[2] - n[2] * push);
    }

    private static int parseInt(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The first `count` numbers of a space-separated value, 0 where missing. */
    private static double[] numbers(String s, int count) {
        double[] out = new double[count];
        String[] parts = s.trim().split("\\s+");
        for (int i = 0; i < count && i < parts.length; i++) {
            try {
                out[i] = Double.parseDouble(parts[i]);
            } catch (NumberFormatException ignored) {
                out[i] = 0;
            }
        }
        return out;
    }

    /**
     * A door's closed box becomes a Minecraft door (two wide at most, a double door) standing on the
     * floor in the middle of the box's thin side; the rest of the opening above it is walled up with
     * the wall's own look so nobody climbs over. Wider openings (garage doors, gates) stay open.
     */
    private void placeDoors(VoxelGrid g, Extras extras) {
        double s = o.scale();
        for (PropSet.Door d : extras.props().doors) {
            double[] b = d.box();
            double fx0 = (b[0] - minX) / s, fx1 = (b[3] - minX) / s;
            double fz0 = (maxY - b[4]) / s, fz1 = (maxY - b[1]) / s;
            double fy0 = (b[2] - minZ) / s, fy1 = (b[5] - minZ) / s;
            boolean alongX = fx1 - fx0 >= fz1 - fz0;
            double w0 = alongX ? fx0 : fz0, w1 = alongX ? fx1 : fz1;
            double t0 = alongX ? fz0 : fx0, t1 = alongX ? fz1 : fx1;
            int start = (int) Math.round(w0);
            int width = Math.max(1, (int) Math.round(w1) - start);
            if (width > extras.doorMaxWidth()) continue;
            int thin = (int) Math.floor((t0 + t1) / 2);
            int bottom = (int) Math.round(fy0);
            int top = Math.max(bottom + 2, (int) Math.round(fy1));
            boolean metal = extras.metalDoors().matcher(d.name().toLowerCase(java.util.Locale.ROOT)).find();
            for (int w = 0; w < width; w++) {
                int x = alongX ? start + w : thin;
                int z = alongX ? thin : start + w;
                if (!g.inside(x, bottom, z) || !g.inside(x, bottom + 1, z)) continue;
                int lower = g.index(x, bottom, z), upper = g.index(x, bottom + 1, z);
                if (g.kind[lower] != VoxelGrid.AIR || g.kind[upper] != VoxelGrid.AIR) continue;
                boolean right = width == 2 && w == 1;
                g.kind[lower] = VoxelGrid.DOOR;
                g.kind[upper] = VoxelGrid.DOOR;
                g.doors.put(lower, new VoxelGrid.Door(false, alongX, right, metal));
                g.doors.put(upper, new VoxelGrid.Door(true, alongX, right, metal));
                for (int y = bottom + 2; y < top && y < g.ny; y++) {
                    int i = g.index(x, y, z);
                    if (g.kind[i] != VoxelGrid.AIR) break;
                    g.kind[i] = VoxelGrid.SOLID;
                    g.texture[i] = neighborTexture(g, x, y, z);
                    g.shape[i] = (byte) -1;
                }
            }
        }
    }

    // ---------------------------------------------------------------- what is where

    private double cx(int gx) { return minX + gx * o.scale(); }
    private double cz(int gy) { return minZ + gy * o.scale(); }
    private double cy(int gz) { return maxY - gz * o.scale(); }

    /**
     * One layer. The region at every block corner is looked up first; a block whose 8 corners sit
     * in the same uniform region (a convex leaf with nothing inside it) needs only one test.
     */
    private void classifyLayer(VoxelGrid g, int y) {
        int w = g.nx + 1, d = g.nz + 1;
        int[] low = new int[w * d];
        int[] high = new int[w * d];
        for (int z = 0; z < d; z++) {
            for (int x = 0; x < w; x++) {
                low[z * w + x] = map.uniformRegion(cx(x), cy(z), cz(y));
                high[z * w + x] = map.uniformRegion(cx(x), cy(z), cz(y + 1));
            }
        }
        double s = o.scale();
        for (int z = 0; z < g.nz; z++) {
            for (int x = 0; x < g.nx; x++) {
                int r = low[z * w + x];
                boolean same = r >= 0
                        && low[z * w + x + 1] == r && low[(z + 1) * w + x] == r && low[(z + 1) * w + x + 1] == r
                        && high[z * w + x] == r && high[z * w + x + 1] == r && high[(z + 1) * w + x] == r && high[(z + 1) * w + x + 1] == r
                        && !brushNear(cx(x), cy(z + 1), cz(y), cx(x + 1), cy(z), cz(y + 1));
                int idx = g.index(x, y, z);
                g.kind[idx] = same
                        ? map.kindAt(0, cx(x) + s / 2, cy(z) - s / 2, cz(y) + s / 2)
                        : sample(x, y, z, g, idx);
            }
        }
    }

    private boolean brushNear(double x0, double y0, double z0, double x1, double y1, double z1) {
        for (BrushEntity e : brushes) if (e.overlaps(x0, y0, z0, x1, y1, z1)) return true;
        return false;
    }

    /**
     * Samples n³ points in the block. Besides the kind, records which eighths are mostly solid
     * (when n is even, so every sample belongs to exactly one eighth) for slabs and stairs.
     */
    private byte sample(int gx, int gy, int gz, VoxelGrid g, int idx) {
        double s = o.scale();
        int n = o.samples();
        double x0 = cx(gx), z0 = cz(gy), y0 = cy(gz + 1);
        int solid = 0, invisible = 0, sky = 0, water = 0, ladder = 0;
        int[] eighths = new int[8];
        int half = n / 2;
        for (int a = 0; a < n; a++) {
            double x = x0 + (a + 0.5) * s / n;
            for (int b = 0; b < n; b++) {
                double y = y0 + (b + 0.5) * s / n;
                for (int c = 0; c < n; c++) {
                    double z = z0 + (c + 0.5) * s / n;
                    byte k = map.kindAt(0, x, y, z);
                    if (k == VoxelGrid.AIR) k = brushAt(x, y, z);
                    if (k == VoxelGrid.SOLID) {
                        // map y grows toward grid north, so a low b is the south (sz = 1) half
                        eighths[(a < half ? 0 : 1) + 2 * (c < half ? 0 : 1) + 4 * (b < half ? 1 : 0)]++;
                    }
                    switch (k) {
                        case VoxelGrid.SOLID -> solid++;
                        case VoxelGrid.SKY -> sky++;
                        case VoxelGrid.WATER -> water++;
                        case VoxelGrid.LADDER -> ladder++;
                        case VoxelGrid.INVISIBLE -> invisible++;
                        default -> { }
                    }
                }
            }
        }
        double total = n * n * n;
        if (n % 2 == 0) {
            int per = half * half * half;
            int mask = 0;
            for (int e = 0; e < 8; e++) if (eighths[e] * 2 >= per) mask |= 1 << e;
            g.shape[idx] = (byte) mask;
        }
        // Sky brushes are thin and sit against the solid outside of the map, so a block holding one is
        // part sky, part "solid". Sky has to win there, or the whole sky box turns into bare walls.
        if (sky / total >= o.threshold()) return VoxelGrid.SKY;
        if (solid / total >= o.threshold()) return VoxelGrid.SOLID;
        if (invisible / total >= o.threshold()) return VoxelGrid.INVISIBLE;
        if (water / total >= 0.5) return VoxelGrid.WATER;
        if (ladder > 0) return VoxelGrid.LADDER;
        return VoxelGrid.AIR;
    }

    private byte brushAt(double x, double y, double z) {
        for (BrushEntity e : brushes) {
            if (!e.contains(x, y, z)) continue;
            byte k = map.kindAt(e.model, x - e.ox, y - e.oy, z - e.oz);
            if (k == VoxelGrid.AIR) continue;
            return e.kind == VoxelGrid.SOLID && k != VoxelGrid.SOLID ? k : e.kind;
        }
        return VoxelGrid.AIR;
    }

    private void collectBrushEntities(java.util.function.Predicate<Map<String, String>> keep) {
        brushes.clear();
        for (Map<String, String> e : map.entities()) {
            String model = e.getOrDefault("model", "");
            if (!model.startsWith("*")) continue;
            if (!keep.test(e)) continue;
            String cls = e.getOrDefault("classname", "").toLowerCase(Locale.ROOT);
            byte kind;
            if (o.solidEntities().contains(cls)) {
                boolean unseen = !"0".equals(e.getOrDefault("rendermode", "0")) && "0".equals(e.getOrDefault("renderamt", "255"));
                kind = unseen ? VoxelGrid.INVISIBLE : VoxelGrid.SOLID;
                String look = firstTexture(model);
                // Black cards that hide what's behind a doorway (areaportal windows) are pure
                // rendering; other tool-textured brushes are invisible walls.
                if (look.startsWith("tools/toolsblack")) continue;
                if (look.startsWith("tools/")) kind = VoxelGrid.INVISIBLE;
            } else if (o.waterEntities().contains(cls)) {
                kind = VoxelGrid.WATER;
            } else if (o.ladderEntities().contains(cls)) {
                kind = VoxelGrid.LADDER;
            } else {
                continue;
            }
            int index;
            try {
                index = Integer.parseInt(model.substring(1));
            } catch (NumberFormatException ex) {
                continue;
            }
            if (index <= 0 || index >= map.modelCount()) continue;
            double[] origin = vector(e.get("origin"));
            double[] mb = map.modelBounds(index);
            brushes.add(new BrushEntity(index, origin[0], origin[1], origin[2], kind,
                    mb[0] + origin[0] - 1, mb[1] + origin[1] - 1, mb[2] + origin[2] - 1,
                    mb[3] + origin[0] + 1, mb[4] + origin[1] + 1, mb[5] + origin[2] + 1));
        }
    }

    private String firstTexture(String model) {
        int index;
        try {
            index = Integer.parseInt(model.substring(1));
        } catch (NumberFormatException ex) {
            return "";
        }
        if (index <= 0 || index >= map.modelCount()) return "";
        for (int f = map.firstFace(index); f < map.firstFace(index) + map.numFaces(index); f++) {
            int t = map.faceTexture(f);
            if (t >= 0) return map.textureNames()[t];
        }
        return "";
    }

    /**
     * Grates, chain-link fences and glass are often a few units thick, too thin to fill any block.
     * Their faces are walked like prop surfaces and turned into blocks in open space, so the
     * placer makes them bars or panes instead of leaving a hole.
     */
    private void placeSeeThroughFaces(VoxelGrid g, java.util.BitSet seeThrough) {
        if (seeThrough.isEmpty()) return;
        List<double[]> models = new ArrayList<>(); // model, origin x, y, z
        models.add(new double[]{0, 0, 0, 0});
        for (BrushEntity e : brushes) models.add(new double[]{e.model, e.ox, e.oy, e.oz});
        double s = o.scale();
        double step = s / 3;
        for (double[] mm : models) {
            int model = (int) mm[0];
            double ox = mm[1], oy = mm[2], oz = mm[3];
            for (int f = map.firstFace(model); f < map.firstFace(model) + map.numFaces(model); f++) {
                int tex = map.faceTexture(f);
                if (tex < 0 || !seeThrough.get(tex)) continue;
                double[] v = map.faceVertices(f);
                int nv = v.length / 3;
                for (int k = 1; k + 1 < nv; k++) {
                    double[] a = {v[0] + ox, v[1] + oy, v[2] + oz};
                    double[] b = {v[k * 3] + ox, v[k * 3 + 1] + oy, v[k * 3 + 2] + oz};
                    double[] c = {v[k * 3 + 3] + ox, v[k * 3 + 4] + oy, v[k * 3 + 5] + oz};
                    int steps = (int) Math.ceil(Math.max(dist(a, b), dist(a, c)) / step) + 1;
                    for (int i = 0; i <= steps; i++) {
                        double u = (double) i / steps;
                        for (int j = 0; i + j <= steps; j++) {
                            double w = (double) j / steps;
                            double x = a[0] + (b[0] - a[0]) * u + (c[0] - a[0]) * w;
                            double y = a[1] + (b[1] - a[1]) * u + (c[1] - a[1]) * w;
                            double z = a[2] + (b[2] - a[2]) * u + (c[2] - a[2]) * w;
                            double fx = (x - minX) / s, fy = (z - minZ) / s, fz = (maxY - y) / s;
                            int gx = (int) Math.floor(fx), gy = (int) Math.floor(fy), gz = (int) Math.floor(fz);
                            if (!g.inside(gx, gy, gz)) continue;
                            int idx = g.index(gx, gy, gz);
                            int bit = 1 << ((fx - gx >= 0.5 ? 1 : 0) + 2 * (fy - gy >= 0.5 ? 1 : 0) + 4 * (fz - gz >= 0.5 ? 1 : 0));
                            if (g.kind[idx] == VoxelGrid.AIR) {
                                g.kind[idx] = VoxelGrid.SOLID;
                                g.texture[idx] = tex;
                                g.shape[idx] = (byte) bit;
                            } else if (g.kind[idx] == VoxelGrid.SOLID && g.texture[idx] == tex) {
                                g.shape[idx] = (byte) (g.shape[idx] | bit);
                            }
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- terrain

    /**
     * Source displacements are surfaces, not volumes. Each triangle becomes a skin of solid blocks,
     * thickened a few blocks away from the side it faces so the ground isn't paper thin.
     */
    private void terrain(VoxelGrid g, int[] votes) {
        double s = o.scale();
        double step = s / 4;
        // Which eighths the surface passes through, per block it newly makes solid, and which way
        // is "into the ground" there. Filled blocks behind the surface are always whole.
        java.util.Map<Integer, int[]> skin = new java.util.HashMap<>();
        java.util.Set<Integer> filled = new java.util.HashSet<>();
        for (MapData.Triangle t : map.extraSurfaces()) {
            double[] a = t.a(), b = t.b(), c = t.c();
            double e1 = dist(a, b), e2 = dist(a, c);
            int steps = (int) Math.ceil(Math.max(e1, e2) / step) + 1;
            int[] back = backDirection(t.normal());
            for (int i = 0; i <= steps; i++) {
                double u = (double) i / steps;
                for (int j = 0; i + j <= steps; j++) {
                    double v = (double) j / steps;
                    double x = a[0] + (b[0] - a[0]) * u + (c[0] - a[0]) * v;
                    double y = a[1] + (b[1] - a[1]) * u + (c[1] - a[1]) * v;
                    double z = a[2] + (b[2] - a[2]) * u + (c[2] - a[2]) * v;
                    double fx = (x - minX) / s, fy = (z - minZ) / s, fz = (maxY - y) / s;
                    int gx = (int) Math.floor(fx), gy = (int) Math.floor(fy), gz = (int) Math.floor(fz);
                    if (!g.inside(gx, gy, gz)) continue;
                    int idx = g.index(gx, gy, gz);
                    if (g.kind[idx] == VoxelGrid.AIR || skin.containsKey(idx)) {
                        int[] entry = skin.computeIfAbsent(idx, k -> new int[]{0, 0});
                        entry[0] |= 1 << ((fx - gx >= 0.5 ? 1 : 0) + 2 * (fy - gy >= 0.5 ? 1 : 0) + 4 * (fz - gz >= 0.5 ? 1 : 0));
                        entry[1] = (back[0] + 1) + 3 * (back[1] + 1) + 9 * (back[2] + 1);
                    }
                    g.kind[idx] = VoxelGrid.SOLID;
                    if (t.texture() >= 0) vote(g, votes, idx, t.texture());
                    for (int k = 1; k <= o.terrainFill(); k++) {
                        int bx = gx + back[0] * k, by = gy + back[1] * k, bz = gz + back[2] * k;
                        if (!g.inside(bx, by, bz)) break;
                        int f = g.index(bx, by, bz);
                        if (g.kind[f] != VoxelGrid.AIR && g.kind[f] != VoxelGrid.SOLID) break;
                        g.kind[f] = VoxelGrid.SOLID;
                        filled.add(f);
                        g.shape[f] = (byte) -1;
                        if (t.texture() >= 0 && g.texture[f] < 0) g.texture[f] = t.texture();
                    }
                }
            }
        }
        for (var e : skin.entrySet()) {
            if (filled.contains(e.getKey())) continue;
            int code = e.getValue()[1];
            int[] back = {code % 3 - 1, code / 3 % 3 - 1, code / 9 - 1};
            g.shape[e.getKey()] = (byte) thickenBehind(e.getValue()[0], back);
        }
    }

    /** Every eighth the surface touches, plus the eighths behind it inside the same block. */
    private static int thickenBehind(int hits, int[] back) {
        int mask = hits;
        for (int bit = 0; bit < 8; bit++) {
            if ((hits & 1 << bit) == 0) continue;
            int[] p = {bit & 1, bit >> 1 & 1, bit >> 2 & 1};
            for (int axis = 0; axis < 3; axis++) {
                if (back[axis] == 0) continue;
                int[] q = p.clone();
                q[axis] = back[axis] > 0 ? 1 : 0;
                mask |= 1 << (q[0] + 2 * q[1] + 4 * q[2]);
            }
        }
        return mask;
    }

    /** The grid step (x, y, z) pointing most directly behind a surface with this map-space normal. */
    private static int[] backDirection(double[] n) {
        // map (x, y, z) -> grid (x, z-up, -y)
        double gx = -n[0], gy = -n[2], gz = n[1];
        double ax = Math.abs(gx), ay = Math.abs(gy), az = Math.abs(gz);
        if (ay >= ax && ay >= az) return new int[]{0, gy > 0 ? 1 : -1, 0};
        if (ax >= az) return new int[]{gx > 0 ? 1 : -1, 0, 0};
        return new int[]{0, 0, gz > 0 ? 1 : -1};
    }

    private static double dist(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // ---------------------------------------------------------------- only the walls you can see

    /**
     * Everything outside a sealed map counts as solid. Keep a solid block only if an open block is
     * within `shell` blocks of it; the rest becomes air.
     */
    private void keepShell(VoxelGrid g) {
        int r = o.shell();
        boolean[] near = new boolean[g.kind.length];
        for (int i = 0; i < near.length; i++) near[i] = VoxelGrid.open(g.kind[i]);
        for (int axis = 0; axis < 3; axis++) near = dilate(g, near, axis, r);
        for (int i = 0; i < g.kind.length; i++) {
            byte k = g.kind[i];
            if ((k == VoxelGrid.SOLID || k == VoxelGrid.SKY || k == VoxelGrid.INVISIBLE) && !near[i]) {
                g.kind[i] = VoxelGrid.AIR;
                g.texture[i] = -1;
            }
        }
    }

    private static boolean[] dilate(VoxelGrid g, boolean[] in, int axis, int r) {
        boolean[] out = new boolean[in.length];
        for (int y = 0; y < g.ny; y++) {
            for (int z = 0; z < g.nz; z++) {
                for (int x = 0; x < g.nx; x++) {
                    if (!in[g.index(x, y, z)]) continue;
                    for (int d = -r; d <= r; d++) {
                        int xx = axis == 0 ? x + d : x, yy = axis == 1 ? y + d : y, zz = axis == 2 ? z + d : z;
                        if (g.inside(xx, yy, zz)) out[g.index(xx, yy, zz)] = true;
                    }
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- textures

    /**
     * Walks every visible face on a fine grid and gives the solid block just behind each point a vote
     * for that face's texture; the majority wins. Blocks deeper in the wall copy a neighbor.
     */
    private void paint(VoxelGrid g, int[] votes) {
        paintModel(g, votes, 0, 0, 0, 0);
        for (BrushEntity e : brushes) {
            if (e.kind == VoxelGrid.SOLID) paintModel(g, votes, e.model, e.ox, e.oy, e.oz);
        }
        for (int pass = 0; pass < 3; pass++) {
            int[] next = g.texture.clone();
            for (int y = 0; y < g.ny; y++) {
                for (int z = 0; z < g.nz; z++) {
                    for (int x = 0; x < g.nx; x++) {
                        int i = g.index(x, y, z);
                        if (g.kind[i] != VoxelGrid.SOLID || g.texture[i] >= 0) continue;
                        int t = neighborTexture(g, x, y, z);
                        if (t >= 0) next[i] = t;
                    }
                }
            }
            System.arraycopy(next, 0, g.texture, 0, next.length);
        }
    }

    private int neighborTexture(VoxelGrid g, int x, int y, int z) {
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] d : dirs) {
            int xx = x + d[0], yy = y + d[1], zz = z + d[2];
            if (!g.inside(xx, yy, zz)) continue;
            int t = g.texture[g.index(xx, yy, zz)];
            if (t >= 0) return t;
        }
        return -1;
    }

    private void paintModel(VoxelGrid g, int[] votes, int model, double ox, double oy, double oz) {
        double step = o.scale() / 2.5;
        double push = o.scale() * 0.35;
        int first = map.firstFace(model);
        int count = map.numFaces(model);
        for (int f = first; f < first + count; f++) {
            int tex = map.faceTexture(f);
            if (tex < 0) continue;
            double[] v = map.faceVertices(f);
            int nv = v.length / 3;
            if (nv < 3) continue;
            moved(v, new double[]{model, ox, oy, oz});
            walkFace(v, map.faceNormal(f), step, push, (x, y, z) -> votePoint(g, votes, tex, x, y, z));
        }
    }

    private void votePoint(VoxelGrid g, int[] votes, int tex, double x, double y, double z) {
        int gx = (int) Math.floor((x - minX) / o.scale());
        int gy = (int) Math.floor((z - minZ) / o.scale());
        int gz = (int) Math.floor((maxY - y) / o.scale());
        if (!g.inside(gx, gy, gz)) return;
        int i = g.index(gx, gy, gz);
        if (g.kind[i] == VoxelGrid.SOLID) vote(g, votes, i, tex);
    }

    /** Boyer-Moore majority: keeps the most common texture without counting them all. */
    private static void vote(VoxelGrid g, int[] votes, int i, int tex) {
        if (g.texture[i] == tex) {
            votes[i]++;
        } else if (votes[i] == 0) {
            g.texture[i] = tex;
            votes[i] = 1;
        } else {
            votes[i]--;
        }
    }

    private static boolean insideConvex(double[] pu, double[] pw, double a, double b) {
        int n = pu.length;
        int sign = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            double cross = (pu[j] - pu[i]) * (b - pw[i]) - (pw[j] - pw[i]) * (a - pu[i]);
            if (Math.abs(cross) < 1e-9) continue;
            int s = cross > 0 ? 1 : -1;
            if (sign == 0) sign = s;
            else if (s != sign) return false;
        }
        return true;
    }

    /** Solid behind a sky-textured face is sky, whatever the brush's contents say. */
    private void skyFromTextures(VoxelGrid g) {
        for (int i = 0; i < g.kind.length; i++) {
            if (g.kind[i] == VoxelGrid.SOLID && g.texture[i] >= 0 && map.isSky(g.texture[i])) {
                g.kind[i] = VoxelGrid.SKY;
                g.texture[i] = -1;
            }
        }
    }

    /**
     * Solid blocks that no face painted and that touch no open space are the map's solid outside,
     * sitting behind walls or behind the sky. Barriers are see-through, so leaving those as stone
     * would show a stone roof where the sky should be; they're dropped instead.
     */
    private static void dropHidden(VoxelGrid g, boolean[] open) {
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        boolean[] drop = new boolean[g.kind.length];
        for (int y = 0; y < g.ny; y++) {
            for (int z = 0; z < g.nz; z++) {
                for (int x = 0; x < g.nx; x++) {
                    int i = g.index(x, y, z);
                    if (g.kind[i] != VoxelGrid.SOLID || g.texture[i] >= 0) continue;
                    boolean seen = false;
                    for (int[] d : dirs) {
                        if (g.inside(x + d[0], y + d[1], z + d[2]) && open[g.index(x + d[0], y + d[1], z + d[2])]) {
                            seen = true;
                            break;
                        }
                    }
                    drop[i] = !seen;
                }
            }
        }
        for (int i = 0; i < drop.length; i++) if (drop[i]) g.kind[i] = VoxelGrid.AIR;
    }

    // ---------------------------------------------------------------- spawns and trimming

    private void findSpawns(VoxelGrid g) {
        // Only the best-ranked class present is used, so a CS map doesn't also pick up info_player_start.
        String human = firstPresent(map.humanSpawnClasses());
        String zombie = firstPresent(map.zombieSpawnClasses());
        for (Map<String, String> e : map.entities()) {
            String cls = e.getOrDefault("classname", "");
            List<VoxelGrid.Spawn> into = cls.equals(human) ? g.ctSpawns : cls.equals(zombie) ? g.tSpawns : null;
            if (into == null) continue;
            double[] p = vector(e.get("origin"));
            double[] angles = vector(e.get("angles"));
            int gx = (int) Math.floor((p[0] - minX) / o.scale());
            int gy = (int) Math.floor((p[2] - map.spawnFeetOffset() - minZ) / o.scale());
            int gz = (int) Math.floor((maxY - p[1]) / o.scale());
            if (!g.inside(gx, 0, gz)) continue;
            int standY = standable(g, gx, gy, gz);
            // Map yaw 0 looks along +x, turning toward +y; Minecraft yaw 0 looks along +z.
            float yaw = (float) (-angles[1] - 90);
            into.add(new VoxelGrid.Spawn(gx + 0.5, standY, gz + 0.5, yaw));
        }
    }

    private String firstPresent(List<String> classes) {
        for (String c : classes) {
            for (Map<String, String> e : map.entities()) {
                if (c.equals(e.get("classname"))) return c;
            }
        }
        return null;
    }

    private static int standable(VoxelGrid g, int x, int y, int z) {
        int[] tries = {0, 1, -1, 2, -2, 3, -3};
        for (int d : tries) {
            int yy = y + d;
            if (yy < 0 || yy + 1 >= g.ny) continue;
            boolean body = VoxelGrid.open(g.at(x, yy, z)) && VoxelGrid.open(g.at(x, yy + 1, z));
            boolean floor = yy == 0 || !VoxelGrid.open(g.at(x, yy - 1, z));
            if (body && floor) return yy;
        }
        return Math.max(0, Math.min(g.ny - 1, y));
    }

    /** Cuts away empty edges so the build starts at the first block that's actually placed. */
    private static VoxelGrid trim(VoxelGrid g) {
        int x0 = g.nx, y0 = g.ny, z0 = g.nz, x1 = -1, y1 = -1, z1 = -1;
        for (int y = 0; y < g.ny; y++) {
            for (int z = 0; z < g.nz; z++) {
                for (int x = 0; x < g.nx; x++) {
                    if (g.kind[g.index(x, y, z)] == VoxelGrid.AIR) continue;
                    x0 = Math.min(x0, x);
                    y0 = Math.min(y0, y);
                    z0 = Math.min(z0, z);
                    x1 = Math.max(x1, x);
                    y1 = Math.max(y1, y);
                    z1 = Math.max(z1, z);
                }
            }
        }
        if (x1 < 0 || (x0 == 0 && y0 == 0 && z0 == 0 && x1 == g.nx - 1 && y1 == g.ny - 1 && z1 == g.nz - 1)) return g;
        VoxelGrid t = new VoxelGrid(x1 - x0 + 1, y1 - y0 + 1, z1 - z0 + 1);
        t.scale = g.scale;
        t.mapX = g.mapX + x0 * g.scale;
        t.mapY = g.mapY - z0 * g.scale;
        t.mapZ = g.mapZ + y0 * g.scale;
        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    int from = g.index(x, y, z);
                    int to = t.index(x - x0, y - y0, z - z0);
                    t.kind[to] = g.kind[from];
                    t.texture[to] = g.texture[from];
                    t.shape[to] = g.shape[from];
                    t.variant[to] = g.variant[from];
                    VoxelGrid.Door door = g.doors.get(from);
                    if (door != null) t.doors.put(to, door);
                }
            }
        }
        for (VoxelGrid.Spawn s : g.ctSpawns) t.ctSpawns.add(new VoxelGrid.Spawn(s.x() - x0, s.y() - y0, s.z() - z0, s.yaw()));
        for (VoxelGrid.Spawn s : g.tSpawns) t.tSpawns.add(new VoxelGrid.Spawn(s.x() - x0, s.y() - y0, s.z() - z0, s.yaw()));
        t.walkThrough.addAll(g.walkThrough);
        for (VoxelGrid.Display d : g.displays) t.displays.add(new VoxelGrid.Display(d.x() - x0, d.y() - y0, d.z() - z0, d.sx(), d.sy(), d.sz(), d.texture()));
        return t;
    }

    private static double[] vector(String s) {
        double[] out = new double[3];
        if (s == null) return out;
        String[] parts = s.trim().split("\\s+");
        for (int i = 0; i < 3 && i < parts.length; i++) {
            try {
                out[i] = Double.parseDouble(parts[i]);
            } catch (NumberFormatException ignored) {
                out[i] = 0;
            }
        }
        return out;
    }
}
