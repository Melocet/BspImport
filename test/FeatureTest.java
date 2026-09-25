import com.melocet.bspimport.bsp.BspFile;
import com.melocet.bspimport.bsp.MapData;
import com.melocet.bspimport.bsp.SourceBsp;
import com.melocet.bspimport.bsp.TextureImage;
import com.melocet.bspimport.bsp.WadLibrary;
import com.melocet.bspimport.convert.BlockPalette;
import com.melocet.bspimport.convert.PatternPicker;
import com.melocet.bspimport.convert.PropBuilder;
import com.melocet.bspimport.convert.PropSet;
import com.melocet.bspimport.convert.VoxelGrid;
import com.melocet.bspimport.convert.Voxelizer;
import com.melocet.bspimport.game.GameFiles;
import com.melocet.bspimport.game.MaterialColors;
import org.bukkit.Material;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/** Lights, pattern variants and display props for one map: args = map, game folder or wad folder. */
public class FeatureTest {
    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]);
        byte[] head = java.util.Arrays.copyOf(Files.readAllBytes(file), 4);
        MapData m = SourceBsp.isSource(head) ? SourceBsp.read(file) : BspFile.read(file);
        boolean source = m instanceof SourceBsp;
        Logger log = Logger.getLogger("t");
        GameFiles files = source ? GameFiles.open(List.of(Path.of(args[1])), m.packedFiles(), log) : null;
        MaterialColors mats = files == null ? null : new MaterialColors(files);
        WadLibrary wads = source ? null : WadLibrary.load(Path.of(args[1]), log);
        PropSet props = source ? PropBuilder.build(m, files, new PropBuilder.Options(true, 4, List.of(Pattern.compile("props_skybox/")), true, true, e -> true)) : PropSet.empty();
        String[] names = m.textureNames();
        IntFunction<TextureImage> images = source ? t -> mats.image("materials/" + names[t])
                : t -> m.textureImage(t) != null ? m.textureImage(t) : wads.image(names[t]);
        int withImage = 0;
        for (int i = 0; i < names.length; i++) if (images.apply(i) != null) withImage++;
        System.out.println("textures " + names.length + ", with pixels " + withImage);
        TextureImage[] imgs = new TextureImage[names.length];
        int[] avg = new int[names.length];
        for (int i = 0; i < names.length; i++) { imgs[i] = images.apply(i); avg[i] = imgs[i] == null ? -1 : imgs[i].average(); }
        java.util.Set<Integer> patterned = new java.util.HashSet<>();
        Voxelizer.Patterns patterns = new Voxelizer.Patterns() {
            public boolean has(int t) { return imgs[t] != null && avg[t] >= 0 && m.textureSize(t) != null; }
            public int variant(int t, double s, double tt, double fs, double ft) {
                int[] size = m.textureSize(t);
                int c = imgs[t].average(s - fs / 2, tt - ft / 2, s + fs / 2, tt + ft / 2, size[0], size[1]);
                if (c < 0) return 0;
                int dr = (c >> 16 & 255) - (avg[t] >> 16 & 255), dg = (c >> 8 & 255) - (avg[t] >> 8 & 255), db = (c & 255) - (avg[t] & 255);
                if (Math.sqrt(dr * dr + dg * dg + db * db) < 30) return 0;
                patterned.add(t);
                return 1;
            }
        };
        Voxelizer.Lights lights = new Voxelizer.Lights(true, Pattern.compile("^~|^[+][0-9a-j]?~|(^|/)lights?/|fluor|lightpanel|light_?[0-9]", Pattern.CASE_INSENSITIVE), 12);
        Voxelizer.Displays displays = new Voxelizer.Displays(2, 8, 8000, 12);
        long t0 = System.currentTimeMillis();
        VoxelGrid g = new Voxelizer(m, new Voxelizer.Options(32, 4, 0.25, 2, 3,
                Set.of("func_brush", "func_wall", "func_breakable", "func_wall_toggle"), Set.of(), Set.of("func_ladder")))
                .run(x -> { }, new Voxelizer.Extras(props, Pattern.compile("metal"), 2, e -> true, new java.util.BitSet(), patterns, lights, displays));
        int varied = 0, solid = 0;
        for (int i = 0; i < g.kind.length; i++) {
            if (g.kind[i] == VoxelGrid.SOLID) solid++;
            if (g.variant[i] > 0) varied++;
        }
        int textures = patterned.size(), variantCount = 0;
        System.out.println("took " + (System.currentTimeMillis() - t0) + " ms; solid " + solid + ", patterned " + varied
                + " (" + textures + " textures, " + variantCount + " variant blocks); lights " + g.count(VoxelGrid.LIGHT)
                + "; display parts " + g.displays.size() + " from " + props.instances.size() + " props");
        int k = 0;
        for (int t : patterned) if (k++ < 12) System.out.println("  " + names[t]);
        java.util.Map<String,Integer> w = new java.util.TreeMap<>();
        for (String x : g.walkThrough) w.merge(x, 1, Integer::sum);
        System.out.println("walk-through: " + w);
    }
}
