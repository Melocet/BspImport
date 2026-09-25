package com.melocet.bspimport.convert;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Texture to blocks. Config rules win; otherwise the texture's name picks a family (wood, metal,
 * tile, fabric...) and the family member closest in color is used, so a wooden pallet stays wood
 * even when its average color is gray. Each pick also comes with slab, stairs and thin variants.
 */
public final class BlockPalette {

    /** How a see-through material is built: grates and fences as bars, glass as glass. */
    public enum SeeThrough { NONE, GRATE, GLASS }

    /**
     * The blocks for one texture. slab/stairs/thin may be null when the block has no such variant;
     * thin is what a sliver of the texture becomes (pane, bars, fence, wall).
     */
    public record Style(Material full, Material slab, Material stairs, Material thin) {}

    private record Rule(Pattern pattern, Material material) {}

    private record Swatch(Material material, int r, int g, int b) {}

    private enum Family { GLASS, GRATE, WOOD, METAL, BRICK, TILE, CONCRETE, FABRIC, GROUND, GRASS, SAND, ROCK }

    private static final List<Map.Entry<Pattern, Family>> FAMILY_NAMES = List.of(
            Map.entry(Pattern.compile("glass|window|windshield"), Family.GLASS),
            Map.entry(Pattern.compile("grate|fence|chainlink|chain_link|bars|cage|mesh|railing|grill|wire"), Family.GRATE),
            Map.entry(Pattern.compile("wood|plank|crate|pallet|timber|board|bark|log[s_]|furniture|desk|table|chair|shelf|bench|cabinet_wood"), Family.WOOD),
            Map.entry(Pattern.compile("metal|steel|iron|tin_|alumin|chrome|pipe|vent|duct|locker|machin|vehicle|car_|truck"), Family.METAL),
            Map.entry(Pattern.compile("brick"), Family.BRICK),
            Map.entry(Pattern.compile("tile|ceramic|porcelain|marble"), Family.TILE),
            Map.entry(Pattern.compile("concrete|cement|plaster|stucco|drywall|wall|ceiling|floor"), Family.CONCRETE),
            Map.entry(Pattern.compile("fabric|cloth|carpet|curtain|mattress|cushion|couch|sofa|bed_|pillow|rug"), Family.FABRIC),
            Map.entry(Pattern.compile("dirt|mud|ground|soil|gravel"), Family.GROUND),
            Map.entry(Pattern.compile("grass|lawn|moss|hedge|leaf|leaves"), Family.GRASS),
            Map.entry(Pattern.compile("sand"), Family.SAND),
            Map.entry(Pattern.compile("rock|cliff|stone|boulder"), Family.ROCK));

    private static final Map<Family, List<Swatch>> FAMILIES = new EnumMap<>(Family.class);
    private static final List<Swatch> ALL = new ArrayList<>();

    private static void add(Family f, Material m, int r, int g, int b) {
        Swatch s = new Swatch(m, r, g, b);
        FAMILIES.computeIfAbsent(f, k -> new ArrayList<>()).add(s);
        if (f != Family.GLASS && f != Family.FABRIC) ALL.add(s);
    }

