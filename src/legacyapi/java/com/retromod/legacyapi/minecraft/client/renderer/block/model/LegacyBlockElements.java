/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer.block.model;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.resources.model.cuboid.CuboidFace;
import net.minecraft.client.resources.model.cuboid.CuboidModelElement;
import net.minecraft.client.resources.model.cuboid.CuboidRotation;
import net.minecraft.core.Direction;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/** The old block model element, face and rotation members, over the records Minecraft 26.1 renamed them to. */
public final class LegacyBlockElements {

    // 26.3 replaced the element's shade flag with a shading direction, so the running host's shape is read once.
    private static volatile Method shade;
    private static volatile Method shadeOverride;
    private static volatile Constructor<?> constructor;
    private static volatile boolean shapeRead;

    private LegacyBlockElements() {}

    // ---- the element ----

    /** {@code element.from}: the element's own vector, which old code could change, or a copy when the host keeps an unchangeable one. */
    public static Vector3f from(CuboidModelElement element) {
        return mutable(element.from());
    }

    /** {@code element.to}. */
    public static Vector3f to(CuboidModelElement element) {
        return mutable(element.to());
    }

    private static Vector3f mutable(Vector3fc vector) {
        return vector instanceof Vector3f own ? own : new Vector3f(vector);
    }

    /** {@code element.faces}: the element's own faces, where one that named no corners reads them from the element's bounds, as in 1.20.1. */
    public static Map<Direction, CuboidFace> faces(CuboidModelElement element) {
        // Most faces name their corners, and then the element's own map is the answer.
        for (CuboidFace face : element.faces().values()) {
            if (face != null && face.uvs() == null) return new FacesWithBoundsUvs(element);
        }
        return element.faces();
    }

    /** The face with the corners 1.20.1 gave a face that named none, or the face itself when it names its own. */
    static CuboidFace withBoundsUvs(CuboidModelElement element, Direction side, CuboidFace face) {
        if (face == null || face.uvs() != null) return face;
        Vector3fc from = element.from();
        Vector3fc to = element.to();
        // A face spans two of the element's axes. Texture rows run down where y and the bottom's z run up,
        // and north and east face back along their width, so those spans count from 16.
        boolean acrossX = side != Direction.WEST && side != Direction.EAST;
        float[] u = span(acrossX ? from.x() : from.z(), acrossX ? to.x() : to.z(), side == Direction.NORTH || side == Direction.EAST);
        boolean flat = side == Direction.DOWN || side == Direction.UP;
        float[] v = span(flat ? from.z() : from.y(), flat ? to.z() : to.y(), side != Direction.UP);
        CuboidFace.UVs corners = new CuboidFace.UVs(u[0], v[0], u[1], v[1]);
        return new CuboidFace(face.cullForDirection(), face.tintIndex(), face.texture(), corners, face.rotation());
    }

    private static float[] span(float low, float high, boolean fromSixteen) {
        return fromSixteen ? new float[]{16 - high, 16 - low} : new float[]{low, high};
    }

    // The element's faces as old code read them: reads fill in corners, and changes go to the element's own map.
    private static final class FacesWithBoundsUvs extends AbstractMap<Direction, CuboidFace> {
        private final CuboidModelElement element;

        FacesWithBoundsUvs(CuboidModelElement element) {
            this.element = element;
        }

        @Override
        public CuboidFace get(Object side) {
            CuboidFace face = element.faces().get(side);
            return side instanceof Direction direction ? withBoundsUvs(element, direction, face) : face;
        }

        @Override
        public boolean containsKey(Object side) {
            return element.faces().containsKey(side);
        }

        @Override
        public CuboidFace put(Direction side, CuboidFace face) {
            return element.faces().put(side, face);
        }

        @Override
        public CuboidFace remove(Object side) {
            return element.faces().remove(side);
        }

        @Override
        public Set<Entry<Direction, CuboidFace>> entrySet() {
            return new AbstractSet<>() {
                @Override
                public int size() {
                    return element.faces().size();
                }

                @Override
                public Iterator<Entry<Direction, CuboidFace>> iterator() {
                    Iterator<Entry<Direction, CuboidFace>> own = element.faces().entrySet().iterator();
                    return new Iterator<>() {
                        @Override
                        public boolean hasNext() {
                            return own.hasNext();
                        }

                        @Override
                        public Entry<Direction, CuboidFace> next() {
                            return new FaceEntry(element, own.next());
                        }

                        @Override
                        public void remove() {
                            own.remove();
                        }
                    };
                }
            };
        }
    }

    // One face of the view: its value has corners, and a new value goes to the element's own entry.
    private static final class FaceEntry extends AbstractMap.SimpleEntry<Direction, CuboidFace> {
        private final transient Map.Entry<Direction, CuboidFace> own;

