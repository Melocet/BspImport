package com.melocet.bspimport.bsp;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A GoldSrc (Half-Life / CS 1.6) map, BSP version 30. Only what building it in blocks needs:
 * the hull 0 node tree for "is this point inside a wall", the faces and their textures for what the
 * walls look like, the brush models of entities, and the entity list.
 */
public final class BspFile implements MapData {

    public static final int CONTENTS_EMPTY = -1;
    public static final int CONTENTS_SOLID = -2;
    public static final int CONTENTS_WATER = -3;
    public static final int CONTENTS_SLIME = -4;
    public static final int CONTENTS_LAVA = -5;
    public static final int CONTENTS_SKY = -6;

    private static final Set<String> HIDDEN_TEXTURES = Set.of("aaatrigger", "clip", "null", "origin", "hint", "skip", "bevel");

    private static final int LUMP_ENTITIES = 0;
    private static final int LUMP_PLANES = 1;
    private static final int LUMP_TEXTURES = 2;
    private static final int LUMP_VERTICES = 3;
    private static final int LUMP_NODES = 5;
    private static final int LUMP_TEXINFO = 6;
    private static final int LUMP_FACES = 7;
    private static final int LUMP_LEAVES = 10;
    private static final int LUMP_EDGES = 12;
    private static final int LUMP_SURFEDGES = 13;
    private static final int LUMP_MODELS = 14;

    // planes
    float[] planeNormal;
    float[] planeDist;
    int[] planeType;
    // hull 0 tree
    int[] nodePlane;
    int[] nodeChildren;
    int[] leafContents;
    // geometry
    float[] vertices;
    int[] edges;
    int[] surfedges;
    int[] facePlane;
    int[] faceSide;
    int[] faceFirstEdge;
    int[] faceNumEdges;
    int[] faceTexinfo;
    int[] texinfoMiptex;
    float[] texinfoAxes;
    int[] textureSizes;
    TextureImage[] textureImages;
    // textures: name, and the average color when the map carries the pixels itself (-1 = in a WAD)
    public String[] textureNames;
    public int[] textureColors;
    // models: 0 is the world, the rest belong to brush entities ("model" "*N")
    float[] modelMins;
    float[] modelMaxs;
    int[] modelHeadNode;
    int[] modelFirstFace;
    int[] modelNumFaces;

    public List<Map<String, String>> entities;

    public static BspFile read(Path file) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
        int version = b.getInt(0);
        if (version != 30) {
            throw new IOException("Not a GoldSrc map (BSP version " + version + ", expected 30)");
        }
        BspFile f = new BspFile();
        int[] ofs = new int[15];
        int[] len = new int[15];
        for (int i = 0; i < 15; i++) {
            ofs[i] = b.getInt(4 + i * 8);
            len[i] = b.getInt(8 + i * 8);
            if (ofs[i] < 0 || len[i] < 0 || (long) ofs[i] + len[i] > b.capacity()) {
                throw new IOException("Damaged map: lump " + i + " points outside the file");
            }
        }

        String entityText = new String(b.array(), ofs[LUMP_ENTITIES], len[LUMP_ENTITIES], StandardCharsets.ISO_8859_1);
        f.entities = EntityParser.parse(entityText);

        int n = len[LUMP_PLANES] / 20;
        f.planeNormal = new float[n * 3];
        f.planeDist = new float[n];
        f.planeType = new int[n];
        for (int i = 0; i < n; i++) {
            int o = ofs[LUMP_PLANES] + i * 20;
            f.planeNormal[i * 3] = b.getFloat(o);
            f.planeNormal[i * 3 + 1] = b.getFloat(o + 4);
            f.planeNormal[i * 3 + 2] = b.getFloat(o + 8);
            f.planeDist[i] = b.getFloat(o + 12);
            f.planeType[i] = b.getInt(o + 16);
        }

        n = len[LUMP_NODES] / 24;
        f.nodePlane = new int[n];
        f.nodeChildren = new int[n * 2];
        for (int i = 0; i < n; i++) {
            int o = ofs[LUMP_NODES] + i * 24;
            f.nodePlane[i] = b.getInt(o);
            f.nodeChildren[i * 2] = b.getShort(o + 4);
            f.nodeChildren[i * 2 + 1] = b.getShort(o + 6);
        }

        n = len[LUMP_LEAVES] / 28;
        f.leafContents = new int[n];
        for (int i = 0; i < n; i++) f.leafContents[i] = b.getInt(ofs[LUMP_LEAVES] + i * 28);