    static {
        // Approximate average colors of plain full blocks.
        add(Family.ROCK, Material.STONE, 125, 125, 125);
        add(Family.ROCK, Material.COBBLESTONE, 127, 127, 127);
        add(Family.ROCK, Material.ANDESITE, 136, 136, 137);
        add(Family.ROCK, Material.DIORITE, 188, 188, 188);
        add(Family.ROCK, Material.GRANITE, 149, 103, 85);
        add(Family.ROCK, Material.TUFF, 108, 109, 102);
        add(Family.ROCK, Material.DEEPSLATE, 80, 80, 82);
        add(Family.ROCK, Material.COBBLED_DEEPSLATE, 77, 77, 80);
        add(Family.ROCK, Material.MOSSY_COBBLESTONE, 110, 118, 94);
        add(Family.ROCK, Material.CALCITE, 223, 224, 220);
        add(Family.TILE, Material.POLISHED_DIORITE, 192, 193, 194);
        add(Family.TILE, Material.POLISHED_ANDESITE, 132, 135, 134);
        add(Family.TILE, Material.POLISHED_GRANITE, 154, 106, 89);
        add(Family.TILE, Material.POLISHED_DEEPSLATE, 72, 72, 73);
        add(Family.TILE, Material.DEEPSLATE_TILES, 54, 54, 55);
        add(Family.TILE, Material.SMOOTH_STONE, 158, 158, 158);
        add(Family.TILE, Material.QUARTZ_BLOCK, 235, 229, 222);
        add(Family.TILE, Material.SMOOTH_QUARTZ, 235, 229, 222);
        add(Family.TILE, Material.POLISHED_TUFF, 98, 104, 99);
        add(Family.TILE, Material.POLISHED_BLACKSTONE, 53, 48, 56);
        add(Family.BRICK, Material.BRICKS, 150, 97, 83);
        add(Family.BRICK, Material.STONE_BRICKS, 122, 121, 122);
        add(Family.BRICK, Material.MOSSY_STONE_BRICKS, 115, 121, 105);
        add(Family.BRICK, Material.DEEPSLATE_BRICKS, 70, 70, 71);
        add(Family.BRICK, Material.MUD_BRICKS, 137, 103, 79);
        add(Family.BRICK, Material.NETHER_BRICKS, 44, 21, 26);
        add(Family.BRICK, Material.RED_NETHER_BRICKS, 69, 7, 9);
        add(Family.BRICK, Material.POLISHED_BLACKSTONE_BRICKS, 48, 42, 49);
        add(Family.WOOD, Material.OAK_PLANKS, 162, 130, 78);
        add(Family.WOOD, Material.SPRUCE_PLANKS, 114, 84, 48);
        add(Family.WOOD, Material.BIRCH_PLANKS, 192, 175, 121);
        add(Family.WOOD, Material.JUNGLE_PLANKS, 160, 115, 80);
        add(Family.WOOD, Material.ACACIA_PLANKS, 168, 90, 50);
        add(Family.WOOD, Material.DARK_OAK_PLANKS, 66, 43, 20);
        add(Family.WOOD, Material.MANGROVE_PLANKS, 117, 54, 48);
        add(Family.WOOD, Material.CHERRY_PLANKS, 226, 178, 172);
        add(Family.METAL, Material.IRON_BLOCK, 220, 220, 220);
        add(Family.METAL, Material.LIGHT_GRAY_CONCRETE, 125, 125, 115);
        add(Family.METAL, Material.GRAY_CONCRETE, 54, 57, 61);
        add(Family.METAL, Material.BLACK_CONCRETE, 8, 10, 15);
        add(Family.METAL, Material.WHITE_CONCRETE, 207, 213, 214);
        add(Family.METAL, Material.POLISHED_ANDESITE, 132, 135, 134);
        add(Family.METAL, Material.POLISHED_DEEPSLATE, 72, 72, 73);
        add(Family.METAL, Material.RED_CONCRETE, 142, 32, 32);
        add(Family.METAL, Material.BLUE_CONCRETE, 44, 46, 143);
        add(Family.METAL, Material.YELLOW_CONCRETE, 240, 175, 21);
        add(Family.CONCRETE, Material.WHITE_CONCRETE, 207, 213, 214);
        add(Family.CONCRETE, Material.LIGHT_GRAY_CONCRETE, 125, 125, 115);
        add(Family.CONCRETE, Material.GRAY_CONCRETE, 54, 57, 61);
        add(Family.CONCRETE, Material.BLACK_CONCRETE, 8, 10, 15);
        add(Family.CONCRETE, Material.BROWN_CONCRETE, 96, 59, 31);
        add(Family.CONCRETE, Material.RED_CONCRETE, 142, 32, 32);
        add(Family.CONCRETE, Material.ORANGE_CONCRETE, 224, 97, 0);
        add(Family.CONCRETE, Material.YELLOW_CONCRETE, 240, 175, 21);
        add(Family.CONCRETE, Material.LIME_CONCRETE, 94, 168, 24);
        add(Family.CONCRETE, Material.GREEN_CONCRETE, 73, 91, 36);
        add(Family.CONCRETE, Material.CYAN_CONCRETE, 21, 119, 136);
        add(Family.CONCRETE, Material.LIGHT_BLUE_CONCRETE, 35, 137, 198);
        add(Family.CONCRETE, Material.BLUE_CONCRETE, 44, 46, 143);
        add(Family.CONCRETE, Material.PURPLE_CONCRETE, 100, 31, 156);
        add(Family.CONCRETE, Material.MAGENTA_CONCRETE, 169, 48, 159);
        add(Family.CONCRETE, Material.PINK_CONCRETE, 213, 101, 142);
        add(Family.CONCRETE, Material.TERRACOTTA, 152, 94, 67);
        add(Family.CONCRETE, Material.WHITE_TERRACOTTA, 209, 178, 161);
        add(Family.CONCRETE, Material.LIGHT_GRAY_TERRACOTTA, 135, 106, 97);
        add(Family.CONCRETE, Material.CYAN_TERRACOTTA, 87, 92, 92);
        add(Family.CONCRETE, Material.BROWN_TERRACOTTA, 77, 51, 36);
        add(Family.CONCRETE, Material.CLAY, 160, 166, 179);
        add(Family.CONCRETE, Material.SMOOTH_STONE, 158, 158, 158);
        add(Family.CONCRETE, Material.POLISHED_ANDESITE, 132, 135, 134);
        add(Family.GROUND, Material.DIRT, 134, 96, 67);
        add(Family.GROUND, Material.COARSE_DIRT, 119, 85, 59);
        add(Family.GROUND, Material.PACKED_MUD, 142, 106, 79);
        add(Family.GROUND, Material.MUD, 60, 57, 61);
        add(Family.GROUND, Material.GRAVEL, 131, 127, 126);
        add(Family.GROUND, Material.ROOTED_DIRT, 144, 103, 76);
        add(Family.GRASS, Material.MOSS_BLOCK, 89, 109, 45);
        add(Family.GRASS, Material.GREEN_CONCRETE, 73, 91, 36);
        add(Family.GRASS, Material.GRASS_BLOCK, 110, 140, 60);
        add(Family.SAND, Material.SAND, 219, 207, 163);
        add(Family.SAND, Material.SANDSTONE, 216, 203, 155);
        add(Family.SAND, Material.SMOOTH_SANDSTONE, 223, 214, 170);
        add(Family.SAND, Material.RED_SAND, 190, 102, 33);
        add(Family.SAND, Material.RED_SANDSTONE, 186, 99, 29);
        add(Family.GLASS, Material.GLASS, 220, 230, 235);
        add(Family.GLASS, Material.LIGHT_GRAY_STAINED_GLASS, 153, 153, 153);
        add(Family.GLASS, Material.GRAY_STAINED_GLASS, 76, 76, 76);
        add(Family.GLASS, Material.BLACK_STAINED_GLASS, 25, 25, 25);
        add(Family.GLASS, Material.BROWN_STAINED_GLASS, 102, 76, 51);
        add(Family.GLASS, Material.RED_STAINED_GLASS, 153, 51, 51);
        add(Family.GLASS, Material.ORANGE_STAINED_GLASS, 216, 127, 51);
        add(Family.GLASS, Material.YELLOW_STAINED_GLASS, 229, 229, 51);
        add(Family.GLASS, Material.GREEN_STAINED_GLASS, 102, 127, 51);
        add(Family.GLASS, Material.CYAN_STAINED_GLASS, 76, 127, 153);
        add(Family.GLASS, Material.LIGHT_BLUE_STAINED_GLASS, 102, 153, 216);
        add(Family.GLASS, Material.BLUE_STAINED_GLASS, 51, 76, 178);
        add(Family.FABRIC, Material.WHITE_WOOL, 234, 236, 237);
        add(Family.FABRIC, Material.LIGHT_GRAY_WOOL, 142, 142, 135);
        add(Family.FABRIC, Material.GRAY_WOOL, 63, 68, 72);
        add(Family.FABRIC, Material.BLACK_WOOL, 21, 21, 26);
        add(Family.FABRIC, Material.BROWN_WOOL, 114, 72, 41);
        add(Family.FABRIC, Material.RED_WOOL, 161, 39, 35);
        add(Family.FABRIC, Material.ORANGE_WOOL, 241, 118, 20);
        add(Family.FABRIC, Material.YELLOW_WOOL, 249, 198, 40);
        add(Family.FABRIC, Material.GREEN_WOOL, 85, 110, 28);
        add(Family.FABRIC, Material.CYAN_WOOL, 21, 138, 145);
        add(Family.FABRIC, Material.LIGHT_BLUE_WOOL, 58, 175, 217);
        add(Family.FABRIC, Material.BLUE_WOOL, 53, 57, 157);
        add(Family.FABRIC, Material.PURPLE_WOOL, 122, 42, 173);
        add(Family.FABRIC, Material.PINK_WOOL, 238, 141, 172);
    }

