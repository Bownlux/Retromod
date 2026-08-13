/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.mapping.IntermediaryToMojangMapper;
import com.retromod.shim.common.LegacyClientInteractionSynthetic;
import com.retromod.shim.fabric.Fabric_1_21_11_to_26_1;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end guard for #180 (AutoClicky). The mod branches between two of its own screens and hands
 * the result to {@code Minecraft.setScreen(Screen)}. If frame computation types that merge as
 * {@code Object}, the JVM rejects the method with a {@code VerifyError} and the game dies at startup.
 *
 * <p>The hierarchy is {@code NewCombat extends OldCombat extends Screen}, so the shared type is
 * reachable from the mod's own jar without resolving any Minecraft class.
 */
class AutoClickyFrameMergeTest {

    private static final Path FIXTURE =
            Path.of("test-jars-mixin/autoclicky-1.2.1+mc1.20.5-1.21.1.jar");
    private static final String OWNER = "com/breelock/autoclicky/AutoClicky";
    private static final String LAMBDA = "lambda$onInitialize$0";
    private static final String OLD_COMBAT = "com/breelock/autoclicky/pages/OldCombat";
    private static final String SLIDER = "com/breelock/autoclicky/widgets/TooltipSliderWidget";
    private static final String PLAYER_METHODS = "com/breelock/autoclicky/PlayerMethods";
    private static final String GUI = "net/minecraft/client/gui/GuiGraphicsExtractor";

    private String savedVersion;

    @AfterEach
    void restore() {
        if (savedVersion != null) RetromodVersion.TARGET_MC_VERSION = savedVersion;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        RetromodTransformer.getInstance().clearJarClassBytesProvider();
    }

