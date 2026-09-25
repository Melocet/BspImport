import com.melocet.bspimport.bsp.MapData;
import com.melocet.bspimport.bsp.SourceBsp;

import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/** Where door-looking models sit that aren't door entities: args = map. */
public class DoorScan {
    public static void main(String[] args) throws Exception {
        MapData m = SourceBsp.read(Path.of(args[0]));
        Map<String, Integer> byClass = new TreeMap<>();
        for (Map<String, String> e : m.entities()) {
            String model = e.getOrDefault("model", "").toLowerCase();
            String cls = e.getOrDefault("classname", "");
            if (model.matches(args.length > 1 ? args[1] : ".*door.*") || cls.contains("door")) byClass.merge(cls + " " + model, 1, Integer::sum);
        }
        for (MapData.Prop p : m.staticProps()) if (p.model().matches(args.length > 1 ? args[1] : ".*door.*")) byClass.merge("prop_static " + p.model(), 1, Integer::sum);
        byClass.forEach((k, v) -> System.out.println(v + "  " + k));
    }
}
