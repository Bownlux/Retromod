/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

/**
 * Loadable stand-ins for the 1.12.2 world generation and enum-extension classes that 1.13 and
 * later removed.
 *
 * <p>A 1.12 mod builds its generators ({@code WorldGenerator} subclasses, {@code WorldGenMinable}
 * ores) and its materials ({@code EnumHelper.addArmorMaterial}) in static fields of the classes
 * its {@code @Mod} class loads first. Without these names the whole mod fails at construction
 * with {@code NoClassDefFoundError}, before any block or item registers. With them the classes
 * load, a generator can be built, and {@code setBlockAndNotifyAdequately} places blocks through
 * the host level.
 *
 * <p>The limits are concrete. 1.12 world generation ran from {@code IWorldGenerator}, which no
 * host calls, so the generators are never invoked. {@code WorldGenMinable.generate} places
 * nothing. {@code EnumHelper} returns null for every new enum constant, because armor materials,
 * tool tiers and enchantment categories are registry entries or records on modern hosts, not
 * enum constants; an item built from such a material fails in its own registry handler.
 */
public final class Forge1122WorldgenBridge {

    private Forge1122WorldgenBridge() {}

    static final String GENERATOR = "com/retromod/generated/forge1122/WorldGenerator";
    static final String MINABLE = "com/retromod/generated/forge1122/WorldGenMinable";
    static final String ENUM_HELPER = "com/retromod/generated/forge1122/EnumHelper";

    private static final String LEVEL = "net/minecraft/world/level/Level";
    private static final String POS = "net/minecraft/core/BlockPos";
    private static final String STATE = "net/minecraft/world/level/block/state/BlockState";
    /**
     * {@code WorldGenerator.generate(World, Random, BlockPos)} after the remap. The bundled 1.12.2
     * SRG table maps its id {@code func_180709_b} to {@code generate} on every owner, so a mod's
     * override and its calls carry that name, and the stand-in must declare it the same way.
     */
    static final String GENERATE = "generate";
    static final String GENERATE_DESC = "(L" + LEVEL + ";Ljava/util/Random;L" + POS + ";)Z";
    /** {@code WorldGenerator.setBlockAndNotifyAdequately(World, BlockPos, IBlockState)}. */
    static final String SET_BLOCK = "func_175903_a";
    static final String SET_BLOCK_DESC = "(L" + LEVEL + ";L" + POS + ";L" + STATE + ";)V";

    public static void register(RetromodTransformer t) {
        t.registerSyntheticClass(GENERATOR, generatorBytes());
        t.registerClassRedirect("net/minecraft/world/gen/feature/WorldGenerator", GENERATOR);
        t.registerSyntheticClass(MINABLE, minableBytes());
        t.registerClassRedirect("net/minecraft/world/gen/feature/WorldGenMinable", MINABLE);
        // Vanilla generators a mod extends or builds. They load and construct; none places blocks.
        String bush = "Lnet/minecraft/world/level/block/BushBlock;";
        registerVanillaGenerator(t, ABSTRACT_TREE, GENERATOR, true, "()V", "(Z)V");
        registerVanillaGenerator(t, "WorldGenTrees", PACKAGE + ABSTRACT_TREE, false,
                "(Z)V", "(ZIL" + STATE + ";L" + STATE + ";Z)V");
        registerVanillaGenerator(t, "WorldGenSwamp", PACKAGE + ABSTRACT_TREE, false, "()V");
        registerVanillaGenerator(t, "WorldGenBush", GENERATOR, false, "(" + bush + ")V");
        t.registerSyntheticClass(ENUM_HELPER, enumHelperBytes());
        t.registerClassRedirect("net/minecraftforge/common/util/EnumHelper", ENUM_HELPER);
    }

