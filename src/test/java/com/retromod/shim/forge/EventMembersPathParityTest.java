/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.FabricModTransformer;
import com.retromod.core.ForgeModTransformer;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.loaderapi.CliHarness;
import com.retromod.mapping.IntermediaryToMojangMapper;
import com.retromod.mapping.SrgToMojangMapper;
import com.retromod.shim.ShimRegistration;
import com.retromod.shim.ShimRegistry;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Every transform path answers the old event members, getTags() and a mod event on an interface-turned base the same way, nested jars too. */
class EventMembersPathParityTest {

    private static final String FORGE_CLIENT = "net/minecraftforge/client/event/";
    private static final String NEO_CLIENT = "net/neoforged/neoforge/client/event/";
    private static final String LIVING_EVENT = "net/minecraftforge/event/entity/living/LivingEvent";
    private static final String NESTED = "META-INF/jarjar/lib.jar";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @BeforeEach
    void reset() {
        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(false);
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
    @DisplayName("Forge: the runtime, CLI batch, CLI transform and AOT paths agree on a Forge 1.20.1 mod and its nested library")
    void forge(String host, @TempDir Path dir) throws Exception {
        McReflect.setForceForge(true);
        Map<String, String> expected = new TreeMap<>();
        for (String prefix : List.of("", "nested:")) {
            expected.put(prefix + "render", "legacyapi:forge/client/event/RenderLivingMembers.getEntity");
            expected.put(prefix + "typed", "legacyapi:minecraft/client/input/LegacyHeldModifiers.modifiers");
            expected.put(prefix + "scrolled", FORGE_CLIENT + "ScreenEvent$MouseScrolled$Post.getDeltaY");
            expected.put(prefix + "pressed", host.equals("26.2") ? FORGE_CLIENT + "ScreenEvent$KeyPressed$Pre.getScanCode"
                    : "legacyapi:forge/client/event/ScreenKeyMembers.scanCode");
            expected.put(prefix + "inputScrolled", FORGE_CLIENT + "InputEvent$MouseScrollingEvent.getDeltaY");
            expected.put(prefix + "inputKey", host.equals("26.2") ? FORGE_CLIENT + "InputEvent$Key.getScanCode"
                    : "legacyapi:forge/client/event/ScreenKeyMembers.inputScanCode");
            expected.put(prefix + "earned", "net/minecraftforge/event/entity/player/AdvancementEvent.getAdvancement, "
                    + "generated:LegacyAdvancements.fromHolder, generated:LegacyAdvancements.getId");
            expected.put(prefix + "tags", "generated:LegacyRegistryTagPairs.getTags");
            expected.put(prefix + "event", "extends legacyapi:forge/event/LegacyLivingEvent");
        }
        assertEveryPathAgrees(host, dir, Fixture.FORGE_1201, expected);
    }

    @ParameterizedTest(name = "NeoForge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("NeoForge: every path moves a Forge 1.20.1 mod's members onto NeoForge's events and the same helpers")
    void neoForge(String host, @TempDir Path dir) throws Exception {
        McReflect.setForceNeoForge(true);
        Map<String, String> expected = new TreeMap<>();
        for (String prefix : List.of("", "nested:")) {
            expected.put(prefix + "render", "legacyapi:neoforge/client/event/RenderLivingMembers.getEntity");
            expected.put(prefix + "typed", "legacyapi:minecraft/client/input/LegacyHeldModifiers.modifiers");
            expected.put(prefix + "scrolled", NEO_CLIENT + "ScreenEvent$MouseScrolled$Post.getScrollDeltaY");
            expected.put(prefix + "pressed", NEO_CLIENT + "ScreenEvent$KeyPressed$Pre."
                    + (host.equals("26.2") ? "getScanCode" : "getKeycode"));
            expected.put(prefix + "inputScrolled", NEO_CLIENT + "InputEvent$MouseScrollingEvent.getScrollDeltaY");
            expected.put(prefix + "inputKey", NEO_CLIENT + "InputEvent$Key." + (host.equals("26.2") ? "getScanCode" : "getKeycode"));
            expected.put(prefix + "earned", "net/neoforged/neoforge/event/entity/player/AdvancementEvent.getAdvancement, "
                    + "generated:LegacyAdvancements.fromHolder, generated:LegacyAdvancements.getId");
            expected.put(prefix + "tags", "generated:LegacyRegistryTagPairs.getTags");
            expected.put(prefix + "event", "extends net/neoforged/neoforge/event/entity/living/LivingEvent");
        }
        assertEveryPathAgrees(host, dir, Fixture.FORGE_1201, expected);
    }

    @ParameterizedTest(name = "Fabric {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("Fabric: every path sends a Fabric 1.21.1 mod's getTags() to the pair helper after the intermediary remap")
    void fabric(String host, @TempDir Path dir) throws Exception {
        Map<String, String> expected = new TreeMap<>();
        for (String prefix : List.of("", "nested:")) {
            expected.put(prefix + "tags", "generated:LegacyRegistryTagPairs.getTags");
        }
        assertEveryPathAgrees(host, dir, Fixture.FABRIC_1211, expected);
    }

    private void assertEveryPathAgrees(String host, Path dir, Fixture fixture, Map<String, String> expected) throws Exception {
        Files.createDirectories(dir);
        Path input = dir.resolve("members.jar");
        Files.write(input, modJar(fixture));

        Map<String, Map<String, String>> outputs = new LinkedHashMap<>();
        outputs.put("runtime", summary(runtime(input, dir, host, fixture), fixture));
        Path batchIn = Files.createDirectory(dir.resolve("batch-in"));
        Path batchOut = Files.createDirectory(dir.resolve("batch-out"));
        Files.copy(input, batchIn.resolve("members.jar"));
        CliHarness.batch(batchIn, batchOut, host);
        outputs.put("CLI batch", summary(LegacyAutoSubscribePathParityTest.onlyJar(batchOut), fixture));
        Path transformed = dir.resolve("transformed.jar");
        CliHarness.transform(input, transformed, host);
        outputs.put("CLI transform", summary(transformed, fixture));
        Path aot = dir.resolve("aot.jar");
        CliHarness.aot(input, aot, host);
        outputs.put("AOT", summary(aot, fixture));

        for (Map.Entry<String, Map<String, String>> output : outputs.entrySet()) {
            assertEquals(expected, output.getValue(), fixture + " on " + host + ", " + output.getKey());
        }
    }

    /** What the loader's entry point registers on the host, mappings and shims, then one runtime transform. */
    private Path runtime(Path input, Path dir, String host, Fixture fixture) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        Path out = Files.createDirectory(dir.resolve("runtime"));
        if (fixture == Fixture.FABRIC_1211) {
            IntermediaryToMojangMapper.applyTo(transformer);
            registerShims(host, Set.of("fabric", "common"));
            return new FabricModTransformer(host).transformMod(input, out);
        }
        SrgToMojangMapper.getInstance().applyTo(transformer);
        registerShims(host, McReflect.isNeoForge() ? Set.of("neoforge", "forge", "common") : Set.of("forge", "common"));
        IntermediaryToMojangMapper.applyClassMovesOnly(transformer);
        Path written = new ForgeModTransformer(host).transformMod(input, out);
        assertNotNull(written, "the runtime wrote a jar");
        return written;
    }

    private void registerShims(String host, Set<String> loaders) {
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (loaders.contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, host)) {
                ShimRegistration.register(shim, transformer);
            }
        }
    }