    private final List<Rule> rules = new ArrayList<>();
    private final Material fallback;

    public BlockPalette(List<String> ruleLines, Material fallback, Logger log) {
        this.fallback = fallback;
        for (String line : ruleLines) {
            int colon = line.lastIndexOf(':');
            if (colon <= 0) {
                log.warning("Bad texture rule (want \"regex: BLOCK\"): " + line);
                continue;
            }
            String regex = line.substring(0, colon).trim();
            Material m = Material.matchMaterial(line.substring(colon + 1).trim());
            if (m == null) {
                log.warning("Unknown block in texture rule: " + line);
                continue;
            }
            try {
                rules.add(new Rule(Pattern.compile(regex), m));
            } catch (PatternSyntaxException e) {
                log.warning("Bad regex in texture rule: " + line);
            }
        }
    }

    /** The full block for a texture; AIR means the surface is skipped. color is 0xRRGGBB or -1. */
    public Material pick(String texture, int color) {
        return pick(texture, color, SeeThrough.NONE);
    }

    public Material pick(String texture, int color, SeeThrough seeThrough) {
        String name = texture.toLowerCase(Locale.ROOT);
        for (Rule r : rules) {
            if (r.pattern.matcher(name).find()) return r.material;
        }
        Family family = seeThrough == SeeThrough.GLASS ? Family.GLASS
                : seeThrough == SeeThrough.GRATE ? Family.GRATE : familyOf(name);
        if (family == Family.GRATE) return Material.IRON_BARS;
        List<Swatch> pool = family == null ? ALL : FAMILIES.get(family);
        if (color < 0) return family == null ? fallback : pool.get(0).material;
        return nearest(pool, color).material;
    }

