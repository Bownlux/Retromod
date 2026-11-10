/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Create's CreateRegistrate on NeoForge 26.3: game-time classes that name removed data-generation classes. */
class LegacyDatagenStandInsTest {

    private static final String GENERATORS = "net/neoforged/neoforge/client/model/generators/";
    private static final String MODEL_FILE = GENERATORS + "ModelFile";
    private static final String MODEL_BUILDER = GENERATORS + "ModelBuilder";
    private static final String CALLER = "test/Registrate";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("each removed generator class gets a stand-in with its old supertypes")
    void standInsKeepTheOldHierarchy() {
        transformer.clearRedirectsForTesting();
        LegacyDatagenStandIns.registerNeoForge(transformer);

        ClassNode builder = read(transformer.getSyntheticClasses().get(LegacyDatagenStandIns.ROOT + MODEL_BUILDER));
        assertEquals(LegacyDatagenStandIns.ROOT + MODEL_FILE, builder.superName,
                "a model builder must stay assignable to a model file");
        ClassNode state = read(transformer.getSyntheticClasses().get(LegacyDatagenStandIns.ROOT + GENERATORS + "IGeneratedBlockState"));
        assertTrue((state.access & ACC_INTERFACE) != 0, "IGeneratedBlockState was an interface");
        ClassNode provider = read(transformer.getSyntheticClasses().get(LegacyDatagenStandIns.ROOT + GENERATORS + "BlockStateProvider"));
        assertEquals(java.util.List.of("net/minecraft/data/DataProvider"), provider.interfaces,
                "a supertype the host still has is kept as it is");
    }

    @Test
    @DisplayName("vanilla's removed tag providers get stand-ins on top of the TagsProvider the host kept")
    void vanillaTagProvidersKeepTheirShape() {
        // Registrate's provider types load a subclass of IntrinsicHolderTagsProvider while the game starts.
        transformer.clearRedirectsForTesting();
        String saved = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        try {
            LegacyDatagenStandIns.registerVanilla(transformer);
        } finally {
            RetromodVersion.TARGET_MC_VERSION = saved;
        }
        String tags = "net/minecraft/data/tags/";
        ClassNode intrinsic = read(transformer.getSyntheticClasses().get(LegacyDatagenStandIns.ROOT + tags + "IntrinsicHolderTagsProvider"));
        assertEquals(tags + "TagsProvider", intrinsic.superName, "the host still has TagsProvider, so it is kept");
        assertTrue((intrinsic.access & ACC_ABSTRACT) != 0);
        ClassNode appender = read(transformer.getSyntheticClasses().get(
                LegacyDatagenStandIns.ROOT + tags + "IntrinsicHolderTagsProvider$IntrinsicTagAppender"));
        assertEquals(LegacyDatagenStandIns.ROOT + tags + "TagsProvider$TagAppender", appender.superName);
    }

    @Test
    @DisplayName("on a 26.1 target only TagAppender gets a stand-in, since 26.1 still has IntrinsicHolderTagsProvider")
    void vanillaStandInsFollowTheTargetVersion() {
        transformer.clearRedirectsForTesting();
        String saved = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "26.1";
        try {
            LegacyDatagenStandIns.registerVanilla(transformer);
        } finally {
            RetromodVersion.TARGET_MC_VERSION = saved;
        }
        String tags = LegacyDatagenStandIns.ROOT + "net/minecraft/data/tags/";
        assertNotNull(transformer.getSyntheticClasses().get(tags + "TagsProvider$TagAppender"));
        assertNull(transformer.getSyntheticClasses().get(tags + "IntrinsicHolderTagsProvider"),
                "26.1 still ships IntrinsicHolderTagsProvider, so a stand-in would hide the real class");
    }

    @Test
    @DisplayName("a class that returns a model builder as a model file links against the stand-ins")
    void callerLinksAgainstStandIns() throws Exception {
        transformer.clearRedirectsForTesting();
        LegacyDatagenStandIns.registerNeoForge(transformer);
        byte[] caller = transformer.transformClass(caller(), CALLER);

        // The JVM verifier loads both types to check the return; without stand-ins neither exists.
        Map<String, byte[]> classes = new HashMap<>();
        classes.put(CALLER, caller);
        transformer.getSyntheticClasses().forEach((name, bytes) -> {
            if (name.startsWith(LegacyDatagenStandIns.ROOT)) classes.put(name, bytes);
        });
        Class<?> linked = Class.forName(CALLER.replace('/', '.'), true, new MapLoader(classes));
        assertEquals("pick", linked.getDeclaredMethods()[0].getName());
        assertTrue(new String(caller, java.nio.charset.StandardCharsets.ISO_8859_1).contains(LegacyDatagenStandIns.ROOT + MODEL_FILE));
    }

    /** static ModelFile pick(ModelBuilder builder) { return builder; } */
    private static byte[] caller() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, CALLER, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "pick", "(L" + MODEL_BUILDER + ";)L" + MODEL_FILE + ";", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        assertNotNull(bytes, "the stand-in should be registered");
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    /** Defines the given classes, and only them, beside the JDK. */
    private static final class MapLoader extends ClassLoader {
        private final Map<String, byte[]> classes;

        MapLoader(Map<String, byte[]> classes) {
            super(ClassLoader.getPlatformClassLoader());
            this.classes = classes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classes.get(name.replace('.', '/'));
            if (bytes == null) throw new ClassNotFoundException(name);
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
