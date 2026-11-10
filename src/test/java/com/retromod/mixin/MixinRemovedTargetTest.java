/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.mixin;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.forge.Forge_26_1_to_26_2;
import com.retromod.shim.forge.LegacyGuiOverlayBridge;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for #79: a mixin whose {@code @Mixin} target is a Minecraft class
 * that was <b>removed</b> on the host (not renamed) must be auto-detected so it can be
 * neutralized, instead of crashing the game with {@code ClassMetadataNotFoundException}
 * mid-transform.
 *
 * <p>Concrete case: Spelunkery (1.20.1) mixes into
 * {@code net/minecraft/world/level/storage/loot/LootDataManager}, deleted in the 1.21
 * loot-data refactor. On a 26.1 host the Mixin framework can't find its metadata and
 * aborts the whole transform pass, taking the game down during MC bootstrap (with a
 * misleading vanilla {@code Blocks.<clinit>} stacktrace that names no mod).</p>
 *
 * <p>The host-class probe is injected so the test can simulate the 26.1 runtime without
 * Minecraft on the test classpath.</p>
 */
class MixinRemovedTargetTest {

    private static final String MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String LOOT_DATA_MANAGER = "net/minecraft/world/level/storage/loot/LootDataManager";
    private static final String BLOCKS = "net/minecraft/world/level/block/Blocks";

    /**
     * Simulated 26.1 host: vanilla {@code Blocks} exists (sanity gate passes), but the
     * removed {@code LootDataManager} does not. Everything else "exists" so unrelated
     * targets are never flagged.
     */
    private static final Predicate<String> HOST_26_1 = name -> {
        String internal = name.replace('.', '/');
        if (internal.equals(LOOT_DATA_MANAGER)) return false;          // removed in 1.21
        return true;                                                    // Blocks + everything else present
    };

