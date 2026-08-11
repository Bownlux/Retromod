/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.retromod.mapping.IntermediaryToMojangMapper;
import com.retromod.mixin.AutomaticMixinTranslator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A Fabric mixin refmap in the intermediary namespace makes {@code @Inject}/{@code @At} selectors
 * name intermediary targets ({@code net/minecraft/class_310}); on a 26.1+ (official) host Mixin
 * rejects those ({@code InvalidInjectionException: … 'net/minecraft/class_310' … not supported}),
 * breaking the mod. Retromod must remap the refmap to Mojang and add a {@code data.official} section.
 * Found via an in-game 26.2 Fabric launch: the OFFLINE batch path (unlike the runtime path) left
 * refmaps unremapped, so AppleSkin's mixins failed.
 */
class MixinRefmapRemapperTest {

    private static final String CONNECT_SCREEN =
            "net/minecraft/client/gui/screens/ConnectScreen";
    private static final String START_CONNECTING_OLD_ARGS =
            "Lnet/minecraft/client/gui/screens/Screen;"
            + "Lnet/minecraft/client/Minecraft;"
            + "Lnet/minecraft/client/multiplayer/resolver/ServerAddress;"
            + "Lnet/minecraft/client/multiplayer/ServerData;Z";
    private static final String TRANSFER_STATE =
            "Lnet/minecraft/client/multiplayer/TransferState;";

    @TempDir
    Path tempDir;

    private final IntermediaryToMojangMapper mapper = IntermediaryToMojangMapper.getInstance();

    @Test
    @DisplayName("plain data.intermediary -> data.official, remapped to Mojang")
    void remapsPlainIntermediaryData() {
        assertEquals("net/minecraft/client/Minecraft", mapper.mapClass("net/minecraft/class_310"),
                "sanity: mapping data loaded");
        String refmap = "{\"data\":{\"intermediary\":{\"MinecraftClientMixin\":{"
                + "\"onTick\":\"net/minecraft/class_310;method_1574()V\"}}}}";
        JsonObject root = JsonParser.parseString(MixinRefmapRemapper.remap(refmap, mapper)).getAsJsonObject();
        assertTrue(root.getAsJsonObject("data").has("official"), "data.official added");
        String off = root.getAsJsonObject("data").getAsJsonObject("official")
                .getAsJsonObject("MinecraftClientMixin").get("onTick").getAsString();
        assertTrue(off.contains("net/minecraft/client/Minecraft") && !off.contains("class_310"),
                "official section is Mojang-mapped: " + off);
    }

    @Test
    @DisplayName("combined data.\"named:intermediary\" -> \"named:official\" (the format AppleSkin ships)")
    void remapsCombinedNamedIntermediaryData() {
        // the AppleSkin case: mappings are dev-named, data keyed "named:intermediary" (dev -> intermediary)
        String refmap = "{"
                + "\"mappings\":{\"DebugHudMixin\":{\"getLeftText\":\"net/minecraft/client/gui/Foo;bar()V\"}},"
                + "\"data\":{\"named:intermediary\":{\"DebugHudMixin\":{"
                + "\"getLeftText\":\"Lnet/minecraft/class_340;method_1835()Ljava/util/List;\"}}}"
                + "}";
        JsonObject data = JsonParser.parseString(MixinRefmapRemapper.remap(refmap, mapper))
                .getAsJsonObject().getAsJsonObject("data");
        assertTrue(data.has("named:official"),
                "a named:official section must be produced for the 26.1+ runtime namespace");
        String off = data.getAsJsonObject("named:official").getAsJsonObject("DebugHudMixin")
                .get("getLeftText").getAsString();
        assertFalse(off.contains("class_340"), "class_340 must be remapped to its Mojang name: " + off);
        assertTrue(off.contains("net/minecraft/client/gui/components/DebugScreenOverlay")
                        || !off.contains("class_"),
                "intermediary tokens are gone: " + off);
    }

    @Test
    @DisplayName("input with nothing to remap and non-JSON input are returned unchanged (fail-safe)")
    void toleratesNonRemappableInput() {
        String noMappings = "{\"foo\":\"bar\"}"; // no mappings, no data -> unchanged
        assertEquals(noMappings, MixinRefmapRemapper.remap(noMappings, mapper), "nothing to remap");
        assertEquals("not json", MixinRefmapRemapper.remap("not json", mapper),
                "unparseable input returned as-is");
    }

