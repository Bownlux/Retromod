/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.forge.embedded.LegacyFluid;
import com.retromod.shim.forge.embedded.LegacyFluidStack;
import com.retromod.shim.forge.embedded.LegacyFluidUtil;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import static org.objectweb.asm.Opcodes.*;

/**
 * The 1.12.2 Forge fluid API as load-time stand-ins. A 1.12 mod builds its {@code Fluid}
 * definitions and fluid blocks in static initializers, so one missing class there fails the
 * whole mod before it registers anything.
 *
 * <p>Fluids become {@link LegacyFluid} definitions that keep their builder values, and
 * {@code BlockFluidClassic}, {@code BlockFluidFinite} and {@code BlockFluidBase} become one
 * generated block that extends the host {@code Block} with default properties. The mod's fluid
 * blocks therefore register and can be placed, but they are solid placeholders: they do not
 * flow, are not host fluids, and have no buckets. A real bridge would need a host
 * {@code FlowingFluid} pair and a fluid type for each definition.
 */
public final class Forge1122FluidBridge {

    private Forge1122FluidBridge() {}

    static final String FLUID = "com/retromod/shim/forge/embedded/LegacyFluid";
    static final String FLUID_STACK = "com/retromod/shim/forge/embedded/LegacyFluidStack";
    static final String FLUID_UTIL = "com/retromod/shim/forge/embedded/LegacyFluidUtil";
    static final String FLUID_BLOCK = "com/retromod/generated/forge1122/LegacyFluidBlock";
    static final String FLUID_BLOCK_ITF = "com/retromod/generated/forge1122/IFluidBlock";

    private static final String BLOCK = "net/minecraft/world/level/block/Block";
    private static final String PROPERTIES = "net/minecraft/world/level/block/state/BlockBehaviour$Properties";
    private static final String OBJ = "Ljava/lang/Object;";

    /**
     * Methods the fluid block stand-in declares itself. Every other call on it is an inherited
     * host {@code Block} method that the remap already named, so only these are erased.
     */
    static final Set<String> FLUID_BLOCK_METHODS = Set.of("<init>", "canDisplace",
            "displaceIfPossible", "getFluid", "setQuantaPerBlock", "setRenderLayer", "setTickRate",
            "setDensity", "setTemperature", "setMaxScaledLight", "getQuantaValue", "getMaxRenderHeightPercentage",
            "getFilledPercentage");

    public static void register(RetromodTransformer t) {
        SyntheticEmbedder.registerClassResource(t, FLUID, LegacyFluid.class);
        SyntheticEmbedder.registerClassResource(t, FLUID_STACK, LegacyFluidStack.class);
        SyntheticEmbedder.registerClassResource(t, FLUID_UTIL, LegacyFluidUtil.class);
        // Forge 1.13 deleted these names, so no later mod carries them and a host-wide redirect
        // is safe. FluidStack, FluidUtil and IFluidBlock are not: see SHARED_NAMES.
        t.registerClassRedirect("net/minecraftforge/fluids/Fluid", FLUID);

        ClassWriter itf = new ClassWriter(0);
        itf.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, FLUID_BLOCK_ITF, null,
                "java/lang/Object", null);
        itf.visitEnd();
        t.registerSyntheticClass(FLUID_BLOCK_ITF, itf.toByteArray());

