/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.RebuiltApiClasses;
import com.retromod.shim.common.embedded.LegacyLog;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;
import java.util.function.Function;

/** Rebuilds the fluid render handlers, FabricBakedModel, the core shader callback and the old menu type constructor of Fabric API on 26.x. */
public final class LegacyFabricClientApiBridge {

    static final String OLD_FLUID = "net/fabricmc/fabric/api/client/render/fluid/v1/";
    static final String OLD_REGISTRY = OLD_FLUID + "FluidRenderHandlerRegistry";
    static final String OLD_SIMPLE = OLD_FLUID + "SimpleFluidRenderHandler";
    static final String HANDLER = OLD_FLUID + "FluidRenderHandler";
    static final String OLD_BAKED = "net/fabricmc/fabric/api/renderer/v1/model/FabricBakedModel";
    static final String OLD_SHADERS = "net/fabricmc/fabric/api/client/rendering/v1/CoreShaderRegistrationCallback";

    static final String FLUID_ROOT = RebuiltApiClasses.ROOT + "fabric/client/render/fluid/";
    static final String REGISTRY = FLUID_ROOT + "LegacyFluidRenderHandlerRegistry";
    static final String SIMPLE = FLUID_ROOT + "LegacySimpleFluidRenderHandler";
    static final String HANDLERS = FLUID_ROOT + "LegacyFluidRenderHandlers";
    static final String SHADERS = RebuiltApiClasses.ROOT + "fabric/client/rendering/LegacyCoreShaderRegistrationCallback";
    // Beside the other renderer API stand-ins, whose RenderContext the old model methods name.
    static final String BAKED = "com/retromod/generated/legacyrenderer/FabricBakedModel";
    static final String RENDER_CONTEXT = "com/retromod/generated/legacyrenderer/RenderContext";

    /** Fabric API for 26.1 is the first without these; the bridge registers from the 26.1 shim on. */
    public static final String FIRST_HOST = "26.1";
    private static final String LEGACY_LOG = LegacyLog.class.getName().replace('.', '/');

    private static final String AT = "(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/level/material/FluidState;)";
    private static final List<String> VERTEX_FORMATS = List.of("com/mojang/blaze3d/vertex/VertexFormat",
            "com/mojang/renderpearl/api/vertex/VertexFormat");

    private LegacyFabricClientApiBridge() {}

    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        if (RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, FIRST_HOST) < 0) return;
        SyntheticEmbedder.registerClassResource(t, LEGACY_LOG, LegacyLog.class);
        if (host.apply(OLD_REGISTRY) == null) registerFluids(t);
        if (host.apply(OLD_BAKED) == null) {
            t.registerSyntheticClass(BAKED, fabricBakedModel());
            t.registerClassRedirect(OLD_BAKED, BAKED);
        }
        if (host.apply(OLD_SHADERS) == null) registerShaders(t);
        Pre1_20_5ScreenHandlerBridge.registerMojang(t);
    }

    private static void registerFluids(RetromodTransformer t) {
        RebuiltApiClasses.registerTree(t, REGISTRY);
        RebuiltApiClasses.registerTree(t, SIMPLE);
        t.registerClassRedirect(OLD_REGISTRY, REGISTRY);
        t.registerClassRedirect(OLD_SIMPLE, SIMPLE);
        // 26.x kept FluidRenderHandler with only its new draw method, so the old reads go to helpers that ask the handler first.
        String self = "(L" + HANDLER + ";";
        String args = AT.substring(1);
        t.registerMethodRedirect(HANDLER, "getFluidSprites", AT + "[Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;",
                HANDLERS, "getFluidSprites", self + args + "[Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;", true);
        t.registerMethodRedirect(HANDLER, "getFluidColor", AT + "I", HANDLERS, "getFluidColor", self + args + "I", true);
        t.registerMethodRedirect(HANDLER, "reloadTextures", "(Lnet/minecraft/client/renderer/texture/TextureAtlas;)V",
                HANDLERS, "reloadTextures", self + "Lnet/minecraft/client/renderer/texture/TextureAtlas;)V", true);
        t.registerMethodRedirect(HANDLER, "renderFluid", "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/client/renderer/block/BlockAndTintGetter;"
                + "Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/world/level/block/state/BlockState;"
                + "Lnet/minecraft/world/level/material/FluidState;)V", HANDLERS, "renderFluid", self + "Lnet/minecraft/core/BlockPos;"
                + "Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Ljava/lang/Object;Lnet/minecraft/world/level/block/state/BlockState;"
                + "Lnet/minecraft/world/level/material/FluidState;)V", true);
    }

    private static void registerShaders(RetromodTransformer t) {
        RebuiltApiClasses.registerTree(t, SHADERS);
        String context = SHADERS + "$RegistrationContext";
        t.registerClassRedirect(OLD_SHADERS, SHADERS);
        t.registerClassRedirect(OLD_SHADERS + "$RegistrationContext", context);
        // The vertex format's type changed on 26.x, so the context takes it as an object.
        String taken = "(Lnet/minecraft/resources/Identifier;Ljava/lang/Object;Ljava/util/function/Consumer;)V";
        for (String owner : List.of(OLD_SHADERS + "$RegistrationContext", context)) {
            for (String format : VERTEX_FORMATS) {
                t.registerMethodRedirect(owner, "register", "(Lnet/minecraft/resources/Identifier;L" + format
                        + ";Ljava/util/function/Consumer;)V", context, "register", taken);
            }
        }
    }

    /**
     * FabricBakedModel with the old methods as defaults. 26.x draws block models through its own pipeline, so a model's
     * emit methods are never called and it reports itself a vanilla adapter.
     */
    static byte[] fabricBakedModel() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE, BAKED, null, "java/lang/Object", null);
        MethodVisitor vanilla = cw.visitMethod(Opcodes.ACC_PUBLIC, "isVanillaAdapter", "()Z", null, null);
        vanilla.visitCode();
        vanilla.visitInsn(Opcodes.ICONST_1);
        vanilla.visitInsn(Opcodes.IRETURN);
        vanilla.visitMaxs(0, 0);
        vanilla.visitEnd();
        String ctx = "L" + RENDER_CONTEXT + ";";
        for (String[] emit : new String[][]{{"emitBlockQuads", "(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;"
                + "Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Ljava/util/function/Supplier;" + ctx + ")V"},
                {"emitItemQuads", "(Lnet/minecraft/world/item/ItemStack;Ljava/util/function/Supplier;" + ctx + ")V"}}) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, emit[0], emit[1], null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }
}