        n = len[LUMP_VERTICES] / 12;
        f.vertices = new float[n * 3];
        for (int i = 0; i < n * 3; i++) f.vertices[i] = b.getFloat(ofs[LUMP_VERTICES] + i * 4);

        n = len[LUMP_EDGES] / 4;
        f.edges = new int[n * 2];
        for (int i = 0; i < n * 2; i++) f.edges[i] = b.getShort(ofs[LUMP_EDGES] + i * 2) & 0xFFFF;

        n = len[LUMP_SURFEDGES] / 4;
        f.surfedges = new int[n];
        for (int i = 0; i < n; i++) f.surfedges[i] = b.getInt(ofs[LUMP_SURFEDGES] + i * 4);

        n = len[LUMP_FACES] / 20;
        f.facePlane = new int[n];
        f.faceSide = new int[n];
        f.faceFirstEdge = new int[n];
        f.faceNumEdges = new int[n];
        f.faceTexinfo = new int[n];
        for (int i = 0; i < n; i++) {
            int o = ofs[LUMP_FACES] + i * 20;
            f.facePlane[i] = b.getShort(o) & 0xFFFF;
            f.faceSide[i] = b.getShort(o + 2);
            f.faceFirstEdge[i] = b.getInt(o + 4);
            f.faceNumEdges[i] = b.getShort(o + 8);
            f.faceTexinfo[i] = b.getShort(o + 10);
        }

        n = len[LUMP_TEXINFO] / 40;
        f.texinfoMiptex = new int[n];
        f.texinfoAxes = new float[n * 8];
        for (int i = 0; i < n; i++) {
            f.texinfoMiptex[i] = b.getInt(ofs[LUMP_TEXINFO] + i * 40 + 32);
            for (int k = 0; k < 8; k++) f.texinfoAxes[i * 8 + k] = b.getFloat(ofs[LUMP_TEXINFO] + i * 40 + k * 4);
        }

        int t = ofs[LUMP_TEXTURES];
        int count = len[LUMP_TEXTURES] >= 4 ? b.getInt(t) : 0;
        f.textureNames = new String[count];
        f.textureColors = new int[count];
        f.textureSizes = new int[count * 2];
        f.textureImages = new TextureImage[count];
        for (int i = 0; i < count; i++) {
            int rel = b.getInt(t + 4 + i * 4);
            if (rel < 0) {
                f.textureNames[i] = "";
                f.textureColors[i] = -1;
                continue;
            }
            int mip = t + rel;
            f.textureNames[i] = Miptex.name(b, mip);
            f.textureColors[i] = Miptex.averageColor(b, mip, f.textureNames[i]);
            f.textureSizes[i * 2] = b.getInt(mip + 16);
            f.textureSizes[i * 2 + 1] = b.getInt(mip + 20);
            f.textureImages[i] = Miptex.image(b, mip, f.textureNames[i]);
        }

