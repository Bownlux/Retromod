/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.retromod.core.ForgeModTransformer;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.core.VersionShim;
import com.retromod.loaderapi.CliHarness;
import com.retromod.shim.ShimRegistration;
import com.retromod.shim.ShimRegistry;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static com.retromod.shim.forge.LegacyForgeItemRendererBridgeTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Every transform path rebases an old item renderer onto its jar's embedded copy and writes the same item definition, nested jars too. */
class LegacyItemRendererPathParityTest {

    private static final String ITEM_MODEL = "net/minecraft/client/renderer/item/ItemModel";
    private static final String SPECIAL = "net/minecraft/client/renderer/special/SpecialModelRenderer";
    private static final String NEO_EXTENSIONS = "net/neoforged/neoforge/client/extensions/common/IClientItemExtensions";
    private static final String FORGE_OF = LegacyForgeItemRendererBridge.CLIENT_ITEM_EXTENSIONS
            + ".of(Lnet/minecraft/world/item/ItemStack;)L" + LegacyForgeItemRendererBridge.CLIENT_ITEM_EXTENSIONS + ";";
    private static final String NEO_OF = NEO_EXTENSIONS + ".of(Lnet/minecraft/world/item/ItemStack;)L" + NEO_EXTENSIONS + ";";
    // The class of each loader's install that hands the item model its lookup.
    private static final String FORGE_TYPES = "forge/client/renderer/LegacyItemRendererTypes";
    private static final String NEO_TYPES = "neoforge/client/renderer/LegacyItemModelTypes";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @BeforeEach
    void forgeTarget() {
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("The Forge runtime, CLI batch, CLI transform and AOT paths agree on the renderer, its getter, the install and the definition")
    void everyPathAgrees(String host, @TempDir Path dir) throws Exception {
        assertEveryPathAgrees(host, dir, modJar(Fixture.FORGE_1201), Fixture.FORGE_1201,
                "[forge/client/LegacyClientRenderBootstrap, forge/client/renderer/LegacyItemRenderers]", FORGE_TYPES, FORGE_OF);
    }

    @ParameterizedTest(name = "NeoForge {0}")
    @ValueSource(strings = {"26.1", "26.2", "26.3"})
    @DisplayName("On NeoForge every path does the same for a Forge 1.20.1 mod, through NeoForge's item model event")
    void neoForgeTakesForgeMods(String host, @TempDir Path dir) throws Exception {
        neoForge();
        assertEveryPathAgrees(host, dir, modJar(Fixture.FORGE_1201), Fixture.FORGE_1201,
                "[neoforge/client/renderer/LegacyItemRenderers]", NEO_TYPES, NEO_OF);
    }

    @ParameterizedTest(name = "NeoForge {0}")
    @ValueSource(strings = {"26.1", "26.2", "26.3"})
    @DisplayName("On NeoForge every path does the same for a NeoForge 1.21.1 mod")
    void neoForgeTakesNeoForgeMods(String host, @TempDir Path dir) throws Exception {
        neoForge();
        assertEveryPathAgrees(host, dir, modJar(Fixture.NEOFORGE_1211), Fixture.NEOFORGE_1211,
                "[neoforge/client/renderer/LegacyItemRenderers]", NEO_TYPES, NEO_OF);
    }

    private void neoForge() {
        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(true);
    }

    private void assertEveryPathAgrees(String host, Path dir, byte[] jar, Fixture fixture, String installs, String typesClass,
            String extensionsOf) throws Exception {
        Path input = dir.resolve("gems.jar");
        Files.write(input, jar);

        Map<String, Map<String, String>> outputs = new LinkedHashMap<>();
        outputs.put("runtime", summary(runtime(input, dir, host), typesClass, extensionsOf));
        Path batchIn = Files.createDirectory(dir.resolve("batch-in"));
        Path batchOut = Files.createDirectory(dir.resolve("batch-out"));
        Files.copy(input, batchIn.resolve("gems.jar"));
        CliHarness.batch(batchIn, batchOut, host);
        outputs.put("CLI batch", summary(LegacyAutoSubscribePathParityTest.onlyJar(batchOut), typesClass, extensionsOf));
        Path transformed = dir.resolve("transformed.jar");
        CliHarness.transform(input, transformed, host);
        outputs.put("CLI transform", summary(transformed, typesClass, extensionsOf));
        Path aot = dir.resolve("aot.jar");
        CliHarness.aot(input, aot, host);
        outputs.put("AOT", summary(aot, typesClass, extensionsOf));

        Map<String, String> expected = new TreeMap<>();
        for (String prefix : List.of("", "nested:")) {
            String namespace = prefix.isEmpty() ? "gemmod" : "fixturelib";
            expected.put(prefix + "install", installs + " from the embedded copy");
            expected.put(prefix + "renderer", "extends the embedded LegacyItemRenderer, whose constructor and renderByItem it links to");
            expected.put(prefix + "getter", "[LegacyItemRendererCalls.getCustomRenderer] on the embedded copy");
            expected.put(prefix + "definition", namespace + ":retromod_legacy_item on " + namespace
                    + ":item/retromod/item_renderer/gem, fallback " + namespace + ":item/retromod/builtin_entity/gem");
            expected.put(prefix + "embedded", "the model is a host ItemModel, its renderer a host SpecialModelRenderer, and the"
                    + " loader's install hands it the host's IClientItemExtensions.of");
        }
        for (Map.Entry<String, Map<String, String>> output : outputs.entrySet()) {
            assertEquals(expected, output.getValue(), fixture + " on " + host + ", " + output.getKey());
        }
    }

    /** What RetromodForge or RetromodNeoForge registers on the host, shims and then the vanilla class moves, then one runtime transform. */
    private Path runtime(Path input, Path dir, String host) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        Set<String> loaders = McReflect.isNeoForge() ? Set.of("neoforge", "forge", "common") : Set.of("forge", "common");
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (loaders.contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, host)) {
                ShimRegistration.register(shim, transformer);
            }
        }
        com.retromod.mapping.IntermediaryToMojangMapper.applyClassMovesOnly(transformer);
        Path out = new ForgeModTransformer(host).transformMod(input, Files.createDirectory(dir.resolve("runtime")));
        assertNotNull(out, "the runtime wrote a jar");
        return out;
    }

    private static Map<String, String> summary(Path jar, String typesClass, String extensionsOf) throws Exception {
        Map<String, String> found = new TreeMap<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            describe(file, "mod/GemMod", "gemmod", "", found, typesClass, extensionsOf);
            Path nested = Files.createTempFile("nested-", ".jar");
            try {
                Files.write(nested, file.getInputStream(file.getJarEntry("META-INF/jarjar/lib.jar")).readAllBytes());
                try (JarFile lib = new JarFile(nested.toFile())) {
                    describe(lib, "fixturelib/LibMod", "fixturelib", "nested:", found, typesClass, extensionsOf);
                }
            } finally {
                Files.deleteIfExists(nested);
            }
        }
        return found;
    }

    private static void describe(JarFile jar, String modClass, String namespace, String prefix, Map<String, String> found,
            String typesClass, String extensionsOf) throws Exception {
        List<String> installs = new ArrayList<>();
        boolean installEmbedded = true;
        for (MethodNode method : node(jar, modClass).methods) {
            if (!method.name.equals("<init>")) continue;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.name.equals("install")) {
                    installs.add(call.owner.substring(call.owner.indexOf("legacyapi/") + "legacyapi/".length()));
                    installEmbedded &= isEmbedded(jar, call.owner);
                }
            }
        }
        Collections.sort(installs);
        found.put(prefix + "install", installs + (installEmbedded ? " from the embedded copy" : " from elsewhere"));
        found.put(prefix + "renderer", renderer(jar));
        found.put(prefix + "getter", getter(jar));
        found.put(prefix + "definition", definition(jar, namespace));
        found.put(prefix + "embedded", embedded(jar, typesClass, extensionsOf));
    }

    // The renderer's base, and that the base has the constructor and the renderByItem descriptor the renderer uses.
    private static String renderer(JarFile jar) throws Exception {
        ClassNode renderer = node(jar, GEM_RENDERER);
        String base = renderer.superName;
        if (!base.endsWith("legacyapi/minecraft/client/renderer/LegacyItemRenderer") || !isEmbedded(jar, base)) return "extends " + base;
        ClassNode embeddedBase = node(jar, base);
        MethodInsnNode superCall = calls(method(renderer, "<init>")).get(0);
        String override = method(renderer, "renderByItem").desc;
        boolean links = superCall.owner.equals(base) && hasMethod(embeddedBase, "<init>", superCall.desc)
                && hasMethod(embeddedBase, "renderByItem", override);
        return "extends the embedded LegacyItemRenderer, whose constructor and renderByItem it "
                + (links ? "links to" : "does not match: " + superCall.owner + superCall.desc + " " + override);
    }

    private static String getter(JarFile jar) throws Exception {
        List<String> seen = new ArrayList<>();
        boolean embedded = true;
        for (MethodInsnNode call : calls(method(node(jar, GEM_PROPERTIES), "drawWith"))) {
            seen.add(call.owner.substring(call.owner.lastIndexOf('/') + 1) + "." + call.name);
            embedded &= isEmbedded(jar, call.owner);
        }
        return seen + (embedded ? " on the embedded copy" : " on another owner");
    }

    private static String definition(JarFile jar, String namespace) throws Exception {
        JarEntry entry = jar.getJarEntry("assets/" + namespace + "/items/gem.json");
        if (entry == null) return "no definition";
        JsonObject model = JsonParser.parseString(new String(jar.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonObject("model");
        return model.get("type").getAsString() + " on " + model.get("model").getAsString() + ", fallback "
                + (model.has("fallback") ? model.getAsJsonObject("fallback").get("model").getAsString() : "none");
    }

    // The embedded copies, which are written for the host and must keep the host's names.
    private static String embedded(JarFile jar, String typesClass, String extensionsOf) throws Exception {
        List<String> wrong = new ArrayList<>();
        if (!embeddedNode(jar, "minecraft", "LegacyItemRendererModel").interfaces.contains(ITEM_MODEL)) wrong.add("the model is no ItemModel");
        if (!embeddedNode(jar, "minecraft", "LegacyItemRendererModel$Renderer").interfaces.contains(SPECIAL)) {
            wrong.add("no SpecialModelRenderer");
        }
        // The install hands the model the host's lookup as a method reference.
        boolean asksHost = false;
        for (MethodNode method : embeddedNode(jar, typesClass.substring(0, typesClass.indexOf('/')),
                typesClass.substring(typesClass.lastIndexOf('/') + 1)).methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof InvokeDynamicInsnNode indy) {
                    for (Object argument : indy.bsmArgs) {
                        if (argument instanceof Handle handle && (handle.getOwner() + "." + handle.getName() + handle.getDesc())
                                .equals(extensionsOf)) {
                            asksHost = true;
                        }
                    }
                }
            }
        }
        if (!asksHost) wrong.add("the install never hands over " + extensionsOf);
        return wrong.isEmpty() ? "the model is a host ItemModel, its renderer a host SpecialModelRenderer, and the loader's install"
                + " hands it the host's IClientItemExtensions.of" : wrong.toString();
    }

    private static ClassNode embeddedNode(JarFile jar, String area, String simpleName) throws Exception {
        String suffix = "/com/retromod/legacyapi/" + area + "/client/renderer/" + simpleName + ".class";
        for (JarEntry entry : Collections.list(jar.entries())) {
            if (entry.getName().startsWith(SyntheticEmbedder.PREFIX) && entry.getName().endsWith(suffix)) {
                return ModConstructorHooksTest.node(jar.getInputStream(entry).readAllBytes());
            }
        }
        throw new AssertionError(simpleName + " is not embedded in " + jar.getName());
    }

    private static boolean hasMethod(ClassNode node, String name, String desc) {
        return node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc));
    }

    private static boolean isEmbedded(JarFile jar, String owner) {
        return owner.startsWith(SyntheticEmbedder.PREFIX) && jar.getJarEntry(owner + ".class") != null;
    }

    private static ClassNode node(JarFile jar, String name) throws Exception {
        JarEntry entry = jar.getJarEntry(name + ".class");
        assertNotNull(entry, name + " is missing from " + jar.getName());
        return ModConstructorHooksTest.node(jar.getInputStream(entry).readAllBytes());
    }

    /** A Forge 1.20.1 mod with a gem drawn by its own renderer, and a nested library mod with the same. */
    /** The mod a fixture jar holds: a Forge 1.20.1 mod, or a NeoForge 1.21.1 one on NeoForge's annotation and extensions. */
    enum Fixture { FORGE_1201, NEOFORGE_1211 }

    /** A mod with a gem drawn by its own renderer, and a nested library mod with the same. */
    private static byte[] modJar(Fixture fixture) throws Exception {
        Map<String, byte[]> lib = files("fixturelib", "fixturelib/LibMod", fixture);
        Map<String, byte[]> mod = files("gemmod", "mod/GemMod", fixture);
        mod.put("META-INF/jarjar/lib.jar", LegacyAutoSubscribePathParityTest.jar(lib));
        return LegacyAutoSubscribePathParityTest.jar(mod);
    }

    private static Map<String, byte[]> files(String modId, String modClass, Fixture fixture) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        if (fixture == Fixture.FORGE_1201) {
            files.put("META-INF/mods.toml", LegacyAutoSubscribePathParityTest.toml(modId));
            files.put(modClass + ".class", ModConstructorHooksTest.modClass(modClass, modId, LegacyAutoSubscribeBridgeTest.OBJECT,
                    (writer, owner) -> { }));
            files.put(GEM_PROPERTIES + ".class", gemProperties());
        } else {
            files.put("META-INF/neoforge.mods.toml", neoforgeToml(modId));
            files.put(modClass + ".class", neoForgeModClass(modClass, modId));
            files.put(GEM_PROPERTIES + ".class", gemProperties(NEO_EXTENSIONS));
        }
        files.put(GEM_RENDERER + ".class", gemRenderer());
        files.put("assets/" + modId + "/models/item/gem.json", ("{\"parent\":\"minecraft:builtin/entity\",\"textures\":{\"layer0\":\""
                + modId + ":item/gem\"},\"display\":{\"gui\":{\"rotation\":[30,45,0]}}}").getBytes(StandardCharsets.UTF_8));
        files.put("assets/" + modId + "/textures/item/gem.png", new byte[]{1});
        return files;
    }

    static byte[] neoforgeToml(String modId) {
        return ("modLoader=\"javafml\"\nloaderVersion=\"[4,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"" + modId + "\"\nversion=\"1.0\"\n"
                + "[[dependencies." + modId + "]]\nmodId=\"neoforge\"\ntype=\"required\"\nversionRange=\"[21.1.95,)\"\nordering=\"NONE\"\n"
                + "side=\"BOTH\"\n[[dependencies." + modId + "]]\nmodId=\"minecraft\"\ntype=\"required\"\nversionRange=\"[1.21.1,1.21.2)\"\n"
                + "ordering=\"NONE\"\nside=\"BOTH\"\n").getBytes(StandardCharsets.UTF_8);
    }

    // A NeoForge @Mod class with an empty constructor, as ModConstructorHooksTest writes Forge's.
    private static byte[] neoForgeModClass(String name, String modId) {
        org.objectweb.asm.tree.ClassNode node = ModConstructorHooksTest.node(ModConstructorHooksTest.modClass(name, modId,
                LegacyAutoSubscribeBridgeTest.OBJECT, (writer, owner) -> { }));
        for (org.objectweb.asm.tree.AnnotationNode annotation : node.visibleAnnotations) {
            if (annotation.desc.equals("Lnet/minecraftforge/fml/common/Mod;")) annotation.desc = "Lnet/neoforged/fml/common/Mod;";
        }
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }
}
