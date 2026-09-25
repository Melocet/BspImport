package com.melocet.bspimport.game;

import com.melocet.bspimport.bsp.TextureImage;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Decodes one small mip level of a Valve texture (.vtf): the first mip no bigger than `maxSide`, or
 * the thumbnail every VTF carries when the main image is in a format this doesn't read.
 */
public final class Vtf {

    private Vtf() {}

    private static final int RGBA8888 = 0, ABGR8888 = 1, RGB888 = 2, BGR888 = 3, I8 = 5, IA88 = 6,
            ARGB8888 = 11, BGRA8888 = 12, DXT1 = 13, DXT3 = 14, DXT5 = 15, BGRX8888 = 16, DXT1_ONEBITALPHA = 20;
    private static final int ENVMAP = 0x4000;

    public static TextureImage decode(byte[] d, int maxSide) {
        if (d == null || d.length < 64 || d[0] != 'V' || d[1] != 'T' || d[2] != 'F') return null;
        try {
            ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
            int minor = b.getInt(8);
            int header = b.getInt(12);
            int w = b.getShort(16) & 0xFFFF, h = b.getShort(18) & 0xFFFF;
            int flags = b.getInt(20);
            int frames = Math.max(1, b.getShort(24) & 0xFFFF);
            int hiFormat = b.getInt(52);
            int mips = Math.max(1, b.get(56) & 0xFF);
            int loFormat = b.getInt(57);
            int lw = b.get(61) & 0xFF, lh = b.get(62) & 0xFF;
            int depth = minor >= 2 ? Math.max(1, b.getShort(63) & 0xFFFF) : 1;
            if ((flags & ENVMAP) != 0 || w == 0 || h == 0) return null;
            int lo = -1, hi = -1;
            if (minor >= 3) {
                int count = b.getInt(68);
                for (int i = 0; i < count && 80 + i * 8 + 8 <= d.length; i++) {
                    int e = 80 + i * 8;
                    int tag = (b.get(e) & 0xFF) | (b.get(e + 1) & 0xFF) << 8 | (b.get(e + 2) & 0xFF) << 16;
                    if (tag == 0x01) lo = b.getInt(e + 4);
                    if (tag == 0x30) hi = b.getInt(e + 4);
                }
            } else {
                lo = header;
                int loSize = lw > 0 && lh > 0 ? size(loFormat, lw, lh) : 0;
                hi = header + Math.max(0, loSize);
            }
            if (hi > 0 && readable(hiFormat)) {
                // The main image is stored smallest mip first; walk up to the one we want.
                int mip = 0;
                while (mip + 1 < mips && Math.max(w >> mip, h >> mip) > maxSide) mip++;
                long at = hi;
                for (int m = mips - 1; m > mip; m--) {
                    at += (long) size(hiFormat, Math.max(1, w >> m), Math.max(1, h >> m)) * frames * depth;
                }
                int mw = Math.max(1, w >> mip), mh = Math.max(1, h >> mip);
                if (at + size(hiFormat, mw, mh) <= d.length) return pixels(b, (int) at, hiFormat, mw, mh);
            }
            if (lo > 0 && lw > 0 && lh > 0 && readable(loFormat) && lo + size(loFormat, lw, lh) <= d.length) {
                return pixels(b, lo, loFormat, lw, lh);
            }
            return null;
        } catch (IndexOutOfBoundsException e) {
            return null;
        }
    }

    private static boolean readable(int f) {
        return f == RGBA8888 || f == ABGR8888 || f == RGB888 || f == BGR888 || f == I8 || f == IA88 || f == ARGB8888
                || f == BGRA8888 || f == DXT1 || f == DXT3 || f == DXT5 || f == BGRX8888 || f == DXT1_ONEBITALPHA;
    }

    /** Bytes of one image; -1 for formats this doesn't know the size of. */
    private static int size(int f, int w, int h) {
        int blocks = Math.max(1, (w + 3) / 4) * Math.max(1, (h + 3) / 4);
        return switch (f) {
            case DXT1, DXT1_ONEBITALPHA -> blocks * 8;
            case DXT3, DXT5 -> blocks * 16;
            case RGBA8888, ABGR8888, ARGB8888, BGRA8888, BGRX8888, 23, 26 -> w * h * 4;
            case RGB888, BGR888, 9, 10 -> w * h * 3;
            case 4, IA88, 17, 18, 19, 21, 22 -> w * h * 2;
            case I8, 7, 8 -> w * h;
            case 24, 25 -> w * h * 8;
            default -> -1;
        };
    }

