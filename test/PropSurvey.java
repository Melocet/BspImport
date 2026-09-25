import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** What props a Source map uses, and which of their models ship inside the map's own pakfile. */
public class PropSurvey {
    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Path.of(args[0]));
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        boolean swap = b.getInt(8 + 1 * 16) < 1036; // L4D2 order when the first field is a small version
        int[] ofs = new int[64], len = new int[64];
        for (int i = 0; i < 64; i++) {
            int a = b.getInt(8 + i * 16), c = b.getInt(12 + i * 16), d = b.getInt(16 + i * 16);
            ofs[i] = swap ? c : a;
            len[i] = swap ? d : c;
        }
        // pakfile: a zip in lump 40
        int models = 0, materials = 0, other = 0;
        java.util.Set<String> packed = new java.util.HashSet<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(data, ofs[40], len[40]))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String n = e.getName().toLowerCase().replace('\\', '/');
                if (n.endsWith(".mdl")) { models++; packed.add(n); }
                else if (n.startsWith("materials/")) materials++;
                else other++;
            }
        }
        System.out.println("pakfile: " + models + " models, " + materials + " material files, " + other + " other");
        // game lump 35: static props ('sprp')
        int g = ofs[35];
        int count = b.getInt(g);
        for (int i = 0; i < count; i++) {
            int e = g + 4 + i * 16;
            int id = b.getInt(e);
            int version = b.getShort(e + 6) & 0xFFFF;
            int fo = b.getInt(e + 8), fl = b.getInt(e + 12);
            if (id != 0x73707270) continue;
            int dict = b.getInt(fo);
            String[] names = new String[dict];
            for (int k = 0; k < dict; k++) {
                int at = fo + 4 + k * 128, end = at;
                while (end < at + 128 && data[end] != 0) end++;
                names[k] = new String(data, at, end - at, StandardCharsets.ISO_8859_1).toLowerCase().replace('\\', '/');
            }
            int p = fo + 4 + dict * 128;
            int leafs = b.getInt(p);
            p += 4 + leafs * 2;
            int props = b.getInt(p);
            int inPak = 0;
            for (String n : names) if (packed.contains(n)) inPak++;
            System.out.println("static props: " + props + " placed, " + dict + " different models (sprp v" + version + "), "
                    + inPak + " of those models are inside the map");
            Map<String, Integer> folders = new TreeMap<>();
            for (String n : names) {
                String[] parts = n.split("/");
                folders.merge(parts.length > 2 ? parts[1] : "?", 1, Integer::sum);
            }
            System.out.println("model folders: " + folders);
            for (int k = 0; k < Math.min(15, dict); k++) System.out.println("  " + names[k]);
        }
        // entity props (dynamic / physics) by counting "model" "models/..." in the entity lump
        String ents = new String(data, ofs[0], len[0], StandardCharsets.ISO_8859_1);
        int dyn = ents.split("\"model\" \"models/", -1).length - 1;
        int doors = ents.split("\"classname\" \"(func_door|func_door_rotating|prop_door_rotating)\"", -1).length - 1;
        System.out.println("entity props with a model: " + dyn + ", doors: " + doors);
    }
}