        n = len[LUMP_MODELS] / 64;
        f.modelMins = new float[n * 3];
        f.modelMaxs = new float[n * 3];
        f.modelHeadNode = new int[n];
        f.modelFirstFace = new int[n];
        f.modelNumFaces = new int[n];
        for (int i = 0; i < n; i++) {
            int o = ofs[LUMP_MODELS] + i * 64;
            for (int k = 0; k < 3; k++) {
                f.modelMins[i * 3 + k] = b.getFloat(o + k * 4);
                f.modelMaxs[i * 3 + k] = b.getFloat(o + 12 + k * 4);
            }
            f.modelHeadNode[i] = b.getInt(o + 36);
            f.modelFirstFace[i] = b.getInt(o + 56);
            f.modelNumFaces[i] = b.getInt(o + 60);
        }
        if (n == 0) throw new IOException("Damaged map: no world model");
        return f;
    }

    /** What is at this point, in the tree of the given model (0 = world). */
    public int contents(int model, double x, double y, double z) {
        int node = modelHeadNode[model];
        int guard = 0;
        while (node >= 0) {
            if (node >= nodePlane.length || ++guard > 4096) return CONTENTS_SOLID;
            int p = nodePlane[node];
            double d;
            int type = planeType[p];
            if (type < 3) {
                d = (type == 0 ? x : type == 1 ? y : z) - planeDist[p];
            } else {
                d = planeNormal[p * 3] * x + planeNormal[p * 3 + 1] * y + planeNormal[p * 3 + 2] * z - planeDist[p];
            }
            node = nodeChildren[node * 2 + (d >= 0 ? 0 : 1)];
        }
        int leaf = -node - 1;
        return leaf < leafContents.length ? leafContents[leaf] : CONTENTS_SOLID;
    }

    // ---------------------------------------------------------------- MapData

    @Override public String engine() { return "GoldSrc"; }

    @Override
    public double[] bounds() {
        return new double[]{modelMins[0], modelMins[1], modelMins[2], modelMaxs[0], modelMaxs[1], modelMaxs[2]};
    }

    @Override
    public byte kindAt(int model, double x, double y, double z) {
        int c = contents(model, x, y, z);
        if (c == CONTENTS_SOLID) return 1;
        if (c == CONTENTS_SKY) return 3;
        if (c == CONTENTS_WATER || c == CONTENTS_SLIME || c == CONTENTS_LAVA || (c <= -9 && c >= -14)) return 2;
        return 0;
    }

    /** Hull 0 leaves are uniform: the leaf index is the region. */
    @Override
    public int uniformRegion(double x, double y, double z) {
        int node = modelHeadNode[0];
        int guard = 0;
        while (node >= 0) {
            if (node >= nodePlane.length || ++guard > 4096) return -1;
            int p = nodePlane[node];
            int type = planeType[p];
            double d = type < 3 ? (type == 0 ? x : type == 1 ? y : z) - planeDist[p]
                    : planeNormal[p * 3] * x + planeNormal[p * 3 + 1] * y + planeNormal[p * 3 + 2] * z - planeDist[p];
            node = nodeChildren[node * 2 + (d >= 0 ? 0 : 1)];
        }
        return -node - 1;
    }

    @Override public int modelCount() { return modelHeadNode.length; }

    @Override
    public double[] modelBounds(int m) {
        return new double[]{modelMins[m * 3], modelMins[m * 3 + 1], modelMins[m * 3 + 2],
                modelMaxs[m * 3], modelMaxs[m * 3 + 1], modelMaxs[m * 3 + 2]};
    }

    @Override public int firstFace(int model) { return modelFirstFace[model]; }
    @Override public int numFaces(int model) { return modelNumFaces[model]; }
    @Override public List<Triangle> extraSurfaces() { return List.of(); }

    @Override
    public float[] faceTexAxes(int face) {
        int ti = faceTexinfo[face];
        if (ti < 0 || ti >= texinfoMiptex.length) return null;
        return java.util.Arrays.copyOfRange(texinfoAxes, ti * 8, ti * 8 + 8);
    }

    @Override
    public int[] textureSize(int texture) {
        int w = textureSizes[texture * 2], h = textureSizes[texture * 2 + 1];
        return w > 0 && h > 0 ? new int[]{w, h} : null;
    }

    @Override
    public TextureImage textureImage(int texture) {
        return textureImages[texture];
    }
    @Override public String[] textureNames() { return textureNames; }
    @Override public int[] textureColors() { return textureColors; }
    @Override public List<Map<String, String>> entities() { return entities; }
    @Override public List<String> humanSpawnClasses() { return List.of("info_player_start"); }
    @Override public List<String> zombieSpawnClasses() { return List.of("info_player_deathmatch"); }
    @Override public double spawnFeetOffset() { return 36; }
    @Override public boolean isSky(int texture) { return "sky".equals(textureNames[texture]); }
    @Override public List<Prop> staticProps() { return List.of(); }
    @Override public byte[] packedFiles() { return null; }

    /** The texture index of a face, or -1 for none or a tool texture. */
    @Override
    public int faceTexture(int face) {
        int mt = rawFaceTexture(face);
        return mt >= 0 && HIDDEN_TEXTURES.contains(textureNames[mt]) ? -1 : mt;
    }

    /** The texture index of a face including tool textures, or -1. */
    public int rawFaceTexture(int face) {
        int ti = faceTexinfo[face];
        if (ti < 0 || ti >= texinfoMiptex.length) return -1;
        int mt = texinfoMiptex[ti];
        return mt >= 0 && mt < textureNames.length ? mt : -1;
    }

    @Override
    public double[] faceNormal(int face) {
        int p = facePlane[face];
        double s = faceSide[face] != 0 ? -1 : 1;
        return new double[]{planeNormal[p * 3] * s, planeNormal[p * 3 + 1] * s, planeNormal[p * 3 + 2] * s};
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
}
