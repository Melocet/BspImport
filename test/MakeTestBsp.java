import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes a tiny BSP v30 by hand to exercise the importer: a 256×256 room open to a sky above
 * z=128, a 64×64 pillar in the middle, a func_wall box, a sand floor and brick walls whose pixels
 * live in the map, and one CT and one T spawn.
 */
public class MakeTestBsp {

    static ByteBuffer buf(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    public static void main(String[] args) throws Exception {
        // planes: normal, dist, type
        float[][] planes = {
                {1, 0, 0, 0, 0}, {1, 0, 0, 256, 0}, {0, 1, 0, 0, 1}, {0, 1, 0, 256, 1}, {0, 0, 1, 0, 2}, {0, 0, 1, 128, 2},
                {1, 0, 0, 96, 0}, {1, 0, 0, 160, 0}, {0, 1, 0, 96, 1}, {0, 1, 0, 160, 1},
                {1, 0, 0, 200, 0}, {1, 0, 0, 240, 0}, {0, 1, 0, 20, 1}, {0, 1, 0, 60, 1}, {0, 0, 1, 0, 2}, {0, 0, 1, 64, 2}};
        // leaves: 0 solid, 1 empty, 2 sky. child -(leaf+1)
        int SOLID = -1, EMPTY = -2, SKY = -3;
        int[][] nodes = {
                {0, 1, SOLID}, {1, SOLID, 2}, {2, 3, SOLID}, {3, SOLID, 4}, {4, 5, SOLID}, {5, SKY, 6},
                {6, 7, EMPTY}, {7, EMPTY, 8}, {8, 9, EMPTY}, {9, EMPTY, SOLID},
                // func_wall tree (head 10): inside = solid, outside = empty
                {10, 11, EMPTY}, {11, EMPTY, 12}, {12, 13, EMPTY}, {13, EMPTY, 14}, {14, 15, EMPTY}, {15, EMPTY, SOLID}};
        int[] leafContents = {-2, -1, -6};

        List<float[]> verts = new ArrayList<>();
        List<int[]> edges = new ArrayList<>();
        edges.add(new int[]{0, 0}); // edge 0 is never used
        List<Integer> surfedges = new ArrayList<>();
        List<int[]> faces = new ArrayList<>(); // plane, side, firstedge, numedges, texinfo

        addFace(verts, edges, surfedges, faces, 4, 0, 0, new float[][]{{0, 0, 0}, {256, 0, 0}, {256, 256, 0}, {0, 256, 0}});
        addFace(verts, edges, surfedges, faces, 0, 0, 1, new float[][]{{0, 0, 0}, {0, 256, 0}, {0, 256, 128}, {0, 0, 128}});
        addFace(verts, edges, surfedges, faces, 1, 1, 1, new float[][]{{256, 0, 0}, {256, 0, 128}, {256, 256, 128}, {256, 256, 0}});
        addFace(verts, edges, surfedges, faces, 2, 0, 1, new float[][]{{0, 0, 0}, {0, 0, 128}, {256, 0, 128}, {256, 0, 0}});
        addFace(verts, edges, surfedges, faces, 3, 1, 1, new float[][]{{0, 256, 0}, {256, 256, 0}, {256, 256, 128}, {0, 256, 128}});

        String ents = "{\n\"classname\" \"worldspawn\"\n\"wad\" \"\\\\half-life\\\\cstrike\\\\cstrike.wad\"\n}\n"
                + "{\n\"classname\" \"info_player_start\"\n\"origin\" \"48 48 36\"\n\"angles\" \"0 90 0\"\n}\n"
                + "{\n\"classname\" \"info_player_deathmatch\"\n\"origin\" \"208 208 36\"\n\"angles\" \"0 270 0\"\n}\n"
                + "{\n\"classname\" \"func_wall\"\n\"model\" \"*1\"\n}\n"
                + "{\n\"classname\" \"func_door\"\n\"model\" \"*1\"\n}\n";

        byte[][] lumps = new byte[15][];
        lumps[0] = (ents + "\0").getBytes(StandardCharsets.ISO_8859_1);
        ByteBuffer b = buf(planes.length * 20);
        for (float[] p : planes) {
            b.putFloat(p[0]).putFloat(p[1]).putFloat(p[2]).putFloat(p[3]).putInt((int) p[4]);
        }
        lumps[1] = b.array();
        lumps[2] = textures(new String[]{"sandfloor", "brickwall"}, new int[][]{{200, 180, 120}, {150, 90, 80}});
        b = buf(verts.size() * 12);
        for (float[] v : verts) b.putFloat(v[0]).putFloat(v[1]).putFloat(v[2]);
        lumps[3] = b.array();
        lumps[4] = new byte[0];
        b = buf(nodes.length * 24);
        for (int[] n : nodes) {
            b.putInt(n[0]).putShort((short) n[1]).putShort((short) n[2]);
            for (int i = 0; i < 6; i++) b.putShort((short) 0);
            b.putShort((short) 0).putShort((short) 0);
        }
        lumps[5] = b.array();
        b = buf(2 * 40);
        for (int t = 0; t < 2; t++) {
            for (int i = 0; i < 8; i++) b.putFloat(0);
            b.putInt(t).putInt(0);
        }
        lumps[6] = b.array();
        b = buf(faces.size() * 20);
        for (int[] f : faces) {
            b.putShort((short) f[0]).putShort((short) f[1]).putInt(f[2]).putShort((short) f[3]).putShort((short) f[4]);
            b.putInt(0).putInt(-1);
        }
        lumps[7] = b.array();
        lumps[8] = new byte[0];
        lumps[9] = new byte[0];
        b = buf(leafContents.length * 28);
        for (int c : leafContents) {
            b.putInt(c).putInt(-1);
            for (int i = 0; i < 6; i++) b.putShort((short) 0);
            b.putShort((short) 0).putShort((short) 0).putInt(0);
        }
        lumps[10] = b.array();
        lumps[11] = new byte[0];
        b = buf(edges.size() * 4);
        for (int[] e : edges) b.putShort((short) e[0]).putShort((short) e[1]);
        lumps[12] = b.array();
        b = buf(surfedges.size() * 4);
        for (int s : surfedges) b.putInt(s);
        lumps[13] = b.array();
        b = buf(2 * 64);
        model(b, new float[]{-16, -16, -16}, new float[]{272, 272, 160}, 0, 0, faces.size());
        model(b, new float[]{200, 20, 0}, new float[]{240, 60, 64}, 10, 0, 0);
        lumps[14] = b.array();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int offset = 4 + 15 * 8;
        ByteBuffer header = buf(offset);
        header.putInt(30);
        for (byte[] l : lumps) {
            header.putInt(offset).putInt(l.length);
            offset += l.length;
        }
        out.write(header.array());
        for (byte[] l : lumps) out.write(l);
        Files.write(Path.of(args[0]), out.toByteArray());
        System.out.println("wrote " + args[0] + " (" + out.size() + " bytes)");
    }

    static void model(ByteBuffer b, float[] mins, float[] maxs, int head, int firstFace, int numFaces) {
        for (float f : mins) b.putFloat(f);
        for (float f : maxs) b.putFloat(f);
        b.putFloat(0).putFloat(0).putFloat(0);
        b.putInt(head).putInt(0).putInt(0).putInt(0);
        b.putInt(0).putInt(firstFace).putInt(numFaces);
    }

    static void addFace(List<float[]> verts, List<int[]> edges, List<Integer> surfedges, List<int[]> faces,
                        int plane, int side, int texinfo, float[][] poly) {
        int base = verts.size();
        for (float[] v : poly) verts.add(v);
        int first = surfedges.size();
        for (int i = 0; i < poly.length; i++) {
            edges.add(new int[]{base + i, base + (i + 1) % poly.length});
            surfedges.add(edges.size() - 1);
        }
        faces.add(new int[]{plane, side, first, poly.length, texinfo});
    }

    /** Miptex lump with 16×16 single-color textures carried in the map. */
    static byte[] textures(String[] names, int[][] colors) {
        int mipSize = 40 + 256 + 64 + 16 + 4 + 2 + 768 + 2;
        ByteBuffer b = buf(4 + names.length * 4 + names.length * mipSize);
        b.putInt(names.length);
        for (int i = 0; i < names.length; i++) b.putInt(4 + names.length * 4 + i * mipSize);
        for (int i = 0; i < names.length; i++) {
            byte[] name = new byte[16];
            byte[] n = names[i].getBytes(StandardCharsets.ISO_8859_1);
            System.arraycopy(n, 0, name, 0, n.length);
            b.put(name).putInt(16).putInt(16);
            b.putInt(40).putInt(40 + 256).putInt(40 + 256 + 64).putInt(40 + 256 + 64 + 16);
            for (int p = 0; p < 256 + 64 + 16 + 4; p++) b.put((byte) 1);
            b.putShort((short) 256);
            for (int c = 0; c < 256; c++) {
                int[] rgb = c == 1 ? colors[i] : new int[]{0, 0, 0};
                b.put((byte) rgb[0]).put((byte) rgb[1]).put((byte) rgb[2]);
            }
            b.putShort((short) 0);
        }
        return b.array();
    }
}
