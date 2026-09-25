package com.melocet.bspimport.bsp;

import java.util.List;
import java.util.Map;

/**
 * What the voxelizer needs from a compiled map, whatever engine made it. Kinds are the
 * VoxelGrid constants (AIR, SOLID, WATER, SKY, LADDER, INVISIBLE).
 */
public interface MapData {

    /** One triangle of a surface that isn't part of any brush (Source displacements: terrain). */
    record Triangle(double[] a, double[] b, double[] c, int texture, double[] normal) {}

    /** Engine name for messages. */
    String engine();

    /** minX, minY, minZ, maxX, maxY, maxZ of the part worth converting (the playable area). */
    double[] bounds();

    /** What is at this point in the given model's tree (0 = world). */
    byte kindAt(int model, double x, double y, double z);

    /**
     * An id for the convex region holding this point when everything in that region is the same
     * kind, or -1. Lets the voxelizer skip sampling blocks whose corners all share one region.
     */
    int uniformRegion(double x, double y, double z);

    int modelCount();

    /** mins then maxs of a brush model, in its own coordinates. */
    double[] modelBounds(int model);

    int firstFace(int model);

    int numFaces(int model);

    /** Texture index, or -1 when the face shouldn't paint anything. */
    int faceTexture(int face);

    /** Normal pointing out of the solid, into open space. */
    double[] faceNormal(int face);

    /** Corners in order, x,y,z triples. */
    double[] faceVertices(int face);

    List<Triangle> extraSurfaces();

    /**
     * How a face's texture lies on it: s axis x, y, z, s offset, then the same for t. A point's texel
     * is dot(point, axis) + offset. Null when unknown.
     */
    float[] faceTexAxes(int face);

    /** Width and height in texels that the texture axes refer to, or null. */
    int[] textureSize(int texture);

    /** The texture's pixels when the map carries them (GoldSrc textures packed in the .bsp), or null. */
    default TextureImage textureImage(int texture) {
        return null;
    }

    String[] textureNames();

    /** Average 0xRRGGBB per texture, -1 when unknown. */
    int[] textureColors();

    List<Map<String, String>> entities();

    /** Entity classes whose position becomes a human (builder) spawn, in order of preference. */
    List<String> humanSpawnClasses();

    /** Entity classes whose position becomes a zombie spawn. */
    List<String> zombieSpawnClasses();

    /** How far below a spawn's origin the feet are. */
    double spawnFeetOffset();

    /** Whether this texture is the sky. Mappers often put it on ordinary solid brushes. */
    boolean isSky(int texture);

    /** A model placed in the map: furniture, cars, lamps... */
    record Prop(String model, double[] origin, double[] angles, boolean solid) {}

    /** Props compiled into the map (Source prop_static). Entity props come from {@link #entities()}. */
    List<Prop> staticProps();

    /** Files packed into the map itself (a zip), or null. */
    byte[] packedFiles();
}
