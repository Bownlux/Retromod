/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #264 (Pyrologer And Friends): a 1.19.2 MCreator mod on Forge 1.20.1 that crashed a ticking
 * entity on {@code new BlockPos(double, double, double)}, and whose damage and explosion
 * procedures read {@code DamageSource.FALL}, {@code isExplosion()} and the old
 * {@code Level.explode} with {@code Explosion.BlockInteraction.BREAK}.
 */
class LegacyDamageAndExplosionBridgeTest {

    private static final String MOD_CLASS = "test/legacy/ModProcedure";
    private static final String DAMAGE_SOURCE = "net/minecraft/world/damagesource/DamageSource";
    private static final String L_DAMAGE_SOURCE = "L" + DAMAGE_SOURCE + ";";
    private static final String LEVEL = "net/minecraft/world/level/Level";
    private static final String BLOCK_INTERACTION = "net/minecraft/world/level/Explosion$BlockInteraction";
    private static final String BLOCK_POS = "net/minecraft/core/BlockPos";

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyDamageConstantBridge.register(transformer);
        LegacyExplosionBridge.register(transformer);
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    private static byte[] modClass(String superName, Consumer<MethodVisitor> body) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, MOD_CLASS, null, superName, null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "run", "()Ljava/lang/Object;", null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private List<AbstractInsnNode> transformedRun(byte[] original) {
        ClassNode cn = new ClassNode();
        new ClassReader(transformer.transformClass(original, MOD_CLASS)).accept(cn, 0);
        MethodNode run = cn.methods.stream().filter(m -> m.name.equals("run")).findFirst().orElseThrow();
        List<AbstractInsnNode> insns = new ArrayList<>();
        run.instructions.forEach(insns::add);
        return insns;
    }

