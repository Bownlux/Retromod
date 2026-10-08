/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import static org.objectweb.asm.Opcodes.*;

/**
 * Keeps a pre-1.19.3 mod's special crafting recipes loading on a 1.19.3 to 1.20.1 host.
 *
 * <p>1.19.3 replaced {@code SimpleRecipeSerializer}, whose factory took only the recipe id, with
 * {@code SimpleCraftingRecipeSerializer}, whose factory also takes a crafting book category, and
 * gave {@code CustomRecipe} the same category argument. 1.19.4 then added a {@code RegistryAccess}
 * to {@code assemble}, so an old override no longer implements the method and crafting the recipe
 * throws {@code AbstractMethodError}. The old serializer is built from a factory that ignores the
 * category, the old {@code CustomRecipe} constructor files the recipe under the miscellaneous book
 * category, and a recipe class with an old {@code assemble} override gets the new method, which
 * forwards to it. 1.20.2 removed the recipe id from both constructors, so newer hosts are left
 * alone.
 */
public final class LegacyCraftingRecipeBridge {

    static final String SYNTH = "com/retromod/generated/LegacyRecipes";
    static final String FACTORY = "com/retromod/generated/LegacyRecipeFactory";

    private static final String CRAFTING = "net/minecraft/world/item/crafting/";
    private static final String OLD_SERIALIZER = CRAFTING + "SimpleRecipeSerializer";
    private static final String SERIALIZER = CRAFTING + "SimpleCraftingRecipeSerializer";
    private static final String FACTORY_TYPE = SERIALIZER + "$Factory";
    private static final String CUSTOM_RECIPE = CRAFTING + "CustomRecipe";
    private static final String CRAFTING_RECIPE = CRAFTING + "CraftingRecipe";
    private static final String RECIPE = CRAFTING + "Recipe";
    private static final String CATEGORY = CRAFTING + "CraftingBookCategory";
    private static final String LOCATION = "Lnet/minecraft/resources/ResourceLocation;";
    private static final String STACK = "Lnet/minecraft/world/item/ItemStack;";
    private static final String CONTAINER = "Lnet/minecraft/world/Container;";
    private static final String CRAFTING_CONTAINER = "Lnet/minecraft/world/inventory/CraftingContainer;";
    static final String NEW_ASSEMBLE = "(" + CONTAINER + "Lnet/minecraft/core/RegistryAccess;)" + STACK;

    private LegacyCraftingRecipeBridge() {
    }

    /** Whether {@code host} builds special recipes from an id and a book category. */
    public static boolean appliesTo(String host) {
        return RetromodVersion.compareMcVersions(host, "1.19.3") >= 0
                && RetromodVersion.compareMcVersions(host, "1.20.2") < 0;
    }

    public static void register(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(SYNTH, generateHelpers());
        transformer.registerSyntheticClass(FACTORY, generateFactory());
        transformer.registerClassRedirect(OLD_SERIALIZER, SERIALIZER);
        transformer.registerConstructorRedirect(OLD_SERIALIZER, "(Ljava/util/function/Function;)V",
                SYNTH, "simpleSerializer", "(Ljava/util/function/Function;)L" + SERIALIZER + ";");
        transformer.registerSuperConstructorRedirect(CUSTOM_RECIPE, "(" + LOCATION + ")V",
                "(" + LOCATION + "L" + CATEGORY + ";)V", CATEGORY, "MISC", "L" + CATEGORY + ";");
    }