    /** Full block plus the slab, stairs and thin blocks that go with it. */
    public Style style(String texture, int color, SeeThrough seeThrough) {
        return styleOf(pick(texture, color, seeThrough));
    }

    /**
     * The block for one spot of a texture's pattern, from the texture's own family; null when a
     * texture rule fixes its block or it's glass or a grate.
     */
    public Material patternBlock(String texture, int color) {
        String name = texture.toLowerCase(Locale.ROOT);
        for (Rule r : rules) {
            if (r.pattern.matcher(name).find()) return null;
        }
        Family family = familyOf(name);
        if (family == Family.GLASS || family == Family.GRATE) return null;
        return nearest(family == null ? ALL : FAMILIES.get(family), color).material;
    }

    /** A full block with the slab, stairs and thin blocks that go with it. */
    public Style styleOf(Material full) {
        if (full.isAir()) return new Style(full, null, null, null);
        if (full == Material.IRON_BARS) return new Style(full, null, null, Material.IRON_BARS);
        Material pane = paneFor(full);
        if (pane != null) return new Style(full, null, null, pane);
        Material slab = variant(full, "_SLAB");
        Material stairs = variant(full, "_STAIRS");
        Swatch own = swatchOf(full);
        // No slab of this block (concrete, terracotta...): borrow the closest block that has one.
        if (own != null && (slab == null || stairs == null)) {
            int c = own.r << 16 | own.g << 8 | own.b;
            Swatch alt = nearest(ALL.stream().filter(s -> variant(s.material, "_SLAB") != null && variant(s.material, "_STAIRS") != null).toList(), c);
            if (slab == null) slab = variant(alt.material, "_SLAB");
            if (stairs == null) stairs = variant(alt.material, "_STAIRS");
        }
        return new Style(full, slab, stairs, thinFor(full));
    }

