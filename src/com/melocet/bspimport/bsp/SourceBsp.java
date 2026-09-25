package com.melocet.bspimport.bsp;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A Source 1 map (VBSP versions 19-29: CS:S, CS:GO, HL2, TF2, GMod, L4D2, Contagion). Solidity comes from the node
 * tree plus the brushes in each leaf, since detail brushes don't split leaves in Source.
 * Displacements (terrain) aren't in the tree at all; they come out as triangles. Texture colors
 * come from the reflectivity vbsp stores for every material, so no game files are needed.
 */
public final class SourceBsp implements MapData {

    private static final int HEADER = 8 + 64 * 16 + 4;
    private static final int L_ENTITIES = 0, L_PLANES = 1, L_TEXDATA = 2, L_VERTEXES = 3, L_NODES = 5,
            L_TEXINFO = 6, L_FACES = 7, L_LEAFS = 10, L_EDGES = 12, L_SURFEDGES = 13, L_MODELS = 14,
            L_LEAFBRUSHES = 17, L_BRUSHES = 18, L_BRUSHSIDES = 19, L_DISPINFO = 26, L_DISP_VERTS = 33,
            L_TEXDATA_STRING_DATA = 43, L_TEXDATA_STRING_TABLE = 44, L_FACES_HDR = 58,
            L_GAME_LUMP = 35, L_PAKFILE = 40;
    private static final int GAMELUMP_STATIC_PROPS = 0x73707270; // "sprp"

    private static final int CONTENTS_SOLID = 0x1, CONTENTS_WINDOW = 0x2, CONTENTS_GRATE = 0x8,
            CONTENTS_SLIME = 0x10, CONTENTS_WATER = 0x20, CONTENTS_PLAYERCLIP = 0x10000,
            CONTENTS_LADDER = 0x20000000;
    private static final int SOLIDISH = CONTENTS_SOLID | CONTENTS_WINDOW | CONTENTS_GRATE;
    private static final int SURF_NODRAW = 0x80;
    /** CS:GO bakes cubemaps into per-map copies: maps/<map>/<material>_<x>_<y>_<z>. */
    private static final Pattern PATCHED = Pattern.compile("^maps/[^/]+/(.*?)(_-?\\d+){3}$");

    private int version;
    private ByteBuffer b;
    private final int[] ofs = new int[64];
    private final int[] len = new int[64];
    private final int[] lumpVersion = new int[64];

    private float[] planeNormal;
    private float[] planeDist;
    private int[] nodePlane;
    private int[] nodeChildren;
    private int[] leafContents;
    private int[] leafFirstBrush;
    private int[] leafNumBrushes;
    private short[] leafBox;
    private int[] leafBrushes;
    private int[] brushFirstSide;
    private int[] brushNumSides;
    private int[] brushContents;
    private int[] sidePlane;
    private float[] vertices;
    private int[] edges;
    private int[] surfedges;
    private int[] facePlane, faceSide, faceFirstEdge, faceNumEdges, faceTexinfo, faceDisp;
    private int[] texinfoFlags, texinfoTexdata;
    private float[] texinfoAxes;
    private int[] textureSizes;
    private String[] textureNames;
    private int[] textureColors;
    private float[] modelBox;
    private int[] modelHead, modelFirstFace, modelNumFaces;
    private List<Map<String, String>> entities;
    private final List<Triangle> terrain = new ArrayList<>();
    private double[] playable;
    private final List<Prop> staticProps = new ArrayList<>();
    private byte[] pakfile;

    public static SourceBsp read(Path file) throws IOException {
        SourceBsp s = new SourceBsp();
        s.b = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
        s.parse();
        return s;
    }

    public static boolean isSource(byte[] head) {
        return head.length >= 4 && head[0] == 'V' && head[1] == 'B' && head[2] == 'S' && head[3] == 'P';
    }

