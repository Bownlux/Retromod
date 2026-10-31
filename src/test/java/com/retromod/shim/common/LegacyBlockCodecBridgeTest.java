/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.shim.ShimRegistry;
import com.retromod.shim.fabric.Fabric_26_2_to_26_3;
import com.retromod.shim.forge.Forge_26_2_to_26_3;
import com.retromod.shim.neoforge.NeoForge_26_2_to_26_3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static com.retromod.shim.common.LegacyBlockCodecBridge.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tiny Dragons Reforged, a 1.21.11 Fabric mod, on 26.3 (#336): each dragon egg block filled its
 * {@code CODEC} with {@code simpleCodec(EggBlock::new)}. 26.3 removed block codecs, so the egg's
 * static initializer threw {@code NoSuchMethodError}, the mod registered no blocks or items, and
 * its loot tables and world generation then named ids the game did not know.
 *
 * <p>The transformed block runs against small stand-ins for the 26.3 classes, compiled at test
 * time in an isolated class loader.
 */
class LegacyBlockCodecBridgeTest {

    private static final String EGG = "test/mod/EggBlock";
    /** The 1.21.11 intermediary name of FallingBlock, which the shipped Fabric jar extends. */
    private static final String INTERMEDIARY_FALLING_BLOCK = "net/minecraft/class_2346";
    private static final String FALLING_BLOCK = "net/minecraft/world/level/block/FallingBlock";

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private RetromodTransformer transformer;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        transformer.clearRedirectsForTesting();
        transformer.clearJarClassBytesProvider();
        // Each test loads the stand-ins in its own class loader; the shared registry must not outlive it.
        System.getProperties().keySet().removeIf(key -> key.toString().startsWith("retromod.legacyRegistry."));
    }

    @Test
    @DisplayName("a Fabric egg block's simpleCodec, named on its own class, loads on a 26.3 host")
    void fabricBlockInitializesOn263() throws Exception {
        Map<String, byte[]> host = compile263StandIns();
        Class<?> egg = loadTransformedEgg(host, INTERMEDIARY_FALLING_BLOCK);

        Object codec = egg.getField("CODEC").get(null);
        assertNotNull(codec, "the block's codec must be filled, so the class finishes initializing");
        assertEquals("com.mojang.serialization.MapCodec", codec.getClass().getName());
    }

    @Test
    @DisplayName("decoding the stand-in codec fails with a message that names the cause")
    void standInCodecRefusesToDecode() throws Exception {
        Class<?> egg = loadTransformedEgg(compile263StandIns(), INTERMEDIARY_FALLING_BLOCK);
        Object codec = egg.getField("CODEC").get(null);

        Supplier<?> decoder = (Supplier<?>) codec.getClass().getField("decoder").get(codec);
        UnsupportedOperationException refused = assertThrows(UnsupportedOperationException.class, decoder::get);
        assertTrue(refused.getMessage().contains("removed block codecs"), refused.getMessage());
    }

    @Test
    @DisplayName("propertiesCodec reads the block's own properties when it encodes")
    void propertiesCodecReadsTheBlocksProperties() throws Exception {
        Class<?> egg = loadTransformedEgg(compile263StandIns(), INTERMEDIARY_FALLING_BLOCK);
        Object builder = egg.getField("PROPERTIES").get(null);
        java.util.function.Function<Object, Object> getter = getterOf(builder);

        Object properties = egg.getClassLoader()
                .loadClass("net.minecraft.world.level.block.state.BlockBehaviour$Properties")
                .getConstructor().newInstance();
        Object block = egg.getConstructor(properties.getClass()).newInstance(properties);

        assertSame(properties, getter.apply(block));
    }

    @Test
    @DisplayName("a block already named in Mojang names takes the same stand-in")
    void mojangNamedBlockTakesTheStandIn() throws Exception {
        Class<?> egg = loadTransformedEgg(compile263StandIns(), FALLING_BLOCK);
        assertNotNull(egg.getField("CODEC").get(null));
    }

    @Test
    @DisplayName("a read of the BLOCK_TYPE registry 26.3 removed gets one that accepts registrations")
    void blockTypeRegistryReadGetsAStandIn() throws Exception {
        // Minepathy registers its block types through its library Zine, whose helper reads
        // BuiltInRegistries.BLOCK_TYPE (#271), so the mod's blocks failed to register on 26.3.
        registerRedirects(transformer);
        String reader = "test/mod/BlockTypes";
        byte[] transformed = transformer.transformClass(blockTypeReader(reader), reader);

        Map<String, byte[]> classes = compile263StandIns();
        classes.putAll(compileStandIns(registryStandIns(), "registry"));
        classes.put(reader.replace('/', '.'), transformed);
        transformer.getSyntheticClasses().forEach((name, bytes) -> classes.put(name.replace('/', '.'), bytes));
        Class<?> types = Class.forName(reader.replace('/', '.'), true, isolatedLoader(classes));

        Object registry = types.getMethod("blockTypes").invoke(null);
        assertEquals("net.minecraft.core.MappedRegistry", registry.getClass().getName());
        assertSame(registry, types.getMethod("blockTypes").invoke(null), "every read gets the same registry");
        Object key = registry.getClass().getField("key").get(registry);
        assertEquals("retromod:legacy_block_type", String.valueOf(key.getClass().getField("location").get(key)));
    }

    @Test
    @DisplayName("the BLOCK_TYPE registry key names the stand-in registry, not the block registry")
    void blockTypeKeyNamesTheStandIn() throws Exception {
        // #366: ProjectE's block type register is keyed on Registries.BLOCK_TYPE. On 26.3 a guess
        // replaced it with Registries.BLOCK, and its block types collided with its blocks.
        registerRedirects(transformer);
        String reader = "test/mod/BlockTypeKey";
        byte[] transformed = transformer.transformClass(blockTypeKeyReader(reader), reader);

        Map<String, byte[]> classes = compile263StandIns();
        classes.putAll(compileStandIns(registryStandIns(), "registry"));
        classes.put(reader.replace('/', '.'), transformed);
        transformer.getSyntheticClasses().forEach((name, bytes) -> classes.put(name.replace('/', '.'), bytes));
        Class<?> types = Class.forName(reader.replace('/', '.'), true, isolatedLoader(classes));

        Object key = types.getMethod("blockTypes").invoke(null);
        assertEquals("retromod:legacy_block_type", String.valueOf(key.getClass().getField("location").get(key)));
    }

    @Test
    @DisplayName("a host that still has simpleCodec keeps the mod's call")
    void gatedOnTheHostShape() {
        assertTrue(hostLostBlockCodecs(blockBehaviour(false)), "26.3 dropped simpleCodec");
        assertFalse(hostLostBlockCodecs(blockBehaviour(true)), "26.2 still has simpleCodec");
    }

    @Test
    @DisplayName("Fabric, Forge, and NeoForge register the stand-in from 26.3, through the core moves")
    void registeredOnEveryLoaderFrom263() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        for (VersionShim shim : List.<VersionShim>of(new Fabric_26_2_to_26_3(), new Forge_26_2_to_26_3(),
                new NeoForge_26_2_to_26_3())) {
            assertFalse(ShimRegistry.isAvailableOnHost(shim, "26.2"), shim.getShimName());
            transformer.clearRedirectsForTesting();
            shim.registerRedirects(transformer);
            assertTrue(transformer.getSyntheticClasses().containsKey(HELPER), shim.getShimName());
        }
    }

    /** Transforms the egg block for a 26.3 host and loads it next to the stand-ins. */
    private Class<?> loadTransformedEgg(Map<String, byte[]> host, String superName) throws Exception {
        registerRedirects(transformer);
        // On the Fabric path the intermediary class names come from the bundled mappings.
        transformer.registerClassRedirect(INTERMEDIARY_FALLING_BLOCK, FALLING_BLOCK);

        byte[] original = eggBlock(superName);
        Map<String, byte[]> readable = new HashMap<>();
        host.forEach((name, bytes) -> readable.put(name.replace('.', '/'), bytes));
        readable.put(EGG, original);
        transformer.setJarClassBytesProvider(readable::get);
        byte[] transformed = transformer.transformClass(original, EGG);

        Map<String, byte[]> classes = new HashMap<>(host);
        classes.put(EGG.replace('/', '.'), transformed);
        transformer.getSyntheticClasses().forEach((name, bytes) -> classes.put(name.replace('/', '.'), bytes));
        try {
            return Class.forName(EGG.replace('/', '.'), true, isolatedLoader(classes));
        } catch (ExceptionInInitializerError e) {
            throw new AssertionError("the egg block failed to initialize: " + e.getCause(), e.getCause());
        }
    }

    private static ClassLoader isolatedLoader(Map<String, byte[]> classes) {
        return new ClassLoader(null) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes == null) return ClassLoader.getPlatformClassLoader().loadClass(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }

    /** {@code public static Registry blockTypes() { return BuiltInRegistries.BLOCK_TYPE; }} */
    private static byte[] blockTypeReader(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "blockTypes",
                "()L" + REGISTRY + ";", null, null);
        m.visitCode();
        m.visitFieldInsn(Opcodes.GETSTATIC, BUILT_IN_REGISTRIES, "BLOCK_TYPE", "L" + REGISTRY + ";");
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code public static ResourceKey blockTypes() { return Registries.BLOCK_TYPE; }} */
    private static byte[] blockTypeKeyReader(String name) {
        String key = "Lnet/minecraft/resources/ResourceKey;";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "blockTypes", "()" + key, null, null);
        m.visitCode();
        m.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/core/registries/Registries", "BLOCK_TYPE", key);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The registry shapes the helper builds, checked against the 26.3 client jar with javap. */
    private static Map<String, String> registryStandIns() {
        Map<String, String> sources = new HashMap<>();
        sources.put("net/minecraft/resources/Identifier.java", """
                package net.minecraft.resources;
                public final class Identifier {
                    private final String text;
                    private Identifier(String text) { this.text = text; }
                    public static Identifier fromNamespaceAndPath(String namespace, String path) {
                        return new Identifier(namespace + ":" + path);
                    }
                    @Override public String toString() { return text; }
                }""");
        sources.put("net/minecraft/resources/ResourceKey.java", """
                package net.minecraft.resources;
                public final class ResourceKey<T> {
                    public final Identifier location;
                    private ResourceKey(Identifier location) { this.location = location; }
                    public static <T> ResourceKey<T> createRegistryKey(Identifier location) {
                        return new ResourceKey<>(location);
                    }
                }""");
        sources.put("com/mojang/serialization/Lifecycle.java", """
                package com.mojang.serialization;
                public final class Lifecycle {
                    public static Lifecycle stable() { return new Lifecycle(); }
                }""");
        sources.put("net/minecraft/core/Registry.java", """
                package net.minecraft.core;
                public interface Registry<T> {}""");
        sources.put("net/minecraft/core/MappedRegistry.java", """
                package net.minecraft.core;
                import com.mojang.serialization.Lifecycle;
                import net.minecraft.resources.ResourceKey;
                public class MappedRegistry<T> implements Registry<T> {
                    public final ResourceKey<? extends Registry<T>> key;
                    public MappedRegistry(ResourceKey<? extends Registry<T>> key, Lifecycle lifecycle) {
                        this.key = key;
                    }
                }""");
        return sources;
    }

    @SuppressWarnings("unchecked")
    private static java.util.function.Function<Object, Object> getterOf(Object builder) throws Exception {
        return (java.util.function.Function<Object, Object>) builder.getClass().getField("getter").get(builder);
    }

    /**
     * An egg block as MCreator writes one:
     * <pre>
     * public class EggBlock extends FallingBlock {
     *     public static final MapCodec&lt;EggBlock&gt; CODEC = simpleCodec(EggBlock::new);
     *     public static final RecordCodecBuilder&lt;EggBlock, Properties&gt; PROPERTIES = propertiesCodec();
     *     public EggBlock(Properties properties) { super(properties); }
     * }
     * </pre>
     * javac names the block's own class as the owner of both inherited static calls.
     */
    private static byte[] eggBlock(String superName) {
        String properties = "Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, EGG, null, superName, null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "CODEC",
                "L" + MAP_CODEC + ";", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "PROPERTIES",
                "L" + RECORD_BUILDER + ";", null, null).visitEnd();

        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + properties + ")V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ALOAD, 1);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "(" + properties + ")V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitInsn(Opcodes.ACONST_NULL);
        clinit.visitMethodInsn(Opcodes.INVOKESTATIC, EGG, "simpleCodec", SIMPLE_CODEC, false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, EGG, "CODEC", "L" + MAP_CODEC + ";");
        clinit.visitMethodInsn(Opcodes.INVOKESTATIC, EGG, "propertiesCodec", PROPERTIES_CODEC, false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, EGG, "PROPERTIES", "L" + RECORD_BUILDER + ";");
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** BlockBehaviour as the gate reads it, with or without 26.2's simpleCodec. */
    private static ClassNode blockBehaviour(boolean withSimpleCodec) {
        ClassNode node = new ClassNode();
        node.name = BLOCK_BEHAVIOUR;
        node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "properties", "()L" + PROPERTIES + ";", null, null));
        if (withSimpleCodec) {
            node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "simpleCodec",
                    SIMPLE_CODEC, null, null));
        }
        return node;
    }

    /** The 26.3 shapes the helper calls, checked against the 26.3 client jar and DFU 10.0.21 with javap. */
    private Map<String, byte[]> compile263StandIns() throws Exception {
        Map<String, String> sources = new HashMap<>();
        sources.put("com/mojang/serialization/MapCodec.java", """
                package com.mojang.serialization;
                import java.util.function.Supplier;
                public final class MapCodec<A> {
                    public final Supplier<A> decoder;
                    private MapCodec(Supplier<A> decoder) { this.decoder = decoder; }
                    public static <A> MapCodec<A> unit(Supplier<A> value) { return new MapCodec<>(value); }
                }""");
        sources.put("com/mojang/serialization/codecs/RecordCodecBuilder.java", """
                package com.mojang.serialization.codecs;
                import com.mojang.serialization.MapCodec;
                import java.util.function.Function;
                public final class RecordCodecBuilder<O, F> {
                    public final Function<O, F> getter;
                    public final MapCodec<F> codec;
                    private RecordCodecBuilder(Function<O, F> getter, MapCodec<F> codec) {
                        this.getter = getter;
                        this.codec = codec;
                    }
                    public static <O, F> RecordCodecBuilder<O, F> of(Function<O, F> getter, MapCodec<F> codec) {
                        return new RecordCodecBuilder<>(getter, codec);
                    }
                }""");
        sources.put("net/minecraft/world/level/block/state/BlockBehaviour.java", """
                package net.minecraft.world.level.block.state;
                public abstract class BlockBehaviour {
                    public static class Properties {
                        public Properties() {}
                    }
                    protected final Properties properties;
                    public BlockBehaviour(Properties properties) { this.properties = properties; }
                    public Properties properties() { return properties; }
                }""");
        sources.put("net/minecraft/world/level/block/Block.java", """
                package net.minecraft.world.level.block;
                import net.minecraft.world.level.block.state.BlockBehaviour;
                public class Block extends BlockBehaviour {
                    public Block(BlockBehaviour.Properties properties) { super(properties); }
                }""");
        sources.put("net/minecraft/world/level/block/FallingBlock.java", """
                package net.minecraft.world.level.block;
                import net.minecraft.world.level.block.state.BlockBehaviour;
                public class FallingBlock extends Block {
                    public FallingBlock(BlockBehaviour.Properties properties) { super(properties); }
                }""");
        return compileStandIns(sources, "codecs");
    }

    private Map<String, byte[]> compileStandIns(Map<String, String> sources, String set) throws Exception {
        Path src = tempDir.resolve(set).resolve("src");
        Path out = tempDir.resolve(set).resolve("out");
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), "--release", "17"));
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = src.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            args.add(file.toString());
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "the tests need a JDK to compile the 26.3 stand-ins");
        assertEquals(0, compiler.run(null, null, null, args.toArray(new String[0])),
                "the 26.3 stand-ins must compile");

        Map<String, byte[]> classes = new HashMap<>();
        try (var files = Files.walk(out)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".class"))::iterator) {
                String name = out.relativize(file).toString().replace(java.io.File.separatorChar, '.');
                classes.put(name.substring(0, name.length() - ".class".length()), Files.readAllBytes(file));
            }
        }
        return classes;
    }
}
