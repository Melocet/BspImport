import com.melocet.bspimport.bsp.MapData;
import com.melocet.bspimport.bsp.SourceBsp;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Which brush entities and props are hidden, disabled at start, or only spawned from templates. */
public class EntityAudit {
    public static void main(String[] args) throws Exception {
        MapData m = SourceBsp.read(Path.of(args[0]));
        Set<String> templated = new HashSet<>();
        Map<String, Integer> keysSeen = new TreeMap<>();
        for (Map<String, String> e : m.entities()) {
            if ("point_template".equals(e.get("classname"))) {
                for (var kv : e.entrySet()) if (kv.getKey().startsWith("template")) templated.add(kv.getValue().toLowerCase());
            }
        }
        System.out.println("names spawned by point_template: " + templated.size());
        Map<String, int[]> byClass = new TreeMap<>(); // total, startDisabled, invisible, templated, named
        for (Map<String, String> e : m.entities()) {
            String cls = e.getOrDefault("classname", "");
            if (!cls.startsWith("func_") && !cls.startsWith("prop_")) continue;
            int[] c = byClass.computeIfAbsent(cls, k -> new int[5]);
            c[0]++;
            if ("1".equals(e.get("startdisabled"))) c[1]++;
            String rm = e.getOrDefault("rendermode", "0");
            if (rm.equals("10") || ("0".equals(e.get("renderamt")) && !rm.equals("0"))) c[2]++;
            String name = e.getOrDefault("targetname", "").toLowerCase();
            if (!name.isEmpty() && templated.contains(name)) c[3]++;
            if (!name.isEmpty()) c[4]++;
            if (cls.equals("func_brush") || cls.equals("func_wall_toggle") || cls.equals("func_breakable")) {
                for (String k : e.keySet()) keysSeen.merge(cls + "." + k, 1, Integer::sum);
            }
        }
        System.out.println("class: total, StartDisabled, invisible, from template, named");
        byClass.forEach((k, v) -> System.out.printf("  %-28s %4d %4d %4d %4d %4d%n", k, v[0], v[1], v[2], v[3], v[4]));
        System.out.println("keys on func_brush/func_wall_toggle/func_breakable: " + keysSeen);
        int shown = 0;
        for (Map<String, String> e : m.entities()) {
            String cls = e.getOrDefault("classname", "");
            if (!cls.equals("func_brush") || shown++ > 12) continue;
            String model = e.getOrDefault("model", "");
            String tex = "";
            if (model.startsWith("*")) {
                int idx = Integer.parseInt(model.substring(1));
                for (int f = m.firstFace(idx); f < m.firstFace(idx) + m.numFaces(idx); f++) {
                    int t = m.faceTexture(f);
                    if (t >= 0) { tex = m.textureNames()[t]; break; }
                }
            }
            System.out.println("  func_brush " + e.getOrDefault("targetname", "-") + " solidity=" + e.get("solidity")
                    + " startdisabled=" + e.get("startdisabled") + " rendermode=" + e.get("rendermode") + " spawnflags=" + e.get("spawnflags") + " tex=" + tex);
        }
    }
}
