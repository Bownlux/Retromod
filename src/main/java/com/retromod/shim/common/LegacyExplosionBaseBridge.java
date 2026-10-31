/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.embedded.LegacyExplosionFinisher;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static org.objectweb.asm.Opcodes.*;

/** Keeps a 1.21.1 mod's {@code Explosion} subclasses and explosion calls working on 1.21.2 and newer, where {@code Explosion} is an interface. */
public final class LegacyExplosionBaseBridge {

    static final String BASE = "com/retromod/generated/LegacyServerExplosion";
    static final String RECORDER = "com/retromod/generated/LegacyExplosionBlockRecorder";
    static final String CALLS = "com/retromod/generated/LegacyExplosionCalls";
    static final String EVENTS = "com/retromod/generated/LegacyExplosionEvents";
    static final String SUPPORT = "com/retromod/shim/common/embedded/LegacyExplosionFinisher";

    static final String EXPLOSION = "net/minecraft/world/level/Explosion";
    static final String SERVER_EXPLOSION = "net/minecraft/world/level/ServerExplosion";
    private static final String INTERACTION = EXPLOSION + "$BlockInteraction";
    private static final String CALCULATOR = "net/minecraft/world/level/ExplosionDamageCalculator";
    private static final String ENTITY_CALCULATOR = "net/minecraft/world/level/EntityBasedExplosionDamageCalculator";
    private static final String LEVEL = "net/minecraft/world/level/Level";
    private static final String SERVER_LEVEL = "net/minecraft/server/level/ServerLevel";
    private static final String ENTITY = "net/minecraft/world/entity/Entity";
    private static final String DAMAGE = "net/minecraft/world/damagesource/DamageSource";
    private static final String VEC3 = "net/minecraft/world/phys/Vec3";
    private static final String BLOCK_POS = "net/minecraft/core/BlockPos";
    private static final String STATE = "net/minecraft/world/level/block/state/BlockState";
    private static final String STATE_BASE = "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase";
    private static final String PARTICLE = "net/minecraft/core/particles/ParticleOptions";
    private static final String HOLDER = "net/minecraft/core/Holder";
    private static final String PROFILER = "net/minecraft/util/profiling/ProfilerFiller";
    private static final String BLOCKS = "it/unimi/dsi/fastutil/objects/ObjectArrayList";
    private static final String LIST = "java/util/List";
    private static final String SET = "java/util/Set";

    // The old constructors, which a subclass's super(...) call keeps after the rebase.
    static final String PLAIN = "(L" + LEVEL + ";L" + ENTITY + ";DDDFZL" + INTERACTION + ";)V";
    static final String WITH_BLOCKS = "(L" + LEVEL + ";L" + ENTITY + ";DDDFZL" + INTERACTION + ";L" + LIST + ";)V";
    static final String WITH_EFFECTS = "(L" + LEVEL + ";L" + ENTITY + ";DDDFL" + LIST + ";L" + INTERACTION + ";L"
            + PARTICLE + ";L" + PARTICLE + ";L" + HOLDER + ";)V";
    static final String FULL = "(L" + LEVEL + ";L" + ENTITY + ";L" + DAMAGE + ";L" + CALCULATOR + ";DDDFZL" + INTERACTION
            + ";L" + PARTICLE + ";L" + PARTICLE + ";L" + HOLDER + ";)V";
    // Every old argument plus the starting block list; the public constructors fill in the rest.
    private static final String ALL = "(L" + LEVEL + ";L" + ENTITY + ";L" + DAMAGE + ";L" + CALCULATOR + ";DDDFZL"
            + INTERACTION + ";L" + PARTICLE + ";L" + PARTICLE + ";L" + HOLDER + ";L" + LIST + ";)V";
    private static final String SERVER_CTOR = "(L" + SERVER_LEVEL + ";L" + ENTITY + ";L" + DAMAGE + ";L" + CALCULATOR
            + ";L" + VEC3 + ";FZL" + INTERACTION + ";)V";
    private static final String OLD_HIT = "(L" + LEVEL + ";L" + BLOCK_POS + ";L" + EXPLOSION + ";Ljava/util/function/BiConsumer;)V";

    private LegacyExplosionBaseBridge() {}

