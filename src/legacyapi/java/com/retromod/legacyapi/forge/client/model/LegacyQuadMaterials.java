/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.model;

import com.retromod.shim.common.embedded.LegacyLog;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad.MaterialInfo;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;

/** Builds and changes a baked quad's MaterialInfo, whose fields differ between Minecraft 26.2 and 26.3. */
public final class LegacyQuadMaterials {

    // Minecraft 26.2 has six fields with a shade flag. 26.3 has eight, two more item render types and a
    // shade direction in place of the flag. The helper reads the running host's own record once, so one
    // copy works on both and keeps any field it does not know when it changes a material.
    static final String SPRITE = "sprite";
    static final String LAYER = "layer";
    static final String TINT_INDEX = "tintIndex";
    static final String SHADE = "shade";
    static final String SHADE_DIRECTION = "shadeDirectionOverride";
    static final String LIGHT_EMISSION = "lightEmission";
    private static final String NOTE = "legacy quad material " + LegacyQuadMaterials.class.getName();

    private static volatile Shape shape;

    private LegacyQuadMaterials() {}

    /**
     * A material for an old quad, with the layer and item render type the host gives a quad of that sprite.
     * {@code shade} false means no directional shading, which 26.3 spells as the brightness of an upward face.
     */
    public static MaterialInfo create(TextureAtlasSprite sprite, int tintIndex, boolean shade, int lightEmission) {
        Shape host = shape();
        // The host's own factory picks the layer and item render types from the sprite's transparency.
        if (sprite != null && host.factory != null) {
            Object shadeArgument = host.factoryTakesDirection ? shadeDirection(shade) : (Object) shade;
            Object made = invoke(host.factory, new Object[]{new Material.Baked(sprite, false), sprite.transparency(),
                    tintIndex, shadeArgument, lightEmission});
            if (made instanceof MaterialInfo info) return info;
        }
        if (host.names != null) {
            Object[] values = new Object[host.names.length];
            for (int i = 0; i < values.length; i++) values[i] = defaultValue(host.types[i]);
            set(host, values, SPRITE, sprite);
            set(host, values, LAYER, ChunkSectionLayer.SOLID);
            set(host, values, TINT_INDEX, tintIndex);
            setShade(host, values, shade);
            set(host, values, LIGHT_EMISSION, lightEmission);
            MaterialInfo built = build(host, values);
            if (built != null) return built;
        }
        return lastResort(host, sprite, tintIndex, shade, lightEmission);
    }

    /** The material with another chunk layer, every other field kept. */
    public static MaterialInfo withLayer(MaterialInfo info, ChunkSectionLayer layer) {
        return with(info, LAYER, layer);
    }

    /** The material with another block light emission, 0 to 15, every other field kept. */
    public static MaterialInfo withLightEmission(MaterialInfo info, int lightEmission) {
        return with(info, LIGHT_EMISSION, Math.max(0, Math.min(15, lightEmission)));
    }

    /** The material with another tint index, every other field kept. */
    public static MaterialInfo withTintIndex(MaterialInfo info, int tintIndex) {
        return with(info, TINT_INDEX, tintIndex);
    }

    /** The material with directional shading on or off, every other field kept. */
    public static MaterialInfo withShade(MaterialInfo info, boolean shade) {
        Shape host = shape();
        if (host.names == null || info == null || shade(info) == shade) return info;
        Object[] values = values(host, info);
        if (values == null) return info;
        setShade(host, values, shade);
        MaterialInfo built = build(host, values);
        return built == null ? info : built;
    }

    /** Whether the material shades by face direction, as Forge 1.20.1's {@code BakedQuad.isShade()} answered. */
    public static boolean shade(MaterialInfo info) {
        Shape host = shape();
        if (host.names == null || info == null) return true;
        int flag = host.index(SHADE);
        if (flag >= 0) return !Boolean.FALSE.equals(read(host, info, flag));
        // 26.3: no override means the quad shades by its own direction.
        int direction = host.index(SHADE_DIRECTION);
        return direction < 0 || read(host, info, direction) == null;
    }