    private void parse() throws IOException {
        if (b.capacity() < HEADER) throw new IOException("File too short for a Source map");
        version = b.getInt(4);
        if (version < 17 || version > 29) throw new IOException("Unsupported Source BSP version " + version);
        readDirectory();

        entities = EntityParser.parse(new String(b.array(), ofs[L_ENTITIES], len[L_ENTITIES], StandardCharsets.ISO_8859_1).replace("\0", ""));

        int n = len[L_PLANES] / 20;
        planeNormal = new float[n * 3];
        planeDist = new float[n];
        for (int i = 0; i < n; i++) {
            int o = ofs[L_PLANES] + i * 20;
            planeNormal[i * 3] = b.getFloat(o);
            planeNormal[i * 3 + 1] = b.getFloat(o + 4);
            planeNormal[i * 3 + 2] = b.getFloat(o + 8);
            planeDist[i] = b.getFloat(o + 12);
        }

        n = len[L_NODES] / 32;
        nodePlane = new int[n];
        nodeChildren = new int[n * 2];
        for (int i = 0; i < n; i++) {
            int o = ofs[L_NODES] + i * 32;
            nodePlane[i] = b.getInt(o);
            nodeChildren[i * 2] = b.getInt(o + 4);
            nodeChildren[i * 2 + 1] = b.getInt(o + 8);
        }

        // Leaf layout depends on the lump version: 0 carries a 24-byte light cube (56 bytes), 1 doesn't (32).
        int leafSize = lumpVersion[L_LEAFS] == 0 ? 56 : 32;
        if (len[L_LEAFS] % leafSize != 0 && len[L_LEAFS] % (88 - leafSize) == 0) leafSize = 88 - leafSize;
        n = len[L_LEAFS] / leafSize;
        leafContents = new int[n];
        leafFirstBrush = new int[n];
        leafNumBrushes = new int[n];
        leafBox = new short[n * 6];
        for (int i = 0; i < n; i++) {
            int o = ofs[L_LEAFS] + i * leafSize;
            leafContents[i] = b.getInt(o);
            for (int k = 0; k < 6; k++) leafBox[i * 6 + k] = b.getShort(o + 8 + k * 2);
            leafFirstBrush[i] = b.getShort(o + 24) & 0xFFFF;
            leafNumBrushes[i] = b.getShort(o + 26) & 0xFFFF;
        }

        n = len[L_LEAFBRUSHES] / 2;
        leafBrushes = new int[n];
        for (int i = 0; i < n; i++) leafBrushes[i] = b.getShort(ofs[L_LEAFBRUSHES] + i * 2) & 0xFFFF;

        n = len[L_BRUSHES] / 12;
        brushFirstSide = new int[n];
        brushNumSides = new int[n];
        brushContents = new int[n];
        for (int i = 0; i < n; i++) {
            int o = ofs[L_BRUSHES] + i * 12;
            brushFirstSide[i] = b.getInt(o);
            brushNumSides[i] = b.getInt(o + 4);
            brushContents[i] = b.getInt(o + 8);
        }

        n = len[L_BRUSHSIDES] / 8;
        sidePlane = new int[n];
        for (int i = 0; i < n; i++) sidePlane[i] = b.getShort(ofs[L_BRUSHSIDES] + i * 8) & 0xFFFF;

        n = len[L_VERTEXES] / 12;
        vertices = new float[n * 3];
        for (int i = 0; i < n * 3; i++) vertices[i] = b.getFloat(ofs[L_VERTEXES] + i * 4);

        n = len[L_EDGES] / 4;
        edges = new int[n * 2];
        for (int i = 0; i < n * 2; i++) edges[i] = b.getShort(ofs[L_EDGES] + i * 2) & 0xFFFF;

        n = len[L_SURFEDGES] / 4;
        surfedges = new int[n];
        for (int i = 0; i < n; i++) surfedges[i] = b.getInt(ofs[L_SURFEDGES] + i * 4);

        int faceLump = len[L_FACES] > 0 ? L_FACES : L_FACES_HDR;
        n = len[faceLump] / 56;
        facePlane = new int[n];
        faceSide = new int[n];
        faceFirstEdge = new int[n];
        faceNumEdges = new int[n];
        faceTexinfo = new int[n];
        faceDisp = new int[n];
        for (int i = 0; i < n; i++) {
            int o = ofs[faceLump] + i * 56;
            facePlane[i] = b.getShort(o) & 0xFFFF;
            faceSide[i] = b.get(o + 2);
            faceFirstEdge[i] = b.getInt(o + 4);
            faceNumEdges[i] = b.getShort(o + 8);
            faceTexinfo[i] = b.getShort(o + 10);
            faceDisp[i] = b.getShort(o + 12);
        }

        n = len[L_TEXINFO] / 72;
        texinfoFlags = new int[n];
        texinfoTexdata = new int[n];
        texinfoAxes = new float[n * 8];
        for (int i = 0; i < n; i++) {
            int o = ofs[L_TEXINFO] + i * 72;
            for (int k = 0; k < 8; k++) texinfoAxes[i * 8 + k] = b.getFloat(o + k * 4);
            texinfoFlags[i] = b.getInt(o + 64);
            texinfoTexdata[i] = b.getInt(o + 68);
        }

        n = len[L_TEXDATA] / 32;
        textureNames = new String[n];
        textureColors = new int[n];
        textureSizes = new int[n * 2];
        int tables = len[L_TEXDATA_STRING_TABLE] / 4;
        for (int i = 0; i < n; i++) {
            int o = ofs[L_TEXDATA] + i * 32;
            textureColors[i] = srgb(b.getFloat(o), b.getFloat(o + 4), b.getFloat(o + 8));
            textureSizes[i * 2] = b.getInt(o + 16);
            textureSizes[i * 2 + 1] = b.getInt(o + 20);
            int id = b.getInt(o + 12);
            textureNames[i] = id >= 0 && id < tables ? cleanName(string(ofs[L_TEXDATA_STRING_DATA] + b.getInt(ofs[L_TEXDATA_STRING_TABLE] + id * 4))) : "";
        }

        n = len[L_MODELS] / 48;
        if (n == 0) throw new IOException("Damaged map: no world model");
        modelBox = new float[n * 6];
        modelHead = new int[n];
        modelFirstFace = new int[n];
        modelNumFaces = new int[n];
        for (int i = 0; i < n; i++) {
            int o = ofs[L_MODELS] + i * 48;
            for (int k = 0; k < 6; k++) modelBox[i * 6 + k] = b.getFloat(o + k * 4);
            modelHead[i] = b.getInt(o + 36);
            modelFirstFace[i] = b.getInt(o + 40);
            modelNumFaces[i] = b.getInt(o + 44);
        }

        readDisplacements();
        readStaticProps();
        if (len[L_PAKFILE] > 0) pakfile = Arrays.copyOfRange(b.array(), ofs[L_PAKFILE], ofs[L_PAKFILE] + len[L_PAKFILE]);
        playable = findPlayableBounds();
        b = null; // the arrays above are all that's needed from here on
    }

