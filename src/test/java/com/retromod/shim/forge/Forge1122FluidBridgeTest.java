/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.forge.embedded.LegacyFluid;
import com.retromod.shim.forge.embedded.LegacyFluidRegistry;
import com.retromod.shim.forge.embedded.LegacyFluidStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** The 1.12.2 fluid API: definitions, stacks, buckets and fluid blocks a mod builds at load. */
class Forge1122FluidBridgeTest {

    private static final String FLUID = "net/minecraftforge/fluids/Fluid";
    private static final String RL = "Lnet/minecraft/util/ResourceLocation;";
    private static final String MATERIAL = "Lnet/minecraft/block/material/Material;";

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        Forge1122LifecycleSynthetics.resetForTesting();
        Forge1122EventBridge.resetForTesting();
        Forge1122FluidBridge.resetForTesting();
    }

    private static RetromodTransformer bridged() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        Forge1122LifecycleSynthetics.register(t);
        Forge1122EventBridge.register(t);
        Forge1122ServiceStubs.register(t);
        return t;
    }

    /** The runtime order for a pre-1.13 jar: shared fluid names first, then the remap. */
    private static ClassNode transform(RetromodTransformer t, byte[] bytes, String name) {
        byte[] renamed = Forge1122FluidBridge.renameSharedFluidTypes(bytes);
        byte[] remapped = t.transformClass(renamed, name + ".class");
        ClassNode cn = new ClassNode();
        new ClassReader(Forge1122EventBridge.rewriteLegacyCalls(remapped)).accept(cn, 0);
        return cn;
    }

    private static boolean declares(ClassNode type, String name, String desc) {
        return type.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc));
    }

    private static boolean declares(Class<?> type, String name, String desc) {
        if (name.equals("<init>")) {
            return Arrays.stream(type.getDeclaredConstructors())
                    .anyMatch(c -> Type.getConstructorDescriptor(c).equals(desc));
        }
        return Arrays.stream(type.getDeclaredMethods())
                .anyMatch(m -> m.getName().equals(name) && Type.getMethodDescriptor(m).equals(desc));
    }

    /** The AbyssalCraft static initializer shape: define, tune, register, then fill a bucket. */
    private static byte[] fluidInit() {
        String stack = "net/minecraftforge/fluids/FluidStack";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/Fluids", null, "java/lang/Object", null);
        var mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "init", "()Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, FLUID);
        mv.visitInsn(DUP);
        mv.visitLdcInsn("liquidcoralium");
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ACONST_NULL);
        mv.visitMethodInsn(INVOKESPECIAL, FLUID, "<init>", "(Ljava/lang/String;" + RL + RL + ")V", false);
        mv.visitIntInsn(SIPUSH, 3000);
        mv.visitMethodInsn(INVOKEVIRTUAL, FLUID, "setDensity", "(I)L" + FLUID + ";", false);
        mv.visitMethodInsn(INVOKESTATIC, "net/minecraftforge/fluids/FluidRegistry", "registerFluid",
                "(L" + FLUID + ";)Z", false);
        mv.visitInsn(POP);
        mv.visitLdcInsn("liquidcoralium");
        mv.visitMethodInsn(INVOKESTATIC, "net/minecraftforge/fluids/FluidRegistry", "getFluid",
                "(Ljava/lang/String;)L" + FLUID + ";", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, FLUID, "getName", "()Ljava/lang/String;", false);
        mv.visitInsn(POP);
        mv.visitTypeInsn(NEW, stack);
        mv.visitInsn(DUP);
        mv.visitInsn(ACONST_NULL);
        mv.visitIntInsn(SIPUSH, 1000);
        mv.visitMethodInsn(INVOKESPECIAL, stack, "<init>", "(L" + FLUID + ";I)V", false);
        mv.visitMethodInsn(INVOKESTATIC, "net/minecraftforge/fluids/FluidUtil", "getFilledBucket",
                "(L" + stack + ";)Lnet/minecraft/item/ItemStack;", false);
        mv.visitInsn(POP);
        mv.visitInsn(ACONST_NULL);
        mv.visitMethodInsn(INVOKESTATIC, "net/minecraftforge/fluids/FluidUtil", "getFluidContained",
                "(Lnet/minecraft/item/ItemStack;)L" + stack + ";", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void fluidDefinitionCallsLinkAgainstTheCompiledStandIns() throws Exception {
        ClassNode cn = transform(bridged(), fluidInit(), "test/Fluids");
        MethodNode init = cn.methods.stream().filter(m -> m.name.equals("init")).findFirst().orElseThrow();

        int checked = 0;
        for (AbstractInsnNode in : init.instructions.toArray()) {
            if (!(in instanceof MethodInsnNode mi)) continue;
            assertTrue(Forge1122ServiceStubs.ERASED_OWNERS.contains(mi.owner),
                    "every fluid call must land on a stand-in: " + mi.owner + "." + mi.name);
            assertTrue(declares(Class.forName(mi.owner.replace('/', '.')), mi.name, mi.desc),
                    mi.owner + " must declare " + mi.name + mi.desc + " or the call fails to link");
            checked++;
        }
        assertEquals(8, checked);
    }

    /** A Forge 1.14+ mod's {@code new FluidStack(fluid, amount)}, with the vanilla fluid type. */
    private static byte[] newerForgeTank() {
        String stack = "net/minecraftforge/fluids/FluidStack";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/Tank", null, "java/lang/Object", null);
        var mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "fill",
                "(Lnet/minecraft/world/level/material/Fluid;)L" + stack + ";", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, stack);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitIntInsn(SIPUSH, 1000);
        mv.visitMethodInsn(INVOKESPECIAL, stack, "<init>",
                "(Lnet/minecraft/world/level/material/Fluid;I)V", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void newerForgeModsKeepTheHostFluidStack() {
        RetromodTransformer t = bridged();
        byte[] remapped = t.transformClass(newerForgeTank(), "test/Tank.class");
        ClassNode cn = new ClassNode();
        new ClassReader(Forge1122EventBridge.rewriteLegacyCalls(remapped)).accept(cn, 0);
        MethodNode fill = cn.methods.stream().filter(m -> m.name.equals("fill")).findFirst().orElseThrow();
        assertTrue(fill.desc.endsWith(")Lnet/minecraftforge/fluids/FluidStack;"),
                "Forge 1.20.1 still ships FluidStack; only a pre-1.13 jar may be renamed: " + fill.desc);
        for (AbstractInsnNode in : fill.instructions.toArray()) {
            if (in instanceof MethodInsnNode mi) {
                assertEquals("net/minecraftforge/fluids/FluidStack", mi.owner);
            }
        }
    }

    @Test
    void registeredFluidsAreFoundByNameAndStacksKeepTheirFluid() {
        LegacyFluid coralium = new LegacyFluid("LiquidCoralium", null, null).setDensity(3000);
        assertTrue(LegacyFluidRegistry.registerFluid(coralium));
        assertFalse(LegacyFluidRegistry.registerFluid(new LegacyFluid("liquidcoralium", null, null)),
                "the first definition for a name wins, as in 1.12");
        assertSame(coralium, LegacyFluidRegistry.getFluid("liquidcoralium"));
        assertTrue(LegacyFluidRegistry.isFluidRegistered("liquidcoralium"));
        assertTrue(LegacyFluidRegistry.isFluidRegistered(coralium));
        assertNotNull(LegacyFluidRegistry.getFluid("water"), "vanilla fluids are pre-registered");

        LegacyFluidStack stack = new LegacyFluidStack(coralium, 1000);
        assertSame(coralium, stack.getFluid());
        assertEquals(1000, stack.amount);
        assertTrue(stack.isFluidEqual(stack.copy()));
    }

    /** {@code class Liquid extends BlockFluidClassic} with the usual super and displacement calls. */
    private static byte[] fluidBlock() {
        String base = "net/minecraftforge/fluids/BlockFluidClassic";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/Liquid", null, base, null);
        var ctor = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitInsn(ACONST_NULL);
        ctor.visitInsn(ACONST_NULL);
        ctor.visitMethodInsn(INVOKESPECIAL, base, "<init>", "(L" + FLUID + ";" + MATERIAL + ")V", false);
        ctor.visitInsn(RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();
        var displace = cw.visitMethod(ACC_PUBLIC, "canDisplace",
                "(Lnet/minecraft/world/IBlockAccess;Lnet/minecraft/util/math/BlockPos;)Z", null, null);
        displace.visitCode();
        displace.visitVarInsn(ALOAD, 0);
        displace.visitVarInsn(ALOAD, 1);
        displace.visitVarInsn(ALOAD, 2);
        displace.visitMethodInsn(INVOKESPECIAL, base, "canDisplace",
                "(Lnet/minecraft/world/IBlockAccess;Lnet/minecraft/util/math/BlockPos;)Z", false);
        displace.visitInsn(IRETURN);
        displace.visitMaxs(0, 0);
        displace.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void fluidBlockSubclassExtendsTheGeneratedHostBlock() {
        ClassNode cn = transform(bridged(), fluidBlock(), "test/Liquid");
        assertEquals(Forge1122FluidBridge.FLUID_BLOCK, cn.superName);

        ClassNode generated = new ClassNode();
        new ClassReader(Forge1122FluidBridge.fluidBlockBytes()).accept(generated, 0);
        assertEquals("net/minecraft/world/level/block/Block", generated.superName,
                "the placeholder must be a host block so the registry accepts it");

        int checked = 0;
        for (MethodNode m : cn.methods) {
            for (AbstractInsnNode in : m.instructions.toArray()) {
                if (in instanceof MethodInsnNode mi && mi.owner.equals(Forge1122FluidBridge.FLUID_BLOCK)) {
                    assertTrue(declares(generated, mi.name, mi.desc),
                            "the generated fluid block must declare " + mi.name + mi.desc);
                    checked++;
                }
            }
        }
        assertEquals(2, checked);
    }
}
