/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.nio.charset.StandardCharsets;

import static org.objectweb.asm.Opcodes.*;

/**
 * The 1.12.2 block state API on the host's {@code BlockState}.
 *
 * <p>In 1.12 {@code IBlockState} and {@code IProperty} were interfaces. The class moves map them
 * to the host's {@code BlockState} and {@code Property} classes, which leaves every call an
 * {@code INVOKEINTERFACE} on a class and fails with {@code IncompatibleClassChangeError}. Both are
 * recorded as class owners so the transformer emits {@code INVOKEVIRTUAL}. Methods that kept
 * their meaning but changed name or erased type ({@code withProperty} is the host's generic
 * {@code setValue}, which erases to {@code Object}) are redirected to the declaring host class,
 * so the Forge 1.20.1 SRG rename finds their exact owner and descriptor. The redirect keys carry
 * 1.12 SRG ids, which no newer mod uses.
 *
 * <p>A 1.12 block declares its properties by returning {@code new BlockStateContainer(this, ...)}
 * from {@code createBlockState()}, which the host never calls. {@link #upgradeBlockClass} gives
 * such a block the host override {@code createBlockStateDefinition(builder)}, which runs the
 * mod's {@code createBlockState()} while the container constructor adds its properties to that
 * builder. Both run from the block constructor, as in 1.12.
 */
public final class Forge1122BlockStateBridge {

    private Forge1122BlockStateBridge() {}

    static final String BLOCK = "net/minecraft/world/level/block/Block";
    static final String BLOCK_STATE = "net/minecraft/world/level/block/state/BlockState";
    static final String STATE_HOLDER = "net/minecraft/world/level/block/state/StateHolder";
    static final String STATE_BASE = "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase";
    static final String PROPERTY = "net/minecraft/world/level/block/state/properties/Property";
    static final String STATE_DEFINITION = "net/minecraft/world/level/block/state/StateDefinition";
    static final String BUILDER = "net/minecraft/world/level/block/state/StateDefinition$Builder";
    static final String DIRECTION_PROPERTY = "net/minecraft/world/level/block/state/properties/DirectionProperty";
    static final String ROTATION = "net/minecraft/world/level/block/Rotation";
    static final String FACTORY = "com/retromod/generated/forge1122/LegacyBlockStates";

    private static final String L_STATE = "L" + BLOCK_STATE + ";";
    private static final String L_PROPERTY = "L" + PROPERTY + ";";
    private static final String L_OBJ = "Ljava/lang/Object;";
    private static final String CONTAINER_DESC = "(L" + BLOCK + ";[" + L_PROPERTY + ")V";
    private static final String FACTORY_DESC = "(L" + BLOCK + ";[" + L_PROPERTY + ")L" + STATE_DEFINITION + ";";
    static final String DEFINITION_DESC = "(L" + BUILDER + ";)V";

    /** {@code Block.createBlockState()}: its SRG id, and the MCP name a deobfuscated jar keeps. */
    private static final java.util.Set<String> CREATE_BLOCK_STATE =
            java.util.Set.of("func_180661_e", "createBlockState");