    /** Registers on a host whose {@code Explosion} is an interface. */
    public static void register(RetromodTransformer t) {
        if (!explosionIsInterface()) return;
        SyntheticEmbedder.registerClassResource(t, SUPPORT, LegacyExplosionFinisher.class);
        t.registerSyntheticClass(RECORDER, generateRecorder(hostCalculatorMethods()));
        t.registerSyntheticClass(BASE, generateBase());
        t.registerSyntheticClass(CALLS, generateCalls());
        // A subclass moves onto the generated server explosion, which keeps the old constructors.
        t.registerSuperclassRebase(EXPLOSION, BASE);
        // Level.getProfiler() gave way to the thread's Profiler.get().
        t.registerInheritedMethodRedirect(LEVEL, "getProfiler", "()L" + PROFILER + ";",
                CALLS, "profiler", "(L" + LEVEL + ";)L" + PROFILER + ";");
        // A block hit by an explosion needs a server level now; a client level only shows it.
        for (String owner : List.of(STATE, STATE_BASE)) {
            t.registerMethodRedirect(owner, "onExplosionHit", OLD_HIT, CALLS, "onExplosionHit",
                    "(L" + STATE_BASE + ";" + OLD_HIT.substring(1), true);
        }
    }

    /** NeoForge's explosion start hook, which takes a server explosion from 1.21.2. */
    public static void registerNeoForge(RetromodTransformer t) {
        if (!explosionIsInterface()) return;
        t.registerSyntheticClass(EVENTS, generateEvents());
        String oldStart = "(L" + LEVEL + ";L" + EXPLOSION + ";)Z";
        t.registerMethodRedirect("net/neoforged/neoforge/event/EventHooks", "onExplosionStart", oldStart,
                EVENTS, "onExplosionStart", oldStart);
    }

