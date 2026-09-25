package com.melocet.bspimport.game;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Average color of a Source material: its .vmt names a $basetexture, and every .vtf header carries
 * a precomputed average (reflectivity), so no pixels need decoding.
 */
public final class MaterialColors {

    private static final Pattern BASE_TEXTURE = Pattern.compile("\"?\\$basetexture\"?\\s+\"?([^\"\\r\\n]+?)\"?\\s*(?:\\r|\\n|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern INCLUDE = Pattern.compile("\"?include\"?\\s+\"?([^\"\\r\\n]+?)\"?\\s*(?:\\r|\\n|$)", Pattern.CASE_INSENSITIVE);

    /** Bits from {@link #seeThrough}: cut-out (grates, fences, foliage) and see-through (glass). */
    public static final int ALPHATEST = 1, TRANSLUCENT = 2;

    private static final Pattern ALPHATEST_KEY = Pattern.compile("\"?\\$alphatest\"?\\s+\"?1", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRANSLUCENT_KEY = Pattern.compile("\"?\\$translucent\"?\\s+\"?1", Pattern.CASE_INSENSITIVE);

    private final GameFiles files;
    private final Map<String, Integer> cache = new HashMap<>();
    private final Map<String, Integer> flagCache = new HashMap<>();

    /** ALPHATEST / TRANSLUCENT bits of the first material that exists, 0 when opaque or unknown. */
    public int seeThrough(String candidates) {
        return flagCache.computeIfAbsent(candidates, c -> {
            for (String path : c.split("\\|")) {
                if (path.isBlank()) continue;
                String vmt = text(path.endsWith(".vmt") ? path : path + ".vmt");
                if (vmt == null) continue;
                String include = find(INCLUDE, vmt);
                if (include != null) {
                    String inner = text(include.toLowerCase(Locale.ROOT).replace('\\', '/'));
                    if (inner != null) vmt = vmt + "\n" + inner;
                }
                int flags = 0;
                if (ALPHATEST_KEY.matcher(vmt).find()) flags |= ALPHATEST;
                if (TRANSLUCENT_KEY.matcher(vmt).find()) flags |= TRANSLUCENT;
                return flags;
            }
            return 0;
        });
    }

    public MaterialColors(GameFiles files) {
        this.files = files;
    }

    /**
     * @param candidates one or more material paths without extension, separated by '|'
     * @return 0xRRGGBB, or -1 when the material or its texture can't be found
     */
    public int color(String candidates) {
        return cache.computeIfAbsent(candidates, this::resolve);
    }

    private int resolve(String candidates) {
        for (String path : candidates.split("\\|")) {
            if (path.isBlank()) continue;
            String vmt = text(path.endsWith(".vmt") ? path : path + ".vmt");
            if (vmt == null) continue;
            String base = find(BASE_TEXTURE, vmt);
            if (base == null) {
                // "patch" materials pull everything from another one
                String include = find(INCLUDE, vmt);
                if (include != null) {
                    String inner = text(include.toLowerCase(Locale.ROOT).replace('\\', '/'));
                    if (inner != null) base = find(BASE_TEXTURE, inner);
                }
            }
            if (base == null) continue;
            String tex = "materials/" + base.toLowerCase(Locale.ROOT).replace('\\', '/');
            if (tex.endsWith(".vtf")) tex = tex.substring(0, tex.length() - 4);
            int c = vtfColor(files.read(tex + ".vtf"));
            if (c >= 0) return c;
        }
        return -1;
    }

    /** A small copy of the material's base texture, for patterns; null when it can't be read. */
    public com.melocet.bspimport.bsp.TextureImage image(String candidates) {
        if (images.containsKey(candidates)) return images.get(candidates);
        String tex = baseTexture(candidates);
        com.melocet.bspimport.bsp.TextureImage img = tex == null ? null : Vtf.decode(files.read(tex + ".vtf"), 64);
        images.put(candidates, img);
        return img;
    }

    private final Map<String, com.melocet.bspimport.bsp.TextureImage> images = new HashMap<>();

    /** "materials/..." path of the first candidate's base texture, without ".vtf". */
    private String baseTexture(String candidates) {
        for (String path : candidates.split("\\|")) {
            if (path.isBlank()) continue;
            String vmt = text(path.endsWith(".vmt") ? path : path + ".vmt");
            if (vmt == null) continue;
            String base = find(BASE_TEXTURE, vmt);
            if (base == null) {
                String include = find(INCLUDE, vmt);
                if (include != null) {
                    String inner = text(include.toLowerCase(Locale.ROOT).replace('\\', '/'));
                    if (inner != null) base = find(BASE_TEXTURE, inner);
                }
            }
            if (base == null) continue;
            String tex = "materials/" + base.toLowerCase(Locale.ROOT).replace('\\', '/');
            return tex.endsWith(".vtf") ? tex.substring(0, tex.length() - 4) : tex;
        }
        return null;
    }

    private String text(String path) {
        byte[] b = files.read(path);
        return b == null ? null : new String(b, StandardCharsets.ISO_8859_1);
    }

    private static String find(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    /** VTF header: "VTF\0", version, header size, size, flags, frames, then reflectivity at 32. */
    static int vtfColor(byte[] vtf) {
        if (vtf == null || vtf.length < 44 || vtf[0] != 'V' || vtf[1] != 'T' || vtf[2] != 'F') return -1;
        ByteBuffer b = ByteBuffer.wrap(vtf).order(ByteOrder.LITTLE_ENDIAN);
        float r = b.getFloat(32), g = b.getFloat(36), bl = b.getFloat(40);
        if (!Float.isFinite(r) || !Float.isFinite(g) || !Float.isFinite(bl)) return -1;
        return channel(r) << 16 | channel(g) << 8 | channel(bl);
    }

    private static int channel(float linear) {
        return (int) Math.round(Math.pow(Math.max(0, Math.min(1, linear)), 1 / 2.2) * 255);
    }
}
