package com.melocet.bspimport.game;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A Valve pack (VPK v1/v2): the *_dir.vpk holds the file tree, the numbered *_NNN.vpk files hold the
 * data. Only the tree is read up front; file contents are read on request.
 */
final class Vpk {

    private record Entry(byte[] preload, int archive, long offset, int length) {}

    private static final int SIGNATURE = 0x55aa1234;
    private static final int IN_DIR = 0x7fff;

    private final Path dirFile;
    private final String prefix;
    private final long dataStart;
    private final Map<String, Entry> entries = new HashMap<>();

    private Vpk(Path dirFile, String prefix, long dataStart) {
        this.dirFile = dirFile;
        this.prefix = prefix;
        this.dataStart = dataStart;
    }

    /** @param wanted extensions to index (lowercase, no dot); others are skipped to save memory */
    static Vpk open(Path dirFile, Set<String> wanted) throws IOException {
        byte[] bytes = Files.readAllBytes(dirFile);
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt(0) != SIGNATURE) throw new IOException(dirFile.getFileName() + " isn't a VPK");
        int version = b.getInt(4);
        int treeSize = b.getInt(8);
        int header = version == 1 ? 12 : version == 2 ? 28 : -1;
        if (header < 0) throw new IOException(dirFile.getFileName() + ": VPK version " + version + " isn't supported");
        String name = dirFile.getFileName().toString();
        String prefix = name.substring(0, name.length() - "_dir.vpk".length());
        Vpk vpk = new Vpk(dirFile, prefix, header + (long) treeSize);
        int[] pos = {header};
        int end = header + treeSize;
        while (pos[0] < end) {
            String ext = cstring(bytes, pos);
            if (ext.isEmpty()) break;
            boolean keep = wanted.contains(ext.toLowerCase(Locale.ROOT));
            while (true) {
                String path = cstring(bytes, pos);
                if (path.isEmpty()) break;
                while (true) {
                    String file = cstring(bytes, pos);
                    if (file.isEmpty()) break;
                    int p = pos[0];
                    int preloadBytes = b.getShort(p + 4) & 0xFFFF;
                    int archive = b.getShort(p + 6) & 0xFFFF;
                    long offset = b.getInt(p + 8) & 0xFFFFFFFFL;
                    int length = b.getInt(p + 12);
                    pos[0] = p + 18 + preloadBytes;
                    if (!keep) continue;
                    byte[] preload = preloadBytes == 0 ? new byte[0] : java.util.Arrays.copyOfRange(bytes, p + 18, p + 18 + preloadBytes);
                    String full = (path.equals(" ") ? "" : path + "/") + file + "." + ext;
                    vpk.entries.put(full.toLowerCase(Locale.ROOT).replace('\\', '/'), new Entry(preload, archive, offset, length));
                }
            }
        }
        return vpk;
    }

    private static String cstring(byte[] bytes, int[] pos) {
        int start = pos[0];
        int end = start;
        while (end < bytes.length && bytes[end] != 0) end++;
        pos[0] = end + 1;
        return new String(bytes, start, end - start, StandardCharsets.ISO_8859_1);
    }

    boolean has(String path) {
        return entries.containsKey(path);
    }

    int size() {
        return entries.size();
    }

    byte[] read(String path) throws IOException {
        Entry e = entries.get(path);
        if (e == null) return null;
        byte[] out = new byte[e.preload.length + e.length];
        System.arraycopy(e.preload, 0, out, 0, e.preload.length);
        if (e.length > 0) {
            Path file = e.archive == IN_DIR ? dirFile : dirFile.resolveSibling(String.format(Locale.ROOT, "%s_%03d.vpk", prefix, e.archive));
            long at = e.archive == IN_DIR ? dataStart + e.offset : e.offset;
            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                raf.seek(at);
                raf.readFully(out, e.preload.length, e.length);
            }
        }
        return out;
    }
}
