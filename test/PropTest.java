import com.melocet.bspimport.bsp.MapData;
import com.melocet.bspimport.bsp.SourceBsp;
import com.melocet.bspimport.convert.BlockPalette;
import com.melocet.bspimport.convert.PropBuilder;
import com.melocet.bspimport.convert.PropSet;
import com.melocet.bspimport.convert.VoxelGrid;
import com.melocet.bspimport.convert.Voxelizer;
import com.melocet.bspimport.game.GameFiles;
import org.bukkit.Material;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/** Full Source pipeline with props and doors: args = map, game folder. */
public class PropTest {
    public static void main(String[] args) throws Exception {
        MapData m = SourceBsp.read(Path.of(args[0]));
        long t = System.nanoTime();
        GameFiles files = GameFiles.open(List.of(Path.of(args[1])), m.packedFiles(), Logger.getLogger("t"));
        PropSet props = PropBuilder.build(m, files, new PropBuilder.Options(true, 12,
                List.of(Pattern.compile("props_skybox/"), Pattern.compile("/gibs?/"), Pattern.compile("foliage|grass|fern|ivy"), Pattern.compile("decal")),
                true, true));
        System.out.printf("props built in %d ms: %s; placed %d, skipped %d, missing %d, doors %d, materials %d%n",
                (System.nanoTime() - t) / 1_000_000, files.describe(), props.placed, props.skipped, props.missingModels,
                props.doors.size(), props.materialNames.size());
        System.out.println("missing: " + props.missingNames);
        int known = 0;
        for (int c : props.materialColors) if (c >= 0) known++;
        System.out.println("prop materials with a color: " + known + "/" + props.materialColors.size());
        t = System.nanoTime();
        VoxelGrid g = new Voxelizer(m, new Voxelizer.Options(Double.parseDouble(System.getProperty("scale", "32")), 4, 0.25, 2, 3,
                Set.of("func_brush", "func_wall", "func_breakable", "func_breakable_surf"), Set.of(), Set.of("func_simpleladder")))
                .run(x -> { }, new Voxelizer.Extras(props, Pattern.compile("metal|steel|iron|jail|cell|bars|gate|security"), 2));
        System.out.printf("voxelized in %d ms: %dx%dx%d, solid %d, doors %d blocks (%d halves recorded)%n",
                (System.nanoTime() - t) / 1_000_000, g.nx, g.ny, g.nz, g.count(VoxelGrid.SOLID), g.count(VoxelGrid.DOOR), g.doors.size());
        int base = m.textureNames().length;
        BlockPalette pal = new BlockPalette(List.of(), Material.STONE, Logger.getLogger("t"));
        Map<String, Integer> propBlocks = new TreeMap<>();
        int propVoxels = 0;
        for (int i = 0; i < g.kind.length; i++) {
            if (g.kind[i] != VoxelGrid.SOLID || g.texture[i] < base) continue;
            propVoxels++;
            int mi = g.texture[i] - base;
            propBlocks.merge(pal.pick(props.materialNames.get(mi), props.materialColors.get(mi)).name(), 1, Integer::sum);
        }
        System.out.println("prop blocks: " + propVoxels + " " + propBlocks);
        for (int i = 0; i < Math.min(8, props.materialNames.size()); i++) {
            System.out.printf("  %s -> %06x -> %s%n", props.materialNames.get(i).split(Pattern.quote("|"))[0], props.materialColors.get(i),
                    pal.pick(props.materialNames.get(i), props.materialColors.get(i)));
        }
        int full = 0, slabs = 0, stairs = 0, slivers = 0, halves = 0, other = 0;
        for (int i = 0; i < g.kind.length; i++) {
            if (g.kind[i] != VoxelGrid.SOLID) continue;
            int mk = g.shape[i] & 0xFF, n = Integer.bitCount(mk), bot = mk & 0x33, top = mk & 0xCC;
            if (n >= 7) full++;
            else if ((bot == 0x33 && top == 0) || (top == 0xCC && bot == 0)) slabs++;
            else if ((bot == 0x33 && Integer.bitCount(top) == 2) || (top == 0xCC && Integer.bitCount(bot) == 2)) stairs++;
            else if (n <= 2) slivers++;
            else if (mk == 0x55 || mk == 0xAA || mk == 0x0F || mk == 0xF0) halves++;
            else other++;
        }
        int[] seen = new int[2];
        for (int i = 0; i < g.kind.length && (seen[0] < 3 || seen[1] < 3); i++) {
            if (g.kind[i] != VoxelGrid.SOLID || g.texture[i] >= base) continue;
            int mk = g.shape[i] & 0xFF, bot = mk & 0x33, top = mk & 0xCC;
            int cat = bot == 0x33 && top == 0 ? 0 : bot == 0x33 && Integer.bitCount(top) == 2 ? 1 : -1;
            if (cat < 0 || seen[cat] >= 3) continue;
            seen[cat]++;
            System.out.println((cat == 0 ? "bottom slab" : "stairs") + " at grid " + (i % g.nx) + " " + (i / (g.nx * g.nz)) + " " + ((i / g.nx) % g.nz) + " tex " + m.textureNames()[g.texture[i] < 0 ? 0 : g.texture[i]]);
        }
        System.out.println("shapes: full " + full + ", slab " + slabs + ", stairs " + stairs + ", sliver " + slivers + ", vertical half " + halves + ", other " + other);
        long metal = g.doors.values().stream().filter(VoxelGrid.Door::metal).count();
        System.out.println("door halves metal: " + metal + " of " + g.doors.size());
        int shown = 0;
        for (var e : new java.util.TreeMap<>(g.doors).entrySet()) {
            if (e.getValue().upper()) continue;
            int i = e.getKey();
            System.out.println("door at grid " + (i % g.nx) + " " + (i / (g.nx * g.nz)) + " " + ((i / g.nx) % g.nz) + " " + e.getValue());
            if (++shown >= 3) break;
        }
    }
}
