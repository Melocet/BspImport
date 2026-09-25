import com.melocet.bspimport.bsp.MapData;
import com.melocet.bspimport.bsp.SourceBsp;

import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/** Textures of faces reaching above a height: args = map, z. */
public class HighFaces {
    public static void main(String[] args) throws Exception {
        MapData m = SourceBsp.read(Path.of(args[0]));
        double limit = Double.parseDouble(args[1]);
        double[] b = m.bounds();
        Map<String, Integer> out = new TreeMap<>();
        for (int f = m.firstFace(0); f < m.firstFace(0) + m.numFaces(0); f++) {
            int t = m.faceTexture(f);
            if (t < 0 || m.isSky(t)) continue;
            double[] v = m.faceVertices(f);
            double top = -1e9, x = 0, y = 0;
            for (int i = 0; i < v.length; i += 3) if (v[i + 2] > top) { top = v[i + 2]; x = v[i]; y = v[i + 1]; }
            if (top > limit && x >= b[0] && x <= b[3] && y >= b[1] && y <= b[4]) out.merge(m.textureNames()[t] + " top " + (int) top, 1, Integer::sum);
        }
        out.forEach((k, v) -> System.out.println(v + "  " + k));
    }
}