    /** Standard lump order is {offset, length, version}; Left 4 Dead 2-style files use {version, offset, length}. */
    private void readDirectory() throws IOException {
        int standard = 0, swapped = 0;
        for (int i = 0; i < 64; i++) {
            int a = b.getInt(8 + i * 16), c = b.getInt(12 + i * 16), d = b.getInt(16 + i * 16);
            if (plausible(a, c)) standard++;
            if (plausible(c, d)) swapped++;
        }
        boolean swap = swapped > standard;
        for (int i = 0; i < 64; i++) {
            int a = b.getInt(8 + i * 16), c = b.getInt(12 + i * 16), d = b.getInt(16 + i * 16);
            ofs[i] = swap ? c : a;
            len[i] = swap ? d : c;
            lumpVersion[i] = swap ? a : d;
            if (!plausible(ofs[i], len[i])) {
                ofs[i] = 0;
                len[i] = 0;
            }
            if (len[i] >= 4 && b.get(ofs[i]) == 'L' && b.get(ofs[i] + 1) == 'Z' && b.get(ofs[i] + 2) == 'M' && b.get(ofs[i] + 3) == 'A') {
                throw new IOException("This map has LZMA-compressed lumps (console builds), which aren't supported");
            }
        }
    }

    private boolean plausible(int offset, int length) {
        return length == 0 || (offset >= HEADER && length > 0 && (long) offset + length <= b.capacity());
    }

