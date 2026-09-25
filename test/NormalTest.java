import com.melocet.bspimport.bsp.BspFile;
import com.melocet.bspimport.bsp.MapData;
import com.melocet.bspimport.bsp.SourceBsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** For every face: is the side its normal points to open, and the other side solid? */
public class NormalTest {
    public static void main(String[] args) throws Exception {
        Path p = Path.of(args[0]);
        byte[] head = Arrays.copyOf(Files.readAllBytes(p), 4);
        MapData m = SourceBsp.isSource(head) ? SourceBsp.read(p) : BspFile.read(p);
        int agree = 0, flipped = 0, unclear = 0;
        int[][] bySide = new int[2][3];
        for (int f = m.firstFace(0); f < m.firstFace(0) + m.numFaces(0); f++) {
            double[] v = m.faceVertices(f);
            int n = v.length / 3;
            if (n < 3) continue;
            double cx = 0, cy = 0, cz = 0;
            for (int i = 0; i < n; i++) { cx += v[i * 3]; cy += v[i * 3 + 1]; cz += v[i * 3 + 2]; }
            cx /= n; cy /= n; cz /= n;
            double[] nn = m.faceNormal(f);
            byte front = m.kindAt(0, cx + nn[0] * 2, cy + nn[1] * 2, cz + nn[2] * 2);
            byte back = m.kindAt(0, cx - nn[0] * 2, cy - nn[1] * 2, cz - nn[2] * 2);
            if (front != 1 && back == 1) agree++;
            else if (front == 1 && back != 1) flipped++;
            else unclear++;
            if (m instanceof SourceBsp s) {
                int side = s.faceSideRaw(f) != 0 ? 1 : 0;
                if (front != 1 && back == 1) bySide[side][0]++;
                else if (front == 1 && back != 1) bySide[side][1]++;
                else bySide[side][2]++;
            }
        }
        System.out.println("normal points to open space: " + agree + ", points into solid: " + flipped + ", unclear: " + unclear);
        System.out.println("side 0 [open, solid, unclear] " + Arrays.toString(bySide[0]) + "; side 1 " + Arrays.toString(bySide[1]));
    }
}