    // Offline, where the host cannot be read, the version decides.
    private static boolean explosionIsInterface() {
        ClassNode explosion = ClassResourceInspector.read(EXPLOSION);
        if (explosion != null) return (explosion.access & ACC_INTERFACE) != 0;
        return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "1.21.2") >= 0;
    }

    /** The calculator's overridable methods on this host, so the recorder forwards every one. */
    static List<String[]> hostCalculatorMethods() {
        List<String[]> methods = new ArrayList<>();
        ClassNode calculator = ClassResourceInspector.read(CALCULATOR);
        if (calculator != null) {
            for (MethodNode method : calculator.methods) {
                if ((method.access & (ACC_STATIC | ACC_PRIVATE | ACC_FINAL)) != 0 || method.name.startsWith("<")) continue;
                methods.add(new String[]{method.name, method.desc});
            }
            return methods;
        }
        String explosion = "L" + EXPLOSION + ";";
        String entity = "L" + ENTITY + ";";
        methods.add(new String[]{"getBlockExplosionResistance", "(" + explosion + "Lnet/minecraft/world/level/BlockGetter;L"
                + BLOCK_POS + ";L" + STATE + ";Lnet/minecraft/world/level/material/FluidState;)Ljava/util/Optional;"});
        methods.add(new String[]{"shouldBlockExplode", "(" + explosion + "Lnet/minecraft/world/level/BlockGetter;L"
                + BLOCK_POS + ";L" + STATE + ";F)Z"});
        methods.add(new String[]{"shouldDamageEntity", "(" + explosion + entity + ")Z"});
        methods.add(new String[]{"getKnockbackMultiplier", "(" + entity + ")F"});
        methods.add(new String[]{"getEntityDamageAmount", "(" + explosion + entity + "F)F"});
        return methods;
    }

    /** A calculator that answers through another one, and while collecting keeps the blocks to break for later. */
    static byte[] generateRecorder(List<String[]> calculatorMethods) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_SUPER, RECORDER, null, CALCULATOR, null);
        cw.visitField(ACC_PUBLIC | ACC_FINAL, "delegate", "L" + CALCULATOR + ";", null, null).visitEnd();
        cw.visitField(ACC_PUBLIC, "collecting", "Z", null, null).visitEnd();
        cw.visitField(ACC_PUBLIC | ACC_FINAL, "positions", "L" + SET + ";", null, null).visitEnd();

        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(L" + CALCULATOR + ";)V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, CALCULATOR, "<init>", "()V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitFieldInsn(PUTFIELD, RECORDER, "delegate", "L" + CALCULATOR + ";");
        init.visitVarInsn(ALOAD, 0);
        init.visitTypeInsn(NEW, "java/util/LinkedHashSet");
        init.visitInsn(DUP);
        init.visitMethodInsn(INVOKESPECIAL, "java/util/LinkedHashSet", "<init>", "()V", false);
        init.visitFieldInsn(PUTFIELD, RECORDER, "positions", "L" + SET + ";");
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        for (String[] method : calculatorMethods) {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, method[0], method[1], null, null);
            mv.visitCode();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, RECORDER, "delegate", "L" + CALCULATOR + ";");
            int slot = 1;
            for (Type argument : Type.getArgumentTypes(method[1])) {
                mv.visitVarInsn(argument.getOpcode(ILOAD), slot);
                slot += argument.getSize();
            }
            mv.visitMethodInsn(INVOKEVIRTUAL, CALCULATOR, method[0], method[1], false);
            if (method[0].equals("shouldBlockExplode")) {
                // A block that would break is kept for finalizeExplosion and spared now.
                Label keep = new Label();
                mv.visitInsn(DUP);
                mv.visitJumpInsn(IFEQ, keep);
                mv.visitVarInsn(ALOAD, 0);
                mv.visitFieldInsn(GETFIELD, RECORDER, "collecting", "Z");
                mv.visitJumpInsn(IFEQ, keep);
                mv.visitInsn(POP);
                mv.visitVarInsn(ALOAD, 0);
                mv.visitFieldInsn(GETFIELD, RECORDER, "positions", "L" + SET + ";");
                mv.visitVarInsn(ALOAD, 3);
                mv.visitMethodInsn(INVOKEVIRTUAL, BLOCK_POS, "immutable", "()L" + BLOCK_POS + ";", false);
                mv.visitMethodInsn(INVOKEINTERFACE, SET, "add", "(Ljava/lang/Object;)Z", true);
                mv.visitInsn(POP);
                mv.visitInsn(ICONST_0);
                mv.visitInsn(IRETURN);
                mv.visitLabel(keep);
                mv.visitFrame(F_SAME1, 0, null, 1, new Object[]{INTEGER});
            }
            mv.visitInsn(Type.getReturnType(method[1]).getOpcode(IRETURN));
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The server explosion old subclasses extend: the 1.21.1 constructors and the two-step explode. */
    static byte[] generateBase() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_SUPER, BASE, null, SERVER_EXPLOSION, null);
        field(cw, "retromod$level", "L" + LEVEL + ";");
        field(cw, "retromod$recorder", "L" + RECORDER + ";");
        field(cw, "retromod$toBlow", "L" + BLOCKS + ";");
        field(cw, "retromod$fire", "Z");
        field(cw, "retromod$small", "L" + PARTICLE + ";");
        field(cw, "retromod$large", "L" + PARTICLE + ";");
        field(cw, "retromod$sound", "L" + HOLDER + ";");

        // Each old constructor names where the full one's arguments come from: its own, or a default.
        delegatingConstructor(cw, PLAIN, "0", "1", "null", "null", "2", "3", "4", "5", "6", "7", "small", "large", "sound", "null");
        delegatingConstructor(cw, WITH_BLOCKS, "0", "1", "null", "null", "2", "3", "4", "5", "6", "7", "small", "large", "sound", "8");
        delegatingConstructor(cw, WITH_EFFECTS, "0", "1", "null", "null", "2", "3", "4", "5", "false", "7", "8", "9", "10", "6");
        delegatingConstructor(cw, FULL, "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "null");
        fullConstructor(cw);
        constructionHelpers(cw);

        explode(cw);
        finalizeExplosion(cw);
        interactsWithBlocks(cw);
        getter(cw, "getToBlow", "()L" + LIST + ";", "retromod$toBlow", "L" + BLOCKS + ";");
        getter(cw, "getExplosionSound", "()L" + HOLDER + ";", "retromod$sound", "L" + HOLDER + ";");
        getter(cw, "getSmallExplosionParticles", "()L" + PARTICLE + ";", "retromod$small", "L" + PARTICLE + ";");
        getter(cw, "getLargeExplosionParticles", "()L" + PARTICLE + ";", "retromod$large", "L" + PARTICLE + ";");

        MethodVisitor clear = cw.visitMethod(ACC_PUBLIC, "clearToBlow", "()V", null, null);
        clear.visitCode();
        clear.visitVarInsn(ALOAD, 0);
        clear.visitFieldInsn(GETFIELD, BASE, "retromod$toBlow", "L" + BLOCKS + ";");
        clear.visitMethodInsn(INVOKEINTERFACE, LIST, "clear", "()V", true);
        clear.visitInsn(RETURN);
        clear.visitMaxs(0, 0);
        clear.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void field(ClassWriter cw, String name, String descriptor) {
        cw.visitField(ACC_PRIVATE, name, descriptor, null, null).visitEnd();
    }

    /** A public old constructor that calls the full one, each argument taken from {@code sources}. */
    private static void delegatingConstructor(ClassWriter cw, String descriptor, String... sources) {
        Type[] own = Type.getArgumentTypes(descriptor);
        Type[] full = Type.getArgumentTypes(ALL);
        int[] slots = new int[own.length];
        int slot = 1;
        for (int i = 0; i < own.length; i++) {
            slots[i] = slot;
            slot += own[i].getSize();
        }
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", descriptor, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        for (int i = 0; i < full.length; i++) {
            switch (sources[i]) {
                case "null" -> mv.visitInsn(ACONST_NULL);
                case "false" -> mv.visitInsn(ICONST_0);
                // 1.21.1's defaults: the explosion particle, its emitter, and the generic explosion sound.
                case "small" -> mv.visitFieldInsn(GETSTATIC, "net/minecraft/core/particles/ParticleTypes", "EXPLOSION",
                        "Lnet/minecraft/core/particles/SimpleParticleType;");
                case "large" -> mv.visitFieldInsn(GETSTATIC, "net/minecraft/core/particles/ParticleTypes", "EXPLOSION_EMITTER",
                        "Lnet/minecraft/core/particles/SimpleParticleType;");
                case "sound" -> mv.visitFieldInsn(GETSTATIC, "net/minecraft/sounds/SoundEvents", "GENERIC_EXPLODE",
                        "L" + HOLDER + "$Reference;");
                default -> {
                    int index = Integer.parseInt(sources[i]);
                    mv.visitVarInsn(own[index].getOpcode(ILOAD), slots[index]);
                }
            }
        }
        mv.visitMethodInsn(INVOKESPECIAL, BASE, "<init>", ALL, false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** Builds the server explosion around a recording calculator and keeps the old arguments. */
    private static void fullConstructor(ClassWriter cw) {
        // Slots: level 1, entity 2, damage 3, calculator 4, x 5, y 7, z 9, radius 11, fire 12,
        // interaction 13, small 14, large 15, sound 16, blocks 17, recorder 18.
        MethodVisitor mv = cw.visitMethod(ACC_PRIVATE, "<init>", ALL, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        // A client level has no server explosion behind it, so its level stays null there.
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESTATIC, BASE, "retromod$serverLevel", "(L" + LEVEL + ";)L" + SERVER_LEVEL + ";", false);
        mv.visitVarInsn(ALOAD, 2);
        // The damage source is made here, because the server explosion would ask its null level.
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKESTATIC, BASE, "retromod$damage",
                "(L" + DAMAGE + ";L" + LEVEL + ";L" + ENTITY + ";)L" + DAMAGE + ";", false);
        mv.visitTypeInsn(NEW, RECORDER);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKESTATIC, BASE, "retromod$calculator",
                "(L" + CALCULATOR + ";L" + ENTITY + ";)L" + CALCULATOR + ";", false);
        mv.visitMethodInsn(INVOKESPECIAL, RECORDER, "<init>", "(L" + CALCULATOR + ";)V", false);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ASTORE, 18);
        mv.visitTypeInsn(NEW, VEC3);
        mv.visitInsn(DUP);
        mv.visitVarInsn(DLOAD, 5);
        mv.visitVarInsn(DLOAD, 7);
        mv.visitVarInsn(DLOAD, 9);
        mv.visitMethodInsn(INVOKESPECIAL, VEC3, "<init>", "(DDD)V", false);
        mv.visitVarInsn(FLOAD, 11);
        mv.visitVarInsn(ILOAD, 12);
        mv.visitVarInsn(ALOAD, 13);
        mv.visitMethodInsn(INVOKESPECIAL, SERVER_EXPLOSION, "<init>", SERVER_CTOR, false);
        store(mv, ALOAD, 1, "retromod$level", "L" + LEVEL + ";");
        store(mv, ALOAD, 18, "retromod$recorder", "L" + RECORDER + ";");
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 17);
        mv.visitMethodInsn(INVOKESTATIC, BASE, "retromod$blocks", "(L" + LIST + ";)L" + BLOCKS + ";", false);
        mv.visitFieldInsn(PUTFIELD, BASE, "retromod$toBlow", "L" + BLOCKS + ";");
        store(mv, ILOAD, 12, "retromod$fire", "Z");
        store(mv, ALOAD, 14, "retromod$small", "L" + PARTICLE + ";");
        store(mv, ALOAD, 15, "retromod$large", "L" + PARTICLE + ";");
        store(mv, ALOAD, 16, "retromod$sound", "L" + HOLDER + ";");
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void store(MethodVisitor mv, int load, int slot, String field, String descriptor) {
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(load, slot);
        mv.visitFieldInsn(PUTFIELD, BASE, field, descriptor);
    }

    /** Static helpers the full constructor calls before the object exists. */
    private static void constructionHelpers(ClassWriter cw) {
        MethodVisitor server = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, "retromod$serverLevel",
                "(L" + LEVEL + ";)L" + SERVER_LEVEL + ";", null, null);
        server.visitCode();
        Label client = new Label();
        server.visitVarInsn(ALOAD, 0);
        server.visitTypeInsn(INSTANCEOF, SERVER_LEVEL);
        server.visitJumpInsn(IFEQ, client);
        server.visitVarInsn(ALOAD, 0);
        server.visitTypeInsn(CHECKCAST, SERVER_LEVEL);
        server.visitInsn(ARETURN);
        server.visitLabel(client);
        server.visitFrame(F_SAME, 0, null, 0, null);
        server.visitInsn(ACONST_NULL);
        server.visitInsn(ARETURN);
        server.visitMaxs(0, 0);
        server.visitEnd();

        MethodVisitor damage = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, "retromod$damage",
                "(L" + DAMAGE + ";L" + LEVEL + ";L" + ENTITY + ";)L" + DAMAGE + ";", null, null);
        damage.visitCode();
        Label byDefault = new Label();
        damage.visitVarInsn(ALOAD, 0);
        damage.visitJumpInsn(IFNULL, byDefault);
        damage.visitVarInsn(ALOAD, 0);
        damage.visitInsn(ARETURN);
        damage.visitLabel(byDefault);
        damage.visitFrame(F_SAME, 0, null, 0, null);
        damage.visitVarInsn(ALOAD, 1);
        damage.visitVarInsn(ALOAD, 2);
        damage.visitMethodInsn(INVOKESTATIC, EXPLOSION, "getDefaultDamageSource",
                "(L" + LEVEL + ";L" + ENTITY + ";)L" + DAMAGE + ";", true);
        damage.visitInsn(ARETURN);
        damage.visitMaxs(0, 0);
        damage.visitEnd();

        // No calculator meant the plain one, or one that asks the source entity, as before.
        MethodVisitor calculator = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, "retromod$calculator",
                "(L" + CALCULATOR + ";L" + ENTITY + ";)L" + CALCULATOR + ";", null, null);
        calculator.visitCode();
        Label missing = new Label();
        Label fromEntity = new Label();
        calculator.visitVarInsn(ALOAD, 0);
        calculator.visitJumpInsn(IFNULL, missing);
        calculator.visitVarInsn(ALOAD, 0);
        calculator.visitInsn(ARETURN);
        calculator.visitLabel(missing);
        calculator.visitFrame(F_SAME, 0, null, 0, null);
        calculator.visitVarInsn(ALOAD, 1);
        calculator.visitJumpInsn(IFNONNULL, fromEntity);
        calculator.visitTypeInsn(NEW, CALCULATOR);
        calculator.visitInsn(DUP);
        calculator.visitMethodInsn(INVOKESPECIAL, CALCULATOR, "<init>", "()V", false);
        calculator.visitInsn(ARETURN);
        calculator.visitLabel(fromEntity);
        calculator.visitFrame(F_SAME, 0, null, 0, null);
        calculator.visitTypeInsn(NEW, ENTITY_CALCULATOR);
        calculator.visitInsn(DUP);
        calculator.visitVarInsn(ALOAD, 1);
        calculator.visitMethodInsn(INVOKESPECIAL, ENTITY_CALCULATOR, "<init>", "(L" + ENTITY + ";)V", false);
        calculator.visitInsn(ARETURN);
        calculator.visitMaxs(0, 0);
        calculator.visitEnd();

        MethodVisitor blocks = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, "retromod$blocks",
                "(L" + LIST + ";)L" + BLOCKS + ";", null, null);
        blocks.visitCode();
        Label empty = new Label();
        blocks.visitTypeInsn(NEW, BLOCKS);
        blocks.visitInsn(DUP);
        blocks.visitMethodInsn(INVOKESPECIAL, BLOCKS, "<init>", "()V", false);
        blocks.visitVarInsn(ASTORE, 1);
        blocks.visitVarInsn(ALOAD, 0);
        blocks.visitJumpInsn(IFNULL, empty);
        blocks.visitVarInsn(ALOAD, 1);
        blocks.visitVarInsn(ALOAD, 0);
        blocks.visitMethodInsn(INVOKEINTERFACE, LIST, "addAll", "(Ljava/util/Collection;)Z", true);
        blocks.visitInsn(POP);
        blocks.visitLabel(empty);
        blocks.visitFrame(F_APPEND, 1, new Object[]{BLOCKS}, 0, null);
        blocks.visitVarInsn(ALOAD, 1);
        blocks.visitInsn(ARETURN);
        blocks.visitMaxs(0, 0);
        blocks.visitEnd();
    }

    /** {@code explode()}: the server explosion hurts entities now, and its blocks wait for {@code finalizeExplosion}. */
    private static void explode(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "explode", "()V", null, null);
        mv.visitCode();
        Label server = new Label();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, BASE, "retromod$level", "L" + LEVEL + ";");
        mv.visitTypeInsn(INSTANCEOF, SERVER_LEVEL);
        mv.visitJumpInsn(IFNE, server);
        mv.visitInsn(RETURN);
        mv.visitLabel(server);
        mv.visitFrame(F_SAME, 0, null, 0, null);
        recording(mv, ICONST_1);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, SERVER_EXPLOSION, "explode", "()I", false);
        mv.visitInsn(POP);
        recording(mv, ICONST_0);
        // The blocks it would have broken become the old list, which a mod may still change.
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, BASE, "retromod$toBlow", "L" + BLOCKS + ";");
        recorderPositions(mv);
        mv.visitMethodInsn(INVOKEINTERFACE, LIST, "addAll", "(Ljava/util/Collection;)Z", true);
        mv.visitInsn(POP);
        recorderPositions(mv);
        mv.visitMethodInsn(INVOKEINTERFACE, SET, "clear", "()V", true);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void recording(MethodVisitor mv, int flag) {
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, BASE, "retromod$recorder", "L" + RECORDER + ";");
        mv.visitInsn(flag);
        mv.visitFieldInsn(PUTFIELD, RECORDER, "collecting", "Z");
    }

    private static void recorderPositions(MethodVisitor mv) {
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, BASE, "retromod$recorder", "L" + RECORDER + ";");
        mv.visitFieldInsn(GETFIELD, RECORDER, "positions", "L" + SET + ";");
    }

    /** {@code finalizeExplosion(spawnParticles)}, run by Retromod's helper. */
    private static void finalizeExplosion(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "finalizeExplosion", "(Z)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, BASE, "retromod$level", "L" + LEVEL + ";");
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, BASE, "retromod$toBlow", "L" + BLOCKS + ";");
        // A subclass may override interactsWithBlocks, as it could in 1.21.1.
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, BASE, "interactsWithBlocks", "()Z", false);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, BASE, "retromod$fire", "Z");
        mv.visitVarInsn(ILOAD, 1);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, BASE, "retromod$sound", "L" + HOLDER + ";");
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, BASE, "retromod$small", "L" + PARTICLE + ";");
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, BASE, "retromod$large", "L" + PARTICLE + ";");
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, BASE, "center", "()L" + VEC3 + ";", false);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, BASE, "radius", "()F", false);
        mv.visitMethodInsn(INVOKESTATIC, SUPPORT, "finalizeExplosion", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/util/List;"
                + "ZZZLjava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;F)V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code interactsWithBlocks()}: anything but keeping the blocks. The host's own is private. */
    private static void interactsWithBlocks(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "interactsWithBlocks", "()Z", null, null);
        mv.visitCode();
        Label keeps = new Label();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, BASE, "getBlockInteraction", "()L" + INTERACTION + ";", false);
        mv.visitFieldInsn(GETSTATIC, INTERACTION, "KEEP", "L" + INTERACTION + ";");
        mv.visitJumpInsn(IF_ACMPEQ, keeps);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IRETURN);
        mv.visitLabel(keeps);
        mv.visitFrame(F_SAME, 0, null, 0, null);
        mv.visitInsn(ICONST_0);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void getter(ClassWriter cw, String name, String descriptor, String field, String fieldDescriptor) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, name, descriptor, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, BASE, field, fieldDescriptor);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code profiler(level)} and {@code onExplosionHit(state, level, pos, explosion, drops)}. */
    static byte[] generateCalls() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, CALLS, null, "java/lang/Object", null);
        MethodVisitor profiler = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "profiler", "(L" + LEVEL + ";)L" + PROFILER + ";", null, null);
        profiler.visitCode();
        profiler.visitMethodInsn(INVOKESTATIC, "net/minecraft/util/profiling/Profiler", "get", "()L" + PROFILER + ";", false);
        profiler.visitInsn(ARETURN);
        profiler.visitMaxs(0, 0);
        profiler.visitEnd();

        MethodVisitor hit = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "onExplosionHit",
                "(L" + STATE_BASE + ";" + OLD_HIT.substring(1), null, null);
        hit.visitCode();
        Label client = new Label();
        hit.visitVarInsn(ALOAD, 1);
        hit.visitTypeInsn(INSTANCEOF, SERVER_LEVEL);
        hit.visitJumpInsn(IFEQ, client);
        hit.visitVarInsn(ALOAD, 0);
        hit.visitVarInsn(ALOAD, 1);
        hit.visitTypeInsn(CHECKCAST, SERVER_LEVEL);
        hit.visitVarInsn(ALOAD, 2);
        hit.visitVarInsn(ALOAD, 3);
        hit.visitVarInsn(ALOAD, 4);
        hit.visitMethodInsn(INVOKEVIRTUAL, STATE_BASE, "onExplosionHit",
                "(L" + SERVER_LEVEL + ";L" + BLOCK_POS + ";L" + EXPLOSION + ";Ljava/util/function/BiConsumer;)V", false);
        hit.visitLabel(client);
        hit.visitFrame(F_SAME, 0, null, 0, null);
        hit.visitInsn(RETURN);
        hit.visitMaxs(0, 0);
        hit.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code onExplosionStart(level, explosion)}, which only a server explosion can start now. */
    static byte[] generateEvents() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, EVENTS, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "onExplosionStart",
                "(L" + LEVEL + ";L" + EXPLOSION + ";)Z", null, null);
        mv.visitCode();
        Label other = new Label();
        mv.visitVarInsn(ALOAD, 1);
        mv.visitTypeInsn(INSTANCEOF, SERVER_EXPLOSION);
        mv.visitJumpInsn(IFEQ, other);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitTypeInsn(CHECKCAST, SERVER_EXPLOSION);
        mv.visitMethodInsn(INVOKESTATIC, "net/neoforged/neoforge/event/EventHooks", "onExplosionStart",
                "(L" + LEVEL + ";L" + SERVER_EXPLOSION + ";)Z", false);
        mv.visitInsn(IRETURN);
        mv.visitLabel(other);
        mv.visitFrame(F_SAME, 0, null, 0, null);
        mv.visitInsn(ICONST_0);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