    private String string(int at) {
        int end = at;
        while (end < b.capacity() && b.get(end) != 0) end++;
        return new String(b.array(), at, end - at, StandardCharsets.ISO_8859_1);
    }

    private static String cleanName(String raw) {
        String n = raw.toLowerCase(Locale.ROOT).replace('\\', '/');
        var m = PATCHED.matcher(n);
        return m.matches() ? m.group(1) : n;
    }

    /** vbsp stores reflectivity in linear light; blocks are compared in sRGB. */
    private static int srgb(float r, float g, float bl) {
        if (!Float.isFinite(r) || !Float.isFinite(g) || !Float.isFinite(bl)) return -1;
        return channel(r) << 16 | channel(g) << 8 | channel(bl);
    }

    private static int channel(float linear) {
        double v = Math.pow(Math.max(0, Math.min(1, linear)), 1 / 2.2) * 255;
        return (int) Math.round(v);
    }

    // ---------------------------------------------------------------- displacements

    /** Turns every displacement into triangles: the base quad, pushed out by each vertex's offset. */
    private void readDisplacements() {
        int count = len[L_DISPINFO] / 176;
        int vertCount = len[L_DISP_VERTS] / 20;
        for (int i = 0; i < count; i++) {
            int o = ofs[L_DISPINFO] + i * 176;
            double[] start = {b.getFloat(o), b.getFloat(o + 4), b.getFloat(o + 8)};
            int firstVert = b.getInt(o + 12);
            int power = b.getInt(o + 20);
            int face = b.getShort(o + 36) & 0xFFFF;
            if (power < 2 || power > 4 || face >= facePlane.length || faceNumEdges[face] != 4) continue;
            int n = (1 << power) + 1;
            if (firstVert < 0 || firstVert + n * n > vertCount) continue;
            double[] corners = faceVertices(face);
            // Rotate the corners so the one nearest the displacement's start comes first.
            int first = 0;
            double best = Double.MAX_VALUE;
            for (int c = 0; c < 4; c++) {
                double dx = corners[c * 3] - start[0], dy = corners[c * 3 + 1] - start[1], dz = corners[c * 3 + 2] - start[2];
                double d = dx * dx + dy * dy + dz * dz;
                if (d < best) {
                    best = d;
                    first = c;
                }
            }
            double[][] p = new double[4][];
            for (int c = 0; c < 4; c++) {
                int k = (first + c) % 4;
                p[c] = new double[]{corners[k * 3], corners[k * 3 + 1], corners[k * 3 + 2]};
            }
            double[][] grid = new double[n * n][];
            for (int r = 0; r < n; r++) {
                double t = (double) r / (n - 1);
                double[] left = lerp(p[0], p[1], t);
                double[] right = lerp(p[3], p[2], t);
                for (int c = 0; c < n; c++) {
                    double[] flat = lerp(left, right, (double) c / (n - 1));
                    int v = ofs[L_DISP_VERTS] + (firstVert + r * n + c) * 20;
                    float dist = b.getFloat(v + 12);
                    grid[r * n + c] = new double[]{
                            flat[0] + b.getFloat(v) * dist, flat[1] + b.getFloat(v + 4) * dist, flat[2] + b.getFloat(v + 8) * dist};
                }
            }
            int tex = textureOfFace(face);
            double[] normal = faceNormal(face);
            for (int r = 0; r < n - 1; r++) {
                for (int c = 0; c < n - 1; c++) {
                    double[] a = grid[r * n + c], bb = grid[(r + 1) * n + c], cc = grid[(r + 1) * n + c + 1], d = grid[r * n + c + 1];
                    terrain.add(new Triangle(a, bb, cc, tex, normal));
                    terrain.add(new Triangle(a, cc, d, tex, normal));
                }
            }
        }
    }

    private static double[] lerp(double[] a, double[] b, double t) {
        return new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
    }

    // ---------------------------------------------------------------- the playable area