    private static List<MethodInsnNode> calls(List<AbstractInsnNode> insns) {
        return insns.stream().filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast).toList();
    }

    @Test
    @DisplayName("a removed static damage source reads the running world's source")
    void staticDamageSourceBecomesGetter() {
        transformer.clearRedirectsForTesting();
        new com.retromod.shim.forge.Forge_1_19_3_to_1_19_4().registerRedirects(transformer);
        List<AbstractInsnNode> insns = transformedRun(modClass("java/lang/Object", mv ->
                mv.visitFieldInsn(Opcodes.GETSTATIC, DAMAGE_SOURCE, "FALL", L_DAMAGE_SOURCE)));
        assertTrue(insns.stream().noneMatch(FieldInsnNode.class::isInstance),
                "DamageSource.FALL no longer exists on 1.19.4 and newer");
        MethodInsnNode call = calls(insns).get(0);
        assertEquals(LegacyDamageConstantBridge.SYNTH, call.owner);
        assertEquals("fall", call.name);
        assertEquals("()" + L_DAMAGE_SOURCE, call.desc);
    }

    @Test
    @DisplayName("new DamageSource(id) becomes the vanilla source with that old message id")
    void namedDamageSourceBecomesFactory() {
        List<AbstractInsnNode> insns = transformedRun(modClass("java/lang/Object", mv -> {
            mv.visitTypeInsn(Opcodes.NEW, DAMAGE_SOURCE);
            mv.visitInsn(Opcodes.DUP);
            mv.visitLdcInsn("onFire");
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, DAMAGE_SOURCE, "<init>", "(Ljava/lang/String;)V", false);
        }));
        assertTrue(insns.stream().noneMatch(i -> i instanceof TypeInsnNode t && t.getOpcode() == Opcodes.NEW),
                "1.19.4 removed the string constructor; Mythic Fantasies' sacred flame used it");
        MethodInsnNode call = calls(insns).get(0);
        assertEquals(LegacyDamageConstantBridge.SYNTH, call.owner);
        assertEquals("named", call.name);
    }

    @Test
    @DisplayName("the 1.19.2 particle provider registration reaches registerSpriteSet")
    void particleProviderRegistrationIsRenamed() {
        transformer.clearRedirectsForTesting();
        new com.retromod.shim.forge.Forge_1_19_2_to_1_19_3().registerRedirects(transformer);
        String event = "net/minecraftforge/client/event/RegisterParticleProvidersEvent";
        String desc = "(Lnet/minecraft/core/particles/ParticleType;"
                + "Lnet/minecraft/client/particle/ParticleEngine$SpriteParticleRegistration;)V";
        List<AbstractInsnNode> insns = transformedRun(modClass("java/lang/Object", mv -> {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, event, "register", desc, false);
            mv.visitInsn(Opcodes.ACONST_NULL);
        }));
        MethodInsnNode call = calls(insns).get(0);
        assertEquals("registerSpriteSet", call.name);
        assertEquals(desc, call.desc);
    }

    @Test
    @DisplayName("a removed damage source predicate asks the damage type tag")
    void damageSourcePredicateBecomesTagCheck() {
        List<AbstractInsnNode> insns = transformedRun(modClass("java/lang/Object", mv -> {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, DAMAGE_SOURCE, "isExplosion", "()Z", false);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf",
                    "(Z)Ljava/lang/Boolean;", false);
        }));
        MethodInsnNode call = calls(insns).get(0);
        assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
        assertEquals(LegacyDamageConstantBridge.SYNTH, call.owner);
        assertEquals("legacyIsExplosion", call.name);
        assertEquals("(" + L_DAMAGE_SOURCE + ")Z", call.desc);
    }

    @Test
    @DisplayName("an old explode call and its BREAK constant reach the 1.19.3 overload")
    void legacyExplosionIsBridged() {
        String oldDesc = "(Lnet/minecraft/world/entity/Entity;DDDFL" + BLOCK_INTERACTION
                + ";)Lnet/minecraft/world/level/Explosion;";
        List<AbstractInsnNode> insns = transformedRun(modClass("java/lang/Object", mv -> {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.DCONST_0);
            mv.visitInsn(Opcodes.DCONST_0);
            mv.visitInsn(Opcodes.DCONST_0);
            mv.visitInsn(Opcodes.FCONST_2);
            mv.visitFieldInsn(Opcodes.GETSTATIC, BLOCK_INTERACTION, "BREAK", "L" + BLOCK_INTERACTION + ";");
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LEVEL, "explode", oldDesc, false);
        }));
        FieldInsnNode constant = insns.stream().filter(FieldInsnNode.class::isInstance)
                .map(FieldInsnNode.class::cast).findFirst().orElseThrow();
        assertEquals("DESTROY", constant.name, "BREAK was renamed DESTROY in 1.19.3");
        MethodInsnNode call = calls(insns).get(0);
        assertEquals(LegacyExplosionBridge.GENERATED, call.owner);
        assertEquals("(L" + LEVEL + ";" + oldDesc.substring(1), call.desc);
    }

    @Test
    @DisplayName("new BlockPos(double, double, double) becomes BlockPos.containing")
    void doubleBlockPosBecomesContaining() {
        transformer.clearRedirectsForTesting();
        new com.retromod.shim.forge.Forge_1_19_3_to_1_19_4().registerRedirects(transformer);
        List<AbstractInsnNode> insns = transformedRun(modClass("java/lang/Object", mv -> {
            mv.visitTypeInsn(Opcodes.NEW, BLOCK_POS);
            mv.visitInsn(Opcodes.DUP);
            mv.visitInsn(Opcodes.DCONST_1);
            mv.visitInsn(Opcodes.DCONST_1);
            mv.visitInsn(Opcodes.DCONST_1);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, BLOCK_POS, "<init>", "(DDD)V", false);
        }));
        assertTrue(insns.stream().noneMatch(i -> i instanceof TypeInsnNode t && t.getOpcode() == Opcodes.NEW),
                "the removed constructor must not be allocated");
        MethodInsnNode call = calls(insns).get(0);
        assertEquals("containing", call.name);
        assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
    }

    @Test
    @DisplayName("the generated damage and explosion classes pass data-flow checks")
    void generatedClassesAreWellFormed() {
        for (byte[] bytes : new byte[][]{LegacyDamageConstantBridge.generate(), LegacyExplosionBridge.generate()}) {
            assertDoesNotThrow(() -> new ClassReader(bytes)
                    .accept(new CheckClassAdapter(new ClassWriter(0), true), 0));
        }
    }

    @Test
    @DisplayName("the server lookup is resolved once and then only invoked")
    void serverLookupIsCached() throws Exception {
        String hooks = "net/minecraftforge/server/ServerLifecycleHooks";
        java.util.Map<String, byte[]> classes = new java.util.HashMap<>();
        // Only the lookup methods are loaded: the rest name Minecraft types the verifier would load.
        ClassNode lookupOnly = new ClassNode();
        new ClassReader(LegacyDamageConstantBridge.generate()).accept(lookupOnly, 0);
        lookupOnly.methods.removeIf(m -> !List.of("getter", "serverGetter", "currentServer").contains(m.name));
        ClassWriter lookupWriter = new ClassWriter(0);
        lookupOnly.accept(lookupWriter);
        classes.put(LegacyDamageConstantBridge.SYNTH, lookupWriter.toByteArray());
        classes.put(hooks, serverHooks(hooks));
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name.replace('.', '/'));
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        Class<?> constants = loader.loadClass(LegacyDamageConstantBridge.SYNTH.replace('/', '.'));
        java.lang.reflect.Method currentServer = constants.getDeclaredMethod("currentServer");
        currentServer.setAccessible(true);
        java.lang.reflect.Field resolved = constants.getDeclaredField("serverGetterResolved");
        resolved.setAccessible(true);
        java.lang.reflect.Field getter = constants.getDeclaredField("serverGetter");
        getter.setAccessible(true);

        assertFalse((boolean) resolved.get(null), "nothing is looked up before the first read");
        Object first = currentServer.invoke(null);
        assertEquals("the server", first, "the read returns the loader's current server");
        assertTrue((boolean) resolved.get(null));
        java.lang.reflect.Method cached = (java.lang.reflect.Method) getter.get(null);
        assertNotNull(cached, "the Forge hook was found and kept");
        assertEquals("the server", currentServer.invoke(null));
        assertSame(cached, getter.get(null), "a second read reuses the cached getter");

        ClassNode node = new ClassNode();
        new ClassReader(LegacyDamageConstantBridge.generate()).accept(node, 0);
        MethodNode sources = node.methods.stream().filter(m -> m.name.equals("sources")).findFirst().orElseThrow();
        for (AbstractInsnNode insn : sources.instructions) {
            if (insn instanceof MethodInsnNode call) {
                assertNotEquals("forName", call.name, "sources() must not look the hook class up per read");
            }
        }
    }

    /** {@code class ServerLifecycleHooks { static Object getCurrentServer() { return "the server"; } }} */
    private static byte[] serverHooks(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getCurrentServer",
                "()Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitLdcInsn("the server");
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    @DisplayName("an inherited call through a rebase base takes the host's SRG name")
    void inheritedSrgNameResolvesThroughRebaseBase() {
        String hostDoor = "test/host/DoorBlock";
        String hostBase = "test/host/BlockBehaviour";
        String legacyDoor = "com/retromod/generated/TestLegacyDoorBlock";
        String desc = "()Ljava/util/List;";
        transformer.registerSyntheticClass(hostBase, emptyClass(hostBase, "java/lang/Object"));
        transformer.registerSyntheticClass(hostDoor, emptyClass(hostDoor, hostBase));
        transformer.registerSyntheticClass(legacyDoor, emptyClass(legacyDoor, hostDoor));
        transformer.registerSuperclassRebase(hostDoor, legacyDoor);
        transformer.registerTargetSrgNameMappings(Map.of(
                com.retromod.mapping.TargetSrgMapper.memberKey(hostBase, "getDrops", desc), "m_49635_"),
                Map.of());

        List<AbstractInsnNode> insns = transformedRun(modClass(hostDoor, mv -> {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, legacyDoor, "getDrops", desc, false);
        }));
        assertEquals("m_49635_", calls(insns).get(0).name,
                "the generated door base extends the class it replaces; the walk must not loop on it");
    }

    private static byte[] emptyClass(String name, String superName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, superName, null);
        cw.visitEnd();
        return cw.toByteArray();
    }
}
