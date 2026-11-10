/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.common.LegacyItemDrawBridge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A mixin into a removed class that Retromod rebuilds inside each mod is skipped, not applied to the mod's own copy. */
class MixinRebuiltTargetTest {

    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String ITEM_RENDERER = "net/minecraft/client/renderer/entity/ItemRenderer";
    private static final String ITEM_RENDERER_INTERMEDIARY = "net/minecraft/class_918";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("EMI's Fabric ItemRendererAccessor, which names ItemRenderer by intermediary, is skipped on 26.3")
    void fabricItemRendererMixinIsSkipped() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        transformer.clearRedirectsForTesting();
        // As the intermediary map registers it, then the 26.1 bridge, offline where no host class is readable.
        transformer.registerClassRedirect(ITEM_RENDERER_INTERMEDIARY, ITEM_RENDERER);
        LegacyItemDrawBridge.register(transformer);

        byte[] out = new MixinCompatibilityTransformer(transformer).transformMixinClass(mixin("dev/emi/emi/mixin/accessor/ItemRendererAccessor",
                ITEM_RENDERER_INTERMEDIARY));

        assertEquals(List.of("retromod/stripped/ItemRendererAccessor"), targets(out), "pointed at an absent placeholder, not the stand-in");
    }

    @Test
    @DisplayName("A Forge or NeoForge mixin naming ItemRenderer by its Mojang name is skipped too")
    void mojangItemRendererMixinIsSkipped() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        transformer.clearRedirectsForTesting();
        LegacyItemDrawBridge.register(transformer);

        byte[] out = new MixinCompatibilityTransformer(transformer).transformMixinClass(mixin("mod/mixin/ItemRendererMixin", ITEM_RENDERER));

        assertEquals(List.of("retromod/stripped/ItemRendererMixin"), targets(out));
    }

    @Test
    @DisplayName("At run time, where Retromod's own jar answers for the stand-in, the Fabric ItemRenderer mixin is still skipped")
    void runtimeProbeFindingTheStandIn() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        transformer.clearRedirectsForTesting();
        transformer.registerClassRedirect(ITEM_RENDERER_INTERMEDIARY, ITEM_RENDERER);
        LegacyItemDrawBridge.register(transformer);
        ClassNode node = node(mixin("net/caffeinemc/mods/sodium/mixin/ItemRendererMixin", ITEM_RENDERER_INTERMEDIARY));

        // Every name resolves, the stand-in included, as ClassResourceInspector finds it in Retromod's jar.
        assertEquals(ITEM_RENDERER_INTERMEDIARY, new MixinCompatibilityTransformer(transformer).mixinTargetsRemovedClass(node, name -> true));
    }

    @Test
    @DisplayName("Mixins into BlockRenderDispatcher, the model shapers and old BakedModel are skipped, and one into 26.x's block model kept")
    void modelClassMixinsAreSkipped() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        transformer.clearRedirectsForTesting();
        // As the intermediary map registers Fabric's names, then the 26.1 bridge, offline where no host class is readable.
        transformer.registerClassRedirect("net/minecraft/class_776", "net/minecraft/client/renderer/block/BlockRenderDispatcher");
        transformer.registerClassRedirect("net/minecraft/class_1087", "net/minecraft/client/renderer/block/dispatch/BlockStateModel");
        LegacyItemDrawBridge.register(transformer);
        MixinCompatibilityTransformer mixins = new MixinCompatibilityTransformer(transformer);

        for (String target : List.of("net/minecraft/class_776", "net/minecraft/client/renderer/block/BlockRenderDispatcher",
                "net/minecraft/client/renderer/ItemModelShaper", "net/minecraft/client/renderer/block/BlockModelShaper",
                "net/minecraft/client/resources/model/BakedModel")) {
            assertEquals(target, mixins.mixinTargetsRemovedClass(node(mixin("mod/mixin/ModelMixin", target)), name -> true), target);
        }
        // Fabric's intermediary name has meant 26.x's block model since 1.21.5, so a mixin naming it is left to the usual checks.
        assertNull(mixins.mixinTargetsRemovedClass(node(mixin("mod/mixin/BlockModelMixin", "net/minecraft/class_1087")), name -> true));
    }

    @Test
    @DisplayName("Zeta's accessor of the old item color map is skipped on NeoForge 26.3 and Forge 26.2; a BlockColors mixin is left to the usual checks")
    void oldItemColorMixinIsSkipped() {
        boolean savedForge = com.retromod.util.McReflect.isForceForge();
        boolean savedNeoForge = com.retromod.util.McReflect.isForceNeoForge();
        try {
            for (String loader : List.of("neoforge", "forge")) {
                transformer.clearRedirectsForTesting();
                com.retromod.util.McReflect.setForceNeoForge(loader.equals("neoforge"));
                com.retromod.util.McReflect.setForceForge(loader.equals("forge"));
                RetromodVersion.TARGET_MC_VERSION = loader.equals("neoforge") ? "26.3" : "26.2";
                com.retromod.shim.neoforge.LegacyNeoForgeColorBridge.register(transformer);
                com.retromod.shim.forge.LegacyForgeColorBridge.register(transformer);

                byte[] out = new MixinCompatibilityTransformer(transformer).transformMixinClass(
                        mixin("org/violetmoon/zetaimplforge/mixin/mixins/client/AccessorItemColors", "net/minecraft/client/color/item/ItemColors"));
                assertEquals(List.of("retromod/stripped/AccessorItemColors"), targets(out), loader + ": not applied to the mod's own copy");
                assertNull(new MixinCompatibilityTransformer(transformer).mixinTargetsRemovedClass(
                        node(mixin("mod/mixin/BlockColorsMixin", "net/minecraft/client/color/block/BlockColors")), name -> true),
                        loader + ": the host keeps BlockColors");
            }
        } finally {
            com.retromod.util.McReflect.setForceForge(savedForge);
            com.retromod.util.McReflect.setForceNeoForge(savedNeoForge);
        }
    }

    // A @Mixin(value = target.class), as javac writes it.
    private static byte[] mixin(String name, String target) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE, name, null, "java/lang/Object", null);
        var annotation = cw.visitAnnotation(MIXIN, false);
        var value = annotation.visitArray("value");
        value.visit(null, Type.getObjectType(target));
        value.visitEnd();
        annotation.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static List<String> targets(byte[] bytes) {
        ClassNode node = node(bytes);
        List<String> found = new ArrayList<>();
        for (List<AnnotationNode> annotations : java.util.Arrays.asList(node.visibleAnnotations, node.invisibleAnnotations)) {
            if (annotations == null) continue;
            for (AnnotationNode annotation : annotations) {
                if (!annotation.desc.equals(MIXIN) || annotation.values == null) continue;
                for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                    if (!(annotation.values.get(i + 1) instanceof List<?> list)) continue;
                    for (Object entry : list) found.add(entry instanceof Type type ? type.getInternalName() : String.valueOf(entry));
                }
            }
        }
        return found;
    }
}
