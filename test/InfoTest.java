import com.melocet.bspimport.bsp.BspFile;
import com.melocet.bspimport.bsp.MapData;
import com.melocet.bspimport.bsp.SourceBsp;
import com.melocet.bspimport.convert.BlockPalette;
import com.melocet.bspimport.convert.VoxelGrid;
import com.melocet.bspimport.convert.Voxelizer;
import org.bukkit.Material;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Logger;

/** Runs a real map through the pipeline and reports what came out, without a server. */
public class InfoTest {
    public static void main(String[] args) throws Exception {
        Path p = Path.of(args[0]);
        byte[] head = Arrays.copyOf(Files.readAllBytes(p), 4);
        long t0 = System.nanoTime();
        MapData m = SourceBsp.isSource(head) ? SourceBsp.read(p) : BspFile.read(p);
        System.out.printf("%s read in %d ms: %d entities, %d textures, %d terrain triangles%n", m.engine(),
                (System.nanoTime() - t0) / 1_000_000, m.entities().size(), m.textureNames().length, m.extraSurfaces().size());
        System.out.println("bounds " + Arrays.toString(m.bounds()));
        int known = 0;
        for (int c : m.textureColors()) if (c >= 0) known++;
        System.out.println("textures with a color in the map: " + known + "/" + m.textureColors().length);
        Map<String, Integer> classes = new TreeMap<>();
        for (Map<String, String> e : m.entities()) classes.merge(e.getOrDefault("classname", "?"), 1, Integer::sum);
        System.out.println("entities: " + classes);
        Voxelizer v = new Voxelizer(m, new Voxelizer.Options(32, 3, 0.25, 2, 3,
                Set.of("func_wall", "func_breakable", "func_wall_toggle", "func_pushable", "func_brush", "func_breakable_surf"),
                Set.of("func_water"), Set.of("func_ladder")));
        System.out.println("grid before trim " + Arrays.toString(v.size()));
        t0 = System.nanoTime();
        VoxelGrid g = v.run(x -> { });
        System.out.printf("voxelized in %d ms: %dx%dx%d, solid %d, sky %d, water %d, ladder %d, invisible %d%n",
                (System.nanoTime() - t0) / 1_000_000, g.nx, g.ny, g.nz, g.count(VoxelGrid.SOLID), g.count(VoxelGrid.SKY),
                g.count(VoxelGrid.WATER), g.count(VoxelGrid.LADDER), g.count(VoxelGrid.INVISIBLE));
        System.out.println("ct " + g.ctSpawns.size() + " t " + g.tSpawns.size() + " first ct " + (g.ctSpawns.isEmpty() ? "-" : g.ctSpawns.get(0)));
        BlockPalette pal = new BlockPalette(List.of("^sky$: AIR", "^!: WATER", "^\\{: IRON_BARS",
                "^tools/toolsskybox: BARRIER", "^tools/: AIR", "glass: GLASS"), Material.STONE, Logger.getLogger("t"));
        Map<Material, Integer> used = new TreeMap<>();
        int untextured = 0;
        for (int i = 0; i < g.kind.length; i++) {
            if (g.kind[i] != VoxelGrid.SOLID) continue;
            int t = g.texture[i];
            if (t < 0) { untextured++; continue; }
            Material mat = pal.pick(m.textureNames()[t], m.textureColors()[t]);
            used.merge(mat, 1, Integer::sum);
        }
        System.out.println("solid blocks without a texture: " + untextured);
        int exposed = 0, exposedUntextured = 0;
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int y = 0; y < g.ny; y++) for (int z = 0; z < g.nz; z++) for (int x = 0; x < g.nx; x++) {
            int i = g.index(x, y, z);
            if (g.kind[i] != VoxelGrid.SOLID) continue;
            boolean seen = false;
            for (int[] d : dirs) if (g.inside(x + d[0], y + d[1], z + d[2]) && VoxelGrid.open(g.at(x + d[0], y + d[1], z + d[2]))) seen = true;
            if (!seen) continue;
            exposed++;
            if (g.texture[i] < 0) exposedUntextured++;
        }
        System.out.println("visible solid blocks: " + exposed + ", of which untextured: " + exposedUntextured);
        System.out.println("blocks used: " + used);
        int[] perLayer = new int[g.ny];
        for (int y = 0; y < g.ny; y++) for (int z = 0; z < g.nz; z++) for (int x = 0; x < g.nx; x++) {
            int i = g.index(x, y, z);
            if (g.kind[i] == VoxelGrid.SOLID && g.texture[i] < 0) perLayer[y]++;
        }
        StringBuilder hist = new StringBuilder("untextured per layer: ");
        for (int y = 0; y < g.ny; y++) if (perLayer[y] > 0) hist.append(y).append("=").append(perLayer[y]).append(" ");
        System.out.println(hist);
        // Look closely at a few visible blocks nothing painted: what's around them in the map?
        int shown = 0;
        int startY = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        outer:
        for (int y = startY; y < g.ny; y += 7) for (int z = 0; z < g.nz; z++) for (int x = 0; x < g.nx; x++) {
            int i = g.index(x, y, z);
            if (g.kind[i] != VoxelGrid.SOLID || g.texture[i] >= 0) continue;
            int[] open = null;
            for (int[] d : dirs) if (VoxelGrid.open(g.at(x + d[0], y + d[1], z + d[2])) && g.inside(x + d[0], y + d[1], z + d[2])) open = d;
            if (open == null) continue;
            double cx = g.mapX + (x + 0.5) * g.scale, cy = g.mapY - (z + 0.5) * g.scale, cz = g.mapZ + (y + 0.5) * g.scale;
            System.out.printf("untextured block %d,%d,%d = map (%.0f %.0f %.0f), kind here %d, open side grid %s -> kind there %d%n",
                    x, y, z, cx, cy, cz, m.kindAt(0, cx, cy, cz), Arrays.toString(open),
                    m.kindAt(0, cx + open[0] * g.scale, cy - open[2] * g.scale, cz + open[1] * g.scale));
            int near = 0;
            for (int f = m.firstFace(0); f < m.firstFace(0) + m.numFaces(0); f++) {
                double[] fv = m.faceVertices(f);
                double mx = 0, my = 0, mz = 0;
                double lo0 = 1e9, hi0 = -1e9, lo1 = 1e9, hi1 = -1e9, lo2 = 1e9, hi2 = -1e9;
                for (int k = 0; k < fv.length / 3; k++) {
                    lo0 = Math.min(lo0, fv[k * 3]); hi0 = Math.max(hi0, fv[k * 3]);
                    lo1 = Math.min(lo1, fv[k * 3 + 1]); hi1 = Math.max(hi1, fv[k * 3 + 1]);
                    lo2 = Math.min(lo2, fv[k * 3 + 2]); hi2 = Math.max(hi2, fv[k * 3 + 2]);
                }
                if (cx < lo0 - 40 || cx > hi0 + 40 || cy < lo1 - 40 || cy > hi1 + 40 || cz < lo2 - 40 || cz > hi2 + 40) continue;
                int tex = m.faceTexture(f);
                double[] n = m.faceNormal(f);
                System.out.printf("   face %d tex=%s normal=(%.2f %.2f %.2f) box x%.0f..%.0f y%.0f..%.0f z%.0f..%.0f%n", f,
                        tex >= 0 ? m.textureNames()[tex] : m instanceof BspFile bf && bf.rawFaceTexture(f) >= 0 ? "(hidden: " + m.textureNames()[bf.rawFaceTexture(f)] + ")" : "(hidden)", n[0], n[1], n[2], lo0, hi0, lo1, hi1, lo2, hi2);
                if (++near > 6) break;
            }
            if (near == 0) System.out.println("   no world face within 40 units");
            if (++shown >= 4) break outer;
        }
        if (args.length > 1) {
            int y = Integer.parseInt(args[1]);
            for (int z = 0; z < g.nz; z++) {
                StringBuilder row = new StringBuilder();
                for (int x = 0; x < g.nx; x++) {
                    byte k = g.kind[g.index(x, y, z)];
                    row.append(k == VoxelGrid.SOLID ? (g.texture[g.index(x, y, z)] < 0 ? 'U' : '#') : k == VoxelGrid.AIR ? ' ' : k == VoxelGrid.SKY ? 's' : (char) ('0' + k));
                }
                System.out.println(row);
            }
        }
    }
}
