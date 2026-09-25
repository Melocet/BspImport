package com.melocet.bspimport.bsp;

/**
 * A small copy of a texture's pixels (usually a lower mip level), 0xRRGGBB per pixel or -1 where it's
 * see-through. Texel coordinates from a face's texture axes are in the full texture's size.
 */
public record TextureImage(int width, int height, int[] rgb) {

    /**
     * Average color over the texel rectangle [s0, s1) × [t0, t1) of a texture fullW × fullH texels
     * big, wrapping around like the texture repeats. -1 when every pixel there is see-through.
     */
    public int average(double s0, double t0, double s1, double t1, int fullW, int fullH) {
        if (fullW <= 0 || fullH <= 0) return -1;
        double kx = (double) width / fullW, ky = (double) height / fullH;
        int x0 = (int) Math.floor(s0 * kx), x1 = Math.max(x0 + 1, (int) Math.ceil(s1 * kx));
        int y0 = (int) Math.floor(t0 * ky), y1 = Math.max(y0 + 1, (int) Math.ceil(t1 * ky));
        // a huge footprint (a stretched texture) only needs a spread of samples
        int stepX = Math.max(1, (x1 - x0) / 16), stepY = Math.max(1, (y1 - y0) / 16);
        long r = 0, g = 0, b = 0, n = 0;
        for (int y = y0; y < y1; y += stepY) {
            int row = Math.floorMod(y, height) * width;
            for (int x = x0; x < x1; x += stepX) {
                int c = rgb[row + Math.floorMod(x, width)];
                if (c < 0) continue;
                r += c >> 16 & 0xFF;
                g += c >> 8 & 0xFF;
                b += c & 0xFF;
                n++;
            }
        }
        if (n == 0) return -1;
        return (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
    }

    /** Average of the whole image, -1 when it's all see-through. */
    public int average() {
        return average(0, 0, width, height, width, height);
    }
}