        FaceEntry(CuboidModelElement element, Map.Entry<Direction, CuboidFace> own) {
            super(own.getKey(), withBoundsUvs(element, own.getKey(), own.getValue()));
            this.own = own;
        }

        @Override
        public CuboidFace setValue(CuboidFace face) {
            super.setValue(face);
            return own.setValue(face);
        }
    }

    /** {@code element.shade}: false for an element drawn without directional shading. */
    public static boolean shade(CuboidModelElement element) {
        readShape();
        try {
            if (shade != null) return (Boolean) shade.invoke(element);
            // On 26.3 an unshaded element lights every face like an upward one, and a shaded one has no override.
            return shadeOverride.invoke(element) == null;
        } catch (IllegalAccessException | InvocationTargetException failure) {
            throw new IllegalStateException("Retromod could not read an element's shading on this host", failure);
        }
    }

    /** {@code new BlockElement(from, to, faces, rotation, shade)}, which gave no light of its own. */
    public static CuboidModelElement element(Vector3f from, Vector3f to, Map<Direction, CuboidFace> faces,
            CuboidRotation rotation, boolean shaded) {
        readShape();
        // 26.3 takes a shading direction instead, and lights an unshaded element like an upward face.
        Object shading = shaded;
        if (shade == null) shading = shaded ? null : Direction.UP;
        try {
            return (CuboidModelElement) constructor.newInstance(from, to, faces, rotation, shading, 0);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Retromod could not build a model element on this host", failure);
        }
    }

    private static void readShape() {
        if (shapeRead) return;
        synchronized (LegacyBlockElements.class) {
            if (shapeRead) return;
            Class<?> type = CuboidModelElement.class;
            // A missing member here would mean a host Retromod does not know, which the next call reports.
            try {
                shade = type.getMethod("shade");
                constructor = type.getConstructor(Vector3fc.class, Vector3fc.class, Map.class, CuboidRotation.class,
                        boolean.class, int.class);
            } catch (NoSuchMethodException newerHost) {
                try {
                    shadeOverride = type.getMethod("shadeDirectionOverride");
                    constructor = type.getConstructor(Vector3fc.class, Vector3fc.class, Map.class, CuboidRotation.class,
                            Direction.class, int.class);
                } catch (NoSuchMethodException unknownHost) {
                    throw new IllegalStateException("Retromod does not know this host's model element", unknownHost);
                }
            }
            shapeRead = true;
        }
    }

    // ---- the rotation ----

    /** {@code rotation.origin()}, which returned a changeable vector. */
    public static Vector3f origin(CuboidRotation rotation) {
        return mutable(rotation.origin());
    }

    /** {@code rotation.axis()}: the one axis an old rotation turned around. */
    public static Direction.Axis axis(CuboidRotation rotation) {
        if (rotation.value() instanceof CuboidRotation.SingleAxisRotation single) return single.axis();
        // A rotation around several axes, which old models could not have, answers its first turned axis.
        CuboidRotation.EulerXYZRotation euler = (CuboidRotation.EulerXYZRotation) rotation.value();
        if (euler.x() != 0) return Direction.Axis.X;
        return euler.z() != 0 && euler.y() == 0 ? Direction.Axis.Z : Direction.Axis.Y;
    }

    /** {@code rotation.angle()}, in degrees around {@link #axis}. */
    public static float angle(CuboidRotation rotation) {
        if (rotation.value() instanceof CuboidRotation.SingleAxisRotation single) return single.angle();
        CuboidRotation.EulerXYZRotation euler = (CuboidRotation.EulerXYZRotation) rotation.value();
        return switch (axis(rotation)) {
            case X -> euler.x();
            case Y -> euler.y();
            case Z -> euler.z();
        };
    }

    /** {@code new BlockElementRotation(origin, axis, angle, rescale)}. */
    public static CuboidRotation rotation(Vector3f origin, Direction.Axis axis, float angle, boolean rescale) {
        return new CuboidRotation(origin, new CuboidRotation.SingleAxisRotation(axis, angle), rescale);
    }

    // ---- the face ----

    /** {@code new BlockElementFace(cullFace, tintIndex, texture, uv)}. */
    public static CuboidFace face(Direction cullFace, int tintIndex, String texture, BlockFaceUV uv) {
        // Corners left out are filled in from the element's bounds when Minecraft bakes the face.
        return uv == null ? new CuboidFace(cullFace, tintIndex, texture, null, com.mojang.math.Quadrant.R0)
                : new CuboidFace(cullFace, tintIndex, texture, uv.hostUvs(), uv.quadrant());
    }

    /** {@code face.uv}: the face's corners and rotation in the old form. */
    public static BlockFaceUV uv(CuboidFace face) {
        return BlockFaceUV.of(face);
    }
}
