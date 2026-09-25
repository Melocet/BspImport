package com.melocet.bspimport.bsp;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Texture colors from WAD3 files (cstrike.wad, halflife.wad...). Most CS maps only name their
 * textures and leave the pixels in these, so without them surfaces fall back to name rules.
 */
public final class WadLibrary {

    private static final int TYPE_MIPTEX = 0x43;
    private final Map<String, Integer> colors = new HashMap<>();
    private final Map<String, TextureImage> images = new HashMap<>();
    private int files;

    public static WadLibrary load(Path dir, Logger log) {
        WadLibrary lib = new WadLibrary();
        if (!Files.isDirectory(dir)) return lib;
        try (Stream<Path> list = Files.list(dir)) {
            list.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".wad")).sorted().forEach(p -> {
                try {
                    lib.read(p);
                    lib.files++;
                } catch (IOException | RuntimeException e) {
                    log.warning("Couldn't read " + p.getFileName() + ": " + e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warning("Couldn't list " + dir + ": " + e.getMessage());
        }
        return lib;
    }

    private void read(Path file) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
        if (b.capacity() < 12 || b.get(0) != 'W' || b.get(1) != 'A' || b.get(2) != 'D' || b.get(3) != '3') {
            throw new IOException("not a WAD3 file");
        }
        int count = b.getInt(4);
        int table = b.getInt(8);
        for (int i = 0; i < count; i++) {
            int e = table + i * 32;
            if (e + 32 > b.capacity()) break;
            int pos = b.getInt(e);
            int type = b.get(e + 12) & 0xFF;
            int compression = b.get(e + 13) & 0xFF;
            if (type != TYPE_MIPTEX || compression != 0) continue;
            String name = Miptex.name(b, e + 16);
            int color = Miptex.averageColor(b, pos, name);
            if (color >= 0) colors.putIfAbsent(name, color);
            TextureImage image = Miptex.image(b, pos, name);
            if (image != null) images.putIfAbsent(name, image);
        }
    }

    /** Average color of the named texture, or -1. */
    public int color(String name) {
        return colors.getOrDefault(name, -1);
    }

    /** A small copy of the named texture's pixels, or null. */
    public TextureImage image(String name) {
        return images.get(name);
    }

    public int textureCount() { return colors.size(); }
    public int fileCount() { return files; }
}
