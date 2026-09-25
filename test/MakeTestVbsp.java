import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes a tiny Source BSP (v20) by hand: a 256×256×128 room, a half-height detail pillar that only
 * exists as a brush inside the room's leaf, a displacement hill on the floor, a CT and a T spawn,
 * and a separate "3D skybox" room far away at x=2000 that the importer must leave out.
 */
public class MakeTestVbsp {

    static ByteBuffer buf(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    public static void main(String[] args) throws Exception {
        // planes: nx, ny, nz, dist, type
        float[][] planes = {
                {1, 0, 0, 1000, 0},                                           // 0 splits main / skybox
                {1, 0, 0, 0, 0}, {1, 0, 0, 256, 0}, {0, 1, 0, 0, 1}, {0, 1, 0, 256, 1}, {0, 0, 1, 0, 2}, {0, 0, 1, 128, 2}, // 1-6 room
                {1, 0, 0, 2000, 0}, {1, 0, 0, 2128, 0}, {0, 1, 0, 128, 1},    // 7-9 skybox (reuses 3, 5, 6)
                // pillar brush sides, normals pointing out of the brush
                {-1, 0, 0, -96, 0}, {1, 0, 0, 160, 0}, {0, -1, 0, -96, 1}, {0, 1, 0, 160, 1}, {0, 0, -1, 0, 2}, {0, 0, 1, 64, 2},  // 10-15
                // flipped twins for faces on the far walls: in Source the face plane faces the open side
                {-1, 0, 0, -256, 0}, {0, -1, 0, -256, 1}}; // 16-17
        int SOLID = -1, ROOM = -2, SKYROOM = -3;
        int[][] nodes = { // plane, front, back
                {0, 7, 1},
                {1, 2, SOLID}, {2, SOLID, 3}, {3, 4, SOLID}, {4, SOLID, 5}, {5, 6, SOLID}, {6, SOLID, ROOM},
                {7, 8, SOLID}, {8, SOLID, 9}, {3, 10, SOLID}, {9, SOLID, 11}, {5, 12, SOLID}, {6, SOLID, SKYROOM}};
        // leaves: contents, box (6 shorts), first leafbrush, count
        int[][] leaves = {{1, 0, 0, 0, 0, 0, 0, 0, 0}, {0, 0, 0, 0, 256, 256, 128, 0, 1}, {0, 2000, 0, 0, 2128, 128, 128, 0, 0}};

        List<float[]> verts = new ArrayList<>();
        List<int[]> edges = new ArrayList<>();
        edges.add(new int[]{0, 0});
        List<Integer> surfedges = new ArrayList<>();
        List<int[]> faces = new ArrayList<>(); // plane, side, firstedge, numedges, texinfo, dispinfo
        addFace(verts, edges, surfedges, faces, 5, 0, 0, -1, new float[][]{{0, 0, 0}, {256, 0, 0}, {256, 256, 0}, {0, 256, 0}});
        addFace(verts, edges, surfedges, faces, 1, 0, 1, -1, new float[][]{{0, 0, 0}, {0, 256, 0}, {0, 256, 128}, {0, 0, 128}});
        addFace(verts, edges, surfedges, faces, 16, 1, 1, -1, new float[][]{{256, 0, 0}, {256, 0, 128}, {256, 256, 128}, {256, 256, 0}});
        addFace(verts, edges, surfedges, faces, 3, 0, 1, -1, new float[][]{{0, 0, 0}, {0, 0, 128}, {256, 0, 128}, {256, 0, 0}});
        addFace(verts, edges, surfedges, faces, 17, 1, 1, -1, new float[][]{{0, 256, 0}, {256, 256, 0}, {256, 256, 128}, {0, 256, 128}});
        // displacement base quad over the corner x 0..128, y 128..256
        addFace(verts, edges, surfedges, faces, 5, 0, 0, 0, new float[][]{{0, 128, 0}, {128, 128, 0}, {128, 256, 0}, {0, 256, 0}});

        String ents = "{\n\"classname\" \"worldspawn\"\n}\n"
                + "{\n\"classname\" \"info_player_counterterrorist\"\n\"origin\" \"200 48 0\"\n\"angles\" \"0 90 0\"\n}\n"
                + "{\n\"classname\" \"info_player_terrorist\"\n\"origin\" \"208 208 0\"\n\"angles\" \"0 270 0\"\n}\n"
                + "{\n\"classname\" \"sky_camera\"\n\"origin\" \"2064 64 64\"\n}\n";

        byte[][] lumps = new byte[64][];
        int[] versions = new int[64];
        for (int i = 0; i < 64; i++) lumps[i] = new byte[0];
        lumps[0] = (ents + "\0").getBytes(StandardCharsets.ISO_8859_1);
        ByteBuffer b = buf(planes.length * 20);
        for (float[] p : planes) b.putFloat(p[0]).putFloat(p[1]).putFloat(p[2]).putFloat(p[3]).putInt((int) p[4]);
        lumps[1] = b.array();
        String[] names = {"DE_DUST/SANDFLOOR01", "maps/test/brick/brickwall001_12_-34_56"};
        float[][] refl = {{0.55f, 0.45f, 0.25f}, {0.30f, 0.10f, 0.07f}};
        b = buf(2 * 32);
        for (int i = 0; i < 2; i++) {
            b.putFloat(refl[i][0]).putFloat(refl[i][1]).putFloat(refl[i][2]).putInt(i).putInt(16).putInt(16).putInt(16).putInt(16);
        }
        lumps[2] = b.array();
        b = buf(verts.size() * 12);
        for (float[] v : verts) b.putFloat(v[0]).putFloat(v[1]).putFloat(v[2]);
        lumps[3] = b.array();
        b = buf(nodes.length * 32);
        for (int[] n : nodes) {
            b.putInt(n[0]).putInt(n[1]).putInt(n[2]);
            for (int i = 0; i < 6; i++) b.putShort((short) 0);
            b.putShort((short) 0).putShort((short) 0).putShort((short) 0).putShort((short) 0);
        }
        lumps[5] = b.array();
        b = buf(2 * 72);
        for (int t = 0; t < 2; t++) {
            for (int i = 0; i < 16; i++) b.putFloat(0);
            b.putInt(0).putInt(t);
        }
        lumps[6] = b.array();
        b = buf(faces.size() * 56);
        for (int[] f : faces) {
            b.putShort((short) f[0]).put((byte) f[1]).put((byte) 0).putInt(f[2]).putShort((short) f[3]).putShort((short) f[4])
                    .putShort((short) f[5]).putShort((short) -1).putInt(0).putInt(-1).putFloat(0)
                    .putInt(0).putInt(0).putInt(0).putInt(0).putInt(0).putShort((short) 0).putShort((short) 0).putInt(0);
        }
        lumps[7] = b.array();
        b = buf(leaves.length * 32);
        for (int[] l : leaves) {
            b.putInt(l[0]).putShort((short) 0).putShort((short) 0);
            for (int i = 1; i <= 6; i++) b.putShort((short) l[i]);
            b.putShort((short) 0).putShort((short) 0).putShort((short) l[7]).putShort((short) l[8]).putShort((short) -1).putShort((short) 0);
        }
        lumps[10] = b.array();
        versions[10] = 1;
        b = buf(edges.size() * 4);
        for (int[] e : edges) b.putShort((short) e[0]).putShort((short) e[1]);
        lumps[12] = b.array();
        b = buf(surfedges.size() * 4);
        for (int s : surfedges) b.putInt(s);
        lumps[13] = b.array();
        b = buf(48);
        b.putFloat(-16).putFloat(-16).putFloat(-16).putFloat(2144).putFloat(272).putFloat(144);
        b.putFloat(0).putFloat(0).putFloat(0).putInt(0).putInt(0).putInt(faces.size());
        lumps[14] = b.array();
        b = buf(2);
        b.putShort((short) 0);
        lumps[17] = b.array();
        b = buf(12);
        b.putInt(0).putInt(6).putInt(0x1 | 0x8000000); // solid, detail
        lumps[18] = b.array();
        b = buf(6 * 8);
        for (int p = 10; p <= 15; p++) b.putShort((short) p).putShort((short) 0).putShort((short) -1).putShort((short) 0);
        lumps[19] = b.array();
        b = buf(176);
        b.putFloat(0).putFloat(128).putFloat(0).putInt(0).putInt(0).putInt(2).putInt(0).putFloat(0).putInt(1).putShort((short) 5);
        lumps[26] = b.array();
        b = buf(25 * 20);
        for (int r = 0; r < 5; r++) {
            for (int c = 0; c < 5; c++) {
                float h = 64f * (1 - Math.max(Math.abs(r - 2), Math.abs(c - 2)) / 2f);
                b.putFloat(0).putFloat(0).putFloat(1).putFloat(h).putFloat(0);
            }
        }
        lumps[33] = b.array();
        ByteArrayOutputStream strings = new ByteArrayOutputStream();
        b = buf(8);
        for (String n : names) {
            b.putInt(strings.size());
            strings.write(n.getBytes(StandardCharsets.ISO_8859_1));
            strings.write(0);
        }
        lumps[43] = strings.toByteArray();
        lumps[44] = b.array();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int header = 8 + 64 * 16 + 4;
        int offset = header;
        ByteBuffer h = buf(header);
        h.put((byte) 'V').put((byte) 'B').put((byte) 'S').put((byte) 'P').putInt(20);
        for (int i = 0; i < 64; i++) {
            h.putInt(lumps[i].length == 0 ? 0 : offset).putInt(lumps[i].length).putInt(versions[i]).putInt(0);
            offset += lumps[i].length;
        }
        h.putInt(1);
        out.write(h.array());
        for (byte[] l : lumps) out.write(l);
        Files.write(Path.of(args[0]), out.toByteArray());
        System.out.println("wrote " + args[0] + " (" + out.size() + " bytes)");
    }

    static void addFace(List<float[]> verts, List<int[]> edges, List<Integer> surfedges, List<int[]> faces,
                        int plane, int side, int texinfo, int disp, float[][] poly) {
        int base = verts.size();
        for (float[] v : poly) verts.add(v);
        int first = surfedges.size();
        for (int i = 0; i < poly.length; i++) {
            edges.add(new int[]{base + i, base + (i + 1) % poly.length});
            surfedges.add(edges.size() - 1);
        }
        faces.add(new int[]{plane, side, first, poly.length, texinfo, disp});
    }
}