    /** True once the bridge is registered on a host whose {@code assemble} takes registries. */
    public static boolean adaptsAssemble(RetromodTransformer transformer) {
        return transformer.getSyntheticClasses().containsKey(SYNTH)
                && RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "1.19.4") >= 0;
    }

    static byte[] generateHelpers() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, SYNTH, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "simpleSerializer",
                "(Ljava/util/function/Function;)L" + SERIALIZER + ";", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, SERIALIZER);
        mv.visitInsn(DUP);
        mv.visitTypeInsn(NEW, FACTORY);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, FACTORY, "<init>", "(Ljava/util/function/Function;)V", false);
        mv.visitMethodInsn(INVOKESPECIAL, SERIALIZER, "<init>", "(L" + FACTORY_TYPE + ";)V", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A factory that builds the recipe from its id alone, as the old serializer did. */
    static byte[] generateFactory() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, FACTORY, null, "java/lang/Object",
                new String[]{FACTORY_TYPE});
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "byId", "Ljava/util/function/Function;", null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(Ljava/util/function/Function;)V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitFieldInsn(PUTFIELD, FACTORY, "byId", "Ljava/util/function/Function;");
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor create = cw.visitMethod(ACC_PUBLIC, "create",
                "(" + LOCATION + "L" + CATEGORY + ";)L" + CRAFTING_RECIPE + ";", null, null);
        create.visitCode();
        create.visitVarInsn(ALOAD, 0);
        create.visitFieldInsn(GETFIELD, FACTORY, "byId", "Ljava/util/function/Function;");
        create.visitVarInsn(ALOAD, 1);
        create.visitMethodInsn(INVOKEINTERFACE, "java/util/function/Function", "apply",
                "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        create.visitTypeInsn(CHECKCAST, CRAFTING_RECIPE);
        create.visitInsn(ARETURN);
        create.visitMaxs(0, 0);
        create.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Gives a crafting recipe class that overrides the old one-argument {@code assemble} the
     * 1.19.4 method, forwarding to the old override. Runs after the remap, so the added method
     * takes the host's name.
     */
    public static byte[] apply(byte[] classBytes, RetromodTransformer transformer) {
        if (classBytes == null) return null;
        String text = new String(classBytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        if (!text.contains(CUSTOM_RECIPE) && !text.contains(CRAFTING_RECIPE)) return classBytes;
        ClassNode node = new ClassNode();
        try {
            new ClassReader(classBytes).accept(node, 0);
        } catch (RuntimeException unreadable) {
            return classBytes;
        }
        if ((node.access & ACC_INTERFACE) != 0 || node.name.startsWith("net/minecraft/")) return classBytes;
        boolean recipe = CUSTOM_RECIPE.equals(node.superName) || node.interfaces.contains(CRAFTING_RECIPE)
                || node.interfaces.contains(RECIPE);
        if (!recipe) return classBytes;

        String hostName = transformer.remapQualifiedMethodName(RECIPE, "assemble", NEW_ASSEMBLE);
        MethodNode old = null;
        for (MethodNode method : node.methods) {
            if (method.desc.equals(NEW_ASSEMBLE) && (method.name.equals(hostName) || method.name.equals("assemble"))) {
                return classBytes;
            }
            if ((method.access & (ACC_STATIC | ACC_ABSTRACT)) != 0) continue;
            boolean oldShape = method.desc.equals("(" + CONTAINER + ")" + STACK)
                    || method.desc.equals("(" + CRAFTING_CONTAINER + ")" + STACK);
            if (oldShape && (method.name.equals("assemble") || method.name.equals(hostName))
                    && (old == null || method.desc.startsWith("(" + CONTAINER))) {
                old = method;
            }
        }
        if (old == null) return classBytes;

        MethodNode bridge = new MethodNode(ACC_PUBLIC | ACC_SYNTHETIC, hostName, NEW_ASSEMBLE, null, null);
        bridge.instructions.add(new VarInsnNode(ALOAD, 0));
        bridge.instructions.add(new VarInsnNode(ALOAD, 1));
        String parameter = old.desc.substring(2, old.desc.indexOf(';'));
        if (!parameter.equals("net/minecraft/world/Container")) {
            bridge.instructions.add(new TypeInsnNode(CHECKCAST, parameter));
        }
        bridge.instructions.add(new MethodInsnNode(INVOKEVIRTUAL, node.name, old.name, old.desc, false));
        bridge.instructions.add(new InsnNode(ARETURN));
        bridge.maxStack = 2;
        bridge.maxLocals = 3;
        node.methods.add(bridge);
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }
}
