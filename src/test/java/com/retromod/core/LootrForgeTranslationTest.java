/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.retromod.mapping.IntermediaryToMojangMapper;
import com.retromod.mapping.SrgToMojangMapper;
import com.retromod.mapping.TargetSrgMapper;
import com.retromod.mixin.MixinCompatibilityTransformer;
import com.retromod.shim.ShimRegistration;
import com.retromod.shim.ShimRegistry;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Lootr for Forge 1.20 on official-name Forge hosts: its SRG refmap and its loot condition type. */
class LootrForgeTranslationTest {

    private static final String MIXIN = "noobanidus/mods/lootr/mixins/MixinPoiTypes";
    private static final String SRG_SELECTOR = "Lnet/minecraft/world/entity/ai/village/poi/PoiTypes;"
            + "m_218075_(Lnet/minecraft/world/level/block/state/BlockState;)Ljava/util/Optional;";
    private static final String MOJANG_SELECTOR = "Lnet/minecraft/world/entity/ai/village/poi/PoiTypes;"
            + "forState(Lnet/minecraft/world/level/block/state/BlockState;)Ljava/util/Optional;";
    // Lootr's refmap entry for MixinPoiTypes, in its mappings and data.searge sections.
    private static final String REFMAP = "{\"mappings\":{\"" + MIXIN + "\":{\"forState\":\"" + SRG_SELECTOR
            + "\"}},\"data\":{\"searge\":{\"" + MIXIN + "\":{\"forState\":\"" + SRG_SELECTOR + "\"}}}}";

    private static final String LOOT = "net/minecraft/world/level/storage/loot/";
    private static final String SERIALIZER = LOOT + "Serializer";
    private static final String CONDITION_TYPE = LOOT + "predicates/LootItemConditionType";

    @TempDir
    Path tempDir;

    private String savedTarget;

    @BeforeEach
    void saveTarget() {
        savedTarget = RetromodVersion.TARGET_MC_VERSION;
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Lootr's owner-qualified SRG selector becomes forState on a Mojang-named Forge host")
    void srgSelectorBecomesMojang() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        SrgToMojangMapper.getInstance().applyTo(transformer);

        String out = MixinRefmapRemapper.remapForgeSelectorsWithRepairs(
                REFMAP, new MixinCompatibilityTransformer(transformer)).json();

        assertSelector(out, MOJANG_SELECTOR);
    }

    @Test
    @DisplayName("Forge 1.20.1 runs SRG names, so Lootr's selector keeps m_218075_ there")
    void srgHostKeepsSrgSelector() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        SrgToMojangMapper.getInstance().applyTo(transformer);
        TargetSrgMapper.forVersion("1.20.1").applyTo(transformer);

        String out = MixinRefmapRemapper.remapForgeSelectorsWithRepairs(
                REFMAP, new MixinCompatibilityTransformer(transformer)).json();

        assertSelector(out, SRG_SELECTOR);
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("A Lootr-shaped mod translates on a Forge host, so the game never loads its SRG refmap")
    void lootrShapedModTranslates(String host) throws Exception {
        forgeHost(host);
        Path input = tempDir.resolve("lootr-like.jar");
        writeLootrShapedJar(input);

        Path output = new ForgeModTransformer(host)
                .transformMod(input, Files.createDirectories(tempDir.resolve("out")));

        // A failed translation leaves the original jar, whose SRG refmap stops the game at bootstrap.
        assertNotNull(output, "the translation must not be refused over an unembedded stand-in");
        try (JarFile jar = new JarFile(output.toFile())) {
            String refmap = new String(jar.getInputStream(jar.getEntry("lootr.refmap.json"))
                    .readAllBytes(), StandardCharsets.UTF_8);
            assertSelector(refmap, MOJANG_SELECTOR);
            String base = SyntheticEmbedder.embeddedBase(input.getFileName().toString());
            assertNotNull(jar.getEntry(base + "com/retromod/shim/common/embedded/LootItemFunctionType.class"),
                    "the loot type factory's function stand-in must travel with it");
        }
    }

    /** Registers what RetromodForge registers on a Forge {@code host} that runs Mojang names. */
    private static void forgeHost(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        McReflect.setForceNeoForge(false);
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (Set.of("forge", "common").contains(shim.getModLoaderType())
                    && ShimRegistry.isAvailableOnHost(shim, host)) {
                ShimRegistration.register(shim, transformer);
            }
        }
        SrgToMojangMapper.getInstance().applyTo(transformer);
        IntermediaryToMojangMapper.applyClassMovesOnly(transformer);
    }

    private static void assertSelector(String refmapJson, String expected) {
        JsonObject root = JsonParser.parseString(refmapJson).getAsJsonObject();
        assertEquals(expected, root.getAsJsonObject("mappings").getAsJsonObject(MIXIN)
                .get("forState").getAsString(), "mappings section");
        assertEquals(expected, root.getAsJsonObject("data").getAsJsonObject("searge")
                .getAsJsonObject(MIXIN).get("forState").getAsString(), "data.searge section");
    }

    /** Lootr's refmap entry plus a class that builds a loot condition type, as Lootr's ModLoot does. */
    private static void writeLootrShapedJar(Path jar) throws Exception {
        String toml = "modLoader=\"javafml\"\nloaderVersion=\"[44,)\"\nlicense=\"MIT\"\n"
                + "[[mods]]\nmodId=\"lootr\"\nversion=\"0.7.35.94\"\n"
                + "[[dependencies.lootr]]\nmodId=\"forge\"\nmandatory=true\nversionRange=\"[44.0.40,)\"\n"
                + "[[dependencies.lootr]]\nmodId=\"minecraft\"\nmandatory=true\nversionRange=\"[1.20,1.20.1]\"\n";
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("META-INF/mods.toml"));
            out.write(toml.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry("lootr.refmap.json"));
            out.write(REFMAP.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry("noobanidus/mods/lootr/init/ModLoot.class"));
            out.write(conditionTypeFactory());
            out.closeEntry();
        }
    }

    /** {@code static LootItemConditionType count(Serializer s) { return new LootItemConditionType(s); }} */
    private static byte[] conditionTypeFactory() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "noobanidus/mods/lootr/init/ModLoot", null,
                "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "count",
                "(L" + SERIALIZER + ";)L" + CONDITION_TYPE + ";", null, null);
        method.visitCode();
        method.visitTypeInsn(Opcodes.NEW, CONDITION_TYPE);
        method.visitInsn(Opcodes.DUP);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, CONDITION_TYPE, "<init>",
                "(L" + SERIALIZER + ";)V", false);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
