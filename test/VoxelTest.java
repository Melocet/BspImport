import com.melocet.bspimport.bsp.BspFile;
import com.melocet.bspimport.convert.BlockPalette;
import com.melocet.bspimport.convert.VoxelGrid;
import com.melocet.bspimport.convert.Voxelizer;
import org.bukkit.Material;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/** Reads a .bsp, voxelizes it, and prints what came out plus one layer as a picture. */
public class VoxelTest {
    public static void main(String[] args) throws Exception {
        BspFile bsp = BspFile.read(Path.of(args[0]));
        System.out.println("entities " + bsp.entities.size() + ", textures " + bsp.textureNames.length);
        for (int i = 0; i < bsp.textureNames.length; i++) {
            System.out.printf("  %s color=%06x%n", bsp.textureNames[i], bsp.textureColors[i]);
        }
        System.out.println("contents: room " + bsp.contents(0, 50, 50, 50) + ", pillar " + bsp.contents(0, 128, 128, 50)
                + ", outside " + bsp.contents(0, -50, 50, 50) + ", sky " + bsp.contents(0, 50, 50, 140)
                + ", func_wall " + bsp.contents(1, 220, 40, 30));
        Voxelizer v = new Voxelizer(bsp, new Voxelizer.Options(32, 3, 0.25, 2, 3,
                Set.of("func_wall"), Set.of("func_water"), Set.of("func_ladder")));
        long t = System.nanoTime();
        VoxelGrid g = v.run(p -> { });
        System.out.printf("grid %dx%dx%d in %d ms%n", g.nx, g.ny, g.nz, (System.nanoTime() - t) / 1_000_000);
        System.out.println("solid " + g.count(VoxelGrid.SOLID) + ", sky " + g.count(VoxelGrid.SKY)
                + ", air " + g.count(VoxelGrid.AIR) + ", water " + g.count(VoxelGrid.WATER));
        System.out.println("ct " + g.ctSpawns + "\nt " + g.tSpawns);
        BlockPalette pal = new BlockPalette(List.of("^sky$: AIR", "^!: WATER"), Material.STONE, Logger.getLogger("t"));
        for (int i = 0; i < bsp.textureNames.length; i++) {
            System.out.println("  " + bsp.textureNames[i] + " -> " + pal.pick(bsp.textureNames[i], bsp.textureColors[i]));
        }
        for (int y : new int[]{0, 1, 5}) {
            System.out.println("layer y=" + y + " (# solid, s sky, . air, letter = texture)");
            for (int z = 0; z < g.nz; z++) {
                StringBuilder row = new StringBuilder("  ");
                for (int x = 0; x < g.nx; x++) {
                    int i = g.index(x, y, z);
                    byte k = g.kind[i];
                    row.append(k == VoxelGrid.SOLID ? (g.texture[i] >= 0 ? (char) ('a' + g.texture[i]) : '#')
                            : k == VoxelGrid.SKY ? 's' : '.');
                }
                System.out.println(row);
            }
        }
    }
}
