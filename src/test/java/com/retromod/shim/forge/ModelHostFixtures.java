/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.Assumptions;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Stand-in Minecraft 26.2 and 26.3 model classes, with Retromod's rebuilt model classes and a second mod's copy of them. */
final class ModelHostFixtures {

    static final String HOST_262 = "26.2";
    static final String HOST_263 = "26.3";
    /** 26.3's material with one more field, as a loader patch could add, to show unknown fields are kept. */
    static final String HOST_263_EXTRA = "26.3 with a ninth material field";
    /** A future host whose material is a plain class with no factory, which the helper cannot read as a record. */
    static final String HOST_UNKNOWN = "a host whose material is not a record";
    static final String SECOND = "com/retromod/embedded/second/";

    private static final List<String> RELOCATED = List.of("com/retromod/legacyapi/", "com/retromod/polyfill/",
            "com/retromod/shim/common/embedded/");

    private ModelHostFixtures() {}

    /** One client: the stand-ins for {@code host}, real JOML, the registered rebuilt classes, and a second copy of them. */
    static Host load(String host, Path work, RetromodTransformer transformer) throws Exception {
        Path joml = jomlJar();
        Assumptions.assumeTrue(joml != null, "no JOML jar in Prism's libraries");
        Map<String, byte[]> classes = new HashMap<>(jarClasses(joml));
        classes.putAll(compile(work, sources(host), joml));
        transformer.getSyntheticClasses().forEach((name, bytes) -> {
            classes.put(name, bytes);
            if (RELOCATED.stream().anyMatch(name::startsWith)) classes.put(SECOND + name, remap(bytes));
        });
        return new Host(loader(classes));
    }

    /** Reflection over one stand-in client. */
    record Host(ClassLoader loader) {

        Class<?> type(String internalName) throws ClassNotFoundException {
            return Class.forName(internalName.replace('/', '.'), true, loader);
        }

        /** Calls a public static method by name and argument count. */
        Object call(String owner, String name, Object... args) throws Exception {
            for (Method method : type(owner).getMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == args.length
                        && java.lang.reflect.Modifier.isStatic(method.getModifiers()) && accepts(method, args)) {
                    try {
                        return method.invoke(null, args);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                        if (failure.getCause() instanceof Exception cause) throw cause;
                        throw failure;
                    }
                }
            }
            throw new AssertionError(owner + " has no static " + name + " for " + args.length + " arguments");
        }

