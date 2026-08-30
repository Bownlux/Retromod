/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.core.FuzzyMethodResolver;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deeper and Darker's {@code PaintingMixin.dropItem} looks like the easiest blocklist entry to
 * retire: the target only gained a leading {@code ServerLevel}, which the re-signature engine
 * already repairs. Retiring it would break the mod.
 *
 * <p>The handler body reads {@code Painting.VARIANT_CODEC}, and that field is gone from 26.2. A
 * repaired handler would apply cleanly and then throw {@code NoSuchFieldError} the first time a
 * painting breaks, which is worse than the current soft-fail. These tests pin that reasoning so the
 * entry is not retired on the strength of its signature alone.
 */
class DeeperDarkerBlocklistAuditTest {

    private static final String PAINTING_MIXIN = "com/kyanite/deeperdarker/mixin/PaintingMixin";
    private static final String HANGING_ITEM_MIXIN =
            "com/kyanite/deeperdarker/mixin/HangingEntityItemMixin";
    private static final String VARIANT_CODEC = "VARIANT_CODEC";

    private String savedVersion;

    @AfterEach
    void restore() {
        if (savedVersion != null) RetromodVersion.TARGET_MC_VERSION = savedVersion;
    }

    @Test
    @DisplayName("The two Deeper and Darker handlers stay blocked")
    void handlersStayBlocked() {
        assertEquals(Set.of("dropItem"), MixinBlocklist.methodsToStrip(PAINTING_MIXIN),
                "retiring dropItem needs the body repaired too, not just the signature");
        assertEquals(Set.of("appendHoverText"), MixinBlocklist.methodsToStrip(HANGING_ITEM_MIXIN));
        assertFalse(MixinBlocklist.isFullStrip(PAINTING_MIXIN),
                "only the one handler is stripped, so getPickResult keeps working");
    }

    @Test
    @DisplayName("dropItem is not in the signature-change table, because repairing it is not enough")
    void dropItemIsNotRegisteredForResignature() {
        savedVersion = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "26.2";

        MethodNode handler = new MethodNode(org.objectweb.asm.Opcodes.ACC_PUBLIC, "dropItem",
                "(Lnet/minecraft/world/entity/Entity;"
                        + "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", null, null);
        org.objectweb.asm.tree.AnnotationNode inject = new org.objectweb.asm.tree.AnnotationNode(
                "Lorg/spongepowered/asm/mixin/injection/Inject;");
        inject.values = new java.util.ArrayList<>(
                List.of("method", new java.util.ArrayList<>(List.of("dropItem"))));
        handler.visibleAnnotations = new java.util.ArrayList<>(List.of(inject));

        assertNull(MixinHandlerResignature.injectSignatureChange(handler),
                "adding dropItem here would repair the descriptor and leave the body reading a "
                        + "deleted field, turning a soft-fail into a crash on breaking a painting");
    }

    @Test
    @DisplayName("The blocked handler really does read the field 26.2 deleted")
    void blockedHandlerReadsADeletedField() throws Exception {
        Path mod = firstExisting(
                Path.of("test-jars-mixin/deeperdarker-neoforge-1.21.1-1.4.1.jar"));
        Assumptions.assumeTrue(mod != null, "deeper and darker fixture present");

        ClassNode mixin = readClass(mod, PAINTING_MIXIN + ".class");
        MethodNode dropItem = mixin.methods.stream()
                .filter(m -> m.name.equals("dropItem")).findFirst().orElseThrow();

        boolean readsVariantCodec = false;
        for (var insn : dropItem.instructions.toArray()) {
            if (insn instanceof FieldInsnNode f && VARIANT_CODEC.equals(f.name)) readsVariantCodec = true;
        }
        assertTrue(readsVariantCodec,
                "the reason this handler cannot be repaired is that its body reads " + VARIANT_CODEC);
    }

    @Test
    @DisplayName("That field is absent from every class on a 26.x host")
    void deletedFieldIsAbsentFromTheHost() throws Exception {
        Path host = hostJar();
        Assumptions.assumeTrue(host != null, "26.2 host jar present");

        try (ZipFile zip = new ZipFile(host.toFile())) {
            for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
                if (!entry.getName().endsWith(".class")) continue;
                if (!entry.getName().startsWith("net/minecraft/")) continue;
                ClassNode node = new ClassNode();
                try (InputStream in = zip.getInputStream(entry)) {
                    new ClassReader(in.readAllBytes()).accept(node, ClassReader.SKIP_CODE);
                }
                if (node.fields == null) continue;
                for (var field : node.fields) {
                    assertNotEquals(VARIANT_CODEC, field.name,
                            VARIANT_CODEC + " came back on " + node.name
                                    + ", so this blocklist entry can be revisited");
                }
            }
        }
    }

    @Test
    @DisplayName("The safety gate rejects this handler on the real host, without over-rejecting")
    void theSafetyGateRejectsItOnTheRealHost() throws Exception {
        Path host = hostJar();
        Assumptions.assumeTrue(host != null, "26.2 host jar present");

        FuzzyMethodResolver resolver = new FuzzyMethodResolver();
        resolver.indexJar(host);
        assertTrue(resolver.isIndexed());

        // The class move puts Painting in its 26.x package, which is what the gate sees post-remap.
        String owner = "net/minecraft/world/entity/decoration/painting/Painting";
        assertTrue(resolver.hasClass(owner), "the moved owner must be present, or the gate skips it");
        assertFalse(resolver.hasFieldName(owner, VARIANT_CODEC),
                "this is the exact condition that declines the repair");

        // A field the host still declares must not trip the same check.
        assertTrue(resolver.hasFieldName(owner, "DEPTH"),
                "a live field must resolve, so the gate does not decline sound handlers");

        // The body also reads game rules through a method the rework removed.
        assertFalse(resolver.hasMethodName("net/minecraft/world/level/Level", "getGameRules"),
                "the game-rule rework removed this accessor, so the gate declines on it too");

        // createSerializationContext survives as a default method on HolderLookup.Provider, which
        // RegistryAccess extends. Resolution is hierarchy-wide, so an inherited default must not
        // read as removed.
        assertTrue(resolver.hasMethodName("net/minecraft/core/RegistryAccess",
                        "createSerializationContext"),
                "an inherited default method still resolves through the interface hierarchy");

        // spawnAtLocation only changed descriptor, so matching by name must NOT decline it. That
        // distinction is the whole reason the gate matches names rather than descriptors: a changed
        // descriptor is what the repair engines exist to fix.
        assertTrue(resolver.hasMethodName(owner, "spawnAtLocation"),
                "a method that merely gained a parameter still resolves by name");
        assertTrue(resolver.hasMethodName(owner, "getVariant"));
    }

    private static Path hostJar() {
        return firstExisting(
                Path.of(System.getProperty("user.home", ""),
                        "Library/Application Support/PrismLauncher/libraries/com/mojang/minecraft",
                        "26.2/minecraft-26.2-client.jar"),
                Path.of("test-jars-mixin/minecraft-26.2-client.jar"));
    }

    private static Path firstExisting(Path... candidates) {
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    private static ClassNode readClass(Path jar, String entryName) throws Exception {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(entryName);
            assertNotNull(entry, "missing " + entryName + " in " + jar);
            ClassNode node = new ClassNode();
            try (InputStream in = zip.getInputStream(entry)) {
                new ClassReader(in.readAllBytes()).accept(node, 0);
            }
            return node;
        }
    }
}
