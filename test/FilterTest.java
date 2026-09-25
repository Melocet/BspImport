import com.melocet.bspimport.bsp.EntityLogic;
import com.melocet.bspimport.bsp.MapData;
import com.melocet.bspimport.bsp.SourceBsp;
import com.melocet.bspimport.convert.PropBuilder;
import com.melocet.bspimport.convert.PropSet;
import com.melocet.bspimport.convert.VoxelGrid;
import com.melocet.bspimport.convert.Voxelizer;
import com.melocet.bspimport.game.GameFiles;
import com.melocet.bspimport.game.MaterialColors;

import java.nio.file.Path;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/** What the keep filter drops, which textures count as see-through, and where doors land: args = map, game folder. */
public class FilterTest {
    public static void main(String[] args) throws Exception {
        MapData m = SourceBsp.read(Path.of(args[0]));
        GameFiles files = GameFiles.open(List.of(Path.of(args[1])), m.packedFiles(), Logger.getLogger("t"));
        MaterialColors mats = new MaterialColors(files);
        EntityLogic logic = new EntityLogic(m.entities());
        Pattern names = Pattern.compile("barricade|blocker", Pattern.CASE_INSENSITIVE);
        Map<String, Integer> dropped = new TreeMap<>();
        Predicate<Map<String, String>> keep = e -> {
            String why = EntityLogic.startsHidden(e) ? "hidden" : logic.isSwitched(e) ? "switched"
                    : names.matcher(e.getOrDefault("targetname", "")).find() ? "name" : null;
            if (why != null) dropped.merge(e.getOrDefault("classname", "?") + ":" + why, 1, Integer::sum);
            return why == null;
        };
        System.out.println("names the logic switches: " + logic.switchedNames());
        BitSet see = new BitSet();
        Pattern byName = Pattern.compile("^[{]|grate|fence|chainlink|chain_link|glass|window", Pattern.CASE_INSENSITIVE);
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < m.textureNames().length; i++) {
            String n = m.textureNames()[i];
            if (n.startsWith("tools/")) continue;
            int fl = mats.seeThrough("materials/" + n);
            if (fl != 0 || byName.matcher(n).find()) {
                see.set(i);
                if (list.length() < 600) list.append(n).append(fl == 2 ? "(glass) " : fl == 1 ? "(cutout) " : "(name) ");
            }
        }
        System.out.println("see-through map textures: " + see.cardinality() + ": " + list);
        PropSet props = PropBuilder.build(m, files, new PropBuilder.Options(true, 12, List.of(Pattern.compile("props_skybox/")), true, true, keep));
        VoxelGrid g = new Voxelizer(m, new Voxelizer.Options(32, 4, 0.25, 2, 3,
                Set.of("func_brush", "func_wall", "func_breakable", "func_breakable_surf", "func_wall_toggle"), Set.of(), Set.of("func_simpleladder")))
                .run(x -> { }, new Voxelizer.Extras(props, Pattern.compile("metal"), 2, keep, see));
        System.out.println("dropped: " + dropped);
        int seeBlocks = 0;
        for (int i = 0; i < g.kind.length; i++) if (g.kind[i] == VoxelGrid.SOLID && g.texture[i] >= 0 && g.texture[i] < m.textureNames().length && see.get(g.texture[i])) seeBlocks++;
        System.out.println("blocks with a see-through texture: " + seeBlocks + ", props placed " + props.placed + ", doors " + props.doors.size());
        int shown = 0;
        for (Map<String, String> e : m.entities()) {
            if (!"prop_door_rotating".equals(e.get("classname")) || shown++ >= 4) continue;
            System.out.println("door " + e.get("model") + " origin " + e.get("origin") + " angles " + e.get("angles") + " spawnpos " + e.get("spawnpos"));
        }
        for (int i = 0; i < Math.min(4, props.doors.size()); i++) {
            double[] b = props.doors.get(i).box();
            System.out.printf("  box x %.0f..%.0f y %.0f..%.0f z %.0f..%.0f  %s%n", b[0], b[3], b[1], b[4], b[2], b[5], props.doors.get(i).name());
        }
    }
}