    /** A @Mixin class targeting {@code targetInternal} via the {@code targets} String[] form. */
    private static ClassNode mixinTargeting(String mixinName, String targetInternal) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, mixinName, null, "java/lang/Object", null);
        cw.visitEnd();
        ClassNode cn = new ClassNode();
        new org.objectweb.asm.ClassReader(cw.toByteArray()).accept(cn, 0);
        AnnotationNode mixin = new AnnotationNode(MIXIN_DESC);
        mixin.values = new java.util.ArrayList<>(List.of(
                "targets", new java.util.ArrayList<>(List.of(targetInternal.replace('/', '.')))));
        cn.invisibleAnnotations = new java.util.ArrayList<>(List.of(mixin));
        return cn;
    }

    @Test
    @DisplayName("#79: a mixin targeting a removed MC class is flagged for neutralization")
    void removedTargetIsFlagged() {
        var mt = new MixinCompatibilityTransformer(RetromodTransformer.getInstance());
        ClassNode mixin = mixinTargeting("com/example/spelunkery/MixinLootDataManager", LOOT_DATA_MANAGER);

        String flagged = mt.mixinTargetsRemovedClass(mixin, HOST_26_1);

        assertEquals(LOOT_DATA_MANAGER, flagged,
                "a @Mixin targeting the removed LootDataManager must be flagged so it can be neutralized");
    }

    @Test
    @DisplayName("A mixin targeting a present MC class is left alone")
    void presentTargetNotFlagged() {
        var mt = new MixinCompatibilityTransformer(RetromodTransformer.getInstance());
        ClassNode mixin = mixinTargeting("com/example/mod/MixinBlocks", BLOCKS);

        assertNull(mt.mixinTargetsRemovedClass(mixin, HOST_26_1),
                "a @Mixin targeting a class that still exists must NOT be flagged");
    }

    @Test
    @DisplayName("Non-vanilla targets are never judged (mod/library classes resolve elsewhere)")
    void modTargetNotJudged() {
        var mt = new MixinCompatibilityTransformer(RetromodTransformer.getInstance());
        // A mod-class target that the probe also reports absent must still be ignored,
        // because it may be provided by a companion mod / jar-in-jar at runtime.
        ClassNode mixin = mixinTargeting("com/example/mod/MixinThirdParty",
                "dev/someone/somelib/SomeClass");

        assertNull(mt.mixinTargetsRemovedClass(mixin, name -> {
            String internal = name.replace('.', '/');
            if (internal.equals("dev/someone/somelib/SomeClass")) return false; // "absent"
            return true; // Blocks present → sanity gate passes
        }), "a non-net/minecraft target must never be neutralized on a missing-class basis");
    }

    @Test
    @DisplayName("Probe that can't see MC at all (no sanity-gate class) never strips")
    void noMcOnHostNeverStrips() {
        var mt = new MixinCompatibilityTransformer(RetromodTransformer.getInstance());
        ClassNode mixin = mixinTargeting("com/example/spelunkery/MixinLootDataManager", LOOT_DATA_MANAGER);

        // Probe reports EVERYTHING absent, including Blocks. The sanity gate must
        // short-circuit so we don't mass-neutralize on a broken/empty classpath.
        assertNull(mt.mixinTargetsRemovedClass(mixin, name -> false),
                "when even the sanity-gate class is absent, the probe is untrustworthy → never strip");
    }

    @Test
    @DisplayName("On Forge 26.2, a mixin on ForgeGui, its debug overlay or VanillaGuiOverlay is dropped, and a vanilla target is kept")
    void rebuiltForgeOverlayTargetsAreRemoved() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        String savedTarget = RetromodVersion.TARGET_MC_VERSION;
        boolean savedForge = McReflect.isForceForge();
        boolean savedNeoForge = McReflect.isForceNeoForge();
        try {
            RetromodVersion.TARGET_MC_VERSION = "26.2";
            McReflect.setForceNeoForge(false);
            McReflect.setForceForge(true);
            transformer.clearRedirectsForTesting();
            var mt = new MixinCompatibilityTransformer(transformer);
            String forgeGui = "net/minecraftforge/client/gui/overlay/ForgeGui";
            ClassNode embeddium = mixinTargeting("me/jellysquid/mods/sodium/mixin/ForgeGuiMixin", forgeGui);
            assertNull(mt.mixinTargetsRemovedClass(embeddium, HOST_26_1), "without the overlay bridge the Forge target is not judged");

            LegacyGuiOverlayBridge.register(transformer);
            assertEquals(forgeGui, mt.mixinTargetsRemovedClass(embeddium, HOST_26_1),
                    "the redirect would land the mixin on the mod's own rebuilt copy");
            assertEquals(forgeGui, mt.mixinTargetsRemovedClass(embeddium, name -> false), "decided offline too, without a host probe");
            String debugOverlay = forgeGui + "$ForgeDebugScreenOverlay";
            assertEquals(debugOverlay, mt.mixinTargetsRemovedClass(
                    mixinTargeting("tech/betterf3/mixin/ForgeGuiMixin", debugOverlay), HOST_26_1));
            String vanillaOverlay = "net/minecraftforge/client/gui/overlay/VanillaGuiOverlay";
            assertEquals(vanillaOverlay, mt.mixinTargetsRemovedClass(
                    mixinTargeting("glitchcore/mixin/MixinVanillaGuiOverlay", vanillaOverlay), HOST_26_1));
            // One of the enum's constant bodies goes with the enum, as the debug overlay goes with ForgeGui.
            String constantBody = vanillaOverlay + "$1";
            assertEquals(constantBody, mt.mixinTargetsRemovedClass(
                    mixinTargeting("glitchcore/mixin/MixinVanillaGuiOverlayBody", constantBody), HOST_26_1));
            assertNull(mt.mixinTargetsRemovedClass(mixinTargeting("net/coderbot/iris/mixin/MixinLevelRenderer",
                    "net/minecraft/client/renderer/LevelRenderer"), HOST_26_1), "a vanilla target that exists is kept");
        } finally {
            RetromodVersion.TARGET_MC_VERSION = savedTarget;
            McReflect.setForceForge(savedForge);
            McReflect.setForceNeoForge(savedNeoForge);
            transformer.clearRedirectsForTesting();
        }
    }

    @Test
    @DisplayName("On Forge 26.2, a mixin into the removed item renderer is dropped, since it would land on the mod's own copy")
    void rebuiltItemRendererTargetIsRemoved() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        String savedTarget = RetromodVersion.TARGET_MC_VERSION;
        boolean savedForge = McReflect.isForceForge();
        boolean savedNeoForge = McReflect.isForceNeoForge();
        try {
            RetromodVersion.TARGET_MC_VERSION = "26.2";
            McReflect.setForceNeoForge(false);
            McReflect.setForceForge(true);
            transformer.clearRedirectsForTesting();
            var mt = new MixinCompatibilityTransformer(transformer);
            String renderer = "net/minecraft/client/renderer/BlockEntityWithoutLevelRenderer";
            ClassNode skinLayers = mixinTargeting("dev/tr7zw/skinlayers/mixin/BlockEntityWithoutLevelRendererMixin", renderer);
            assertNull(mt.mixinTargetsRemovedClass(skinLayers, name -> false), "offline without the bridge, nothing decides it");

            new Forge_26_1_to_26_2().registerRedirects(transformer);
            transformer.setModSourceVersion("1.20.1");
            assertEquals(renderer, mt.mixinTargetsRemovedClass(skinLayers, name -> false), "decided by the redirect, offline too");
            // Retromod's runtime jar carries the rebuilt class, so the host probe alone would keep the mixin.
            assertEquals(renderer, mt.mixinTargetsRemovedClass(skinLayers, name -> true));
            transformer.setModSourceVersion("26.2");
            assertNull(mt.mixinTargetsRemovedClass(skinLayers, name -> true), "a mod that keeps the name is judged by the host");
        } finally {
            RetromodVersion.TARGET_MC_VERSION = savedTarget;
            McReflect.setForceForge(savedForge);
            McReflect.setForceNeoForge(savedNeoForge);
            transformer.setModSourceVersion(null);
            transformer.clearRedirectsForTesting();
        }
    }
}
