/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** #408 (Monke Madness, Forge 1.18.2 on Forge 1.20.1): 1.19 replaced java.util.Random with RandomSource in method signatures. */
class LegacyRandomCallAdapterTest {

    private static final String RANDOM = "Ljava/util/Random;";
    private static final String SOURCE = "Lnet/minecraft/util/RandomSource;";
    private static final String BLOCK = "net/minecraft/world/level/block/Block";
    private static final String ANIMAL = "net/minecraft/world/entity/animal/Animal";
    private static final String PREDICATE = "net/minecraft/world/entity/SpawnPlacements$SpawnPredicate";
    private static final String TICK_ARGS = "(Lnet/minecraft/world/level/block/state/BlockState;"
            + "Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;";
    private static final String SPAWN_ARGS = "(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/level/LevelAccessor;"
            + "Lnet/minecraft/world/entity/MobSpawnType;Lnet/minecraft/core/BlockPos;";
    private static final String TRUNK_PLACER = "net/minecraft/world/level/levelgen/feature/trunkplacers/TrunkPlacer";
    private static final String BONEMEALABLE = "net/minecraft/world/level/block/BonemealableBlock";
    private static final String PLACE_ARGS = "(Lnet/minecraft/world/level/LevelSimulatedReader;";
    private static final String BONEMEAL_ARGS = "(Lnet/minecraft/server/level/ServerLevel;";
    private static final String TEST_ARGS = "(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/level/ServerLevelAccessor;"
            + "Lnet/minecraft/world/entity/MobSpawnType;Lnet/minecraft/core/BlockPos;";

    private RetromodTransformer transformer;
    private URLClassLoader host;

    @BeforeEach
    void setUp(@TempDir Path hostDir) throws Exception {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        // The 1.19 host: each method takes a RandomSource where 1.18 took a Random.
        write(hostDir, hostClass(BLOCK, ACC_PUBLIC, "java/lang/Object", "tick", TICK_ARGS + SOURCE + ")V", 0));
        write(hostDir, hostClass(ANIMAL, ACC_PUBLIC, "java/lang/Object", "checkAnimalSpawnRules", SPAWN_ARGS + SOURCE + ")Z", ACC_STATIC));
        write(hostDir, hostClass(TRUNK_PLACER, ACC_PUBLIC | ACC_ABSTRACT, "java/lang/Object", "placeTrunk",
                PLACE_ARGS + SOURCE + "I)V", ACC_ABSTRACT));
        write(hostDir, hostClass(BONEMEALABLE, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, "java/lang/Object", "performBonemeal",
                BONEMEAL_ARGS + SOURCE + ")V", ACC_ABSTRACT));
        write(hostDir, hostClass(PREDICATE, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, "java/lang/Object", "test",
                TEST_ARGS + SOURCE + ")Z", ACC_ABSTRACT));
        host = new URLClassLoader(new URL[]{hostDir.toUri().toURL()}, null);
        ClassResourceInspector.setOfflineHost(host);
    }