    @Test
    @DisplayName("Fabric refmap selectors gain parameters proven by the exact host index")
    void repairsUniqueParameterAdditionInOfficialSections() throws IOException {
        AutomaticMixinTranslator translator = translatorFor(targetClass(false));
        String oldIntermediaryArgs =
                "Lnet/minecraft/class_437;Lnet/minecraft/class_310;"
                + "Lnet/minecraft/class_639;Lnet/minecraft/class_642;Z";
        String oldValue = "Lnet/minecraft/class_412;method_36877("
                + oldIntermediaryArgs + ")V";
        String descriptorKey = "method_36877(" + oldIntermediaryArgs + ")V";
        String refmap = "{"
                + "\"mappings\":{"
                + "\"MixinConnectScreen\":{\"startConnecting\":\"" + oldValue + "\"},"
                + "\"DescriptorMixin\":{\"" + descriptorKey + "\":\"" + oldValue + "\"}"
                + "},"
                + "\"data\":{\"named:intermediary\":{\"MixinConnectScreen\":{"
                + "\"startConnecting\":\"" + oldValue + "\"}},"
                + "\"official\":{\"ExistingOfficialMixin\":{"
                + "\"startConnecting\":\"L" + CONNECT_SCREEN + ";startConnecting("
                + START_CONNECTING_OLD_ARGS + ")V\"}}}"
                + "}";

        JsonObject before = JsonParser.parseString(refmap).getAsJsonObject();
        JsonObject result = JsonParser.parseString(MixinRefmapRemapper.remap(
                refmap, mapper, translator::translateResourceSelector)).getAsJsonObject();
        String expectedMember = "startConnecting("
                + START_CONNECTING_OLD_ARGS + TRANSFER_STATE + ")V";
        String expectedValue = "L" + CONNECT_SCREEN + ";" + expectedMember;

        JsonObject mappings = result.getAsJsonObject("mappings");
        assertEquals(expectedValue, mappings.getAsJsonObject("MixinConnectScreen")
                .get("startConnecting").getAsString(),
                "the exact No Chat Reports selector shape must gain TransferState");
        assertEquals(expectedValue, mappings.getAsJsonObject("DescriptorMixin")
                .get(expectedMember).getAsString(),
                "a descriptor-shaped key must follow its repaired value");

        JsonObject data = result.getAsJsonObject("data");
        assertEquals(before.getAsJsonObject("data").getAsJsonObject("named:intermediary"),
                data.getAsJsonObject("named:intermediary"),
                "the source intermediary section must remain available and unchanged");
        assertEquals(expectedValue, data.getAsJsonObject("named:official")
                .getAsJsonObject("MixinConnectScreen").get("startConnecting").getAsString(),
                "the generated official section must receive the host-index repair");
        assertEquals(expectedValue, data.getAsJsonObject("official")
                .getAsJsonObject("ExistingOfficialMixin").get("startConnecting").getAsString(),
                "an existing official section must receive the host-index repair");
    }

    @Test
    @DisplayName("Ambiguous host overloads leave a refmap selector byte-identical")
    void refusesAmbiguousParameterAddition() throws IOException {
        AutomaticMixinTranslator translator = translatorFor(targetClass(true));
        String selector = "L" + CONNECT_SCREEN + ";ambiguous(Ljava/lang/String;)V";
        String refmap = "{\"mappings\":{\"AmbiguousMixin\":{"
                + "\"ambiguous(Ljava/lang/String;)V\":\"" + selector + "\"}}}";

        String repairedSelector = translator.translateResourceSelector(selector);
        JsonObject result = JsonParser.parseString(MixinRefmapRemapper.remap(
                refmap, mapper, translator::translateResourceSelector)).getAsJsonObject();

        assertEquals(selector, repairedSelector,
                "two insertion-compatible overloads must be refused byte-for-byte");
        JsonObject entries = result.getAsJsonObject("mappings")
                .getAsJsonObject("AmbiguousMixin");
        assertEquals(selector, entries.get("ambiguous(Ljava/lang/String;)V").getAsString(),
                "the refmap value must remain unchanged after refusal");
    }

    private AutomaticMixinTranslator translatorFor(byte[] targetClass) throws IOException {
        Path jar = tempDir.resolve("target.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(CONNECT_SCREEN + ".class"));
            out.write(targetClass);
            out.closeEntry();
        }
        FuzzyMethodResolver resolver = new FuzzyMethodResolver();
        resolver.indexJar(jar);
        assertTrue(resolver.isIndexed(), "the synthetic target JAR must be indexed");
        return new AutomaticMixinTranslator(resolver);
    }

    private static byte[] targetClass(boolean includeAmbiguousOverloads) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                CONNECT_SCREEN, null, "java/lang/Object", null);
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "startConnecting", "(" + START_CONNECTING_OLD_ARGS + TRANSFER_STATE + ")V",
                null, null).visitEnd();
        if (includeAmbiguousOverloads) {
            writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                    "ambiguous", "(ILjava/lang/String;)V", null, null).visitEnd();
            writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                    "ambiguous", "(Ljava/lang/String;I)V", null, null).visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }
}