        t.registerSyntheticClass(FLUID_BLOCK, fluidBlockBytes());
        for (String base : new String[]{"BlockFluidBase", "BlockFluidClassic", "BlockFluidFinite"}) {
            t.registerClassRedirect("net/minecraftforge/fluids/" + base, FLUID_BLOCK);
        }
        registered = true;
        t.onRegistrationReset(() -> registered = false);
    }

    /**
     * 1.12.2 fluid names that Forge 1.13 and later reuse for different classes. Forge 1.20.1
     * still ships all three, and the Forge to NeoForge bridge maps FluidStack and FluidUtil to
     * NeoForge's. A host-wide redirect would hijack every newer Forge mod's fluid code, or lose
     * to that bridge, so only the classes of a pre-1.13 jar are renamed, before the remap.
     */
    private static final Map<String, String> SHARED_NAMES = Map.of(
            "net/minecraftforge/fluids/FluidStack", FLUID_STACK,
            "net/minecraftforge/fluids/FluidUtil", FLUID_UTIL,
            "net/minecraftforge/fluids/IFluidBlock", FLUID_BLOCK_ITF);

    /** Set once the stand-ins are registered, so a renamed reference always has a target. */
    private static volatile boolean registered;

    static void resetForTesting() {
        registered = false;
    }

    /**
     * Renames the shared 1.12.2 fluid names in one class of a pre-1.13 Forge jar. The caller
     * decides that the jar is pre-1.13; the class bytes alone cannot tell a 1.12.2
     * {@code FluidStack} from a 1.20.1 one. Returns the input array when nothing matched.
     */
    public static byte[] renameSharedFluidTypes(byte[] classBytes) {
        if (!registered || classBytes == null) return classBytes;
        String pool = new String(classBytes, StandardCharsets.ISO_8859_1);
        if (SHARED_NAMES.keySet().stream().noneMatch(pool::contains)) return classBytes;
        ClassWriter cw = new ClassWriter(0);
        new ClassReader(classBytes).accept(new ClassRemapper(cw, new SimpleRemapper(SHARED_NAMES)), 0);
        return cw.toByteArray();
    }

    /** Post-remap erasure of the stand-ins' own calls. Returns true when {@code mi} changed. */
    static boolean rewriteCall(MethodNode m, MethodInsnNode mi) {
        if (mi.owner.equals(FLUID_BLOCK) && FLUID_BLOCK_METHODS.contains(mi.name)) {
            return Forge1122ServiceStubs.eraseToStandIn(m, mi);
        }
        return false;
    }

    /**
     * The generated fluid block. Its constructors take the erased {@code (fluid, material)} and
     * {@code (fluid, material, mapColor)} shapes, build default host properties through the
     * bridge's reflective helper (the property factory has an SRG name on Forge 1.20.1), and link
     * the block to its fluid definition the way 1.12 did.
     */
    static byte[] fluidBlockBytes() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, FLUID_BLOCK, null, BLOCK, new String[]{FLUID_BLOCK_ITF});
        String lFluid = "L" + FLUID + ";";
        cw.visitField(ACC_PROTECTED | ACC_FINAL, "definedFluid", lFluid, null, null).visitEnd();

        MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>", "(" + OBJ + OBJ + ")V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitMethodInsn(INVOKESTATIC, Forge1122EventBridge.EVENTS, "defaultBlockProperties",
                "()" + OBJ, false);
        ctor.visitTypeInsn(CHECKCAST, PROPERTIES);
        ctor.visitMethodInsn(INVOKESPECIAL, BLOCK, "<init>", "(L" + PROPERTIES + ";)V", false);
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitVarInsn(ALOAD, 1);
        ctor.visitTypeInsn(CHECKCAST, FLUID);
        ctor.visitFieldInsn(PUTFIELD, FLUID_BLOCK, "definedFluid", lFluid);
        ctor.visitVarInsn(ALOAD, 1);
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitMethodInsn(INVOKESTATIC, FLUID, "attachBlock", "(" + OBJ + OBJ + ")V", false);
        ctor.visitInsn(RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        MethodVisitor colored = cw.visitMethod(ACC_PUBLIC, "<init>", "(" + OBJ + OBJ + OBJ + ")V",
                null, null);
        colored.visitCode();
        colored.visitVarInsn(ALOAD, 0);
        colored.visitVarInsn(ALOAD, 1);
        colored.visitVarInsn(ALOAD, 2);
        colored.visitMethodInsn(INVOKESPECIAL, FLUID_BLOCK, "<init>", "(" + OBJ + OBJ + ")V", false);
        colored.visitInsn(RETURN);
        colored.visitMaxs(0, 0);
        colored.visitEnd();

        MethodVisitor fluid = cw.visitMethod(ACC_PUBLIC, "getFluid", "()" + lFluid, null, null);
        fluid.visitCode();
        fluid.visitVarInsn(ALOAD, 0);
        fluid.visitFieldInsn(GETFIELD, FLUID_BLOCK, "definedFluid", lFluid);
        fluid.visitInsn(ARETURN);
        fluid.visitMaxs(0, 0);
        fluid.visitEnd();

        // A placeholder never displaces anything, so the mod's own displacement logic stays put.
        for (String name : new String[]{"canDisplace", "displaceIfPossible"}) {
            constant(cw, name, "(" + OBJ + OBJ + ")Z", ICONST_0);
        }
        constant(cw, "getQuantaValue", "(" + OBJ + OBJ + ")I", ICONST_0);
        constant(cw, "getFilledPercentage", "(" + OBJ + OBJ + ")F", FCONST_1);
        constant(cw, "getMaxRenderHeightPercentage", "()F", FCONST_1);

        // Builder setters configured the flow simulation, which a placeholder does not run.
        for (String name : new String[]{"setQuantaPerBlock", "setTickRate", "setDensity",
                "setTemperature", "setMaxScaledLight"}) {
            returnSelf(cw, name, "(I)" + OBJ);
        }
        returnSelf(cw, "setRenderLayer", "(" + OBJ + ")" + OBJ);

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void constant(ClassWriter cw, String name, String desc, int constOpcode) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, name, desc, null, null);
        mv.visitCode();
        mv.visitInsn(constOpcode);
        mv.visitInsn(constOpcode == FCONST_1 ? FRETURN : IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void returnSelf(ClassWriter cw, String name, String desc) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, name, desc, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
