package com.melocet.bspimport.convert;

import com.melocet.bspimport.bsp.MapData;
import com.melocet.bspimport.game.GameFiles;
import com.melocet.bspimport.game.MaterialColors;
import com.melocet.bspimport.game.StudioModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Gathers a map's props (compiled prop_static plus prop_dynamic/prop_physics entities), loads their
 * models from the game files and turns them into map-space triangles; also collects doors.
 */
public final class PropBuilder {

    public record Options(boolean props, double minSize, List<Pattern> skip, boolean nonSolid, boolean doors,
                          java.util.function.Predicate<Map<String, String>> keep) {}

    private static final Set<String> ENTITY_PROPS = Set.of("prop_dynamic", "prop_dynamic_override", "prop_physics",
            "prop_physics_multiplayer", "prop_physics_override", "prop_dynamic_ornament");
    private static final Set<String> BRUSH_DOORS = Set.of("func_door", "func_door_rotating");
    private static final Set<String> MODEL_DOORS = Set.of("prop_door_rotating");
    /**
     * Door models placed as plain props (a door that never opens in the game, jail cell doors). They
     * become real doors too; built from blocks they would wall the doorway up. Frames, signs and
     * handles only have "door" in their name.
     */
    private static final Pattern DOOR_MODEL = Pattern.compile("door(?![^/]*(frame|sign|way|knob|handle|trim|stop|mat|bell))[^/]*[.]mdl$");

    private PropBuilder() {}

    public static PropSet build(MapData map, GameFiles files, Options o) {
        PropSet set = new PropSet();
        Map<String, StudioModel> models = new HashMap<>();
        MaterialColors colors = files == null ? null : new MaterialColors(files);

        List<MapData.Prop> all = new ArrayList<>(o.props() ? map.staticProps() : List.of());
        for (Map<String, String> e : map.entities()) {
            String cls = e.getOrDefault("classname", "").toLowerCase(Locale.ROOT);
            String model = e.getOrDefault("model", "").toLowerCase(Locale.ROOT).replace('\\', '/');
            if (o.props() && ENTITY_PROPS.contains(cls) && model.startsWith("models/") && o.keep().test(e)) {
                boolean solid = !"0".equals(e.getOrDefault("solid", "6"));
                all.add(new MapData.Prop(model, vector(e.get("origin")), vector(e.get("angles")), solid));
            }
            if (!o.doors()) continue;
            if (BRUSH_DOORS.contains(cls) && model.startsWith("*")) {
                int index = parse(model.substring(1));
                if (index <= 0 || index >= map.modelCount()) continue;
                double[] mb = map.modelBounds(index);
                double[] origin = vector(e.get("origin"));
                double[] box = {mb[0] + origin[0], mb[1] + origin[1], mb[2] + origin[2], mb[3] + origin[0], mb[4] + origin[1], mb[5] + origin[2]};
                set.doors.add(new PropSet.Door(box, faceName(map, index) + " " + e.getOrDefault("targetname", "")));
            } else if (MODEL_DOORS.contains(cls) && model.startsWith("models/") && files != null) {
                StudioModel m = models.computeIfAbsent(model, k -> StudioModel.load(files, k));
                if (m == null) continue;
                set.doors.add(new PropSet.Door(transformedBox(m.bounds, vector(e.get("origin")), vector(e.get("angles"))), model));
            }
        }
        if (files == null) return set;

        for (MapData.Prop p : all) {
            if (DOOR_MODEL.matcher(p.model()).find()) {
                StudioModel door = o.doors() ? models.computeIfAbsent(p.model(), k -> StudioModel.load(files, k)) : null;
                if (door != null) set.doors.add(new PropSet.Door(transformedBox(door.bounds, p.origin(), p.angles()), p.model()));
                continue;
            }
            if (!p.solid() && !o.nonSolid()) {
                set.skipped++;
                continue;
            }
            if (o.skip().stream().anyMatch(s -> s.matcher(p.model()).find())) {
                set.skipped++;
                continue;
            }
            StudioModel m = models.computeIfAbsent(p.model(), k -> StudioModel.load(files, k));
            if (m == null) {
                set.missingModels++;
                if (set.missingNames.size() < 20 && !set.missingNames.contains(p.model())) set.missingNames.add(p.model());
                continue;
            }
            float[] b = m.bounds;
            double size = Math.max(b[3] - b[0], Math.max(b[4] - b[1], b[5] - b[2]));
            if (size < o.minSize()) {
                set.skipped++;
                continue;
            }
            double[][] r = rotation(p.angles());
            double[] at = p.origin();
            float[] out = new float[m.triangles.length];
            for (int i = 0; i < m.triangles.length; i += 3) {
                double x = m.triangles[i], y = m.triangles[i + 1], z = m.triangles[i + 2];
                out[i] = (float) (r[0][0] * x + r[0][1] * y + r[0][2] * z + at[0]);
                out[i + 1] = (float) (r[1][0] * x + r[1][1] * y + r[1][2] * z + at[1]);
                out[i + 2] = (float) (r[2][0] * x + r[2][1] * y + r[2][2] * z + at[2]);
            }
            int[] mats = new int[m.triangleMaterial.length];
            int[] local = new int[m.materials.size()];
            for (int k = 0; k < local.length; k++) {
                String name = m.materials.get(k);
                local[k] = set.material(name, colors.color(name), colors.seeThrough(name));
            }
            for (int t = 0; t < mats.length; t++) mats[t] = local[m.triangleMaterial[t]];
            set.instances.add(new PropSet.Instance(p.model(), out, mats));
            set.placed++;
        }
        return set;
    }

    /** Source's AngleMatrix: pitch, yaw, roll in degrees; columns are forward, left, up. */
    static double[][] rotation(double[] angles) {
        double p = Math.toRadians(angles[0]), y = Math.toRadians(angles[1]), r = Math.toRadians(angles[2]);
        double sp = Math.sin(p), cp = Math.cos(p), sy = Math.sin(y), cy = Math.cos(y), sr = Math.sin(r), cr = Math.cos(r);
        return new double[][]{
                {cp * cy, sr * sp * cy - cr * sy, cr * sp * cy + sr * sy},
                {cp * sy, sr * sp * sy + cr * cy, cr * sp * sy - sr * cy},
                {-sp, sr * cp, cr * cp}};
    }

    private static double[] transformedBox(float[] b, double[] origin, double[] angles) {
        double[][] r = rotation(angles);
        double[] box = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (int c = 0; c < 8; c++) {
            double x = (c & 1) == 0 ? b[0] : b[3], y = (c & 2) == 0 ? b[1] : b[4], z = (c & 4) == 0 ? b[2] : b[5];
            for (int k = 0; k < 3; k++) {
                double v = r[k][0] * x + r[k][1] * y + r[k][2] * z + origin[k];
                box[k] = Math.min(box[k], v);
                box[k + 3] = Math.max(box[k + 3], v);
            }
        }
        return box;
    }

    private static String faceName(MapData map, int model) {
        int first = map.firstFace(model);
        for (int f = first; f < first + map.numFaces(model); f++) {
            int t = map.faceTexture(f);
            if (t >= 0) return map.textureNames()[t];
        }
        return "";
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static double[] vector(String s) {
        double[] out = new double[3];
        if (s == null) return out;
        String[] parts = s.trim().split("\\s+");
        for (int i = 0; i < 3 && i < parts.length; i++) {
            try {
                out[i] = Double.parseDouble(parts[i]);
            } catch (NumberFormatException ignored) {
                out[i] = 0;
            }
        }
        return out;
    }
}
