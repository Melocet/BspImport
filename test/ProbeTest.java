import com.melocet.bspimport.bsp.MapData;
import com.melocet.bspimport.bsp.SourceBsp;

import java.nio.file.Path;

/** Asks what the map has at a handful of points: args = map, then x y z triples. */
public class ProbeTest {
    public static void main(String[] args) throws Exception {
        MapData m = SourceBsp.read(Path.of(args[0]));
        for (int i = 1; i + 2 < args.length; i += 3) {
            double x = Double.parseDouble(args[i]), y = Double.parseDouble(args[i + 1]), z = Double.parseDouble(args[i + 2]);
            System.out.printf("(%s %s %s) kind %d region %d%n", args[i], args[i + 1], args[i + 2], m.kindAt(0, x, y, z), m.uniformRegion(x, y, z));
        }
    }
}