    /** Whether this host's materials could be read, so their fields can be changed; when not, old quads keep theirs. */
    public static boolean isKnown() {
        return shape().names != null;
    }

    /** The host material's field names in constructor order, for checks and diagnostics; empty when unknown. */
    public static String[] fieldNames() {
        String[] names = shape().names;
        return names == null ? new String[0] : names.clone();
    }

    private static MaterialInfo with(MaterialInfo info, String name, Object value) {
        Shape host = shape();
        int index = host.index(name);
        if (index < 0 || info == null) return info;
        Object[] values = values(host, info);
        if (values == null || java.util.Objects.equals(values[index], value)) return info;
        values[index] = value;
        MaterialInfo built = build(host, values);
        return built == null ? info : built;
    }

    private static Object[] values(Shape host, MaterialInfo info) {
        Object[] values = new Object[host.names.length];
        for (int i = 0; i < values.length; i++) {
            values[i] = read(host, info, i);
            if (values[i] == FAILED) return null;
        }
        return values;
    }

    private static final Object FAILED = new Object();

    private static Object read(Shape host, MaterialInfo info, int index) {
        try {
            return (Object) host.accessors[index].invokeExact((Object) info);
        } catch (Throwable failure) {
            note("could not read a quad material: " + failure);
            return FAILED;
        }
    }

    private static MaterialInfo build(Shape host, Object[] values) {
        Object made = invoke(host.constructor, values);
        return made instanceof MaterialInfo info ? info : null;
    }

    private static Object invoke(MethodHandle handle, Object[] arguments) {
        try {
            return (Object) handle.invokeExact(arguments);
        } catch (Throwable failure) {
            note("could not build a quad material: " + failure);
            return null;
        }
    }

