package com.melocet.bspimport.convert;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Everything placed on top of the map's brushes: prop triangles in map space, and doors. */
public final class PropSet {

    /** Triangles of one placed prop, 9 floats each, and the material of each triangle. */
    public record Instance(String model, float[] triangles, int[] material) {}

    /** A door's closed box in map space, and whatever names it (texture or model) for picking its look. */
    public record Door(double[] box, String name) {}

    public final List<Instance> instances = new ArrayList<>();
    public final List<Door> doors = new ArrayList<>();
    /** Prop materials: path and average color (0xRRGGBB or -1). */
    public final List<String> materialNames = new ArrayList<>();
    public final List<Integer> materialColors = new ArrayList<>();
    /** MaterialColors.ALPHATEST / TRANSLUCENT bits per material. */
    public final List<Integer> materialFlags = new ArrayList<>();
    private final Map<String, Integer> materialIndex = new HashMap<>();

    public int placed;
    public int missingModels;
    public int skipped;
    public final List<String> missingNames = new ArrayList<>();

    int material(String name, int color, int flags) {
        return materialIndex.computeIfAbsent(name, n -> {
            materialNames.add(n);
            materialColors.add(color);
            materialFlags.add(flags);
            return materialNames.size() - 1;
        });
    }

    public static PropSet empty() {
        return new PropSet();
    }
}
