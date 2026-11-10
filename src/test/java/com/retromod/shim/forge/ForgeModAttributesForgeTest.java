/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Forge 26.2 dropped ForgeMod's reach, gravity and step fields; old reads get a Forge registry object for the vanilla attribute. */
class ForgeModAttributesForgeTest {

    private static final String MOD = "mod/Reach";
    private static final String FORGE_MOD = LegacyForgeModAttributes.FORGE_MOD;
    private static final String REGISTRY_OBJECT = LegacyForgeModAttributes.REGISTRY_OBJECT;

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();
    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @TempDir
    Path tempDir;

    @BeforeEach
    void forge262() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Each removed field names its vanilla attribute, and one registry object serves every read")
    void readsBecomeOneRegistryObjectPerAttribute() throws Exception {
        LegacyForgeModAttributes.registerForge(transformer);
        ClassLoader host = new ForgeHostStandIns()
                .add("net/minecraft/resources/Identifier.java", """
                        package net.minecraft.resources;
                        public final class Identifier {
                            private final String id;
                            private Identifier(String id) { this.id = id; }
                            public static Identifier fromNamespaceAndPath(String namespace, String path) {
                                return new Identifier(namespace + ":" + path);
                            }
                            public String toString() { return id; }
                        }""")
                .add("net/minecraftforge/registries/IForgeRegistry.java",
                        "package net.minecraftforge.registries; public interface IForgeRegistry<T> {}")
                .add("net/minecraftforge/registries/ForgeRegistries.java", """
                        package net.minecraftforge.registries;
                        public class ForgeRegistries {
                            public static final IForgeRegistry<Object> ATTRIBUTES = new IForgeRegistry<>() {};
                        }""")
                .add("net/minecraftforge/registries/RegistryObject.java", """
                        package net.minecraftforge.registries;
                        public final class RegistryObject<T> {
                            public static int created;
                            private final String id;
                            private RegistryObject(String id) { this.id = id; }
                            public static <T, U extends T> RegistryObject<U> create(
                                    net.minecraft.resources.Identifier name, IForgeRegistry<T> registry) {
                                if (registry != ForgeRegistries.ATTRIBUTES) throw new IllegalArgumentException("not the attribute registry");
                                created++;
                                return new RegistryObject<>(name.toString());
                            }
                            public String toString() { return id; }
                        }""")
                .loader(tempDir, transformer, Map.of(MOD, transformer.transformClass(mod(), MOD)));
        Class<?> mod = host.loadClass("mod.Reach");

        Object entityReach = mod.getMethod("read", String.class).invoke(null, "ENTITY_REACH");
        assertEquals("minecraft:entity_interaction_range", entityReach.toString());
        assertSame(entityReach, mod.getMethod("read", String.class).invoke(null, "ENTITY_REACH"),
                "a second read must not register another listener with the host");
        assertEquals("minecraft:block_interaction_range", mod.getMethod("read", String.class).invoke(null, "BLOCK_REACH").toString());
        assertEquals("minecraft:gravity", mod.getMethod("read", String.class).invoke(null, "ENTITY_GRAVITY").toString());
        assertEquals("minecraft:step_height", mod.getMethod("read", String.class).invoke(null, "STEP_HEIGHT_ADDITION").toString());
        assertEquals(4, host.loadClass("net.minecraftforge.registries.RegistryObject").getField("created").get(null));
    }

    @Test
    @DisplayName("Swim speed and name tag distance stay on Forge's own ForgeMod, and other hosts register nothing")
    void onlyRemovedFieldsOnForge262() {
        LegacyForgeModAttributes.registerForge(transformer);
        List<String> redirected = transformer.getFieldRedirects().keySet().stream()
                .filter(key -> key.owner().equals(FORGE_MOD)).map(key -> key.name()).sorted().toList();
        assertEquals(List.of("BLOCK_REACH", "ENTITY_GRAVITY", "ENTITY_REACH", "STEP_HEIGHT_ADDITION"), redirected);

        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        LegacyForgeModAttributes.registerForge(transformer);
        assertTrue(transformer.getFieldRedirects().isEmpty(), "Forge 1.21.11 is not checked to lack the fields");

        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceNeoForge(true);
        LegacyForgeModAttributes.registerForge(transformer);
        assertTrue(transformer.getFieldRedirects().isEmpty(), "NeoForge keeps its DeferredHolder bridge");
    }

    @Test
    @DisplayName("The Forge helper passes the stack verifier")
    void helperVerifies() throws Exception {
        ClassNode helper = new ClassNode();
        new ClassReader(LegacyForgeModAttributes.generateForgeHelper(List.<String[]>of(LegacyForgeModAttributes.ATTRIBUTES[0])))
                .accept(helper, 0);
        for (MethodNode method : helper.methods) new Analyzer<>(new BasicVerifier()).analyze(helper.name, method);
    }

    /** {@code static Object read(String field)} reading each of the four old ForgeMod fields by name. */
    private static byte[] mod() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, MOD, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "read", "(Ljava/lang/String;)Ljava/lang/Object;", null, null);
        mv.visitCode();
        for (String field : List.of("ENTITY_REACH", "BLOCK_REACH", "ENTITY_GRAVITY", "STEP_HEIGHT_ADDITION")) {
            org.objectweb.asm.Label next = new org.objectweb.asm.Label();
            mv.visitLdcInsn(field);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false);
            mv.visitJumpInsn(IFEQ, next);
            mv.visitFieldInsn(GETSTATIC, FORGE_MOD, field, REGISTRY_OBJECT);
            mv.visitInsn(ARETURN);
            mv.visitLabel(next);
        }
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