    public static void register(RetromodTransformer t) {
        t.registerClassOwner(BLOCK_STATE);
        t.registerClassOwner(PROPERTY);
        // The redirect targets below: a redirected call keeps its original opcode otherwise.
        t.registerClassOwner(STATE_HOLDER);
        t.registerClassOwner(STATE_BASE);

        // withProperty and cycleProperty: the host versions are generic and return Object.
        t.registerMethodRedirect(BLOCK_STATE, "func_177226_a",
                "(" + L_PROPERTY + "Ljava/lang/Comparable;)" + L_STATE,
                STATE_HOLDER, "setValue", "(" + L_PROPERTY + "Ljava/lang/Comparable;)" + L_OBJ);
        t.registerMethodRedirect(BLOCK_STATE, "func_177231_a", "(" + L_PROPERTY + ")" + L_STATE,
                STATE_HOLDER, "cycle", "(" + L_PROPERTY + ")" + L_OBJ);
        // withRotation is rotate since 1.13; the 1.12 table keeps the old name.
        t.registerMethodRedirect(BLOCK_STATE, "withRotation", "(L" + ROTATION + ";)" + L_STATE,
                STATE_BASE, "rotate", "(L" + ROTATION + ";)" + L_STATE);

        // PropertyDirection.create(name, filter) took a Guava predicate, which extends the JDK
        // one the host takes, so only the descriptor changes. 1.21.2 removed DirectionProperty.
        if (com.retromod.core.RetromodVersion.compareMcVersions(
                com.retromod.core.RetromodVersion.TARGET_MC_VERSION, "1.21.2") < 0) {
            String lDirection = "L" + DIRECTION_PROPERTY + ";";
            for (String name : new String[]{"create", "func_177712_a"}) {
                t.registerMethodRedirect(DIRECTION_PROPERTY, name,
                        "(Ljava/lang/String;Lcom/google/common/base/Predicate;)" + lDirection,
                        DIRECTION_PROPERTY, "create",
                        "(Ljava/lang/String;Ljava/util/function/Predicate;)" + lDirection);
            }
        }

        // The 1.13 shim maps the container to an intermediate name first, and constructor
        // redirects are matched on the first pass only, so the container goes straight to the host.
        t.registerClassRedirect("net/minecraft/block/state/BlockStateContainer", STATE_DEFINITION);
        t.registerSyntheticClass(FACTORY, factoryBytes());
        t.registerSyntheticClass(CONTAINER_BUILDER, containerBuilderBytes());
        // A block lists unlisted properties in an IUnlistedProperty[] and declares them as classes
        // that implement it. Without a loadable type its whole createBlockState() fails and the
        // listed properties are lost too. Nothing on the host reads the unlisted values.
        Forge1122ServiceStubs.stubInterface(t, "net/minecraftforge/common/property/IUnlistedProperty", UNLISTED);
        t.registerClassRedirect("net/minecraft/block/state/BlockStateContainer$Builder", CONTAINER_BUILDER);
        t.registerConstructorRedirect(STATE_DEFINITION, CONTAINER_DESC, FACTORY, "container", FACTORY_DESC);
    }