    /**
     * {@code abstract class WorldGenerator}: the 1.12 constructors, the abstract
     * {@code generate}, and {@code setBlockAndNotifyAdequately} as {@code level.setBlock(pos,
     * state, 3)}. The embedder gives the host call its SRG name on Forge 1.20.1.
     */
    static byte[] generatorBytes() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_SUPER, GENERATOR, null, "java/lang/Object", null);
        emitSuperConstructor(cw, "()V", "java/lang/Object");
        // WorldGenerator(boolean notify): the flag chose block updates, which setBlock always sends.
        emitSuperConstructor(cw, "(Z)V", "java/lang/Object");
        cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, GENERATE, GENERATE_DESC, null, null).visitEnd();

        MethodVisitor set = cw.visitMethod(ACC_PROTECTED, SET_BLOCK, SET_BLOCK_DESC, null, null);
        set.visitCode();
        set.visitVarInsn(ALOAD, 1);
        set.visitVarInsn(ALOAD, 2);
        set.visitVarInsn(ALOAD, 3);
        set.visitInsn(ICONST_3);
        set.visitMethodInsn(INVOKEVIRTUAL, LEVEL, "setBlock", "(L" + POS + ";L" + STATE + ";I)Z", false);
        set.visitInsn(POP);
        set.visitInsn(RETURN);
        set.visitMaxs(0, 0);
        set.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code WorldGenMinable(state, size[, predicate])}: builds, and its generate places nothing. */
    static byte[] minableBytes() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, MINABLE, null, GENERATOR, null);
        emitSuperConstructor(cw, "(L" + STATE + ";I)V", GENERATOR);
        emitSuperConstructor(cw, "(L" + STATE + ";ILcom/google/common/base/Predicate;)V", GENERATOR);
        MethodVisitor gen = cw.visitMethod(ACC_PUBLIC, GENERATE, GENERATE_DESC, null, null);
        gen.visitCode();
        gen.visitInsn(ICONST_0);
        gen.visitInsn(IRETURN);
        gen.visitMaxs(0, 0);
        gen.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static final String PACKAGE = "com/retromod/generated/forge1122/";
    private static final String ABSTRACT_TREE = "WorldGenAbstractTree";

    /** A generator stand-in under the old simple name, with constructors that ignore their arguments. */
    private static void registerVanillaGenerator(RetromodTransformer t, String simpleName, String superName,
                                                 boolean isAbstract, String... ctorDescs) {
        t.registerSyntheticClass(PACKAGE + simpleName,
                vanillaGeneratorBytes(PACKAGE + simpleName, superName, isAbstract, ctorDescs));
        t.registerClassRedirect("net/minecraft/world/gen/feature/" + simpleName, PACKAGE + simpleName);
    }

    static byte[] vanillaGeneratorBytes(String name, String superName, boolean isAbstract, String... ctorDescs) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER | (isAbstract ? ACC_ABSTRACT : 0), name, null, superName, null);
        for (String desc : ctorDescs) emitSuperConstructor(cw, desc, superName);
        if (!isAbstract) {
            MethodVisitor gen = cw.visitMethod(ACC_PUBLIC, GENERATE, GENERATE_DESC, null, null);
            gen.visitCode();
            gen.visitInsn(ICONST_0);
            gen.visitInsn(IRETURN);
            gen.visitMaxs(0, 0);
            gen.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A constructor that ignores its arguments and calls {@code superName()}. */
    private static void emitSuperConstructor(ClassWriter cw, String desc, String superName) {
        MethodVisitor c = cw.visitMethod(ACC_PUBLIC, "<init>", desc, null, null);
        c.visitCode();
        c.visitVarInsn(ALOAD, 0);
        c.visitMethodInsn(INVOKESPECIAL, superName, "<init>", "()V", false);
        c.visitInsn(RETURN);
        c.visitMaxs(0, 0);
        c.visitEnd();
    }

    /**
     * {@code EnumHelper} with every 1.12 factory a mod calls. The post-remap pass erases the
     * Minecraft types in these calls to {@code Object} and casts the result back, as for the
     * other compiled stand-ins, so the declared shapes use {@code Object} for them.
     */
    static byte[] enumHelperBytes() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, ENUM_HELPER, null, "java/lang/Object", null);
        String o = "Ljava/lang/Object;";
        String[][] factories = {
                {"addArmorMaterial", "(" + o + o + "I" + o + "I" + o + "F)" + o},
                {"addToolMaterial", "(" + o + "IIFFI)" + o},
                {"addEnchantmentType", "(" + o + o + ")" + o},
                {"addCreatureAttribute", "(" + o + ")" + o},
                {"addRarity", "(" + o + o + o + ")" + o},
                {"addAction", "(" + o + ")" + o},
                {"addEnum", "(" + o + o + o + o + ")" + o},
                {"addEnum", "(" + o + o + o + ")" + o},
        };
        for (String[] factory : factories) {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, factory[0], factory[1], null, null);
            mv.visitCode();
            mv.visitInsn(ACONST_NULL);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }
}
