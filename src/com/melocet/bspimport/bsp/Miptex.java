package com.melocet.bspimport.bsp;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** GoldSrc texture (miptex): a 16-byte name, the size, four mip levels and a 256-color palette. */
final class Miptex {

    private Miptex() {}

    static String name(ByteBuffer b, int at) {
        int end = 0;
        while (end < 16 && b.get(at + end) != 0) end++;
        return new String(b.array(), at, end, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
    }

    /** The pixels of mip level 2 (a quarter of the size), or null when the map only names the texture. */
    static TextureImage image(ByteBuffer b, int at, String name) {
        try {
            int width = b.getInt(at + 16);
            int height = b.getInt(at + 20);
            int mip2 = b.getInt(at + 32);
            int mip3 = b.getInt(at + 36);
            if (mip2 == 0 || width <= 0 || height <= 0 || width > 4096 || height > 4096) return null;
            int w = Math.max(1, width / 4), h = Math.max(1, height / 4);
            int palette = at + mip3 + Math.max(1, width / 8) * Math.max(1, height / 8) + 2;
            if (palette + 768 > b.capacity()) return null;
            boolean masked = name.startsWith("{");
            int[] rgb = new int[w * h];
            for (int i = 0; i < rgb.length; i++) {
                int idx = b.get(at + mip2 + i) & 0xFF;
                rgb[i] = masked && idx == 255 ? -1
                        : (b.get(palette + idx * 3) & 0xFF) << 16 | (b.get(palette + idx * 3 + 1) & 0xFF) << 8 | (b.get(palette + idx * 3 + 2) & 0xFF);
            }
            return new TextureImage(w, h, rgb);
        } catch (IndexOutOfBoundsException e) {
            return null;
        }
    }

    /**
     * Average color (0xRRGGBB) of the smallest mip level, or -1 when the pixels aren't here (the
     * map only names a texture that lives in a WAD) or the data doesn't add up.
     */
    static int averageColor(ByteBuffer b, int at, String name) {
        try {
            int width = b.getInt(at + 16);
            int height = b.getInt(at + 20);
            int mip0 = b.getInt(at + 24);
            int mip3 = b.getInt(at + 36);
            if (mip0 == 0 || width <= 0 || height <= 0 || width > 4096 || height > 4096) return -1;
            int w3 = Math.max(1, width / 8);
            int h3 = Math.max(1, height / 8);
            int pixels = at + mip3;
            int palette = pixels + w3 * h3 + 2;
            if (palette + 768 > b.capacity()) return -1;
            boolean masked = name.startsWith("{");
            long r = 0, g = 0, bl = 0, n = 0;
            for (int i = 0; i < w3 * h3; i++) {
                int idx = b.get(pixels + i) & 0xFF;
                if (masked && idx == 255) continue; // the see-through color of fences and grates
                r += b.get(palette + idx * 3) & 0xFF;
                g += b.get(palette + idx * 3 + 1) & 0xFF;
                bl += b.get(palette + idx * 3 + 2) & 0xFF;
                n++;
            }
            if (n == 0) return -1;
            return (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (bl / n);
        } catch (IndexOutOfBoundsException e) {
            return -1;
        }
    }
}