    /** {@code static StateDefinition container(Block, Property[])}, which fills the open builder. */
    static byte[] factoryBytes() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, FACTORY, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "container", FACTORY_DESC, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESTATIC, Forge1122EventBridge.EVENTS, "addLegacyStates",
                "(" + L_OBJ + "[" + L_OBJ + ")" + L_OBJ, false);
        mv.visitTypeInsn(CHECKCAST, STATE_DEFINITION);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    static final String CONTAINER_BUILDER = "com/retromod/generated/forge1122/BlockStateContainer$Builder";
    static final String UNLISTED = "com/retromod/generated/forge1122/IUnlistedProperty";

    /**
     * Forge's {@code BlockStateContainer.Builder(block).add(..).build()}: collects the listed
     * properties and hands them to the open builder on {@code build()}. Unlisted properties carried
     * extra render data that the host block state has no place for, so they are ignored.
     */
    static byte[] containerBuilderBytes() {
        String self = "L" + CONTAINER_BUILDER + ";";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, CONTAINER_BUILDER, null, "java/lang/Object", null);
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "block", "L" + BLOCK + ";", null, null).visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "listed", "Ljava/util/List;", null, null).visitEnd();

        MethodVisitor c = cw.visitMethod(ACC_PUBLIC, "<init>", "(L" + BLOCK + ";)V", null, null);
        c.visitCode();
        c.visitVarInsn(ALOAD, 0);
        c.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        c.visitVarInsn(ALOAD, 0);
        c.visitVarInsn(ALOAD, 1);
        c.visitFieldInsn(PUTFIELD, CONTAINER_BUILDER, "block", "L" + BLOCK + ";");
        c.visitVarInsn(ALOAD, 0);
        c.visitTypeInsn(NEW, "java/util/ArrayList");
        c.visitInsn(DUP);
        c.visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
        c.visitFieldInsn(PUTFIELD, CONTAINER_BUILDER, "listed", "Ljava/util/List;");
        c.visitInsn(RETURN);
        c.visitMaxs(0, 0);
        c.visitEnd();

        MethodVisitor add = cw.visitMethod(ACC_PUBLIC, "add", "([" + L_PROPERTY + ")" + self, null, null);
        add.visitCode();
        add.visitVarInsn(ALOAD, 0);
        add.visitFieldInsn(GETFIELD, CONTAINER_BUILDER, "listed", "Ljava/util/List;");
        add.visitVarInsn(ALOAD, 1);
        add.visitMethodInsn(INVOKESTATIC, "java/util/Arrays", "asList", "([" + L_OBJ + ")Ljava/util/List;", false);
        add.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "addAll", "(Ljava/util/Collection;)Z", true);
        add.visitInsn(POP);
        add.visitVarInsn(ALOAD, 0);
        add.visitInsn(ARETURN);
        add.visitMaxs(0, 0);
        add.visitEnd();

        MethodVisitor unlisted = cw.visitMethod(ACC_PUBLIC, "add", "([L" + UNLISTED + ";)" + self, null, null);
        unlisted.visitCode();
        unlisted.visitVarInsn(ALOAD, 0);
        unlisted.visitInsn(ARETURN);
        unlisted.visitMaxs(0, 0);
        unlisted.visitEnd();

        MethodVisitor build = cw.visitMethod(ACC_PUBLIC, "build", "()L" + STATE_DEFINITION + ";", null, null);
        build.visitCode();
        build.visitVarInsn(ALOAD, 0);
        build.visitFieldInsn(GETFIELD, CONTAINER_BUILDER, "block", "L" + BLOCK + ";");
        build.visitVarInsn(ALOAD, 0);
        build.visitFieldInsn(GETFIELD, CONTAINER_BUILDER, "listed", "Ljava/util/List;");
        build.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "toArray", "()[" + L_OBJ, true);
        build.visitMethodInsn(INVOKESTATIC, Forge1122EventBridge.EVENTS, "addLegacyStates",
                "(" + L_OBJ + "[" + L_OBJ + ")" + L_OBJ, false);
        build.visitTypeInsn(CHECKCAST, STATE_DEFINITION);
        build.visitInsn(ARETURN);
        build.visitMaxs(0, 0);
        build.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Gives a 1.12 block that declares {@code createBlockState()} the host override that runs it.
     * {@code hostDefinitionName} is the host's name for {@code createBlockStateDefinition}, an SRG
     * id on Forge 1.20.1. Returns the input array when the class has no 1.12 state container or
     * already declares the host override.
     */
    public static byte[] upgradeBlockClass(byte[] classBytes, String hostDefinitionName) {
        if (classBytes == null || hostDefinitionName == null) return classBytes;
        String pool = new String(classBytes, StandardCharsets.ISO_8859_1);
        if (!pool.contains(STATE_DEFINITION)
                || (!pool.contains("func_180661_e") && !pool.contains("createBlockState"))) {
            return classBytes;
        }
        ClassNode cn = new ClassNode();
        new ClassReader(classBytes).accept(cn, 0);
        String legacyName = null;
        for (MethodNode m : cn.methods) {
            if ((m.access & ACC_STATIC) == 0 && CREATE_BLOCK_STATE.contains(m.name)
                    && m.desc.equals("()L" + STATE_DEFINITION + ";")) {
                legacyName = m.name;
            }
            if (m.name.equals(hostDefinitionName) && m.desc.equals(DEFINITION_DESC)) return classBytes;
        }
        if (legacyName == null) return classBytes;

        MethodNode override = new MethodNode(ACC_PROTECTED, hostDefinitionName, DEFINITION_DESC, null, null);
        override.instructions.add(new VarInsnNode(ALOAD, 0));
        override.instructions.add(new VarInsnNode(ALOAD, 1));
        override.instructions.add(new org.objectweb.asm.tree.LdcInsnNode(legacyName));
        override.instructions.add(new MethodInsnNode(INVOKESTATIC, Forge1122EventBridge.EVENTS,
                "defineLegacyStates", "(" + L_OBJ + L_OBJ + "Ljava/lang/String;)V", false));
        override.instructions.add(new InsnNode(RETURN));
        override.maxLocals = 2;
        override.maxStack = 3;
        cn.methods.add(override);

        // The override is straight-line code, so it needs no frames, and the rest keeps its own.
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }
}
