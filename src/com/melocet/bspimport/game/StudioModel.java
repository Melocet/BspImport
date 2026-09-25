package com.melocet.bspimport.game;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A Source model's shape: the triangles of its first level of detail and default body, plus the
 * material each triangle uses. Built from three files: .mdl (parts, meshes, materials), .vvd
 * (vertex positions) and .dx90.vtx (triangle indices).
 */
public final class StudioModel {

    /** x,y,z of every triangle corner, 9 floats per triangle, in model space. */
    public final float[] triangles;
    /** Material index per triangle, into {@link #materials}. */
    public final int[] triangleMaterial;
    /** Material paths without extension, e.g. "materials/models/props_rpd/desk01". */
    public final List<String> materials;
    /** Model-space bounds: min x, y, z, max x, y, z. */
    public final float[] bounds;
    private int rejected;

    private StudioModel(float[] triangles, int[] triangleMaterial, List<String> materials, float[] bounds) {
        this.triangles = triangles;
        this.triangleMaterial = triangleMaterial;
        this.materials = materials;
        this.bounds = bounds;
    }

    /** @param path e.g. "models/props_rpd/desk01.mdl"; returns null when the files are missing or unreadable */
    public static StudioModel load(GameFiles files, String path) {
        String base = path.toLowerCase(Locale.ROOT).replace('\\', '/');
        if (base.endsWith(".mdl")) base = base.substring(0, base.length() - 4);
        byte[] mdl = files.read(base + ".mdl");
        byte[] vvd = files.read(base + ".vvd");
        byte[] vtx = files.read(base + ".dx90.vtx");
        if (vtx == null) vtx = files.read(base + ".vtx");
        if (mdl == null || vvd == null || vtx == null) return null;
        // Strip group headers are 25 bytes in older VTX files and 33 in newer ones. Try both and keep
        // the reading where every index lands inside its tables.
        StudioModel best = null;
        for (int size : new int[]{25, 33}) {
            try {
                StudioModel m = parse(mdl, vvd, vtx, size);
                if (best == null || m.rejected < best.rejected) best = m;
                if (m.rejected == 0) break;
            } catch (RuntimeException ignored) {
                // try the other layout
            }
        }
        return best;
    }