    private static Family familyOf(String name) {
        for (Map.Entry<Pattern, Family> e : FAMILY_NAMES) {
            if (e.getKey().matcher(name).find()) return e.getValue();
        }
        return null;
    }

    private static Swatch swatchOf(Material m) {
        for (Swatch s : ALL) if (s.material == m) return s;
        return null;
    }

    private static final Map<Material, Material> SLABS = new EnumMap<>(Material.class);
    private static final Map<Material, Material> STAIRS = new EnumMap<>(Material.class);

    /** The block's slab or stairs, by Minecraft's naming ("OAK_PLANKS" → "OAK_SLAB", "BRICKS" → "BRICK_SLAB"). */
    static Material variant(Material full, String suffix) {
        Map<Material, Material> cache = suffix.equals("_SLAB") ? SLABS : STAIRS;
        if (cache.containsKey(full)) return cache.get(full);
        String n = full.name();
        String stem = n.endsWith("_PLANKS") ? n.substring(0, n.length() - 7)
                : n.endsWith("BRICKS") ? n.substring(0, n.length() - 1)
                : n.endsWith("TILES") ? n.substring(0, n.length() - 1)
                : n.equals("QUARTZ_BLOCK") ? "QUARTZ"
                : n.equals("WAXED_COPPER_BLOCK") ? "WAXED_CUT_COPPER"
                : n.startsWith("WAXED_") && n.contains("COPPER") ? n.replace("COPPER", "CUT_COPPER")
                : n;
        Material m = Material.matchMaterial(stem + suffix);
        if (m != null && !m.isBlock()) m = null;
        cache.put(full, m);
        return m;
    }

    /** What a sliver of this block becomes: fences for wood, walls for stone, null when neither exists. */
    private static Material thinFor(Material full) {
        String n = full.name();
        if (n.endsWith("_PLANKS")) return Material.matchMaterial(n.substring(0, n.length() - 7) + "_FENCE");
        String stem = n.endsWith("BRICKS") ? n.substring(0, n.length() - 1)
                : n.endsWith("TILES") ? n.substring(0, n.length() - 1) : n;
        Material wall = Material.matchMaterial(stem + "_WALL");
        if (wall != null) return wall;
        if (n.contains("CONCRETE") || n.contains("TERRACOTTA") || n.equals("SMOOTH_STONE") || n.equals("CLAY")) return Material.ANDESITE_WALL;
        if (n.contains("COPPER") || n.equals("IRON_BLOCK")) return Material.IRON_BARS;
        return null;
    }

    private static Material paneFor(Material full) {
        if (full == Material.GLASS) return Material.GLASS_PANE;
        if (full.name().endsWith("_STAINED_GLASS")) return Material.matchMaterial(full.name() + "_PANE");
        return null;
    }

    private static Swatch nearest(List<Swatch> pool, int color) {
        int r = color >> 16 & 0xFF;
        int g = color >> 8 & 0xFF;
        int b = color & 0xFF;
        Swatch best = pool.get(0);
        double bestDist = Double.MAX_VALUE;
        for (Swatch s : pool) {
            // "Redmean" weighting: a cheap, decent stand-in for how different two colors look.
            double rm = (r + s.r) / 2.0;
            double dr = r - s.r, dg = g - s.g, db = b - s.b;
            double d = (2 + rm / 256) * dr * dr + 4 * dg * dg + (2 + (255 - rm) / 256) * db * db;
            if (d < bestDist) {
                bestDist = d;
                best = s;
            }
        }
        return best;
    }

    static Material nearest(int color) {
        return nearest(ALL, color).material;
    }
}