        /** Calls a public instance method by name and argument count. */
        Object invoke(Object target, String name, Object... args) throws Exception {
            for (Method method : target.getClass().getMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == args.length && accepts(method, args)) {
                    method.setAccessible(true);
                    return method.invoke(target, args);
                }
            }
            throw new AssertionError(target.getClass() + " has no " + name);
        }

        Object renderType(String token) throws Exception {
            return call("net/minecraft/client/renderer/rendertype/RenderTypes", token);
        }

        Object layer(String name) throws Exception {
            return type("net/minecraft/client/renderer/chunk/ChunkSectionLayer").getField(name).get(null);
        }

        Object direction(String name) throws Exception {
            return type("net/minecraft/core/Direction").getField(name).get(null);
        }

        /** A sprite whose pixels are opaque, partly transparent or translucent. */
        Object sprite(String name, boolean transparent, boolean translucent) throws Exception {
            Class<?> transparency = type("com/mojang/blaze3d/platform/Transparency");
            Object kind = transparency.getConstructor(boolean.class, boolean.class).newInstance(transparent, translucent);
            return type("net/minecraft/client/renderer/texture/TextureAtlasSprite").getConstructor(String.class, transparency)
                    .newInstance(name, kind);
        }

        @SuppressWarnings("unchecked")
        List<String> initialized() throws Exception {
            return (List<String>) type("testhost/Inits").getField("SEEN").get(null);
        }

        private static boolean accepts(Method method, Object[] args) {
            Class<?>[] params = method.getParameterTypes();
            for (int i = 0; i < args.length; i++) {
                if (args[i] == null) continue;
                Class<?> param = params[i].isPrimitive() ? boxed(params[i]) : params[i];
                if (!param.isInstance(args[i])) return false;
            }
            return true;
        }

        private static Class<?> boxed(Class<?> primitive) {
            if (primitive == int.class) return Integer.class;
            if (primitive == boolean.class) return Boolean.class;
            if (primitive == long.class) return Long.class;
            return Float.class;
        }
    }

    // ---- stand-in host classes ----

    static Map<String, String> sources(String host) {
        boolean shadeFlag = host.equals(HOST_262) || host.equals(HOST_UNKNOWN);
        boolean extra = host.equals(HOST_263_EXTRA);
        Map<String, String> sources = new HashMap<>();
        sources.put("testhost/Inits.java", """
                package testhost;
                public final class Inits {
                    public static final java.util.List<String> SEEN = new java.util.ArrayList<>();
                    public static void note(String name) { SEEN.add(name); }
                }""");
        sources.put("net/minecraft/core/Direction.java",
                "package net.minecraft.core; public enum Direction { DOWN, UP, NORTH, SOUTH, WEST, EAST }");
        sources.put("net/minecraft/client/renderer/chunk/ChunkSectionLayer.java", """
                package net.minecraft.client.renderer.chunk;
                public enum ChunkSectionLayer {
                    SOLID, CUTOUT, TRANSLUCENT;
                    static { testhost.Inits.note("ChunkSectionLayer"); }
                }""");
        sources.put("net/minecraft/client/renderer/rendertype/RenderType.java", """
                package net.minecraft.client.renderer.rendertype;
                public class RenderType {
                    private final String name;
                    public RenderType(String name) { this.name = name; }
                    @Override public String toString() { return name; }
                }""");
        sources.put("net/minecraft/client/renderer/rendertype/RenderTypes.java", """
                package net.minecraft.client.renderer.rendertype;
                public final class RenderTypes {
                    static { testhost.Inits.note("RenderTypes"); }
                    private static final RenderType SOLID = new RenderType("solid");
                    private static final RenderType CUTOUT = new RenderType("cutout");
                    private static final RenderType TRANSLUCENT = new RenderType("translucent");
                    public static RenderType solidMovingBlock() { return SOLID; }
                    public static RenderType cutoutMovingBlock() { return CUTOUT; }
                    public static RenderType translucentMovingBlock() { return TRANSLUCENT; }
                }""");
        sources.put("net/minecraft/client/renderer/Sheets.java", """
                package net.minecraft.client.renderer;
                import net.minecraft.client.renderer.rendertype.RenderType;
                public final class Sheets {
                    public static final RenderType CUTOUT_ITEM = new RenderType("cutout_item");
                    public static final RenderType TRANSLUCENT_ITEM = new RenderType("translucent_item");
                    public static final RenderType GLINT = new RenderType("glint");
                    public static final RenderType GLINT_SPECIAL = new RenderType("glint_special");
                }""");
        sources.put("com/mojang/blaze3d/platform/Transparency.java", """
                package com.mojang.blaze3d.platform;
                public record Transparency(boolean hasTransparent, boolean hasTranslucent) {
                    public static final Transparency NONE = new Transparency(false, false);
                }""");
        sources.put("net/minecraft/client/renderer/texture/TextureAtlasSprite.java", """
                package net.minecraft.client.renderer.texture;
                import com.mojang.blaze3d.platform.Transparency;
                public class TextureAtlasSprite {
                    private final String name;
                    private final Transparency transparency;
                    public TextureAtlasSprite(String name, Transparency transparency) { this.name = name; this.transparency = transparency; }
                    public Transparency transparency() { return transparency; }
                    @Override public String toString() { return name; }
                }""");
        sources.put("net/minecraft/client/resources/model/sprite/Material.java", """
                package net.minecraft.client.resources.model.sprite;
                import net.minecraft.client.renderer.texture.TextureAtlasSprite;
                public record Material(String sprite) {
                    public record Baked(TextureAtlasSprite sprite, boolean forceTranslucent) {}
                }""");
        String fields = shadeFlag
                ? "TextureAtlasSprite sprite, ChunkSectionLayer layer, RenderType itemRenderType, int tintIndex, boolean shade,"
                        + " int lightEmission"
                : "TextureAtlasSprite sprite, ChunkSectionLayer layer, RenderType itemRenderType, RenderType itemGlintRenderType,"
                        + " RenderType itemGlintSpecialRenderType, int tintIndex, Direction shadeDirectionOverride, int lightEmission"
                        + (extra ? ", int extraFlag" : "");
        String shadeParam = shadeFlag ? "boolean shade" : "Direction shadeDirectionOverride";
        String made = shadeFlag
                ? "new MaterialInfo(material.sprite(), layer, item, tintIndex, shade, lightEmission)"
                : "new MaterialInfo(material.sprite(), layer, item, Sheets.GLINT, Sheets.GLINT_SPECIAL, tintIndex,"
                        + " shadeDirectionOverride, lightEmission" + (extra ? ", 7" : "") + ")";
        sources.put("net/minecraft/client/resources/model/geometry/BakedQuad.java", """
                package net.minecraft.client.resources.model.geometry;
                import com.mojang.blaze3d.platform.Transparency;
                import net.minecraft.client.renderer.Sheets;
                import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
                import net.minecraft.client.renderer.rendertype.RenderType;
                import net.minecraft.client.renderer.texture.TextureAtlasSprite;
                import net.minecraft.client.resources.model.sprite.Material;
                import net.minecraft.core.Direction;
                import org.joml.Vector3fc;
                public record BakedQuad(Vector3fc position0, Vector3fc position1, Vector3fc position2, Vector3fc position3,
                        long packedUV0, long packedUV1, long packedUV2, long packedUV3, Direction direction, MaterialInfo materialInfo) {
                    public Vector3fc position(int vertex) {
                        return switch (vertex) { case 0 -> position0; case 1 -> position1; case 2 -> position2; default -> position3; };
                    }
                    public long packedUV(int vertex) {
                        return switch (vertex) { case 0 -> packedUV0; case 1 -> packedUV1; case 2 -> packedUV2; default -> packedUV3; };
                    }
                    %s
                }""".formatted(host.equals(HOST_UNKNOWN) ? PLAIN_MATERIAL : """
                    public record MaterialInfo(%s) {
                        public static MaterialInfo of(Material.Baked material, Transparency transparency, int tintIndex, %s,
                                int lightEmission) {
                            ChunkSectionLayer layer = transparency.hasTranslucent() ? ChunkSectionLayer.TRANSLUCENT
                                    : transparency.hasTransparent() ? ChunkSectionLayer.CUTOUT : ChunkSectionLayer.SOLID;
                            RenderType item = transparency.hasTranslucent() ? Sheets.TRANSLUCENT_ITEM : Sheets.CUTOUT_ITEM;
                            return %s;
                        }
                        public boolean isTinted() { return tintIndex != -1; }
                    }""".formatted(fields, shadeParam, made)));
        // The client the common layer polyfill reads: each state's model and the layer its one quad draws in.
        sources.put("net/minecraft/util/RandomSource.java",
                "package net.minecraft.util; public class RandomSource { public static RandomSource create() { return new RandomSource(); } }");
        sources.put("net/minecraft/client/Minecraft.java", """
                package net.minecraft.client;
                public class Minecraft {
                    private static final Minecraft INSTANCE = new Minecraft();
                    private final testhost.Models models = new testhost.Models();
                    public static Minecraft getInstance() { return INSTANCE; }
                    public testhost.Models getModelManager() { return models; }
                }""");
        sources.put("testhost/Models.java", """
                package testhost;
                public class Models {
                    public static final java.util.Map<Object, Object> LAYERS = new java.util.IdentityHashMap<>();
                    public static java.util.function.Function<Object, Object> whileRead;
                    public static int reads;
                    public Models getBlockStateModelSet() { return this; }
                    public Model get(net.minecraft.world.level.block.state.BlockState state) {
                        return LAYERS.containsKey(state) ? new Model(state) : null;
                    }
                    public static final class Model {
                        private final Object state;
                        Model(Object state) { this.state = state; }
                        public void collectParts(net.minecraft.util.RandomSource random, java.util.List<Object> parts) {
                            reads++;
                            if (whileRead != null) whileRead.apply(state);
                            parts.add(new Part(LAYERS.get(state)));
                        }
                    }
                    public static final class Part {
                        private final Object layer;
                        Part(Object layer) { this.layer = layer; }
                        public java.util.List<Object> getQuads(net.minecraft.core.Direction side) { return java.util.List.of(new Quad(layer)); }
                    }
                    public static final class Quad {
                        private final Object layer;
                        Quad(Object layer) { this.layer = layer; }
                        public Info materialInfo() { return new Info(layer); }
                    }
                    public static final class Info {
                        private final Object layer;
                        Info(Object layer) { this.layer = layer; }
                        public Object layer() { return layer; }
                    }
                }""");
        sources.put("com/mojang/math/Transformation.java", """
                package com.mojang.math;
                public final class Transformation {
                    private final org.joml.Matrix4f matrix;
                    public Transformation(org.joml.Matrix4fc matrix) { this.matrix = new org.joml.Matrix4f(matrix); }
                    public org.joml.Matrix4fc getMatrix() { return matrix; }
                }""");
        sources.put("net/minecraft/world/level/block/Block.java", """
                package net.minecraft.world.level.block;
                public class Block {
                    private final String name;
                    public Block(String name) { this.name = name; }
                    @Override public String toString() { return name; }
                }""");
        sources.put("net/minecraft/world/level/block/state/BlockState.java", """
                package net.minecraft.world.level.block.state;
                public class BlockState {
                    private final net.minecraft.world.level.block.Block block;
                    public BlockState(net.minecraft.world.level.block.Block block) { this.block = block; }
                    public net.minecraft.world.level.block.Block getBlock() { return block; }
                }""");
        sources.put("net/minecraftforge/client/RenderTypeGroup.java", """
                package net.minecraftforge.client;
                import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
                import net.minecraft.client.renderer.rendertype.RenderType;
                public record RenderTypeGroup(ChunkSectionLayer block, RenderType entity, RenderType entityFabulous) {
                    public static final RenderTypeGroup EMPTY = new RenderTypeGroup(null, null, null);
                    public RenderTypeGroup {
                        if ((block == null) != (entity == null) || (entity == null) != (entityFabulous == null)) {
                            throw new IllegalArgumentException("The render types in a group must either be all null, or all non-null.");
                        }
                    }
                    public RenderTypeGroup(ChunkSectionLayer block, RenderType entity) { this(block, entity, entity); }
                    public boolean isEmpty() { return block == null; }
                }""");
        return sources;
    }

    private static final String PLAIN_MATERIAL = """
            public static final class MaterialInfo {
                    private final TextureAtlasSprite sprite;
                    private final ChunkSectionLayer layer;
                    private final RenderType itemRenderType;
                    private final int tintIndex;
                    private final boolean shade;
                    private final int lightEmission;
                    public MaterialInfo(TextureAtlasSprite sprite, ChunkSectionLayer layer, RenderType itemRenderType, int tintIndex,
                            boolean shade, int lightEmission) {
                        this.sprite = sprite;
                        this.layer = layer;
                        this.itemRenderType = itemRenderType;
                        this.tintIndex = tintIndex;
                        this.shade = shade;
                        this.lightEmission = lightEmission;
                    }
                    public TextureAtlasSprite sprite() { return sprite; }
                    public ChunkSectionLayer layer() { return layer; }
                    public RenderType itemRenderType() { return itemRenderType; }
                    public int tintIndex() { return tintIndex; }
                    public boolean shade() { return shade; }
                    public int lightEmission() { return lightEmission; }
                    public boolean isTinted() { return tintIndex != -1; }
                }""";

    // ---- class loading ----

    private static byte[] remap(byte[] bytes) {
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(bytes).accept(new ClassRemapper(writer, new Remapper() {
            @Override
            public String map(String internalName) {
                return RELOCATED.stream().anyMatch(internalName::startsWith) ? SECOND + internalName : internalName;
            }
        }), 0);
        return writer.toByteArray();
    }

    private static ClassLoader loader(Map<String, byte[]> classes) {
        return new ClassLoader(ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name.replace('.', '/'));
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }

    private static Map<String, byte[]> compile(Path work, Map<String, String> sources, Path joml) throws Exception {
        Path src = work.resolve("src");
        Path out = work.resolve("out");
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), "--release", "17", "-proc:none",
                "-classpath", joml.toString()));
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = src.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            args.add(file.toString());
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "the tests need a JDK to compile the host stand-ins");
        assertEquals(0, compiler.run(null, null, null, args.toArray(new String[0])), "the host stand-ins must compile");
        Map<String, byte[]> classes = new HashMap<>();
        try (Stream<Path> files = Files.walk(out)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                String name = out.relativize(file).toString().replace(File.separatorChar, '/');
                classes.put(name.substring(0, name.length() - ".class".length()), Files.readAllBytes(file));
            }
        }
        return classes;
    }

    private static Map<String, byte[]> jarClasses(Path jar) throws Exception {
        Map<String, byte[]> classes = new HashMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                String name = entry.getName();
                if (!name.endsWith(".class") || name.endsWith("module-info.class") || name.startsWith("META-INF/")) continue;
                classes.put(name.substring(0, name.length() - ".class".length()), zip.getInputStream(entry).readAllBytes());
            }
        }
        return classes;
    }

    /** JOML, which Minecraft 26.2 and 26.3 ship: a system property, then Prism's libraries. */
    private static Path jomlJar() {
        List<Path> candidates = new ArrayList<>();
        String configured = System.getProperty("retromod.test.joml");
        if (configured != null && !configured.isBlank()) candidates.add(Path.of(configured));
        Path libraries = Path.of(System.getProperty("user.home"), "Library/Application Support/PrismLauncher/libraries/org/joml/joml");
        for (String version : List.of("1.10.8", "1.10.9", "1.10.5")) {
            candidates.add(libraries.resolve(version).resolve("joml-" + version + ".jar"));
        }
        return candidates.stream().filter(Files::isRegularFile).findFirst().orElse(null);
    }
}
