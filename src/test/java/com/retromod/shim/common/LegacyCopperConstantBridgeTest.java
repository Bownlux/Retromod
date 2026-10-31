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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** A mod built before 26.2 reading per-state copper constants, which 26.2 folded into weathering copper collections. */
class LegacyCopperConstantBridgeTest {

    private static final String BLOCKS = "net/minecraft/world/level/block/Blocks";
    private static final String BLOCK = "Lnet/minecraft/world/level/block/Block;";
    private static final String COLLECTION = "L" + LegacyCopperConstantBridge.COLLECTION + ";";
    private static final String OLD_RECORD = "net/minecraft/world/level/block/WeatheringCopperBlocks";

    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;
    private RetromodTransformer transformer;

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("A removed state reads the collection, and a 26.2 read of the reused name stays a field read")
    void constantsReadTheCollection() {
        bridged("26.2");
        ClassNode out = transform(reads(new String[]{"EXPOSED_COPPER", BLOCK}, new String[]{"COPPER_BLOCK", BLOCK},
                new String[]{"COPPER_BLOCK", COLLECTION}));

        assertEquals(List.of(LegacyCopperConstantBridge.HELPER + ".Blocks$EXPOSED_COPPER",
                LegacyCopperConstantBridge.HELPER + ".Blocks$COPPER_BLOCK"), calls(out));
        assertEquals(List.of(BLOCKS + ".COPPER_BLOCK:" + COLLECTION), fields(out),
                "a mod built for 26.2 reads the collection itself");
    }

    @Test
    @DisplayName("A 1.21.9 copper record read becomes the collection, and its accessors helper calls")
    void recordCallsReachTheHelper() {
        bridged("26.3");
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/Copper", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        for (String accessor : List.of("exposed", "waxed")) {
            mv.visitFieldInsn(Opcodes.GETSTATIC, BLOCKS, "COPPER_BARS", "L" + OLD_RECORD + ";");
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, OLD_RECORD, accessor, "()" + BLOCK, false);
            mv.visitInsn(Opcodes.POP);
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode out = transform(cw.toByteArray());

        assertEquals(List.of(LegacyCopperConstantBridge.HELPER + ".WeatheringCopperBlocks$exposed",
                LegacyCopperConstantBridge.HELPER + ".WeatheringCopperBlocks$waxed"), calls(out));
        assertEquals(List.of(BLOCKS + ".COPPER_BARS:" + COLLECTION, BLOCKS + ".COPPER_BARS:" + COLLECTION), fields(out));
    }

    @Test
    @DisplayName("A 26.1 host keeps its own copper constants")
    void olderHostsAreLeftAlone() {
        bridged("26.1.2");
        assertFalse(RetromodTransformer.getInstance().getSyntheticClasses().containsKey(LegacyCopperConstantBridge.HELPER));
    }

    @Test
    @DisplayName("The generated helper verifies")
    void helperVerifies() {
        new ClassReader(LegacyCopperConstantBridge.generate()).accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
    }

    @ParameterizedTest(name = "in {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("Every collection field and member the helper reads is in the released client jar")
    void helperReadsExistOnTheHost(String version) throws Exception {
        Path jar = Path.of(System.getProperty("user.home"), "Library/Application Support/PrismLauncher/libraries",
                "com/mojang/minecraft", version, "minecraft-" + version + "-client.jar");
        assumeTrue(Files.exists(jar), "no local " + version + " client jar; it is local test material");
        ClassNode helper = new ClassNode();
        new ClassReader(LegacyCopperConstantBridge.generate()).accept(helper, 0);
        List<String> missing = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (MethodNode method : helper.methods) {
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof FieldInsnNode field && !declaresField(zip, field.owner, field.name, field.desc)) {
                        missing.add(field.owner + "." + field.name + ":" + field.desc);
                    } else if (insn instanceof MethodInsnNode call && call.owner.startsWith("net/minecraft/")
                            && !declaresMethod(zip, call.owner, call.name, call.desc)) {
                        missing.add(call.owner + "." + call.name + call.desc);
                    }
                }
            }
        }
        assertEquals(List.of(), missing);
    }

    private static void bridged(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyCopperConstantBridge.register(transformer);
    }

    /** A class that reads each {@code {name, desc}} of Blocks and drops it. */
    private static byte[] reads(String[]... fields) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/Copper", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        for (String[] field : fields) {
            mv.visitFieldInsn(Opcodes.GETSTATIC, BLOCKS, field[0], field[1]);
            mv.visitInsn(Opcodes.POP);
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode transform(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(RetromodTransformer.getInstance().transformClass(classBytes, "mod/Copper")).accept(node, 0);
        return node;
    }

    private static List<String> calls(ClassNode node) {
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
            }
        }
        return calls;
    }

    private static List<String> fields(ClassNode node) {
        List<String> fields = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode field) fields.add(field.owner + "." + field.name + ":" + field.desc);
            }
        }
        return fields;
    }

    private static ClassNode read(ZipFile zip, String owner) throws Exception {
        var entry = zip.getEntry(owner + ".class");
        if (entry == null) return null;
        ClassNode node = new ClassNode();
        new ClassReader(zip.getInputStream(entry).readAllBytes()).accept(node, ClassReader.SKIP_CODE);
        return node;
    }

    private static boolean declaresField(ZipFile zip, String owner, String name, String desc) throws Exception {
        ClassNode node = read(zip, owner);
        return node != null && node.fields.stream().anyMatch(field -> field.name.equals(name) && field.desc.equals(desc));
    }

    private static boolean declaresMethod(ZipFile zip, String owner, String name, String desc) throws Exception {
        ClassNode node = read(zip, owner);
        return node != null && node.methods.stream().anyMatch(method -> method.name.equals(name) && method.desc.equals(desc));
    }
}
