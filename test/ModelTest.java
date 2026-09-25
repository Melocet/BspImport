import com.melocet.bspimport.game.GameFiles;
import com.melocet.bspimport.game.StudioModel;

import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

/** Opens a game folder and loads a few models: args = folder, then model paths. */
public class ModelTest {
    public static void main(String[] args) {
        long t = System.nanoTime();
        GameFiles files = GameFiles.open(List.of(Path.of(args[0])), null, Logger.getLogger("t"));
        System.out.printf("opened in %d ms: %s%n", (System.nanoTime() - t) / 1_000_000, files.describe());
        for (int i = 1; i < args.length; i++) {
            t = System.nanoTime();
            StudioModel m = StudioModel.load(files, args[i]);
            if (m == null) {
                System.out.println(args[i] + ": NOT LOADED");
                continue;
            }
            System.out.printf("%s: %d triangles in %d ms, bounds %.0f..%.0f %.0f..%.0f %.0f..%.0f, materials %s%n", args[i],
                    m.triangles.length / 9, (System.nanoTime() - t) / 1_000_000,
                    m.bounds[0], m.bounds[3], m.bounds[1], m.bounds[4], m.bounds[2], m.bounds[5], m.materials);
        }
    }
}