    // A host whose material could not be read gets the widest public constructor, filled by name where the
    // parameters have names and by type where not: a solid layer, the sprite, and nothing else.
    private static MaterialInfo lastResort(Shape host, TextureAtlasSprite sprite, int tintIndex, boolean shade,
            int lightEmission) {
        if (host.fallback == null) return null;
        Parameter[] params = host.fallback.getParameters();
        Object[] values = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            Class<?> type = params[i].getType();
            String name = params[i].isNamePresent() ? params[i].getName() : "";
            if (name.equals(TINT_INDEX)) values[i] = tintIndex;
            else if (name.equals(LIGHT_EMISSION)) values[i] = lightEmission;
            else if (name.equals(SHADE_DIRECTION)) values[i] = shadeDirection(shade);
            else if (type == boolean.class) values[i] = shade;
            else if (type == ChunkSectionLayer.class) values[i] = ChunkSectionLayer.SOLID;
            else if (type.isInstance(sprite)) values[i] = sprite;
            else values[i] = defaultValue(type);
        }
        try {
            return (MaterialInfo) host.fallback.newInstance(values);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            note("could not build a quad material: " + failure);
            return null;
        }
    }

    private static void setShade(Shape host, Object[] values, boolean shade) {
        set(host, values, SHADE, shade);
        set(host, values, SHADE_DIRECTION, shadeDirection(shade));
    }

    private static Direction shadeDirection(boolean shade) {
        // Forge 1.20.1 lit an unshaded quad as brightly as an upward face, in every dimension.
        return shade ? null : Direction.UP;
    }

    private static void set(Shape host, Object[] values, String name, Object value) {
        int index = host.index(name);
        if (index >= 0) values[index] = value;
    }

    private static Object defaultValue(Class<?> type) {
        if (type == int.class) return 0;
        if (type == boolean.class) return false;
        return null;
    }

    private static void note(String problem) {
        LegacyLog.warnOnce(NOTE, "Retromod " + problem + ". Old mods' quads on this Minecraft version keep the"
                + " material they were given, drawn in the solid layer when they had none.");
    }

    // Read once; a host that could not be read is remembered too, so no later quad asks again.
    private static Shape shape() {
        Shape known = shape;
        if (known == null) {
            known = Shape.read();
            shape = known;
        }
        return known;
    }

    /** The running host's MaterialInfo: its fields in constructor order, their accessors, its factory, any of them missing. */
    private static final class Shape {
        final String[] names;
        final Class<?>[] types;
        final MethodHandle[] accessors;
        final MethodHandle constructor;
        final MethodHandle factory;
        final boolean factoryTakesDirection;
        final Constructor<?> fallback;

        private Shape(String[] names, Class<?>[] types, MethodHandle[] accessors, MethodHandle constructor,
                MethodHandle factory, boolean factoryTakesDirection, Constructor<?> fallback) {
            this.names = names;
            this.types = types;
            this.accessors = accessors;
            this.constructor = constructor;
            this.factory = factory;
            this.factoryTakesDirection = factoryTakesDirection;
            this.fallback = fallback;
        }

        int index(String name) {
            if (names == null) return -1;
            for (int i = 0; i < names.length; i++) {
                if (names[i].equals(name)) return i;
            }
            return -1;
        }

        static Shape read() {
            Class<MaterialInfo> type = MaterialInfo.class;
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            String[] names = null;
            Class<?>[] types = null;
            MethodHandle[] accessors = null;
            MethodHandle constructor = null;
            // The record's fields and canonical constructor, which every change to a material goes through.
            try {
                RecordComponent[] components = type.getRecordComponents();
                if (components == null) throw new IllegalStateException("BakedQuad.MaterialInfo is not a record");
                String[] readNames = new String[components.length];
                Class<?>[] readTypes = new Class<?>[components.length];
                MethodHandle[] readAccessors = new MethodHandle[components.length];
                for (int i = 0; i < components.length; i++) {
                    readNames[i] = components[i].getName();
                    readTypes[i] = components[i].getType();
                    readAccessors[i] = lookup.unreflect(components[i].getAccessor())
                            .asType(MethodType.methodType(Object.class, Object.class));
                }
                constructor = spread(lookup.findConstructor(type, MethodType.methodType(void.class, readTypes)));
                names = readNames;
                types = readTypes;
                accessors = readAccessors;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                note("could not read Minecraft's BakedQuad.MaterialInfo (" + failure + ")");
            }
            MethodHandle factory = null;
            boolean takesDirection = false;
            try {
                Method of = factoryMethod(type);
                if (of != null) {
                    factory = spread(lookup.unreflect(of));
                    takesDirection = of.getParameterTypes()[3] == Direction.class;
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                note("could not use Minecraft's BakedQuad.MaterialInfo.of (" + failure + ")");
            }
            return new Shape(names, types, accessors, constructor, factory, takesDirection, widestConstructor(type));
        }

        // Object in, Object out, unboxing where the host takes an int or a boolean.
        private static MethodHandle spread(MethodHandle handle) {
            int count = handle.type().parameterCount();
            return handle.asType(handle.type().generic()).asSpreader(Object[].class, count);
        }

        // of(Material.Baked, Transparency, int tintIndex, boolean shade or Direction override, int lightEmission).
        private static Method factoryMethod(Class<?> type) {
            for (Method method : type.getMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (method.getName().equals("of") && Modifier.isStatic(method.getModifiers()) && params.length == 5
                        && params[0] == Material.Baked.class && params[2] == int.class && params[4] == int.class
                        && (params[3] == boolean.class || params[3] == Direction.class)) {
                    return method;
                }
            }
            return null;
        }

        private static Constructor<?> widestConstructor(Class<?> type) {
            Constructor<?> widest = null;
            try {
                for (Constructor<?> candidate : type.getConstructors()) {
                    if (widest == null || candidate.getParameterCount() > widest.getParameterCount()) widest = candidate;
                }
            } catch (RuntimeException | LinkageError failure) {
                return null;
            }
            return widest;
        }
    }
}
