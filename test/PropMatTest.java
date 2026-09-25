import com.melocet.bspimport.bsp.MapData;
import com.melocet.bspimport.bsp.SourceBsp;
import com.melocet.bspimport.convert.PropBuilder;
import com.melocet.bspimport.convert.PropSet;
import com.melocet.bspimport.game.GameFiles;

import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/** Prop materials with a see-through flag: args = map, game folder. */
public class PropMatTest {
    public static void main(String[] args) throws Exception {
        MapData m = SourceBsp.read(Path.of(args[0]));
        GameFiles files = GameFiles.open(List.of(Path.of(args[1])), m.packedFiles(), Logger.getLogger("t"));
        PropSet s = PropBuilder.build(m, files, new PropBuilder.Options(true, 12, List.of(Pattern.compile("props_skybox/")), true, true, e -> true));
        System.out.println("placed " + s.placed + " missing " + s.missingModels + " mats " + s.materialNames.size() + " inst " + s.instances.size());
        for (int i = 0; i < s.materialNames.size(); i++)
            System.out.println(s.materialNames.get(i) + " flags=" + s.materialFlags.get(i));
    }
}
