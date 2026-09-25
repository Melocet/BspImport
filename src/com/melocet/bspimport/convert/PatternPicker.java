package com.melocet.bspimport.convert;

import com.melocet.bspimport.bsp.MapData;
import com.melocet.bspimport.bsp.TextureImage;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * Picks blocks for the spots of a texture that stand out from its average (grout between tiles,
 * a stripe along a wall, a sign's letters), so a wall shows its pattern instead of one flat block.
 * Plain spots keep the texture's own block, so a plain wall doesn't turn into noise.
 */
public final class PatternPicker implements Voxelizer.Patterns {

    /** More variants than this for one texture is noise, not a pattern. */
    private static final int MAX_VARIANTS = 24;

    private final MapData map;
    private final IntFunction<TextureImage> images;
    private final BlockPalette palette;
    private final BlockPalette.Style[] base;
    private final boolean shapes;
    private final int contrast;
    private final TextureImage[] loaded;
    private final boolean[] tried;
    private final int[] averages;
    private final Boolean[] usable;
    private final List<List<BlockPalette.Style>> variants = new ArrayList<>();

    /**
     * @param base     the block style of each map texture (props are never patterned)
     * @param contrast how far (0..441, RGB distance) a spot's color has to be from the texture's average
     */
    public PatternPicker(MapData map, IntFunction<TextureImage> images, BlockPalette palette, BlockPalette.Style[] base,
                         boolean shapes, int contrast) {
        int n = map.textureNames().length;
        this.map = map;
        this.images = images;
        this.palette = palette;
        this.base = base;
        this.shapes = shapes;
        this.contrast = contrast;
        this.loaded = new TextureImage[n];
        this.tried = new boolean[n];
        this.averages = new int[n];
        this.usable = new Boolean[n];
        for (int i = 0; i < n; i++) variants.add(new ArrayList<>());
    }

    private TextureImage image(int tex) {
        if (!tried[tex]) {
            tried[tex] = true;
            loaded[tex] = images.apply(tex);
            averages[tex] = loaded[tex] == null ? -1 : loaded[tex].average();
        }
        return loaded[tex];
    }

    @Override
    public boolean has(int tex) {
        if (tex < 0 || tex >= usable.length) return false;
        if (usable[tex] == null) {
            BlockPalette.Style st = base[tex];
            boolean plain = st != null && st.full().isSolid() && st.full().isOccluding() && !isLeaves(st.full())
                    && st.full() != Material.BARRIER && st.full() != Material.IRON_BARS;
            usable[tex] = plain && map.textureSize(tex) != null && image(tex) != null && averages[tex] >= 0
                    && palette.patternBlock(map.textureNames()[tex], averages[tex]) != null;
        }
        return usable[tex];
    }

    @Override
    public int variant(int tex, double s, double t, double footS, double footT) {
        TextureImage img = image(tex);
        int[] size = map.textureSize(tex);
        if (img == null || size == null) return 0;
        int c = img.average(s - footS / 2, t - footT / 2, s + footS / 2, t + footT / 2, size[0], size[1]);
        if (c < 0 || distance(c, averages[tex]) < contrast) return 0;
        Material m = palette.patternBlock(map.textureNames()[tex], c);
        if (m == null || m == base[tex].full()) return 0;
        List<BlockPalette.Style> list = variants.get(tex);
        for (int i = 0; i < list.size(); i++) if (list.get(i).full() == m) return i + 1;
        if (list.size() >= MAX_VARIANTS) return 0;
        BlockPalette.Style st = palette.styleOf(m);
        list.add(new BlockPalette.Style(st.full(), shapes ? st.slab() : null, shapes ? st.stairs() : null, null));
        return list.size();
    }

    /** Per texture, the styles variant 1, 2, ... stand for (index 0 is variant 1); null when none. */
    public BlockPalette.Style[][] variants() {
        BlockPalette.Style[][] out = new BlockPalette.Style[variants.size()][];
        for (int i = 0; i < out.length; i++) {
            if (!variants.get(i).isEmpty()) out[i] = variants.get(i).toArray(new BlockPalette.Style[0]);
        }
        return out;
    }

    private static boolean isLeaves(Material m) {
        return m.name().endsWith("_LEAVES");
    }

    private static double distance(int a, int b) {
        int dr = (a >> 16 & 0xFF) - (b >> 16 & 0xFF), dg = (a >> 8 & 0xFF) - (b >> 8 & 0xFF), db = (a & 0xFF) - (b & 0xFF);
        return Math.sqrt(dr * dr + dg * dg + db * db);
    }
}
