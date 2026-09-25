package com.melocet.bspimport.game;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Game content lookup for models and materials, in this order: files packed into the map itself,
 * loose files under a configured folder (models/..., materials/...), then the folder's VPKs.
 * Paths are lowercase with forward slashes.
 */
public final class GameFiles {

    private static final Set<String> WANTED = Set.of("mdl", "vvd", "vtx", "vmt", "vtf");

    private final Map<String, byte[]> packed = new HashMap<>();
    private final List<Path> looseRoots = new ArrayList<>();
    private final List<Vpk> vpks = new ArrayList<>();

    /**
     * @param roots  game folders: each may hold *_dir.vpk files and/or loose models/ and materials/
     * @param pakfile the map's embedded zip (Source lump 40), or null
     */
    public static GameFiles open(List<Path> roots, byte[] pakfile, Logger log) {
        GameFiles g = new GameFiles();
        if (pakfile != null && pakfile.length > 0) {
            try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(pakfile))) {
                ZipEntry e;
                while ((e = z.getNextEntry()) != null) {
                    String n = e.getName().toLowerCase(Locale.ROOT).replace('\\', '/');
                    String ext = n.substring(n.lastIndexOf('.') + 1);
                    if (WANTED.contains(ext)) g.packed.put(n, z.readAllBytes());
                }
            } catch (IOException ex) {
                log.warning("Couldn't read the map's packed files: " + ex.getMessage());
            }
        }
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                log.warning("Game folder not found: " + root);
                continue;
            }
            if (Files.isDirectory(root.resolve("models")) || Files.isDirectory(root.resolve("materials"))) g.looseRoots.add(root);
            try (Stream<Path> files = Files.list(root)) {
                for (Path p : files.filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith("_dir.vpk")).sorted().toList()) {
                    try {
                        Vpk v = Vpk.open(p, WANTED);
                        if (v.size() > 0) g.vpks.add(v);
                    } catch (IOException ex) {
                        log.warning("Couldn't read " + p.getFileName() + ": " + ex.getMessage());
                    }
                }
            } catch (IOException ex) {
                log.warning("Couldn't list " + root + ": " + ex.getMessage());
            }
        }
        return g;
    }

    /** File contents, or null when no source has it. */
    public byte[] read(String path) {
        String p = path.toLowerCase(Locale.ROOT).replace('\\', '/');
        byte[] b = packed.get(p);
        if (b != null) return b;
        for (Path root : looseRoots) {
            Path f = root.resolve(p);
            if (Files.isRegularFile(f)) {
                try {
                    return Files.readAllBytes(f);
                } catch (IOException ignored) {
                    // try the next source
                }
            }
        }
        for (Vpk v : vpks) {
            if (!v.has(p)) continue;
            try {
                return v.read(p);
            } catch (IOException ignored) {
                // try the next source
            }
        }
        return null;
    }

    public boolean isEmpty() {
        return packed.isEmpty() && looseRoots.isEmpty() && vpks.isEmpty();
    }

    public String describe() {
        return packed.size() + " packed file(s), " + looseRoots.size() + " loose folder(s), " + vpks.size() + " VPK(s)";
    }
}
