import com.melocet.bspimport.bsp.MapData;
import com.melocet.bspimport.bsp.SourceBsp;
import com.melocet.bspimport.game.GameFiles;
import com.melocet.bspimport.game.MaterialColors;

import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/** Lists the textures the plugin would treat as see-through, with the flags and face count: args = map, game folder. */
public class SeeTest {
    public static void main(String[] args) throws Exception {
        MapData m = SourceBsp.read(Path.of(args[0]));
        GameFiles files = GameFiles.open(List.of(Path.of(args[1])), m.packedFiles(), Logger.getLogger("t"));
        MaterialColors mats = new MaterialColors(files);
        Pattern byName = Pattern.compile("^[{]|grate|fence|chainlink|chain_link|glass|window", Pattern.CASE_INSENSITIVE);
        Pattern kinds = Pattern.compile("glass|window|bar|rail|fence|grate|grill|chain|wire|mesh|cage", Pattern.CASE_INSENSITIVE);
        Pattern notThese = Pattern.compile("puddle|water|decal|overlay|blood|graffiti|leaf|leaves|foliage|grass|plant|tree|mirror", Pattern.CASE_INSENSITIVE);
        String[] names = m.textureNames();
        for (int i = 0; i < names.length; i++) {
            String n = names[i];
            if (n.startsWith("tools/") || notThese.matcher(n).find()) continue;
            int fl = mats.seeThrough("materials/" + n);
            if (fl != 0 && kinds.matcher(n).find() || byName.matcher(n).find()) System.out.println(n + " flags=" + fl);
        }
    }
}