    /**
     * Source maps carry a 3D skybox: a small scaled copy of the scenery walled off somewhere far
     * away, which blows up the map's bounding box. Start from the leaves holding the spawns and
     * join open leaves whose boxes touch; the box around that group is the part worth building.
     */
    private double[] findPlayableBounds() {
        List<Integer> open = new ArrayList<>();
        collectOpenLeaves(modelHead[0], open, 0);
        double[] fallback = {modelBox[0], modelBox[1], modelBox[2], modelBox[3], modelBox[4], modelBox[5]};
        if (open.isEmpty()) return fallback;
        int n = open.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (x, y) -> Short.compare(leafBox[open.get(x) * 6], leafBox[open.get(y) * 6]));
        for (int i = 0; i < n; i++) {
            int li = open.get(order[i]);
            for (int j = i + 1; j < n; j++) {
                int lj = open.get(order[j]);
                if (leafBox[lj * 6] > leafBox[li * 6 + 3] + 1) break;
                if (touches(li, lj)) union(parent, order[i], order[j]);
            }
        }
        boolean[] seed = new boolean[n];
        boolean any = false;
        for (Map<String, String> e : entities) {
            String cls = e.getOrDefault("classname", "");
            if (!humanSpawnClasses().contains(cls) && !zombieSpawnClasses().contains(cls)) continue;
            double[] o = vector(e.get("origin"));
            int leaf = leafAt(0, o[0], o[1], o[2] + 16);
            int idx = open.indexOf(leaf);
            if (idx >= 0) {
                seed[find(parent, idx)] = true;
                any = true;
            }
        }
        if (!any) return fallback;
        double[] box = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (int i = 0; i < n; i++) {
            if (!seed[find(parent, i)]) continue;
            int l = open.get(i);
            for (int k = 0; k < 3; k++) {
                box[k] = Math.min(box[k], leafBox[l * 6 + k]);
                box[k + 3] = Math.max(box[k + 3], leafBox[l * 6 + 3 + k]);
            }
        }
        // Terrain can sit right on the edge of the open space; take it in if it touches the box.
        for (Triangle t : terrain) {
            for (double[] v : new double[][]{t.a(), t.b(), t.c()}) {
                if (v[0] >= box[0] - 64 && v[0] <= box[3] + 64 && v[1] >= box[1] - 64 && v[1] <= box[4] + 64) {
                    box[2] = Math.min(box[2], v[2]);
                    box[5] = Math.max(box[5], v[2]);
                }
            }
        }
        double pad = 64;
        return new double[]{box[0] - pad, box[1] - pad, box[2] - pad, box[3] + pad, box[4] + pad, box[5] + pad};
    }

    private void collectOpenLeaves(int node, List<Integer> out, int depth) {
        if (depth > 4096) return;
        if (node < 0) {
            int leaf = -node - 1;
            if (leaf < leafContents.length && (leafContents[leaf] & SOLIDISH) == 0) out.add(leaf);
            return;
        }
        if (node >= nodePlane.length) return;
        collectOpenLeaves(nodeChildren[node * 2], out, depth + 1);
        collectOpenLeaves(nodeChildren[node * 2 + 1], out, depth + 1);
    }

    private boolean touches(int a, int c) {
        for (int k = 0; k < 3; k++) {
            if (leafBox[a * 6 + k] > leafBox[c * 6 + 3 + k] + 1 || leafBox[c * 6 + k] > leafBox[a * 6 + 3 + k] + 1) return false;
        }
        return true;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int c) {
        parent[find(parent, a)] = find(parent, c);
    }

    // ---------------------------------------------------------------- point tests

    private int leafAt(int model, double x, double y, double z) {
        int node = modelHead[model];
        int guard = 0;
        while (node >= 0) {
            if (node >= nodePlane.length || ++guard > 4096) return -1;
            int p = nodePlane[node];
            double d = planeNormal[p * 3] * x + planeNormal[p * 3 + 1] * y + planeNormal[p * 3 + 2] * z - planeDist[p];
            node = nodeChildren[node * 2 + (d >= 0 ? 0 : 1)];
        }
        return -node - 1;
    }

    private boolean insideBrush(int brush, double x, double y, double z) {
        int first = brushFirstSide[brush];
        for (int s = first; s < first + brushNumSides[brush]; s++) {
            int p = sidePlane[s];
            if (planeNormal[p * 3] * x + planeNormal[p * 3 + 1] * y + planeNormal[p * 3 + 2] * z - planeDist[p] > 0.01) return false;
        }
        return true;
    }

    @Override
    public byte kindAt(int model, double x, double y, double z) {
        int leaf = leafAt(model, x, y, z);
        if (leaf < 0 || leaf >= leafContents.length) return 1;
        int contents = leafContents[leaf];
        if ((contents & SOLIDISH) != 0) return 1;
        int first = leafFirstBrush[leaf];
        for (int i = first; i < first + leafNumBrushes[leaf] && i < leafBrushes.length; i++) {
            int brush = leafBrushes[i];
            if (brush < brushContents.length && insideBrush(brush, x, y, z)) contents |= brushContents[brush];
        }
        if ((contents & SOLIDISH) != 0) return 1;
        if ((contents & CONTENTS_LADDER) != 0) return 4;
        if ((contents & CONTENTS_PLAYERCLIP) != 0) return 5;
        if ((contents & (CONTENTS_WATER | CONTENTS_SLIME)) != 0) return 2;
        return 0;
    }

    @Override
    public int uniformRegion(double x, double y, double z) {
        int leaf = leafAt(0, x, y, z);
        if (leaf < 0 || leaf >= leafContents.length) return -1;
        return (leafContents[leaf] & SOLIDISH) != 0 || leafNumBrushes[leaf] == 0 ? leaf : -1;
    }

    // ---------------------------------------------------------------- faces

    private int textureOfFace(int face) {
        int ti = faceTexinfo[face];
        if (ti < 0 || ti >= texinfoTexdata.length) return -1;
        int td = texinfoTexdata[ti];
        return td >= 0 && td < textureNames.length ? td : -1;
    }

    @Override
    public int faceTexture(int face) {
        if (faceDisp[face] >= 0) return -1; // drawn as terrain instead
        int ti = faceTexinfo[face];
        if (ti >= 0 && ti < texinfoFlags.length && (texinfoFlags[ti] & SURF_NODRAW) != 0) return -1;
        return textureOfFace(face);
    }

    public int faceSideRaw(int face) {
        return faceSide[face];
    }

    /**
     * Unlike GoldSrc, a Source face's plane already faces out of the solid; `side` only says it's
     * the flipped twin of the node's plane. Flipping by it points a third of the faces into walls.
     */
    @Override
    public double[] faceNormal(int face) {
        int p = facePlane[face];
        return new double[]{planeNormal[p * 3], planeNormal[p * 3 + 1], planeNormal[p * 3 + 2]};
    }

    @Override
    public double[] faceVertices(int face) {
        int count = faceNumEdges[face];
        double[] out = new double[count * 3];
        for (int i = 0; i < count; i++) {
            int se = surfedges[faceFirstEdge[face] + i];
            int v = se >= 0 ? edges[se * 2] : edges[-se * 2 + 1];
            out[i * 3] = vertices[v * 3];
            out[i * 3 + 1] = vertices[v * 3 + 1];
            out[i * 3 + 2] = vertices[v * 3 + 2];
        }
        return out;
    }

    // ---------------------------------------------------------------- the rest of MapData

    @Override public String engine() { return "Source (v" + version + ")"; }
    @Override public double[] bounds() { return playable.clone(); }
    @Override public int modelCount() { return modelHead.length; }

    @Override
    public double[] modelBounds(int m) {
        return new double[]{modelBox[m * 6], modelBox[m * 6 + 1], modelBox[m * 6 + 2], modelBox[m * 6 + 3], modelBox[m * 6 + 4], modelBox[m * 6 + 5]};
    }

    @Override public int firstFace(int model) { return modelFirstFace[model]; }
    @Override public int numFaces(int model) { return modelNumFaces[model]; }
    @Override public List<Triangle> extraSurfaces() { return terrain; }

    @Override
    public float[] faceTexAxes(int face) {
        int ti = faceTexinfo[face];
        if (ti < 0 || ti >= texinfoTexdata.length) return null;
        return java.util.Arrays.copyOfRange(texinfoAxes, ti * 8, ti * 8 + 8);
    }

    @Override
    public int[] textureSize(int texture) {
        int w = textureSizes[texture * 2], h = textureSizes[texture * 2 + 1];
        return w > 0 && h > 0 ? new int[]{w, h} : null;
    }
    @Override public String[] textureNames() { return textureNames; }
    @Override public int[] textureColors() { return textureColors; }
    @Override public List<Map<String, String>> entities() { return entities; }
    /** CS:S/CS:GO teams first; survivor games (L4D2, Contagion) and plain HL2 maps fall back to their own. */
    @Override
    public List<String> humanSpawnClasses() {
        return List.of("info_player_counterterrorist", "info_survivor_position", "info_player_survivor", "info_player_start");
    }

    @Override
    public List<String> zombieSpawnClasses() {
        return List.of("info_player_terrorist", "info_zombie_spawn", "info_player_zombie");
    }
    @Override public double spawnFeetOffset() { return 0; }
    @Override public boolean isSky(int texture) { return textureNames[texture].startsWith("tools/toolsskybox"); }
    @Override public List<Prop> staticProps() { return staticProps; }
    @Override public byte[] packedFiles() { return pakfile; }

    /**
     * prop_static lives in the game lump under "sprp": a table of model names, a leaf list, then one
     * record per prop. Records grew over the versions but always start with origin, angles, model
     * index, leaf range and the solid flag, and their size follows from the lump length.
     */
    private void readStaticProps() {
        if (len[L_GAME_LUMP] < 4) return;
        int g = ofs[L_GAME_LUMP];
        int count = b.getInt(g);
        for (int i = 0; i < count && i < 64; i++) {
            int e = g + 4 + i * 16;
            if (b.getInt(e) != GAMELUMP_STATIC_PROPS) continue;
            int fo = b.getInt(e + 8), fl = b.getInt(e + 12);
            // Absolute on PC; some builds store it relative to the game lump.
            if (fo < HEADER || (long) fo + fl > b.capacity()) fo += g;
            if (fo < 0 || (long) fo + fl > b.capacity() || fl < 12) return;
            try {
                int dict = b.getInt(fo);
                String[] names = new String[dict];
                for (int k = 0; k < dict; k++) {
                    int at = fo + 4 + k * 128;
                    int end = at;
                    while (end < at + 128 && b.get(end) != 0) end++;
                    names[k] = new String(b.array(), at, end - at, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT).replace('\\', '/');
                }
                int p = fo + 4 + dict * 128;
                int leafs = b.getInt(p);
                p += 4 + leafs * 2;
                int props = b.getInt(p);
                p += 4;
                if (props <= 0) return;
                int size = (fo + fl - p) / props;
                if (size < 32) return;
                for (int k = 0; k < props; k++) {
                    int r = p + k * size;
                    int type = b.getShort(r + 24) & 0xFFFF;
                    if (type >= names.length) continue;
                    double[] origin = {b.getFloat(r), b.getFloat(r + 4), b.getFloat(r + 8)};
                    double[] angles = {b.getFloat(r + 12), b.getFloat(r + 16), b.getFloat(r + 20)};
                    boolean solid = b.get(r + 30) != 0;
                    staticProps.add(new Prop(names[type], origin, angles, solid));
                }
            } catch (IndexOutOfBoundsException ignored) {
                // a damaged prop table just means no props
            }
            return;
        }
    }

    private static double[] vector(String s) {
        double[] out = new double[3];
        if (s == null) return out;
        String[] parts = s.trim().split("\\s+");
        for (int i = 0; i < 3 && i < parts.length; i++) {
            try {
                out[i] = Double.parseDouble(parts[i]);
            } catch (NumberFormatException ignored) {
                out[i] = 0;
            }
        }
        return out;
    }
}