    // ---- reading the output ----

    private static Map<String, String> summary(Path jar, Fixture fixture) throws Exception {
        Map<String, String> found = new TreeMap<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            describe(file, "mod/", "", fixture, found);
            Path nested = Files.createTempFile("nested-", ".jar");
            try {
                Files.write(nested, file.getInputStream(file.getJarEntry(fixture.nested)).readAllBytes());
                try (JarFile lib = new JarFile(nested.toFile())) {
                    describe(lib, "lib/", "nested:", fixture, found);
                }
            } finally {
                Files.deleteIfExists(nested);
            }
        }
        return found;
    }

    // Each fixture method's calls, and the event's superclass, with Retromod's embedded classes named by their rebuilt name.
    private static void describe(JarFile jar, String pkg, String prefix, Fixture fixture, Map<String, String> found) throws Exception {
        ClassNode handlers = node(jar, pkg + "Handlers");
        for (MethodNode method : handlers.methods) {
            if (method.name.equals("<init>")) continue;
            List<String> calls = new ArrayList<>();
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(name(jar, call.owner) + "." + call.name);
            }
            found.put(prefix + method.name, String.join(", ", calls));
        }
        if (fixture != Fixture.FABRIC_1211) found.put(prefix + "event", "extends " + name(jar, node(jar, pkg + "CurioEvent").superName));
    }

    /** A class Retromod embedded, as {@code legacyapi:} or {@code generated:} and its own name, when the jar holds it. */
    private static String name(JarFile jar, String owner) {
        for (String root : List.of("com/retromod/legacyapi/", "com/retromod/generated/")) {
            int at = owner.indexOf(root);
            if (owner.startsWith("com/retromod/embedded/") && at > 0) {
                String kind = root.contains("legacyapi") ? "legacyapi:" : "generated:";
                return jar.getJarEntry(owner + ".class") != null ? kind + owner.substring(at + root.length()) : "missing " + owner;
            }
        }
        return owner;
    }

    private static ClassNode node(JarFile jar, String name) throws Exception {
        JarEntry entry = jar.getJarEntry(name + ".class");
        assertNotNull(entry, name + " is missing from " + jar.getName());
        return ModConstructorHooksTest.node(jar.getInputStream(entry).readAllBytes());
    }

    // ---- the mod ----

    /** The names a mod is written in: SRG for Forge 1.20.1, intermediary for Fabric 1.21.1. */
    enum Fixture {
        FORGE_1201("net/minecraft/core/Registry", "m_203612_", "META-INF/jarjar/lib.jar"),
        FABRIC_1211("net/minecraft/class_2378", "method_40272", "META-INF/jars/lib.jar");

        final String registry;
        final String getTags;
        final String nested;

        Fixture(String registry, String getTags, String nested) {
            this.registry = registry;
            this.getTags = getTags;
            this.nested = nested;
        }
    }

    private static byte[] modJar(Fixture fixture) throws Exception {
        Map<String, byte[]> lib = files("fixturelib", "lib/", fixture, null);
        Map<String, byte[]> mod = files("membersmod", "mod/", fixture, fixture.nested);
        mod.put(fixture.nested, LegacyAutoSubscribePathParityTest.jar(lib));
        return LegacyAutoSubscribePathParityTest.jar(mod);
    }

    private static Map<String, byte[]> files(String modId, String pkg, Fixture fixture, String nestedJar) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        if (fixture == Fixture.FORGE_1201) {
            files.put("META-INF/mods.toml", LegacyAutoSubscribePathParityTest.toml(modId));
            files.put(pkg + "Handlers.class", forgeHandlers(pkg + "Handlers", fixture));
            files.put(pkg + "CurioEvent.class", LegacyInterfaceEventBasesTest.modEvent(pkg + "CurioEvent", LIVING_EVENT,
                    new String[] {"net/minecraft/world/entity/LivingEntity"}));
        } else {
            files.put("fabric.mod.json", ("{\"schemaVersion\":1,\"id\":\"" + modId + "\",\"version\":\"1.0\","
                    + "\"depends\":{\"minecraft\":\"1.21.1\"}" + (nestedJar == null ? "" : ",\"jars\":[{\"file\":\"" + nestedJar + "\"}]")
                    + "}").getBytes(StandardCharsets.UTF_8));
            files.put(pkg + "Handlers.class", handlers(pkg + "Handlers", List.of(tags(fixture))));
        }
        return files;
    }

    /** One call to make: the method that makes it, the receiver's type, and the member called. */
    private record Call(String method, String owner, String name, String desc, String then) {}

    private static Call tags(Fixture fixture) {
        return new Call("tags", fixture.registry, fixture.getTags, "()Ljava/util/stream/Stream;", null);
    }

    // As Alex's Mobs, JEI, Zeta, Lootr and REI make these calls, in Forge 1.20.1's names.
    private static byte[] forgeHandlers(String name, Fixture fixture) {
        return handlers(name, List.of(
                new Call("render", FORGE_CLIENT + "RenderLivingEvent$Pre", "getEntity", "()Lnet/minecraft/world/entity/LivingEntity;", null),
                new Call("typed", FORGE_CLIENT + "ScreenEvent$CharacterTyped$Pre", "getModifiers", "()I", null),
                new Call("scrolled", FORGE_CLIENT + "ScreenEvent$MouseScrolled$Post", "getScrollDelta", "()D", null),
                new Call("pressed", FORGE_CLIENT + "ScreenEvent$KeyPressed$Pre", "getScanCode", "()I", null),
                new Call("inputScrolled", FORGE_CLIENT + "InputEvent$MouseScrollingEvent", "getScrollDelta", "()D", null),
                new Call("inputKey", FORGE_CLIENT + "InputEvent$Key", "getScanCode", "()I", null),
                new Call("earned", "net/minecraftforge/event/entity/player/AdvancementEvent", "getAdvancement",
                        "()Lnet/minecraft/advancements/Advancement;", "m_138327_"),
                tags(fixture)));
    }

    private static byte[] handlers(String name, List<Call> calls) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        for (Call call : calls) {
            boolean registry = call.owner().endsWith("Registry") || call.owner().endsWith("class_2378");
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, call.method(), "(L" + call.owner() + ";)V", null, null);
            mv.visitCode();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(registry ? INVOKEINTERFACE : INVOKEVIRTUAL, call.owner(), call.name(), call.desc(), registry);
            if (call.then() != null) {
                // Lootr reads the id off the advancement the event gave it.
                mv.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/advancements/Advancement", call.then(),
                        "()Lnet/minecraft/resources/ResourceLocation;", false);
            }
            mv.visitInsn(Type.getReturnType(call.desc()).getSize() == 2 ? POP2 : POP);
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }
}
