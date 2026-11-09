/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static com.retromod.shim.common.LegacyComposterBridge.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** On 26.3, which moved compost chances into an item component, an old mod's composter table is rebuilt inside it. */
class LegacyComposterBridgeTest {

    private static final String MOD = "mod/Compost";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @BeforeEach
    void clean() {
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("A read of the old table calls the copy rebuilt in the mod, and the map calls stay as they were")
    void oldReadTakesTheRebuiltTable() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        LegacyComposterBridge.register(transformer, composter(false));

        ClassNode out = node(transformer.transformClass(modClass(), MOD + ".class"));

        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method(out, "register").instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
            if (insn instanceof FieldInsnNode field) calls.add("field " + field.owner + "." + field.name);
        }
        assertEquals(List.of(REBUILT + ".compostables()" + MAP,
                "it/unimi/dsi/fastutil/objects/Object2FloatMap.put(Ljava/lang/Object;F)F"), calls);
        assertTrue(transformer.getSyntheticClasses().containsKey(REBUILT), "the table is embedded into the mod");
    }

    @Test
    @DisplayName("A host that still has the table, and an offline target before 26.3, keep the old field")
    void hostsWithTheTableKeepIt() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        LegacyComposterBridge.register(transformer, composter(true));
        assertFalse(transformer.getSyntheticClasses().containsKey(REBUILT), "26.2 and older keep their table");

        RetromodVersion.TARGET_MC_VERSION = "26.2";
        LegacyComposterBridge.register(transformer, name -> null);
        assertFalse(transformer.getSyntheticClasses().containsKey(REBUILT), "offline, 26.2");

        RetromodVersion.TARGET_MC_VERSION = "26.3";
        LegacyComposterBridge.register(transformer, name -> null);
        assertTrue(transformer.getSyntheticClasses().containsKey(REBUILT), "offline, 26.3");
    }

    @ParameterizedTest(name = "NeoForge's Minecraft {0}")
    @ValueSource(strings = {"21.11.42", "26.1.2.60-beta", "26.2.0.0-beta", "26.3.0.43-beta"})
    @DisplayName("The real client jars: 1.21.11 to 26.2 have the table, 26.3 does not")
    void realClientJars(String version) throws Exception {
        Path jar = Path.of(System.getProperty("user.home"), "Library", "Application Support", "PrismLauncher",
                "libraries", "net", "neoforged", "minecraft-client-patched", version,
                "minecraft-client-patched-" + version + ".jar");
        Assumptions.assumeTrue(Files.isRegularFile(jar), "no Minecraft " + version + " jar");
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Function<String, ClassNode> host = name -> {
                ZipEntry entry = zip.getEntry(name + ".class");
                if (entry == null) return null;
                try (InputStream in = zip.getInputStream(entry)) {
                    return node(in.readAllBytes());
                } catch (IOException unreadable) {
                    return null;
                }
            };
            assertEquals(version.startsWith("26.3"), hostLacksTable(host), version);
        }
    }

    /** {@code static void register(Object item) { ComposterBlock.COMPOSTABLES.put(item, 0.3f); }} */
    private static byte[] modClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_SUPER, MOD, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "register", "(Ljava/lang/Object;)V", null, null);
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, COMPOSTER, FIELD, MAP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitLdcInsn(0.3f);
        mv.visitMethodInsn(INVOKEINTERFACE, "it/unimi/dsi/fastutil/objects/Object2FloatMap", "put",
                "(Ljava/lang/Object;F)F", true);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A host composter class, with or without the old table. */
    private static Function<String, ClassNode> composter(boolean withTable) {
        ClassNode node = new ClassNode();
        node.name = COMPOSTER;
        if (withTable) node.fields.add(new FieldNode(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, FIELD, MAP, null, null));
        return name -> name.equals(COMPOSTER) ? node : null;
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }
}