    private static byte[] entry(Path jar, String name) throws Exception {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry e = zip.getEntry(name);
            assertNotNull(e, "missing " + name);
            try (InputStream in = zip.getInputStream(e)) {
                return in.readAllBytes();
            }
        }
    }

    @Test
    @DisplayName("AutoClicky legacy Fabric API dependency uses the current mod ID")
    void migratesLegacyFabricApiDependencyFromExactJar() throws Exception {
        // The fixture is a third-party jar, so it is not in the repository and CI never has it.
        // FabricMetadataCompatTest covers the migration itself everywhere; this is the smoke check
        // that the shipped mod really carries the shape that migration expects.
        Assumptions.assumeTrue(Files.isRegularFile(FIXTURE), "autoclicky fixture present");

        byte[] metadata = entry(FIXTURE, "fabric.mod.json");
        byte[] migrated = FabricMetadataCompat.migrateLegacyFabricApiDependency(metadata);
        JsonObject depends = JsonParser.parseString(new String(migrated,
                java.nio.charset.StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonObject("depends");

        assertFalse(depends.has("fabric"), "retired Fabric API umbrella ID survived");
        assertEquals("*", depends.get("fabric-api").getAsString());
    }

    /** Every reference type that appears on the operand stack of the lambda's frames. */
    private static List<String> stackTypes(byte[] classBytes, String methodName) {
        ClassNode cn = new ClassNode();
        new ClassReader(classBytes).accept(cn, ClassReader.EXPAND_FRAMES);
        List<String> types = new ArrayList<>();
        for (MethodNode m : cn.methods) {
            if (!methodName.equals(m.name)) continue;
            for (var insn : m.instructions.toArray()) {
                if (insn instanceof FrameNode f && f.stack != null) {
                    for (Object o : f.stack) {
                        if (o instanceof String s) types.add(s);
                    }
                }
            }
        }
        return types;
    }

    /** Unpacks the fixture's classes the way every loader path does before transforming them. */
    private static void unpack(Path jar, Path dir) throws Exception {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry e : java.util.Collections.list(zip.entries())) {
                if (!e.getName().endsWith(".class")) continue;
                Path out = dir.resolve(e.getName());
                Files.createDirectories(out.getParent());
                try (InputStream in = zip.getInputStream(e)) {
                    Files.write(out, in.readAllBytes());
                }
            }
        }
    }

    private static Map<String, byte[]> classes(Path jar) throws Exception {
        Map<String, byte[]> classes = new HashMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry e : java.util.Collections.list(zip.entries())) {
                if (!e.getName().endsWith(".class")) continue;
                try (InputStream in = zip.getInputStream(e)) {
                    classes.put(e.getName().substring(0, e.getName().length() - 6),
                            in.readAllBytes());
                }
            }
        }
        return classes;
    }

    private static List<MethodInsnNode> calls(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        List<MethodInsnNode> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (var instruction : method.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode call) calls.add(call);
            }
        }
        return calls;
    }

    private static boolean hasMethod(byte[] classBytes, String name, String descriptor) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        return node.methods.stream().anyMatch(method -> name.equals(method.name)
                && descriptor.equals(method.desc));
    }

    private static void assertNoCall(Map<String, byte[]> transformed,
            String owner, String name, String descriptor) {
        for (Map.Entry<String, byte[]> entry : transformed.entrySet()) {
            assertFalse(calls(entry.getValue()).stream().anyMatch(call -> owner.equals(call.owner)
                            && name.equals(call.name) && descriptor.equals(call.desc)),
                    () -> "stale call survived in " + entry.getKey() + ": "
                            + owner + "." + name + descriptor);
        }
    }

    @Test
    @DisplayName("#180: wrapping the entrypoint must not widen a merge of the mod's own screens")
    void entrypointWrapKeepsTheSharedType(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(FIXTURE), "autoclicky fixture present");
        savedVersion = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        unpack(FIXTURE, dir);

        // This is the pass that actually rebuilt the frame in the reported crash: it wraps
        // onInitialize in a try-catch and re-emits the class with recomputed frames.
        new FabricModTransformer("26.2").wrapEntrypoints(dir);

        List<String> after = stackTypes(Files.readAllBytes(dir.resolve(OWNER + ".class")), LAMBDA);
        assertFalse(after.contains("java/lang/Object"),
                "the entrypoint wrapper widened the merge to Object, which the verifier rejects "
                        + "when the value reaches setScreen(Screen). Stack types: " + after);
    }

    @Test
    @DisplayName("#180: two of a mod's own screens must not merge to Object")
    void modOwnedScreensKeepTheirSharedType(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(FIXTURE), "autoclicky fixture present");
        savedVersion = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "26.2";

        // The original jar carries the type javac computed, which proves the information exists.
        List<String> before = stackTypes(entry(FIXTURE, OWNER + ".class"), LAMBDA);
        assertFalse(before.contains("java/lang/Object"),
                "the mod as shipped does not merge to Object: " + before);

        // Unpack so the transformer can read the mod's own hierarchy, exactly as the loader paths do.
        try (ZipFile zip = new ZipFile(FIXTURE.toFile())) {
            for (ZipEntry e : java.util.Collections.list(zip.entries())) {
                if (!e.getName().endsWith(".class")) continue;
                Path out = dir.resolve(e.getName());
                Files.createDirectories(out.getParent());
                try (InputStream in = zip.getInputStream(e)) {
                    Files.write(out, in.readAllBytes());
                }
            }
        }

        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.setJarClassBytesProvider(name -> {
            try {
                Path cf = dir.resolve(name + ".class");
                return Files.exists(cf) ? Files.readAllBytes(cf) : null;
            } catch (Exception e) {
                return null;
            }
        });
        // One redirect is enough to make the transformer actually re-emit the class.
        transformer.registerClassRedirect("net/minecraft/class_437",
                "net/minecraft/client/gui/screens/Screen");

        byte[] out = transformer.transformClass(Files.readAllBytes(dir.resolve(OWNER + ".class")), OWNER);
        assertNotNull(out, "the class should be re-emitted");

        List<String> after = stackTypes(out, LAMBDA);
        assertFalse(after.contains("java/lang/Object"),
                "frame merge widened to Object, which the verifier rejects when the value is "
                        + "passed to setScreen(Screen). Stack types: " + after);
    }

    @Test
    @DisplayName("#180: the exact AutoClicky jar receives the bounded 26.x call and GUI repairs")
    void exactJarReceivesInteractionAndGuiRepairs() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(FIXTURE), "autoclicky fixture present");
        assertEquals("535eed931ffd7fcea6a889fd898268529b2bf5f289b208ef46fe264964dd859d",
                java.util.HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(FIXTURE))),
                "the regression fixture must remain byte-identical to the reported release jar");

        savedVersion = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        Map<String, byte[]> source = classes(FIXTURE);
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        transformer.setJarClassBytesProvider(source::get);
        IntermediaryToMojangMapper.applyTo(transformer);
        new Fabric_1_21_11_to_26_1().registerRedirects(transformer);

        Map<String, byte[]> transformed = new HashMap<>();
        for (Map.Entry<String, byte[]> entry : source.entrySet()) {
            transformed.put(entry.getKey(),
                    transformer.transformClass(entry.getValue(), entry.getKey()));
        }

        String component = "Lnet/minecraft/network/chat/Component;";
        String player = "Lnet/minecraft/world/entity/player/Player;";
        String entity = "Lnet/minecraft/world/entity/Entity;";
        String hand = "Lnet/minecraft/world/InteractionHand;";
        String result = "Lnet/minecraft/world/InteractionResult;";
        String render = "(L" + GUI + ";IIF)V";
        String font = "Lnet/minecraft/client/gui/Font;";

        assertNoCall(transformed, "net/minecraft/client/player/LocalPlayer",
                "displayClientMessage", "(" + component + "Z)V");
        assertNoCall(transformed, "net/minecraft/client/multiplayer/MultiPlayerGameMode",
                "interact", "(" + player + entity + hand + ")" + result);
        assertNoCall(transformed, "net/minecraft/client/multiplayer/MultiPlayerGameMode",
                "hasInfiniteItems", "()Z");
        assertNoCall(transformed, "net/minecraft/world/InteractionResult",
                "shouldSwing", "()Z");
        assertNoCall(transformed, "net/minecraft/client/gui/components/Checkbox",
                "render", render);
        assertNoCall(transformed, "net/minecraft/client/gui/screens/Screen",
                "mouseClicked", "(DDI)Z");
        assertNoCall(transformed, GUI, "text", "(" + font + component + "IIIZ)I");
        assertNoCall(transformed, GUI, "extractTooltip",
                "(" + font + component + "II)V");

        assertTrue(calls(transformed.get(OWNER)).stream().anyMatch(call ->
                        LegacyClientInteractionSynthetic.INTERNAL.equals(call.owner)
                                && "displayClientMessage".equals(call.name)),
                "the action-bar boolean must reach the semantic message bridge");
        assertTrue(calls(transformed.get(PLAYER_METHODS)).stream().anyMatch(call ->
                        LegacyClientInteractionSynthetic.INTERNAL.equals(call.owner)
                                && "shouldSwing".equals(call.name)),
                "the swing decision must not be fuzzily changed to consumesAction");

        assertTrue(hasMethod(transformed.get(OLD_COMBAT), "extractRenderState", render));
        assertTrue(hasMethod(transformed.get(OLD_COMBAT), "mouseClicked",
                "(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z"));
        assertTrue(hasMethod(transformed.get(OLD_COMBAT), "keyPressed",
                "(Lnet/minecraft/client/input/KeyEvent;)Z"));
        assertTrue(hasMethod(transformed.get(SLIDER), "extractWidgetRenderState", render));
        assertTrue(calls(transformed.get(SLIDER)).stream().anyMatch(call ->
                        GUI.equals(call.owner) && "setTooltipForNextFrame".equals(call.name)),
                "the legacy tooltip call must enter the current deferred-tooltip API");
        assertTrue(transformer.getSyntheticClasses().containsKey(
                LegacyClientInteractionSynthetic.INTERNAL));
    }
}