    private static TextureImage pixels(ByteBuffer b, int at, int f, int w, int h) {
        int[] out = new int[w * h];
        if (f == DXT1 || f == DXT1_ONEBITALPHA || f == DXT3 || f == DXT5) {
            int bw = Math.max(1, (w + 3) / 4), bh = Math.max(1, (h + 3) / 4);
            int blockSize = f == DXT3 || f == DXT5 ? 16 : 8;
            int[] colors = new int[4];
            boolean[] clear = new boolean[16];
            for (int by = 0; by < bh; by++) {
                for (int bx = 0; bx < bw; bx++) {
                    int p = at + (by * bw + bx) * blockSize;
                    java.util.Arrays.fill(clear, false);
                    if (f == DXT3) {
                        for (int i = 0; i < 16; i++) clear[i] = ((b.get(p + i / 2) >> (i % 2 * 4)) & 0xF) < 8;
                        p += 8;
                    } else if (f == DXT5) {
                        int a0 = b.get(p) & 0xFF, a1 = b.get(p + 1) & 0xFF;
                        long bits = 0;
                        for (int i = 0; i < 6; i++) bits |= (long) (b.get(p + 2 + i) & 0xFF) << (8 * i);
                        for (int i = 0; i < 16; i++) {
                            int code = (int) (bits >> (3 * i) & 7);
                            int a = code == 0 ? a0 : code == 1 ? a1
                                    : a0 > a1 ? ((8 - code) * a0 + (code - 1) * a1) / 7
                                    : code == 6 ? 0 : code == 7 ? 255 : ((6 - code) * a0 + (code - 1) * a1) / 5;
                            clear[i] = a < 128;
                        }
                        p += 8;
                    }
                    int c0 = b.getShort(p) & 0xFFFF, c1 = b.getShort(p + 2) & 0xFFFF;
                    colors[0] = rgb565(c0);
                    colors[1] = rgb565(c1);
                    boolean four = c0 > c1 || f == DXT3 || f == DXT5;
                    colors[2] = four ? mix(colors[0], colors[1], 2, 1, 3) : mix(colors[0], colors[1], 1, 1, 2);
                    colors[3] = four ? mix(colors[0], colors[1], 1, 2, 3) : -1;
                    int idx = b.getInt(p + 4);
                    for (int i = 0; i < 16; i++) {
                        int x = bx * 4 + i % 4, y = by * 4 + i / 4;
                        if (x >= w || y >= h) continue;
                        int c = colors[idx >>> (2 * i) & 3];
                        out[y * w + x] = clear[i] ? -1 : c;
                    }
                }
            }
            return new TextureImage(w, h, out);
        }
        int bpp = size(f, 1, 1);
        for (int i = 0; i < w * h; i++) {
            int p = at + i * bpp;
            int c0 = b.get(p) & 0xFF, c1 = bpp > 1 ? b.get(p + 1) & 0xFF : 0, c2 = bpp > 2 ? b.get(p + 2) & 0xFF : 0, c3 = bpp > 3 ? b.get(p + 3) & 0xFF : 255;
            int r, g, bl, a = 255;
            switch (f) {
                case RGBA8888 -> { r = c0; g = c1; bl = c2; a = c3; }
                case ABGR8888 -> { a = c0; bl = c1; g = c2; r = c3; }
                case RGB888 -> { r = c0; g = c1; bl = c2; }
                case BGR888 -> { bl = c0; g = c1; r = c2; }
                case ARGB8888 -> { a = c0; r = c1; g = c2; bl = c3; }
                case BGRA8888 -> { bl = c0; g = c1; r = c2; a = c3; }
                case BGRX8888 -> { bl = c0; g = c1; r = c2; }
                case IA88 -> { r = g = bl = c0; a = c1; }
                default -> { r = g = bl = c0; } // I8
            }
            out[i] = a < 128 ? -1 : r << 16 | g << 8 | bl;
        }
        return new TextureImage(w, h, out);
    }

    private static int rgb565(int c) {
        int r = (c >> 11 & 0x1F) * 255 / 31, g = (c >> 5 & 0x3F) * 255 / 63, b = (c & 0x1F) * 255 / 31;
        return r << 16 | g << 8 | b;
    }

    private static int mix(int a, int b, int wa, int wb, int div) {
        int r = ((a >> 16 & 0xFF) * wa + (b >> 16 & 0xFF) * wb) / div;
        int g = ((a >> 8 & 0xFF) * wa + (b >> 8 & 0xFF) * wb) / div;
        int bl = ((a & 0xFF) * wa + (b & 0xFF) * wb) / div;
        return r << 16 | g << 8 | bl;
    }
}
