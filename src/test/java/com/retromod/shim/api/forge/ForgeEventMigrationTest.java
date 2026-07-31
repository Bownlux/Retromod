/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.forge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Forge to NeoForge event-package migration (#85). {@code forge-event-renames.json} redirects the
 * classes whose names survived the {@code net.minecraftforge.event} to {@code net.neoforged.neoforge.event}
 * fork; renamed/removed events live in {@link ForgeEventApiShim} or are out of scope.
 *
 * <p>{@code registerRedirects} is gated on {@code McReflect.isNeoForge()} (false in a unit JVM), so
 * the tests drive the {@code loadBulkEventRenames} worker directly.</p>
 */
class ForgeEventMigrationTest {

    @BeforeEach
    void loadBulk() {
        RetromodVersion.TARGET_MC_VERSION = "26.1";
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        ForgeEventApiShim shim = new ForgeEventApiShim();
        shim.loadBulkEventRenames(t);
        shim.loadBulkFmlRenames(t);
    }

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = "26.1";
        McReflect.setForceNeoForge(false); // never leak the offline override to another test
    }

    /**
     * Regression for #85/#115 (Macaw's / Management Wanted on NeoForge 26.2). NeoForge DELETED
     * FMLJavaModLoadingContext, so it can't be a same-name bulk rename; the B4 bridge synthetic
     * supplies it, but a Forge mod references the {@code net/minecraftforge/...} name and the
     * {@code SyntheticEmbedder} only embeds a synthetic whose EXACT registered name the mod
     * references. So the migration must redirect the Forge name to the synthetic's NeoForge name,
     * and that redirect must live on the API-shim path ({@code registerRedirects}) which runs on
     * BOTH the offline CLI/AOT batch and the live NeoForge runtime, not only in the runtime-only
     * {@code Forge_1_20_to_NeoForge_1_21}. Before the fix the Forge reference survived, the embedder
     * never matched, and the mod crashed at construct with NoClassDefFoundError.
     */
    @Test
    @DisplayName("registerRedirects redirects the deleted FMLJavaModLoadingContext to the B4 synthetic name")
    void fmlJavaModLoadingContextRedirectedToSyntheticName() {
        McReflect.setForceNeoForge(true); // the offline --target-loader neoforge override
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        new ForgeEventApiShim().registerRedirects(t); // the on-both-paths entry point, not the bulk worker

        assertEquals("net/neoforged/fml/javafmlmod/FMLJavaModLoadingContext",
                t.getClassRedirects().get("net/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext"),
                "the deleted FMLJavaModLoadingContext must be redirected to the synthetic's NeoForge name");
    }

    @Test
    @DisplayName("a @Mod ctor's FMLJavaModLoadingContext.get().getModEventBus() call owner is rewritten")
    void fmlJavaModLoadingContextCallSiteRewritten() {
        McReflect.setForceNeoForge(true);
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        new ForgeEventApiShim().registerRedirects(t);

        // a class whose ctor does FMLJavaModLoadingContext.get().getModEventBus() (the near-universal idiom)
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/ModFixture", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext",
                "get", "()Lnet/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext",
                "getModEventBus", "()Lnet/minecraftforge/eventbus/api/IEventBus;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        byte[] out = t.transformClass(cw.toByteArray(), "test/ModFixture");
        String text = new String(out, StandardCharsets.ISO_8859_1);
        assertFalse(text.contains("net/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext"),
                "no Forge FMLJavaModLoadingContext reference may survive (embedder keys on the NeoForge name)");
        assertTrue(text.contains("net/neoforged/fml/javafmlmod/FMLJavaModLoadingContext"),
                "the call owner must be rewritten to the NeoForge name the synthetic is registered under");
    }

    @Test
    @DisplayName("representative Forge events redirect to the NeoForge event package")
    void representativeEventsRedirected() {
        Map<String, String> r = RetromodTransformer.getInstance().getClassRedirects();
        assertEquals("net/neoforged/neoforge/event/level/BlockEvent$BreakEvent",
                r.get("net/minecraftforge/event/level/BlockEvent$BreakEvent"), "inner BlockEvent.BreakEvent");
        assertEquals("net/neoforged/neoforge/event/AnvilUpdateEvent",
                r.get("net/minecraftforge/event/AnvilUpdateEvent"), "top-level AnvilUpdateEvent");
        assertEquals("net/neoforged/neoforge/event/server/ServerStartingEvent",
                r.get("net/minecraftforge/event/server/ServerStartingEvent"), "server lifecycle event");
        assertEquals("net/neoforged/neoforge/client/event/RenderTooltipEvent",
                r.get("net/minecraftforge/client/event/RenderTooltipEvent"), "client render event");
    }

    @Test
    @DisplayName("an event-handler method's Forge event param type is rewritten to the NeoForge class")
    void handlerParamRewritten() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/EvtFixture", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "on",
                "(Lnet/minecraftforge/event/level/BlockEvent$BreakEvent;)V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        byte[] out = RetromodTransformer.getInstance().transformClass(cw.toByteArray(), "test/EvtFixture");
        ClassNode cn = new ClassNode();
        new ClassReader(out).accept(cn, 0);
        MethodNode on = cn.methods.stream().filter(m -> m.name.equals("on")).findFirst().orElseThrow();
        assertEquals("(Lnet/neoforged/neoforge/event/level/BlockEvent$BreakEvent;)V", on.desc,
                "the handler's event param must be remapped to the NeoForge event class");
    }

    @Test
    @DisplayName("FML @Mod lifecycle events + extension points redirect to net/neoforged/fml")
    void fmlLifecycleRedirected() {
        Map<String, String> r = RetromodTransformer.getInstance().getClassRedirects();
        assertEquals("net/neoforged/fml/event/lifecycle/FMLCommonSetupEvent",
                r.get("net/minecraftforge/fml/event/lifecycle/FMLCommonSetupEvent"), "common setup");
        assertEquals("net/neoforged/fml/event/lifecycle/FMLClientSetupEvent",
                r.get("net/minecraftforge/fml/event/lifecycle/FMLClientSetupEvent"), "client setup");
        assertEquals("net/neoforged/fml/event/config/ModConfigEvent$Loading",
                r.get("net/minecraftforge/fml/event/config/ModConfigEvent$Loading"), "config event inner");
        assertEquals("net/neoforged/fml/IExtensionPoint",
                r.get("net/minecraftforge/fml/IExtensionPoint"), "extension point");
        assertEquals("net/neoforged/fml/ModList",
                r.get("net/minecraftforge/fml/ModList"), "ModList");
    }

    @Test
    @DisplayName("FML table is well-formed and excludes classes owned by other shims/synthetics")
    void fmlTableShape() throws Exception {
        JsonObject root;
        try (InputStream in = ForgeEventApiShim.class.getResourceAsStream(ForgeEventApiShim.FML_RENAMES_RESOURCE)) {
            assertNotNull(in, "the FML-rename resource must be bundled");
            root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        assertTrue(root.size() >= 18, "expected the FML lifecycle surface, got " + root.size());
        root.entrySet().forEach(e -> {
            assertTrue(e.getKey().startsWith("net/minecraftforge/fml/"),
                    "unexpected source package: " + e.getKey());
            assertTrue(e.getValue().getAsString().startsWith("net/neoforged/fml/"),
                    "unexpected target package: " + e.getValue().getAsString());
        });
        // Owned elsewhere: Forge_1_20_to_NeoForge_1_21, the @Mod rewrite, and the B4 synthetic.
        assertFalse(root.has("net/minecraftforge/fml/ModLoadingContext"), "ModLoadingContext is hand-handled");
        assertFalse(root.has("net/minecraftforge/fml/loading/FMLPaths"), "FMLPaths is hand-handled");
        assertFalse(root.has("net/minecraftforge/fml/common/Mod"), "@Mod is hand-handled");
        assertFalse(root.has("net/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext"),
                "FMLJavaModLoadingContext is supplied by the B4 synthetic");
    }

    @Test
    @DisplayName("rename table is well-formed, comprehensive, and disjoint from hand-handled renames")
    void tableShapeAndDisjointness() throws Exception {
        JsonObject root;
        try (InputStream in = ForgeEventApiShim.class.getResourceAsStream(ForgeEventApiShim.EVENT_RENAMES_RESOURCE)) {
            assertNotNull(in, "the event-rename resource must be bundled");
            root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        assertTrue(root.size() >= 250, "expected a comprehensive table, got " + root.size());
        root.entrySet().forEach(e -> {
            assertTrue(e.getKey().startsWith("net/minecraftforge/event/")
                            || e.getKey().startsWith("net/minecraftforge/client/event/"),
                    "unexpected source package: " + e.getKey());
            assertTrue(e.getValue().getAsString().startsWith("net/neoforged/neoforge/"),
                    "unexpected target package: " + e.getValue().getAsString());
        });
        // Renamed/removed events must not appear as clean renames, or they'd clobber the
        // hand-tuned mappings in ForgeEventApiShim and point at wrong classes.
        assertFalse(root.has("net/minecraftforge/event/entity/EntityJoinWorldEvent"),
                "EntityJoinWorldEvent is hand-renamed to EntityJoinLevelEvent");
        assertFalse(root.has("net/minecraftforge/event/entity/living/LivingHurtEvent"),
                "LivingHurtEvent was removed/merged into LivingDamageEvent");
        assertFalse(root.has("net/minecraftforge/event/TickEvent"),
                "TickEvent has no clean NeoForge counterpart (phase→Pre/Post redesign)");
    }
}