    private static ByteBuffer le(byte[] b) {
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static StudioModel parse(byte[] mdlBytes, byte[] vvdBytes, byte[] vtxBytes, int stripGroupSize) {
        ByteBuffer mdl = le(mdlBytes);
        if (mdl.getInt(0) != 0x54534449) throw new IllegalStateException("not an MDL"); // "IDST"

        // --- materials: texture names searched in the cd texture folders, skin family 0
        int numTextures = mdl.getInt(204), textureIndex = mdl.getInt(208);
        int numCdTextures = mdl.getInt(212), cdTextureIndex = mdl.getInt(216);
        int numSkinRef = mdl.getInt(220), skinIndex = mdl.getInt(228);
        List<String> dirs = new ArrayList<>();
        for (int i = 0; i < numCdTextures; i++) dirs.add(cstring(mdlBytes, mdl.getInt(cdTextureIndex + i * 4)));
        List<String> textureNames = new ArrayList<>();
        for (int i = 0; i < numTextures; i++) {
            int t = textureIndex + i * 64;
            textureNames.add(cstring(mdlBytes, t + mdl.getInt(t)));
        }
        List<String> materials = new ArrayList<>();
        for (String tex : textureNames) {
            // The caller tries each folder; keep them all, separated by '|'.
            StringBuilder sb = new StringBuilder();
            for (String d : dirs) {
                if (!sb.isEmpty()) sb.append('|');
                sb.append(("materials/" + d + tex).toLowerCase(Locale.ROOT).replace('\\', '/').replace("//", "/"));
            }
            if (sb.isEmpty()) sb.append(("materials/" + tex).toLowerCase(Locale.ROOT).replace('\\', '/'));
            materials.add(sb.toString());
        }

        // --- vertices: level 0, rebuilt through the fixup table when there is one
        ByteBuffer vvd = le(vvdBytes);
        if (vvd.getInt(0) != 0x56534449) throw new IllegalStateException("not a VVD"); // "IDSV"
        int lod0Count = vvd.getInt(16);
        int numFixups = vvd.getInt(48), fixupStart = vvd.getInt(52), vertexStart = vvd.getInt(56);
        float[] verts = new float[lod0Count * 3];
        int written = 0;
        if (numFixups > 0) {
            for (int f = 0; f < numFixups; f++) {
                int fo = fixupStart + f * 12;
                int lod = vvd.getInt(fo), src = vvd.getInt(fo + 4), count = vvd.getInt(fo + 8);
                if (lod < 0) continue;
                for (int v = 0; v < count && written < lod0Count; v++) copyVertex(vvd, vertexStart, src + v, verts, written++);
            }
        } else {
            for (int v = 0; v < lod0Count; v++) copyVertex(vvd, vertexStart, v, verts, written++);
        }

        // --- triangles: walk MDL body parts / models / meshes alongside the VTX tree
        ByteBuffer vtx = le(vtxBytes);
        int numBodyParts = mdl.getInt(232), bodyPartIndex = mdl.getInt(236);
        int vtxBodyParts = vtx.getInt(28), vtxBodyOffset = vtx.getInt(32);
        List<float[]> tris = new ArrayList<>();
        List<Integer> triMat = new ArrayList<>();
        float[] bounds = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        int rejected = 0;
        for (int bp = 0; bp < Math.min(numBodyParts, vtxBodyParts); bp++) {
            int mbp = bodyPartIndex + bp * 16;
            int numModels = mdl.getInt(mbp + 4), modelIndex = mdl.getInt(mbp + 12);
            int vbp = vtxBodyOffset + bp * 8;
            int vNumModels = vtx.getInt(vbp), vModelOffset = vtx.getInt(vbp + 4);
            if (numModels == 0 || vNumModels == 0) continue;
            // Model 0 is the default body in each part.
            int mm = mbp + modelIndex;
            int numMeshes = mdl.getInt(mm + 72), meshIndex = mdl.getInt(mm + 76), modelVertexIndex = mdl.getInt(mm + 84);
            int vm = vbp + vModelOffset;
            int vLodOffset = vtx.getInt(vm + 4);
            int lod = vm + vLodOffset; // level 0
            int vNumMeshes = vtx.getInt(lod), vMeshOffset = vtx.getInt(lod + 4);
            for (int me = 0; me < Math.min(numMeshes, vNumMeshes); me++) {
                int mmesh = mm + meshIndex + me * 116;
                int material = mdl.getInt(mmesh);
                int meshVertexOffset = mdl.getInt(mmesh + 12);
                int texture = skinTexture(mdl, skinIndex, numSkinRef, material);
                int vmesh = lod + vMeshOffset + me * 9;
                int numStripGroups = vtx.getInt(vmesh), sgOffset = vtx.getInt(vmesh + 4);
                for (int sg = 0; sg < numStripGroups; sg++) {
                    int g = vmesh + sgOffset + sg * stripGroupSize;
                    int numVerts = vtx.getInt(g), vertOffset = vtx.getInt(g + 4);
                    int numIndices = vtx.getInt(g + 8), indexOffset = vtx.getInt(g + 12);
                    for (int i = 0; i + 2 < numIndices; i += 3) {
                        float[] t = new float[9];
                        boolean ok = true;
                        for (int c = 0; c < 3; c++) {
                            int idx = vtx.getShort(g + indexOffset + (i + c) * 2) & 0xFFFF;
                            if (idx >= numVerts) { ok = false; break; }
                            int orig = vtx.getShort(g + vertOffset + idx * 9 + 4) & 0xFFFF;
                            int vi = modelVertexIndex / 48 + meshVertexOffset + orig;
                            if (vi < 0 || vi >= written) { ok = false; break; }
                            for (int k = 0; k < 3; k++) {
                                float value = verts[vi * 3 + k];
                                t[c * 3 + k] = value;
                                bounds[k] = Math.min(bounds[k], value);
                                bounds[k + 3] = Math.max(bounds[k + 3], value);
                            }
                        }
                        if (!ok) {
                            rejected++;
                            continue;
                        }
                        tris.add(t);
                        triMat.add(texture);
                    }
                }
            }
        }
        if (tris.isEmpty()) throw new IllegalStateException("no triangles");
        float[] flat = new float[tris.size() * 9];
        int[] mats = new int[tris.size()];
        for (int i = 0; i < tris.size(); i++) {
            System.arraycopy(tris.get(i), 0, flat, i * 9, 9);
            int m = triMat.get(i);
            mats[i] = m >= 0 && m < materials.size() ? m : 0;
        }
        if (materials.isEmpty()) materials.add("");
        StudioModel model = new StudioModel(flat, mats, materials, bounds);
        model.rejected = rejected;
        return model;
    }

    private static void copyVertex(ByteBuffer vvd, int start, int index, float[] out, int slot) {
        int o = start + index * 48 + 16;
        out[slot * 3] = vvd.getFloat(o);
        out[slot * 3 + 1] = vvd.getFloat(o + 4);
        out[slot * 3 + 2] = vvd.getFloat(o + 8);
    }

    private static int skinTexture(ByteBuffer mdl, int skinIndex, int numSkinRef, int material) {
        if (material < 0 || material >= numSkinRef) return material;
        return mdl.getShort(skinIndex + material * 2);
    }

    private static String cstring(byte[] b, int at) {
        int end = at;
        while (end < b.length && b[end] != 0) end++;
        return new String(b, at, end - at, StandardCharsets.ISO_8859_1);
    }
}
