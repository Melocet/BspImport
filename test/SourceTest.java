import com.melocet.bspimport.bsp.MapData;
import com.melocet.bspimport.bsp.SourceBsp;
import com.melocet.bspimport.convert.BlockPalette;
import com.melocet.bspimport.convert.VoxelGrid;
import com.melocet.bspimport.convert.Voxelizer;
import org.bukkit.Material;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/** Reads a Source .bsp, voxelizes it and prints the result, with a few layers drawn as text. */
public class SourceTest {
    public static void main(String[] args) throws Exception {
        MapData m = SourceBsp.read(Path.of(args[0]));
        System.out.println(m.engine() + ", entities " + m.entities().size() + ", terrain triangles " + m.extraSurfaces().size());
        System.out.println("bounds " + Arrays.toString(m.bounds()));
        for (int i = 0; i < m.textureNames().length; i++) {
            System.out.printf("  %s color=%06x%n", m.textureNames()[i], m.textureColors()[i]);
        }
        System.out.println("kinds: room " + m.kindAt(0, 50, 50, 50) + ", pillar " + m.kindAt(0, 128, 128, 30)
                + ", above pillar " + m.kindAt(0, 128, 128, 100) + ", outside " + m.kindAt(0, -50, 50, 50)
                + ", skybox room " + m.kindAt(0, 2064, 64, 64));
        Voxelizer v = new Voxelizer(m, new Voxelizer.Options(32, 3, 0.25, 2, 3, Set.of("func_brush"), Set.of(), Set.of()));
        long t = System.nanoTime();
        VoxelGrid g = v.run(p -> { });
        System.out.printf("grid %dx%dx%d in %d ms%n", g.nx, g.ny, g.nz, (System.nanoTime() - t) / 1_000_000);
        System.out.println("solid " + g.count(VoxelGrid.SOLID) + ", air " + g.count(VoxelGrid.AIR));
        System.out.println("ct " + g.ctSpawns + "\nt " + g.tSpawns);
        BlockPalette pal = new BlockPalette(List.of("^tools/toolsskybox: BARRIER"), Material.STONE, Logger.getLogger("t"));
        for (int i = 0; i < m.textureNames().length; i++) {
            System.out.println("  " + m.textureNames()[i] + " -> " + pal.pick(m.textureNames()[i], m.textureColors()[i]));
        }
        for (int y = 0; y < g.ny; y++) {
            System.out.println("layer y=" + y);
            for (int z = 0; z < g.nz; z++) {
                StringBuilder row = new StringBuilder("  ");
                for (int x = 0; x < g.nx; x++) {
                    int i = g.index(x, y, z);
                    byte k = g.kind[i];
                    row.append(k == VoxelGrid.SOLID ? (g.texture[i] >= 0 ? (char) ('a' + g.texture[i]) : '#')
                            : k == VoxelGrid.AIR ? '.' : (char) ('0' + k));
                }
                System.out.println(row);
            }
        }
    }
}