    @AfterEach
    void tearDown() throws Exception {
        ClassResourceInspector.setOfflineHost(null);
        host.close();
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("a block's old tick override also answers the RandomSource tick, and its super call reaches the host's")
    void tickOverrideIsBridged() throws Exception {
        String name = "test/mm/BambooBlock";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, name, null, BLOCK, null);
        MethodVisitor tick = cw.visitMethod(ACC_PUBLIC, "tick", TICK_ARGS + RANDOM + ")V", null, null);
        tick.visitCode();
        for (int i = 0; i <= 4; i++) tick.visitVarInsn(ALOAD, i);
        tick.visitMethodInsn(INVOKESPECIAL, BLOCK, "tick", TICK_ARGS + RANDOM + ")V", false);
        tick.visitInsn(RETURN);
        tick.visitMaxs(0, 0);
        tick.visitEnd();
        cw.visitEnd();

        ClassNode out = apply(cw.toByteArray());
        MethodNode bridge = method(out, "tick", TICK_ARGS + SOURCE + ")V");
        assertNotNull(bridge, "the host calls the RandomSource tick, so the class must answer it");
        // A member the old override reaches that the host lacks makes the bridge log once and run the host's tick.
        assertEquals(List.of(LegacyRandomSourceAdapter.VIEW + ".wrap", name + ".tick",
                LegacyRandomCallAdapter.LOG + ".warnOnce", BLOCK + ".tick"), calls(bridge));
        assertEquals("java/lang/LinkageError", bridge.tryCatchBlocks.get(0).type);
        new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicVerifier())
                .analyze(name, bridge);
        MethodNode old = method(out, "tick", TICK_ARGS + RANDOM + ")V");
        assertEquals(List.of(LegacyRandomSourceAdapter.VIEW + ".source", BLOCK + ".tick"), calls(old));
        assertEquals(TICK_ARGS + SOURCE + ")V", ((MethodInsnNode) last(old, BLOCK + ".tick")).desc);
        new ClassReader(write(out)).accept(new CheckClassAdapter(new ClassWriter(0), false), 0);
    }

    @Test
    @DisplayName("an override of an abstract or interface method gets a plain bridge, since there is no host body to fall back to")
    void abstractAndInterfaceOverridesHaveNoFallback() {
        MethodNode trunk = method(apply(overrideClass("test/mm/PalmTrunkPlacer", TRUNK_PLACER, new String[0],
                "placeTrunk", PLACE_ARGS + RANDOM + "I)V")), "placeTrunk", PLACE_ARGS + SOURCE + "I)V");
        assertNotNull(trunk);
        assertTrue(trunk.tryCatchBlocks.isEmpty(), "a super call to an abstract method would throw AbstractMethodError");
        MethodNode bonemeal = method(apply(overrideClass("test/mm/BananaSapling", BLOCK, new String[]{BONEMEALABLE},
                "performBonemeal", BONEMEAL_ARGS + RANDOM + ")V")), "performBonemeal", BONEMEAL_ARGS + SOURCE + ")V");
        assertNotNull(bonemeal);
        assertTrue(bonemeal.tryCatchBlocks.isEmpty(), "Block has no performBonemeal for a super call to reach");
    }

    @Test
    @DisplayName("a call with a Random argument in any position goes to the RandomSource method")
    void staticCallIsRetargeted() {
        String name = "test/mm/MonkeyEntity";
        ClassNode out = apply(modClass(name, mv -> {
            for (int i = 0; i < 4; i++) mv.visitInsn(ACONST_NULL);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESTATIC, ANIMAL, "checkAnimalSpawnRules", SPAWN_ARGS + RANDOM + ")Z", false);
            mv.visitInsn(POP);
        }));
        MethodNode run = method(out, "run", "(" + RANDOM + ")V");
        MethodInsnNode call = (MethodInsnNode) last(run, ANIMAL + ".checkAnimalSpawnRules");
        assertEquals(SPAWN_ARGS + SOURCE + ")Z", call.desc);
        assertTrue(calls(run).contains(LegacyRandomSourceAdapter.VIEW + ".source"), "the Random must be converted first");
    }

    @Test
    @DisplayName("a spawn predicate method reference implements the RandomSource test through a bridge")
    void methodReferenceIsBridged() {
        String name = "test/mm/MonkeyEntity";
        String own = TEST_ARGS + RANDOM + ")Z";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor rule = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "checkMonkeySpawnRules", own, null, null);
        rule.visitCode();
        rule.visitInsn(ICONST_1);
        rule.visitInsn(IRETURN);
        rule.visitMaxs(0, 0);
        rule.visitEnd();
        MethodVisitor run = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "run", "()V", null, null);
        run.visitCode();
        Handle metafactory = new Handle(H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                + "Ljava/lang/invoke/CallSite;", false);
        run.visitInvokeDynamicInsn("test", "()L" + PREDICATE + ";", metafactory, Type.getMethodType(own),
                new Handle(H_INVOKESTATIC, name, "checkMonkeySpawnRules", own, false), Type.getMethodType(own));
        run.visitInsn(POP);
        run.visitInsn(RETURN);
        run.visitMaxs(0, 0);
        run.visitEnd();
        cw.visitEnd();

        ClassNode out = apply(cw.toByteArray());
        InvokeDynamicInsnNode indy = (InvokeDynamicInsnNode) instructions(method(out, "run", "()V")).stream()
                .filter(InvokeDynamicInsnNode.class::isInstance).findFirst().orElseThrow();
        assertEquals(TEST_ARGS + SOURCE + ")Z", ((Type) indy.bsmArgs[0]).getDescriptor());
        Handle impl = (Handle) indy.bsmArgs[1];
        assertEquals("checkMonkeySpawnRules" + LegacyRandomCallAdapter.LAMBDA_BRIDGE, impl.getName());
        MethodNode bridge = method(out, impl.getName(), impl.getDesc());
        assertEquals(List.of(LegacyRandomSourceAdapter.VIEW + ".wrap", name + ".checkMonkeySpawnRules"), calls(bridge));
    }

    @Test
    @DisplayName("a super call to the parent mod block's own old tick stays, so the bridge never calls itself")
    void superCallToTheJarsOwnOverrideStays() {
        String parent = "test/mm/BaseBlock";
        String child = "test/mm/BambooBlock";
        transformer.setJarClassBytesProvider(java.util.Map.of(parent, blockWithTick(parent, BLOCK))::get);
        try {
            ClassNode out = apply(blockWithTick(child, parent));
            MethodNode old = method(out, "tick", TICK_ARGS + RANDOM + ")V");
            MethodInsnNode superCall = (MethodInsnNode) last(old, parent + ".tick");
            assertEquals(TICK_ARGS + RANDOM + ")V", superCall.desc, "the parent's own Random tick answers the call");
        } finally {
            transformer.clearJarClassBytesProvider();
        }
    }

    /** A block class whose old tick calls its superclass's tick, as a 1.18 mod's override does. */
    private static byte[] blockWithTick(String name, String superName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, name, null, superName, null);
        MethodVisitor tick = cw.visitMethod(ACC_PUBLIC, "tick", TICK_ARGS + RANDOM + ")V", null, null);
        tick.visitCode();
        for (int i = 0; i <= 4; i++) tick.visitVarInsn(ALOAD, i);
        tick.visitMethodInsn(INVOKESPECIAL, superName, "tick", TICK_ARGS + RANDOM + ")V", false);
        tick.visitInsn(RETURN);
        tick.visitMaxs(0, 0);
        tick.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    @DisplayName("nothing changes where the host has no RandomSource form of the method")
    void unknownMethodIsLeftAlone() {
        byte[] in = modClass("test/mm/Other", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESTATIC, "net/minecraft/util/Mth", "nextInt", "(" + RANDOM + "II)I", false);
            mv.visitInsn(POP);
        });
        assertSame(in, LegacyRandomCallAdapter.apply(in, transformer));
    }

    private ClassNode apply(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(LegacyRandomCallAdapter.apply(bytes, transformer)).accept(node, 0);
        return node;
    }

    private static byte[] modClass(String name, java.util.function.Consumer<MethodVisitor> body) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "run", "(" + RANDOM + ")V", null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A mod class that overrides {@code method(desc)} with an empty body. */
    private static byte[] overrideClass(String name, String superName, String[] interfaces, String method, String desc) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, name, null, superName, interfaces);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, method, desc, null, null);
        mv.visitCode();
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] hostClass(String name, int access, String superName, String method, String desc, int methodAccess) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, access, name, null, superName, null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | methodAccess, method, desc, null, null);
        if ((methodAccess & ACC_ABSTRACT) == 0) {
            mv.visitCode();
            Type result = Type.getReturnType(desc);
            if (result.getSort() == Type.VOID) {
                mv.visitInsn(RETURN);
            } else {
                mv.visitInsn(ICONST_0);
                mv.visitInsn(IRETURN);
            }
            mv.visitMaxs(1, 8);
        }
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void write(Path dir, byte[] bytes) throws Exception {
        Path file = dir.resolve(new ClassReader(bytes).getClassName() + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    private static byte[] write(ClassNode node) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElse(null);
    }

    private static List<AbstractInsnNode> instructions(MethodNode method) {
        List<AbstractInsnNode> out = new ArrayList<>();
        method.instructions.forEach(out::add);
        return out;
    }

    private static List<String> calls(MethodNode method) {
        assertNotNull(method, "the method must exist");
        return instructions(method).stream().filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast)
                .map(call -> call.owner + "." + call.name).toList();
    }

    private static AbstractInsnNode last(MethodNode method, String call) {
        AbstractInsnNode found = null;
        for (AbstractInsnNode insn : instructions(method)) {
            if (insn instanceof MethodInsnNode m && (m.owner + "." + m.name).equals(call)) found = insn;
        }
        assertNotNull(found, call);
        return found;
    }
}
