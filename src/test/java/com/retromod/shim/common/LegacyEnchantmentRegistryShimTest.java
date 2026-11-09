/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ForgeModTransformer;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.core.VersionShim;
import com.retromod.shim.ShimRegistration;
import com.retromod.loaderapi.CliHarness;
import com.retromod.shim.ShimRegistry;
import com.retromod.shim.common.embedded.LegacyEnchantmentRegistry;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayOutputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Saint's Dragons (Forge 1.20.1) reads {@code BuiltInRegistries.ENCHANTMENT} while it loads its trade
 * data, and 1.21 moved enchantments into data packs, so NeoForge 1.21.1 failed to load data packs.
 */
class LegacyEnchantmentRegistryShimTest {

    private static final String HELPER = LegacyEnchantmentRegistryShim.HELPER;
    private static final String BUILT_IN = "net/minecraft/core/registries/BuiltInRegistries";
    private static final String ITEM_STACK = "net/minecraft/world/item/ItemStack";
    private static final String INTERMEDIARY_BUILT_IN = "net/minecraft/class_7923";
    private static final String INTERMEDIARY_STACK = "net/minecraft/class_1799";
    private static final String ENCHANT = "(Lnet/minecraft/world/item/enchantment/Enchantment;I)V";
    private static final String VIEW_CALL = HELPER + ".enchantments()Ljava/lang/Object;";
    private static final String ENCHANT_CALL = HELPER + ".enchant(Ljava/lang/Object;Ljava/lang/Object;I)V";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedNeoForge = McReflect.isForceNeoForge();
    private final boolean savedForge = McReflect.isForceForge();

    @TempDir
    Path tempDir;

    @BeforeEach
    void clear() {
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceNeoForge(savedNeoForge);
        McReflect.setForceForge(savedForge);
        transformer.clearRedirectsForTesting();
        System.getProperties().remove(LegacyEnchantmentRegistry.RELOAD_SLOT);
    }

    // ---- host gate ----

    @Test
    @DisplayName("The shim registers on hosts from 1.21 and never on 1.20.x, which still have the field")
    void registersFrom121() {
        LegacyEnchantmentRegistryShim shim = new LegacyEnchantmentRegistryShim();
        for (String host : List.of("1.20.1", "1.20.6")) {
            assertFalse(ShimRegistry.isAvailableOnHost(shim, host), host + " still has BuiltInRegistries.ENCHANTMENT");
        }
        for (String host : List.of("1.21", "1.21.1", "1.21.11", "26.1.2", "26.3")) {
            assertTrue(ShimRegistry.isAvailableOnHost(shim, host), host + " has no BuiltInRegistries.ENCHANTMENT");
        }
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"neoforge, 1.21.1", "neoforge, 26.3", "forge, 1.21.1", "forge, 26.3", "fabric, 26.3"})
    @DisplayName("On a host without the field, the read goes to the view and the old enchant call goes through it")
    void hostWithoutTheFieldGetsTheView(String loader, String host) {
        onHost(loader, host);
        new LegacyEnchantmentRegistryShim().registerRedirects(transformer);
        byte[] out = transformer.transformClass(oldTrades(BUILT_IN, "ENCHANTMENT", ITEM_STACK, "enchant"), "mod/Trades");

        assertEquals(List.of(VIEW_CALL), BridgeHarness.calls(out, "registry"), "the field read calls the view");
        assertEquals(List.of(), BridgeHarness.fields(out, "registry"), "the removed field is no longer read");
        assertEquals(List.of(ENCHANT_CALL), BridgeHarness.calls(out, "enchant"));
        assertTrue(transformer.getSyntheticClasses().containsKey(HELPER), "the view is embedded with the mod");
    }

    @Test
    @DisplayName("A 1.20.1 target takes no redirect, and a Fabric 1.21.x target matches only intermediary names")
    void olderAndIntermediaryHosts() {
        onHost("neoforge", "1.20.1");
        new LegacyEnchantmentRegistryShim().registerRedirects(transformer);
        assertUnchanged("a 1.20.1 host still has the field and the old enchant");

        for (String host : List.of("1.21.1", "1.21.11")) {
            onHost("fabric", host);
            new LegacyEnchantmentRegistryShim().registerRedirects(transformer);
            byte[] out = transformer.transformClass(oldTrades(INTERMEDIARY_BUILT_IN, "field_41176",
                    INTERMEDIARY_STACK, "method_7978"), "mod/Trades");
            assertEquals(List.of(VIEW_CALL), BridgeHarness.calls(out, "registry"), host + ": the intermediary read calls the view");
            assertEquals(List.of(ENCHANT_CALL), BridgeHarness.calls(out, "enchant"), host);
            byte[] mojang = transformer.transformClass(oldTrades(BUILT_IN, "ENCHANTMENT", ITEM_STACK, "enchant"), "mod/Trades");
            assertEquals(List.of(BUILT_IN + ".ENCHANTMENT:Lnet/minecraft/core/Registry;"), BridgeHarness.fields(mojang, "registry"),
                    host + ": Mojang names never reach a Fabric host before 26.1");
        }
    }

    @Test
    @DisplayName("Read from the host's own classes, the gate redirects only the members the host lost, under its names")
    void hostClassesDecide() {
        onHost("neoforge", "1.21.1");
        LegacyEnchantmentRegistryShim.Names mojang = LegacyEnchantmentRegistryShim.MOJANG;
        ClassNode oldBuiltIn = node(BUILT_IN, new FieldNode(ACC_PUBLIC | ACC_STATIC, "ENCHANTMENT", mojang.fieldDesc(), null, null));
        ClassNode newBuiltIn = node(BUILT_IN, new FieldNode(ACC_PUBLIC | ACC_STATIC, "ITEM",
                "Lnet/minecraft/core/DefaultedRegistry;", null, null));
        ClassNode oldStack = node(ITEM_STACK, new MethodNode(ACC_PUBLIC, "enchant", ENCHANT, null, null));
        ClassNode newStack = node(ITEM_STACK, new MethodNode(ACC_PUBLIC, "enchant", "(Lnet/minecraft/core/Holder;I)V", null, null));
        assertFalse(LegacyEnchantmentRegistryShim.lacksField(Map.of(BUILT_IN, oldBuiltIn)::get, mojang, "1.21.1"));
        assertTrue(LegacyEnchantmentRegistryShim.lacksField(Map.of(BUILT_IN, newBuiltIn)::get, mojang, "1.21.1"));
        assertFalse(LegacyEnchantmentRegistryShim.lacksOldEnchant(Map.of(ITEM_STACK, oldStack)::get, mojang, "1.21.1"));
        assertTrue(LegacyEnchantmentRegistryShim.lacksOldEnchant(Map.of(ITEM_STACK, newStack)::get, mojang, "1.21.1"));

        // A Fabric 1.20.1 host still has both under intermediary names; 1.21.1 changed enchant to take class_6880.
        LegacyEnchantmentRegistryShim.Names intermediary = LegacyEnchantmentRegistryShim.INTERMEDIARY;
        ClassNode fabric1201 = node(INTERMEDIARY_BUILT_IN, new FieldNode(ACC_PUBLIC | ACC_STATIC, "field_41176",
                intermediary.fieldDesc(), null, null));
        ClassNode stack1201 = node(INTERMEDIARY_STACK, new MethodNode(ACC_PUBLIC, "method_7978", intermediary.enchantDesc(), null, null));
        ClassNode stack1211 = node(INTERMEDIARY_STACK, new MethodNode(ACC_PUBLIC, "method_7978", "(Lnet/minecraft/class_6880;I)V", null, null));
        assertFalse(LegacyEnchantmentRegistryShim.lacksField(Map.of(INTERMEDIARY_BUILT_IN, fabric1201)::get, intermediary, "1.20.1"));
        assertFalse(LegacyEnchantmentRegistryShim.lacksOldEnchant(Map.of(INTERMEDIARY_STACK, stack1201)::get, intermediary, "1.20.1"));
        assertTrue(LegacyEnchantmentRegistryShim.lacksOldEnchant(Map.of(INTERMEDIARY_STACK, stack1211)::get, intermediary, "1.21.1"));
        assertFalse(LegacyEnchantmentRegistryShim.lacksField(name -> null, intermediary, "1.20.1"),
                "offline, a 1.20.1 target keeps the field");
    }

    // ---- every transform path, on the real names old jars carry ----

    @Test
    @DisplayName("A Forge 1.20.1 jar's SRG names reach the embedded view on NeoForge 1.21.1 and Forge 26.3, in game and offline")
    void forgeJarOnNewerHosts(@TempDir Path dir) throws Exception {
        Path input = dir.resolve("trades.jar");
        Files.write(input, jar(Map.of("META-INF/mods.toml", forgeToml(),
                "mod/Trades.class", oldTrades(BUILT_IN, "f_256876_", ITEM_STACK, "m_41663_"))));
        for (String[] host : new String[][] {{"neoforge", "1.21.1"}, {"forge", "26.3"}}) {
            onHost(host[0], host[1]);
            Path output = dir.resolve(host[0] + host[1] + ".jar");
            CliHarness.transform(input, output, host[1]);
            assertCallsEmbeddedView(output, "CLI " + host[0] + " " + host[1]);

            // What the NeoForge or Forge entry point registers on that host, shims then SRG names, then its own transform.
            onHost(host[0], host[1]);
            for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
                if (Set.of("neoforge", "forge", "common").contains(shim.getModLoaderType())
                        && ShimRegistry.isAvailableOnHost(shim, host[1])) {
                    ShimRegistration.register(shim, transformer);
                }
            }
            com.retromod.mapping.SrgToMojangMapper.getInstance().applyTo(transformer);
            Path runtime = new ForgeModTransformer(host[1]).transformMod(input,
                    Files.createDirectories(dir.resolve("runtime-" + host[0] + host[1])));
            assertNotNull(runtime, "the in-game transform wrote a jar");
            assertCallsEmbeddedView(runtime, "in game " + host[0] + " " + host[1]);
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"game", "CLI transform", "CLI batch", "AOT"})
    @DisplayName("On NeoForge 1.21.1, a Forge 1.20.1 jar's ForgeRegistries.ENCHANTMENTS read reaches the shared view on every path")
    void forgeRegistriesEnchantmentsOnEveryPath(String path, @TempDir Path dir) throws Exception {
        Path input = dir.resolve("enchmod.jar");
        Files.write(input, jar(Map.of("META-INF/mods.toml", forgeToml(), "mod/Trades.class",
                forgeRegistryRead("net/minecraftforge/registries/ForgeRegistries", "ENCHANTMENTS"))));
        onHost("neoforge", "1.21.1");
        Path output = dir.resolve("out.jar");
        switch (path) {
            case "game" -> {
                for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
                    if (Set.of("neoforge", "forge", "common").contains(shim.getModLoaderType())
                            && ShimRegistry.isAvailableOnHost(shim, "1.21.1")) {
                        ShimRegistration.register(shim, transformer);
                    }
                }
                output = new ForgeModTransformer("1.21.1").transformMod(input, Files.createDirectories(dir.resolve("game")));
            }
            case "CLI transform" -> CliHarness.transform(input, output, "1.21.1");
            case "CLI batch" -> {
                Path in = Files.createDirectories(dir.resolve("batch-in"));
                Path out = Files.createDirectories(dir.resolve("batch-out"));
                Files.copy(input, in.resolve(input.getFileName()));
                CliHarness.batch(in, out, "1.21.1");
                try (var jars = Files.list(out)) {
                    output = jars.filter(file -> file.toString().endsWith(".jar")).findFirst().orElseThrow();
                }
            }
            default -> CliHarness.aot(input, output, "1.21.1");
        }
        try (JarFile jar = new JarFile(output.toFile())) {
            List<String> calls = BridgeHarness.calls(jar.getInputStream(jar.getJarEntry("mod/Trades.class")).readAllBytes(), "registry");
            String stand = "com/retromod/shim/api/forge/embedded/LegacyDatapackRegistries.enchantments()Ljava/lang/Object;";
            assertTrue(calls.size() == 1 && calls.get(0).startsWith(SyntheticEmbedder.PREFIX) && calls.get(0).endsWith(stand),
                    path + ": the read goes to the jar's own copy of the stand-in, as in game: " + calls);
            String root = calls.get(0).substring(0, calls.get(0).length() - stand.length());
            assertNotNull(jar.getJarEntry(root + HELPER + ".class"), path + ": the jar carries the shared view");
        }
    }

        @ParameterizedTest(name = "Fabric {0}")
    @CsvSource({"1.21.1", "1.21.11", "26.3"})
    @DisplayName("A Fabric 1.20.1 jar's intermediary names reach the embedded view on Fabric 1.21.x and 26.3")
    void fabricJarOnNewerFabric(String host, @TempDir Path dir) throws Exception {
        Path input = dir.resolve("trades.jar");
        Files.write(input, jar(Map.of("fabric.mod.json", fabricJson(), "mod/Trades.class",
                oldTrades(INTERMEDIARY_BUILT_IN, "field_41176", INTERMEDIARY_STACK, "method_7978"))));
        onHost("fabric", host);
        Path output = dir.resolve("fabric.jar");
        CliHarness.transform(input, output, host);
        assertCallsEmbeddedView(output, "fabric " + host);
    }

    // ---- the view at run time ----

    @Test
    @DisplayName("Before any world, the view is one empty enchantment registry and a codec built then reads ids later")
    void beforeAWorld() throws Throwable {
        BridgeHarness.Run run = run(false);
        Object view = run.call("mod/Trades", "registry");
        assertSame(view, run.call("mod/Trades", "registry"), "every read of the field is the same registry object");
        assertNull(run.call("mod/Trades", "find", "minecraft:sharpness"), "getOptional finds nothing yet");
        assertNull(run.call("mod/Trades", "get", "minecraft:sharpness"), "get answers null, as for an unknown id");
        assertEquals(0, run.call("mod/Trades", "count"));
        assertEquals("ResourceKey[minecraft:root / minecraft:enchantment]", run.call("mod/Trades", "key").toString());
        assertTrue(run.call("mod/Trades", "decode", "minecraft:sharpness").toString().startsWith("error"),
                "the codec has no world to read yet");

        run.call("test/World", "start", (Object) new String[] {"minecraft:sharpness", "minecraft:mending"});
        assertEquals("success value minecraft:sharpness", run.call("mod/Trades", "decode", "minecraft:sharpness").toString(),
                "the codec built in the static initializer reads the world loaded now");
    }

    @Test
    @DisplayName("With a server running, lookups, names, iteration and the old enchant call use its registry")
    void serverWorld() throws Throwable {
        BridgeHarness.Run run = run(false);
        run.call("test/World", "start", (Object) new String[] {"minecraft:sharpness", "minecraft:mending"});
        Object sharpness = run.call("mod/Trades", "find", "minecraft:sharpness");
        assertEquals("value minecraft:sharpness", String.valueOf(sharpness));
        assertSame(sharpness, run.call("mod/Trades", "get", "minecraft:sharpness"));
        assertEquals("minecraft:sharpness", run.call("mod/Trades", "name", sharpness));
        assertEquals(2, run.call("mod/Trades", "count"));

        Object stack = run.loadClass("net.minecraft.world.item.ItemStack").getConstructor().newInstance();
        run.call("mod/Trades", "enchant", stack, sharpness, 3);
        assertEquals("[minecraft:sharpness=3]", stack.getClass().getField("enchantments").get(stack).toString(),
                "the old enchant call puts the world's holder on the item");

        run.call("test/World", "stop");
        assertNull(run.call("mod/Trades", "find", "minecraft:sharpness"), "the view never keeps a closed world's registry");
    }

    @Test
    @DisplayName("During a data pack load, before any server exists, the view reads the registries Retromod recorded")
    void dataPackLoad() throws Throwable {
        BridgeHarness.Run run = run(false);
        Object registries = run.call("test/World", "access", (Object) new String[] {"minecraft:sharpness"});
        System.getProperties().put(LegacyEnchantmentRegistry.RELOAD_SLOT, new AtomicReference<Object>(new WeakReference<>(registries)));
        assertEquals("value minecraft:sharpness", String.valueOf(run.call("mod/Trades", "find", "minecraft:sharpness")));
        assertNull(run.call("mod/Trades", "find", "mod:missing"));
        assertNotNull(registries, "held until here, as the load holds its registries while it runs");

        // A server that starts or stops empties the slot, so the title screen never reads a closed world.
        ReloadRegistryRecorder.slot().set(new WeakReference<>(registries));
        ReloadRegistryRecorder.clear(null);
        assertNull(run.call("mod/Trades", "find", "minecraft:sharpness"));
    }

    @Test
    @DisplayName("On a client, the client thread reads the client's registries and other threads the integrated server's")
    void clientThreadReadsTheConnection() throws Throwable {
        BridgeHarness.Run run = run(false, CLIENT_SOURCES);
        run.call("test/Client", "singleplayer", new String[] {"minecraft:sharpness", "client:only"},
                new String[] {"minecraft:sharpness", "server:only"});
        run.call("test/Client", "onClientThread", true);
        assertNotNull(run.call("mod/Trades", "find", "client:only"), "the client thread holds the client's objects");
        assertNull(run.call("mod/Trades", "find", "server:only"));
        run.call("test/Client", "onClientThread", false);
        assertNotNull(run.call("mod/Trades", "find", "server:only"), "the server thread holds the server's objects");
        assertNull(run.call("mod/Trades", "find", "client:only"));
    }

    @Test
    @DisplayName("An enchantment the world lacks is left off the item, with one warning")
    void unknownEnchantmentWarnsOnce() throws Throwable {
        BridgeHarness.Run run = run(false);
        run.call("test/World", "start", (Object) new String[] {"minecraft:sharpness"});
        Object stray = run.loadClass("net.minecraft.world.item.enchantment.Enchantment").getConstructor(String.class).newInstance("stray");
        Object stack = run.loadClass("net.minecraft.world.item.ItemStack").getConstructor().newInstance();
        String printed = captureErrAndOut(() -> {
            run.call("mod/Trades", "enchant", stack, stray, 1);
            run.call("mod/Trades", "enchant", stack, stray, 2);
        });
        assertEquals("[]", stack.getClass().getField("enchantments").get(stack).toString());
        assertEquals(1, printed.lines().filter(line -> line.contains("does not have that enchantment")).count(), printed);
    }

    @Test
    @DisplayName("Before any world, the old enchant call leaves the item alone and says no world has loaded")
    void enchantBeforeAWorld() throws Throwable {
        BridgeHarness.Run run = run(false);
        Object sharpness = run.loadClass("net.minecraft.world.item.enchantment.Enchantment").getConstructor(String.class)
                .newInstance("minecraft:sharpness");
        Object stack = run.loadClass("net.minecraft.world.item.ItemStack").getConstructor().newInstance();
        String printed = captureErrAndOut(() -> run.call("mod/Trades", "enchant", stack, sharpness, 1));
        assertEquals("[]", stack.getClass().getField("enchantments").get(stack).toString());
        assertTrue(printed.contains("before a world loaded its enchantments"), printed);
    }

    @Test
    @DisplayName("In a jar written against the old Enchantment class, entries come back as that jar's stand-ins")
    void oldJarSeesItsStandIns() throws Throwable {
        BridgeHarness.Run run = run(true);
        Class<?> standIn = run.loadClass("com.retromod.generated.LegacyEnchantment");
        Object mending = standIn.getConstructor(String.class).newInstance("minecraft:mending");
        assertEquals("minecraft:mending", run.call("mod/Trades", "name", mending),
                "a stand-in names its id before any world loads");

        run.call("test/World", "start", (Object) new String[] {"minecraft:sharpness", "minecraft:mending"});
        Object sharpness = run.call("mod/Trades", "find", "minecraft:sharpness");
        assertEquals("stand-in minecraft:sharpness", String.valueOf(sharpness));
        assertEquals(2, run.call("mod/Trades", "count"), "iteration hands out stand-ins the mod's cast accepts");
        assertEquals(2, run.call("mod/Trades", "split"), "so does a spliterator");
        assertEquals("success stand-in minecraft:mending", run.call("mod/Trades", "decode", "minecraft:mending").toString());
        assertSame(sharpness, run.call("mod/Trades", "find", "minecraft:sharpness"), "one stand-in per id, so == holds");
    }

    @ParameterizedTest(name = "lookup {0}")
    @CsvSource({"method_33310", "method_46759"})
    @DisplayName("On Fabric 1.21.1 (registry, method_33310) and 1.21.11 (lookup, method_46759), the view reads intermediary names")
    void intermediaryHost(String lookupName) throws Throwable {
        BridgeHarness.Run run = runIntermediary(lookupName);
        assertNull(run.call("mod/Trades", "get", "minecraft:sharpness"), "before a world, an empty registry answers");
        run.call("test/World", "start", (Object) new String[] {"minecraft:sharpness"});
        Object sharpness = run.call("mod/Trades", "get", "minecraft:sharpness");
        assertEquals("value minecraft:sharpness", String.valueOf(sharpness));
        assertEquals("minecraft:sharpness", run.call("mod/Trades", "name", sharpness));
        Object stack = run.loadClass("net.minecraft.class_1799").getConstructor().newInstance();
        run.call("mod/Trades", "enchant", stack, sharpness, 2);
        assertEquals("[minecraft:sharpness=2]", stack.getClass().getField("enchantments").get(stack).toString());
    }

    @Test
    @DisplayName("The generated stand-in keeps the name and members the view looks for")
    void standInContract() {
        LegacyEnchantmentBridge.registerSynthetics(transformer);
        ClassNode standIn = new ClassNode();
        new ClassReader(transformer.getSyntheticClasses().get(LegacyEnchantmentBridge.LEGACY)).accept(standIn, 0);
        assertTrue(standIn.methods.stream().anyMatch(m -> m.name.equals("fromKey")
                && m.desc.equals("(Lnet/minecraft/resources/ResourceKey;)L" + LegacyEnchantmentBridge.LEGACY + ";")
                && (m.access & ACC_STATIC) != 0 && (m.access & ACC_PUBLIC) != 0));
        assertTrue(standIn.fields.stream().anyMatch(f -> f.name.equals("id") && (f.access & ACC_PUBLIC) != 0));
        LegacyEnchantmentRegistryShim.register(transformer, true, true);
        String view = new String(transformer.getSyntheticClasses().get(HELPER), StandardCharsets.ISO_8859_1);
        assertTrue(view.contains(LegacyEnchantmentBridge.LEGACY.replace('/', '.')), "the view looks for the stand-in by this name");
    }

    // ---- the recorder ----

    @Test
    @DisplayName("Each loader's reload event hands its registries to the slot every copy of the view reads")
    void recorderFindsEachEventShape() {
        Object access = new Object();
        assertSame(access, ReloadRegistryRecorder.registriesOf(new NeoForge1211Event(access)));
        assertSame(access, ReloadRegistryRecorder.registriesOf(new Forge26Event(access)));
        assertSame(access, ReloadRegistryRecorder.registriesOf(new NeoForge263Event(access)));
        assertNull(ReloadRegistryRecorder.registriesOf(new Object()));

        ReloadRegistryRecorder.record(new NeoForge1211Event(access));
        Object held = ((AtomicReference<?>) System.getProperties().get(LegacyEnchantmentRegistry.RELOAD_SLOT)).get();
        assertSame(access, ((WeakReference<?>) held).get(), "held weakly, so a closed world's registries are released");
    }

    /** NeoForge 1.21 to 26.2 and Forge 1.21.x name the registries {@code getRegistryAccess()}. */
    public static final class NeoForge1211Event {
        private final Object access;

        NeoForge1211Event(Object access) {
            this.access = access;
        }

        public Object getRegistryAccess() {
            return access;
        }
    }

    /** Forge 26.x names them {@code getRegistries()}. */
    public static final class Forge26Event {
        private final Object access;

        Forge26Event(Object access) {
            this.access = access;
        }

        public Object getRegistries() {
            return access;
        }
    }

    /** NeoForge 26.3 keeps them on the server resources: {@code getServerResources().fullRegistries().lookup()}. */
    public static final class NeoForge263Event {
        private final Object access;

        NeoForge263Event(Object access) {
            this.access = access;
        }

        public NeoForge263Event getServerResources() {
            return this;
        }

        public NeoForge263Event fullRegistries() {
            return this;
        }

        public Object lookup() {
            return access;
        }
    }

    // ---- helpers ----

    private void onHost(String loader, String host) {
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = host;
        McReflect.setForceNeoForge(loader.equals("neoforge"));
        McReflect.setForceForge(loader.equals("forge"));
    }

    private void assertUnchanged(String why) {
        byte[] out = transformer.transformClass(oldTrades(BUILT_IN, "ENCHANTMENT", ITEM_STACK, "enchant"), "mod/Trades");
        assertEquals(List.of(BUILT_IN + ".ENCHANTMENT:Lnet/minecraft/core/Registry;"), BridgeHarness.fields(out, "registry"), why);
        assertEquals(List.of(ITEM_STACK + ".enchant" + ENCHANT), BridgeHarness.calls(out, "enchant"), why);
        assertFalse(transformer.getSyntheticClasses().containsKey(HELPER), why);
    }

    /** {@code registry()} reads the field; {@code enchant(stack, enchantment)} calls the old enchant, under the given names. */
    private static byte[] oldTrades(String builtIn, String field, String stack, String enchant) {
        String enchantment = builtIn.startsWith("net/minecraft/class_") ? "net/minecraft/class_1887"
                : "net/minecraft/world/item/enchantment/Enchantment";
        String registry = builtIn.startsWith("net/minecraft/class_") ? "Lnet/minecraft/class_2378;" : "Lnet/minecraft/core/Registry;";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/Trades", null, "java/lang/Object", null);
        MethodVisitor read = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "registry", "()Ljava/lang/Object;", null, null);
        read.visitCode();
        read.visitFieldInsn(GETSTATIC, builtIn, field, registry);
        read.visitInsn(ARETURN);
        read.visitMaxs(0, 0);
        read.visitEnd();
        MethodVisitor apply = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "enchant", "(L" + stack + ";L" + enchantment + ";)V", null, null);
        apply.visitCode();
        apply.visitVarInsn(ALOAD, 0);
        apply.visitVarInsn(ALOAD, 1);
        apply.visitInsn(ICONST_2);
        apply.visitMethodInsn(INVOKEVIRTUAL, stack, enchant, "(L" + enchantment + ";I)V", false);
        apply.visitInsn(RETURN);
        apply.visitMaxs(0, 0);
        apply.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void assertCallsEmbeddedView(Path output, String path) throws Exception {
        try (JarFile jar = new JarFile(output.toFile())) {
            byte[] trades = jar.getInputStream(jar.getJarEntry("mod/Trades.class")).readAllBytes();
            List<String> calls = BridgeHarness.calls(trades, "registry");
            assertEquals(1, calls.size(), path + ": " + calls);
            String owner = calls.get(0).substring(0, calls.get(0).indexOf(".enchantments"));
            assertTrue(owner.startsWith(SyntheticEmbedder.PREFIX) && owner.endsWith(HELPER),
                    path + ": the read calls the jar's own copy of the view: " + owner);
            assertNotNull(jar.getJarEntry(owner + ".class"), path + ": the jar carries its view");
            String root = owner.substring(0, owner.length() - HELPER.length());
            assertNotNull(jar.getJarEntry(root + "com/retromod/shim/common/embedded/LegacyPublicMethods.class"),
                    path + ": the jar carries the method lookup the view uses");
            assertEquals(List.of(owner + ".enchant(Ljava/lang/Object;Ljava/lang/Object;I)V"),
                    BridgeHarness.calls(trades, "enchant"), path + ": the old enchant goes through the same copy");
        }
    }

    /** {@code registry()} reads one registry field, as a Forge mod's static initializer does. */
    private static byte[] forgeRegistryRead(String owner, String field) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/Trades", null, "java/lang/Object", null);
        MethodVisitor read = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "registry", "()Ljava/lang/Object;", null, null);
        read.visitCode();
        read.visitFieldInsn(GETSTATIC, owner, field, "Lnet/minecraftforge/registries/IForgeRegistry;");
        read.visitInsn(ARETURN);
        read.visitMaxs(0, 0);
        read.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode node(String name, Object member) {
        ClassNode node = new ClassNode();
        node.name = name;
        if (member instanceof FieldNode field) node.fields.add(field);
        if (member instanceof MethodNode method) node.methods.add(method);
        return node;
    }

    private static byte[] forgeToml() {
        return ("modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"trades\"\n"
                + "version=\"1.0\"\n[[dependencies.trades]]\nmodId=\"minecraft\"\nmandatory=true\n"
                + "versionRange=\"[1.20.1,1.20.2)\"\nordering=\"NONE\"\nside=\"BOTH\"\n").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] fabricJson() {
        return ("{\"schemaVersion\":1,\"id\":\"trades\",\"version\":\"1.0\",\"depends\":{\"minecraft\":\"1.20.1\","
                + "\"fabricloader\":\">=0.14\"}}").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] jar(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : new LinkedHashMap<>(entries).entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private interface Action {
        void run() throws Throwable;
    }

    // LegacyLog writes through the game's logger when there is one, else to standard error.
    private static String captureErrAndOut(Action action) throws Throwable {
        java.io.PrintStream err = System.err;
        java.io.PrintStream out = System.out;
        ByteArrayOutputStream printed = new ByteArrayOutputStream();
        java.io.PrintStream capture = new java.io.PrintStream(printed, true, StandardCharsets.UTF_8);
        System.setErr(capture);
        System.setOut(capture);
        try {
            action.run();
        } finally {
            System.setErr(err);
            System.setOut(out);
        }
        return printed.toString(StandardCharsets.UTF_8);
    }

    // ---- the run-time world: an old mod compiled against 1.20.1 shapes, run against 1.21.1 shapes ----

    /** The mod, compiled against the old host, transformed for NeoForge 1.21.1, loaded beside the new host's stand-ins. */
    private BridgeHarness.Run run(boolean oldEnchantmentJar) throws Exception {
        return run(oldEnchantmentJar, Map.of());
    }

    /** The same, with {@code extraHost} sources beside the new host's, such as a client. */
    private BridgeHarness.Run run(boolean oldEnchantmentJar, Map<String, String> extraHost) throws Exception {
        Map<String, String> oldSources = new LinkedHashMap<>(SHARED_SOURCES);
        oldSources.putAll(OLD_HOST_SOURCES);
        oldSources.put("mod/Trades.java", MOD_SOURCE);
        Map<String, byte[]> mod = BridgeHarness.only(BridgeHarness.compile(tempDir.resolve("old"), oldSources), "mod/");
        if (oldEnchantmentJar) mod.replaceAll((name, bytes) -> LegacyEnchantmentBridge.rename(bytes));

        Map<String, String> newSources = new LinkedHashMap<>(SHARED_SOURCES);
        newSources.putAll(NEW_HOST_SOURCES);
        newSources.putAll(extraHost);
        if (oldEnchantmentJar) newSources.put("com/retromod/generated/LegacyEnchantment.java", STAND_IN_SOURCE);
        Map<String, byte[]> host = BridgeHarness.compile(tempDir.resolve("new"), newSources);

        McReflect.setForceNeoForge(true);
        Map<String, byte[]> transformed = BridgeHarness.transform("1.21.1",
                t -> LegacyEnchantmentRegistryShim.register(t, true, true), mod);
        return new BridgeHarness.Run(host, transformed);
    }

    /** An old Fabric mod in intermediary names, run on a Fabric 1.21.x host whose registry lookup is {@code lookupName}. */
    private BridgeHarness.Run runIntermediary(String lookupName) throws Exception {
        Map<String, String> oldSources = new LinkedHashMap<>(INTERMEDIARY_OLD_HOST);
        oldSources.put("mod/Trades.java", INTERMEDIARY_MOD);
        Map<String, byte[]> mod = BridgeHarness.only(BridgeHarness.compile(tempDir.resolve("old"), oldSources), "mod/");
        Map<String, String> newSources = new LinkedHashMap<>(INTERMEDIARY_NEW_HOST);
        newSources.put("net/minecraft/class_5455.java", INTERMEDIARY_ACCESS.formatted(lookupName));
        newSources.put("net/minecraft/class_2960.java", INTERMEDIARY_OLD_HOST.get("net/minecraft/class_2960.java"));
        Map<String, byte[]> host = BridgeHarness.compile(tempDir.resolve("new"), newSources);
        Map<String, byte[]> transformed = BridgeHarness.transform("1.21.1",
                t -> LegacyEnchantmentRegistryShim.register(t, LegacyEnchantmentRegistryShim.INTERMEDIARY, true, true), mod);
        return new BridgeHarness.Run(host, transformed);
    }

    private static final String MOD_SOURCE = """
            package mod;
            import com.mojang.serialization.Codec;
            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.enchantment.Enchantment;
            public final class Trades {
                // Built in the static initializer, before any world, as Corgilib and Exposure build theirs.
                static final Codec<Enchantment> CODEC = BuiltInRegistries.ENCHANTMENT.byNameCodec();
                public static Object registry() { return BuiltInRegistries.ENCHANTMENT; }
                public static Object key() { return BuiltInRegistries.ENCHANTMENT.key(); }
                public static Enchantment find(String id) {
                    return BuiltInRegistries.ENCHANTMENT.getOptional(ResourceLocation.tryParse(id)).orElse(null);
                }
                public static Enchantment get(String id) { return BuiltInRegistries.ENCHANTMENT.get(ResourceLocation.tryParse(id)); }
                public static String name(Enchantment enchantment) {
                    return String.valueOf(BuiltInRegistries.ENCHANTMENT.getKey(enchantment));
                }
                public static int count() {
                    int count = 0;
                    for (Enchantment enchantment : BuiltInRegistries.ENCHANTMENT) if (enchantment != null) count++;
                    return count;
                }
                public static void enchant(ItemStack stack, Enchantment enchantment, int level) { stack.enchant(enchantment, level); }
                public static Object decode(String id) { return CODEC.decode(null, id).map(pair -> pair.getFirst()); }
                public static int split() {
                    int[] count = {0};
                    BuiltInRegistries.ENCHANTMENT.spliterator().forEachRemaining(enchantment -> {
                        Enchantment seen = enchantment;
                        if (seen != null) count[0]++;
                    });
                    return count[0];
                }
            }
            """;

    private static final Map<String, String> SHARED_SOURCES = Map.of(
            "net/minecraft/resources/ResourceLocation.java", """
                    package net.minecraft.resources;
                    public final class ResourceLocation {
                        private final String id;
                        private ResourceLocation(String id) { this.id = id; }
                        public static ResourceLocation tryParse(String id) { return id == null || id.indexOf(':') < 0 ? null : new ResourceLocation(id); }
                        @Override public boolean equals(Object other) { return other instanceof ResourceLocation r && r.id.equals(id); }
                        @Override public int hashCode() { return id.hashCode(); }
                        @Override public String toString() { return id; }
                    }""",
            "com/mojang/serialization/DynamicOps.java", """
                    package com.mojang.serialization;
                    public interface DynamicOps<T> {}""",
            "com/mojang/serialization/DataResult.java", """
                    package com.mojang.serialization;
                    public interface DataResult<R> {
                        static <R> DataResult<R> success(R value) { return new Result<>(value, null); }
                        static <R> DataResult<R> error(String message) { return new Result<>(null, message); }
                        <U> DataResult<U> map(java.util.function.Function<? super R, ? extends U> function);
                        record Result<R>(R value, String error) implements DataResult<R> {
                            public <U> DataResult<U> map(java.util.function.Function<? super R, ? extends U> function) {
                                return error != null ? DataResult.error(error) : DataResult.success(function.apply(value));
                            }
                            @Override public String toString() { return error != null ? "error " + error : "success " + value; }
                        }
                    }""",
            "com/mojang/datafixers/util/Pair.java", """
                    package com.mojang.datafixers.util;
                    public final class Pair<F, S> {
                        private final F first; private final S second;
                        public Pair(F first, S second) { this.first = first; this.second = second; }
                        public F getFirst() { return first; }
                        public <F2> Pair<F2, S> mapFirst(java.util.function.Function<? super F, ? extends F2> function) {
                            return new Pair<>(function.apply(first), second);
                        }
                    }""",
            "com/mojang/serialization/Codec.java", """
                    package com.mojang.serialization;
                    import com.mojang.datafixers.util.Pair;
                    public interface Codec<A> {
                        <T> DataResult<T> encode(A input, DynamicOps<T> ops, T prefix);
                        <T> DataResult<Pair<A, T>> decode(DynamicOps<T> ops, T input);
                        default <T> DataResult<A> parse(DynamicOps<T> ops, T input) { return decode(ops, input).map(Pair::getFirst); }
                    }""");

    private static final Map<String, String> OLD_HOST_SOURCES = Map.of(
            "net/minecraft/core/Registry.java", """
                    package net.minecraft.core;
                    public interface Registry<T> extends Iterable<T> {
                        net.minecraft.resources.ResourceKey<? extends Registry<T>> key();
                        T get(net.minecraft.resources.ResourceLocation id);
                        net.minecraft.resources.ResourceLocation getKey(T value);
                        java.util.Optional<T> getOptional(net.minecraft.resources.ResourceLocation id);
                        com.mojang.serialization.Codec<T> byNameCodec();
                    }""",
            "net/minecraft/core/registries/BuiltInRegistries.java", """
                    package net.minecraft.core.registries;
                    public final class BuiltInRegistries {
                        public static final net.minecraft.core.Registry<net.minecraft.world.item.enchantment.Enchantment> ENCHANTMENT = null;
                    }""",
            "net/minecraft/world/item/enchantment/Enchantment.java", """
                    package net.minecraft.world.item.enchantment;
                    public class Enchantment {}""",
            "net/minecraft/resources/ResourceKey.java", """
                    package net.minecraft.resources;
                    public final class ResourceKey<T> {}""",
            "net/minecraft/world/item/ItemStack.java", """
                    package net.minecraft.world.item;
                    public class ItemStack { public void enchant(net.minecraft.world.item.enchantment.Enchantment enchantment, int level) {} }""");

    private static final Map<String, String> NEW_HOST_SOURCES = Map.ofEntries(
            Map.entry("net/minecraft/resources/ResourceKey.java", """
                    package net.minecraft.resources;
                    public final class ResourceKey<T> {
                        private final ResourceLocation registry; private final ResourceLocation location;
                        private ResourceKey(ResourceLocation registry, ResourceLocation location) { this.registry = registry; this.location = location; }
                        public static <T> ResourceKey<T> create(ResourceKey<? extends net.minecraft.core.Registry<T>> registry, ResourceLocation location) {
                            return new ResourceKey<>(registry.location, location);
                        }
                        public static <T> ResourceKey<net.minecraft.core.Registry<T>> createRegistryKey(ResourceLocation location) {
                            return new ResourceKey<>(ResourceLocation.tryParse("minecraft:root"), location);
                        }
                        public ResourceLocation location() { return location; }
                        @Override public boolean equals(Object other) {
                            return other instanceof ResourceKey<?> key && key.registry.equals(registry) && key.location.equals(location);
                        }
                        @Override public int hashCode() { return registry.hashCode() * 31 + location.hashCode(); }
                        @Override public String toString() { return "ResourceKey[" + registry + " / " + location + "]"; }
                    }"""),
            Map.entry("com/mojang/serialization/Lifecycle.java", """
                    package com.mojang.serialization;
                    public final class Lifecycle { public static Lifecycle stable() { return new Lifecycle(); } }"""),
            Map.entry("net/minecraft/core/registries/Registries.java", """
                    package net.minecraft.core.registries;
                    import net.minecraft.resources.*;
                    public final class Registries {
                        public static final ResourceKey<net.minecraft.core.Registry<net.minecraft.world.item.enchantment.Enchantment>> ENCHANTMENT =
                                ResourceKey.createRegistryKey(ResourceLocation.tryParse("minecraft:enchantment"));
                    }"""),
            Map.entry("net/minecraft/core/registries/BuiltInRegistries.java", """
                    package net.minecraft.core.registries;
                    public final class BuiltInRegistries {}"""),
            Map.entry("net/minecraft/world/item/enchantment/Enchantment.java", """
                    package net.minecraft.world.item.enchantment;
                    public final class Enchantment {
                        private final String name;
                        public Enchantment(String name) { this.name = name; }
                        @Override public String toString() { return "value " + name; }
                    }"""),
            Map.entry("net/minecraft/core/Holder.java", """
                    package net.minecraft.core;
                    import java.util.Optional;
                    import net.minecraft.resources.ResourceKey;
                    public interface Holder<T> {
                        T value();
                        Optional<ResourceKey<T>> unwrapKey();
                        record Reference<T>(ResourceKey<T> key, T value) implements Holder<T> {
                            public Optional<ResourceKey<T>> unwrapKey() { return Optional.of(key); }
                        }
                        record Direct<T>(T value) implements Holder<T> {
                            public Optional<ResourceKey<T>> unwrapKey() { return Optional.empty(); }
                        }
                    }"""),
            Map.entry("net/minecraft/core/Registry.java", """
                    package net.minecraft.core;
                    import java.util.*;
                    import net.minecraft.resources.*;
                    public interface Registry<T> extends Iterable<T> {
                        ResourceKey<? extends Registry<T>> key();
                        T get(ResourceLocation id);
                        T get(ResourceKey<T> key);
                        ResourceLocation getKey(T value);
                        Optional<ResourceKey<T>> getResourceKey(T value);
                        default Optional<T> getOptional(ResourceLocation id) { return Optional.ofNullable(get(id)); }
                        default Optional<T> getOptional(ResourceKey<T> key) { return Optional.ofNullable(get(key)); }
                        Holder<T> wrapAsHolder(T value);
                        Set<Map.Entry<ResourceKey<T>, T>> entrySet();
                        Registry<T> freeze();
                        default com.mojang.serialization.Codec<T> byNameCodec() { return new RegistryCodec<>(this); }
                    }"""),
            Map.entry("net/minecraft/core/RegistryCodec.java", """
                    package net.minecraft.core;
                    import com.mojang.datafixers.util.Pair;
                    import com.mojang.serialization.*;
                    import net.minecraft.resources.ResourceLocation;
                    final class RegistryCodec<T> implements Codec<T> {
                        private final Registry<T> registry;
                        RegistryCodec(Registry<T> registry) { this.registry = registry; }
                        @SuppressWarnings("unchecked")
                        public <O> DataResult<O> encode(T input, DynamicOps<O> ops, O prefix) {
                            ResourceLocation id = registry.getKey(input);
                            return id == null ? DataResult.error("Unknown registry element " + input) : DataResult.success((O) id.toString());
                        }
                        public <O> DataResult<Pair<T, O>> decode(DynamicOps<O> ops, O input) {
                            T value = registry.get(ResourceLocation.tryParse(String.valueOf(input)));
                            return value == null ? DataResult.error("Unknown registry key " + input) : DataResult.success(new Pair<>(value, input));
                        }
                    }"""),
            Map.entry("net/minecraft/core/MappedRegistry.java", """
                    package net.minecraft.core;
                    import java.util.*;
                    import net.minecraft.resources.*;
                    public class MappedRegistry<T> implements Registry<T> {
                        private final ResourceKey<? extends Registry<T>> key;
                        private final Map<ResourceLocation, T> byId = new LinkedHashMap<>();
                        private final Map<T, ResourceKey<T>> keys = new IdentityHashMap<>();
                        private boolean frozen;
                        public MappedRegistry(ResourceKey<? extends Registry<T>> key, com.mojang.serialization.Lifecycle lifecycle) { this.key = key; }
                        public void register(ResourceKey<T> entry, T value) {
                            if (frozen) throw new IllegalStateException("Registry is already frozen");
                            byId.put(entry.location(), value);
                            keys.put(value, entry);
                        }
                        public ResourceKey<? extends Registry<T>> key() { return key; }
                        public T get(ResourceLocation id) { return byId.get(id); }
                        public T get(ResourceKey<T> entry) { return entry == null ? null : byId.get(entry.location()); }
                        public ResourceLocation getKey(T value) { ResourceKey<T> entry = keys.get(value); return entry == null ? null : entry.location(); }
                        public Optional<ResourceKey<T>> getResourceKey(T value) { return Optional.ofNullable(keys.get(value)); }
                        public Holder<T> wrapAsHolder(T value) {
                            ResourceKey<T> entry = keys.get(value);
                            return entry == null ? new Holder.Direct<>(value) : new Holder.Reference<>(entry, value);
                        }
                        public Set<Map.Entry<ResourceKey<T>, T>> entrySet() {
                            Set<Map.Entry<ResourceKey<T>, T>> entries = new LinkedHashSet<>();
                            keys.forEach((value, entry) -> entries.add(Map.entry(entry, value)));
                            return entries;
                        }
                        public Iterator<T> iterator() { return byId.values().iterator(); }
                        public Registry<T> freeze() { frozen = true; return this; }
                    }"""),
            Map.entry("net/minecraft/core/RegistryAccess.java", """
                    package net.minecraft.core;
                    public interface RegistryAccess {
                        <E> java.util.Optional<Registry<E>> registry(net.minecraft.resources.ResourceKey<? extends Registry<? extends E>> key);
                    }"""),
            Map.entry("net/minecraft/world/item/ItemStack.java", """
                    package net.minecraft.world.item;
                    public class ItemStack {
                        public final java.util.List<String> enchantments = new java.util.ArrayList<>();
                        public void enchant(net.minecraft.core.Holder<net.minecraft.world.item.enchantment.Enchantment> holder, int level) {
                            enchantments.add(holder.unwrapKey().orElseThrow().location() + "=" + level);
                        }
                    }"""),
            Map.entry("net/minecraft/server/MinecraftServer.java", """
                    package net.minecraft.server;
                    public class MinecraftServer {
                        private final net.minecraft.core.RegistryAccess access;
                        public MinecraftServer(net.minecraft.core.RegistryAccess access) { this.access = access; }
                        public net.minecraft.core.RegistryAccess registryAccess() { return access; }
                    }"""),
            Map.entry("net/neoforged/neoforge/server/ServerLifecycleHooks.java", """
                    package net.neoforged.neoforge.server;
                    public final class ServerLifecycleHooks {
                        public static net.minecraft.server.MinecraftServer current;
                        public static net.minecraft.server.MinecraftServer getCurrentServer() { return current; }
                    }"""),
            Map.entry("test/World.java", """
                    package test;
                    import java.util.Optional;
                    import net.minecraft.core.*;
                    import net.minecraft.core.registries.Registries;
                    import net.minecraft.resources.*;
                    import net.minecraft.world.item.enchantment.Enchantment;
                    public final class World {
                        public static RegistryAccess access(String[] ids) {
                            MappedRegistry<Enchantment> registry = new MappedRegistry<>(Registries.ENCHANTMENT, com.mojang.serialization.Lifecycle.stable());
                            for (String id : ids) registry.register(ResourceKey.create(Registries.ENCHANTMENT, ResourceLocation.tryParse(id)), new Enchantment(id));
                            registry.freeze();
                            return new RegistryAccess() {
                                @SuppressWarnings("unchecked")
                                public <E> Optional<Registry<E>> registry(ResourceKey<? extends Registry<? extends E>> key) {
                                    return key.equals(Registries.ENCHANTMENT) ? Optional.of((Registry<E>) (Object) registry) : Optional.empty();
                                }
                            };
                        }
                        public static void start(String[] ids) { net.neoforged.neoforge.server.ServerLifecycleHooks.current = new net.minecraft.server.MinecraftServer(access(ids)); }
                        public static void stop() { net.neoforged.neoforge.server.ServerLifecycleHooks.current = null; }
                    }"""));

    // A client: NeoForge names its side, and Minecraft knows its thread, its own server and its connection.
    private static final Map<String, String> CLIENT_SOURCES = Map.of(
            "net/neoforged/fml/loading/FMLEnvironment.java", """
                    package net.neoforged.fml.loading;
                    public final class FMLEnvironment { public static final Object dist = "CLIENT"; }""",
            "net/minecraft/client/multiplayer/ClientPacketListener.java", """
                    package net.minecraft.client.multiplayer;
                    public class ClientPacketListener {
                        private final net.minecraft.core.RegistryAccess access;
                        public ClientPacketListener(net.minecraft.core.RegistryAccess access) { this.access = access; }
                        public net.minecraft.core.RegistryAccess registryAccess() { return access; }
                    }""",
            "net/minecraft/client/Minecraft.java", """
                    package net.minecraft.client;
                    public class Minecraft {
                        public static Minecraft instance;
                        public boolean sameThread;
                        public net.minecraft.server.MinecraftServer server;
                        public net.minecraft.client.multiplayer.ClientPacketListener connection;
                        public static Minecraft getInstance() { return instance; }
                        public net.minecraft.server.MinecraftServer getSingleplayerServer() { return server; }
                        public net.minecraft.client.multiplayer.ClientPacketListener getConnection() { return connection; }
                        public boolean isSameThread() { return sameThread; }
                    }""",
            "test/Client.java", """
                    package test;
                    import net.minecraft.client.Minecraft;
                    public final class Client {
                        public static void singleplayer(String[] clientIds, String[] serverIds) {
                            net.minecraft.server.MinecraftServer server = new net.minecraft.server.MinecraftServer(World.access(serverIds));
                            net.neoforged.neoforge.server.ServerLifecycleHooks.current = server;
                            Minecraft minecraft = new Minecraft();
                            minecraft.server = server;
                            minecraft.connection = new net.minecraft.client.multiplayer.ClientPacketListener(World.access(clientIds));
                            Minecraft.instance = minecraft;
                        }
                        public static void onClientThread(boolean client) { Minecraft.instance.sameThread = client; }
                    }""");

    private static final String INTERMEDIARY_MOD = """
            package mod;
            import net.minecraft.*;
            public final class Trades {
                public static Object registry() { return class_7923.field_41176; }
                public static class_1887 get(String id) { return class_7923.field_41176.method_10223(class_2960.method_12829(id)); }
                public static String name(class_1887 enchantment) { return String.valueOf(class_7923.field_41176.method_10221(enchantment)); }
                public static void enchant(class_1799 stack, class_1887 enchantment, int level) { stack.method_7978(enchantment, level); }
            }
            """;

    // Fabric 1.20.1 in intermediary names: BuiltInRegistries class_7923 with field_41176, ItemStack class_1799.
    private static final Map<String, String> INTERMEDIARY_OLD_HOST = Map.of(
            "net/minecraft/class_2960.java", """
                    package net.minecraft;
                    public final class class_2960 {
                        private final String id;
                        private class_2960(String id) { this.id = id; }
                        public static class_2960 method_12829(String id) { return id == null || id.indexOf(':') < 0 ? null : new class_2960(id); }
                        @Override public boolean equals(Object other) { return other instanceof class_2960 o && o.id.equals(id); }
                        @Override public int hashCode() { return id.hashCode(); }
                        @Override public String toString() { return id; }
                    }""",
            "net/minecraft/class_2378.java", """
                    package net.minecraft;
                    public interface class_2378<T> extends Iterable<T> {
                        T method_10223(class_2960 id);
                        class_2960 method_10221(T value);
                    }""",
            "net/minecraft/class_7923.java", """
                    package net.minecraft;
                    public final class class_7923 { public static final class_2378<class_1887> field_41176 = null; }""",
            "net/minecraft/class_1887.java", """
                    package net.minecraft;
                    public class class_1887 {}""",
            "net/minecraft/class_1799.java", """
                    package net.minecraft;
                    public class class_1799 { public void method_7978(class_1887 enchantment, int level) {} }""");

    // RegistryAccess: registry(key) is method_33310 on 1.21.1, lookup(key) is method_46759 from 1.21.2.
    private static final String INTERMEDIARY_ACCESS = """
            package net.minecraft;
            public interface class_5455 {
                <E> java.util.Optional<class_2378<E>> %s(class_5321<? extends class_2378<? extends E>> key);
            }""";

    // Fabric 1.21.x in intermediary names, with the members the view calls checked against the 1.21.1 and 1.21.11 mappings.
    private static final Map<String, String> INTERMEDIARY_NEW_HOST = Map.ofEntries(
            Map.entry("com/mojang/serialization/Lifecycle.java", """
                    package com.mojang.serialization;
                    public final class Lifecycle { public static Lifecycle stable() { return new Lifecycle(); } }"""),
            Map.entry("net/minecraft/class_5321.java", """
                    package net.minecraft;
                    public final class class_5321<T> {
                        private final class_2960 registry; private final class_2960 location;
                        private class_5321(class_2960 registry, class_2960 location) { this.registry = registry; this.location = location; }
                        public static <T> class_5321<T> method_29179(class_5321<? extends class_2378<T>> registry, class_2960 location) {
                            return new class_5321<>(registry.location, location);
                        }
                        public static <T> class_5321<class_2378<T>> registryKey(class_2960 location) {
                            return new class_5321<>(class_2960.method_12829("minecraft:root"), location);
                        }
                        public class_2960 location() { return location; }
                        @Override public boolean equals(Object other) {
                            return other instanceof class_5321<?> key && key.registry.equals(registry) && key.location.equals(location);
                        }
                        @Override public int hashCode() { return registry.hashCode() * 31 + location.hashCode(); }
                    }"""),
            Map.entry("net/minecraft/class_7924.java", """
                    package net.minecraft;
                    public final class class_7924 {
                        public static final class_5321<class_2378<class_1887>> field_41265 =
                                class_5321.registryKey(class_2960.method_12829("minecraft:enchantment"));
                    }"""),
            Map.entry("net/minecraft/class_1887.java", """
                    package net.minecraft;
                    public final class class_1887 {
                        private final String name;
                        public class_1887(String name) { this.name = name; }
                        @Override public String toString() { return "value " + name; }
                    }"""),
            Map.entry("net/minecraft/class_6880.java", """
                    package net.minecraft;
                    public interface class_6880<T> {
                        java.util.Optional<class_5321<T>> method_40230();
                        record Reference<T>(class_5321<T> key) implements class_6880<T> {
                            public java.util.Optional<class_5321<T>> method_40230() { return java.util.Optional.of(key); }
                        }
                        record Direct<T>(T value) implements class_6880<T> {
                            public java.util.Optional<class_5321<T>> method_40230() { return java.util.Optional.empty(); }
                        }
                    }"""),
            Map.entry("net/minecraft/class_2378.java", """
                    package net.minecraft;
                    public interface class_2378<T> extends Iterable<T> {
                        T method_10223(class_2960 id);
                        class_2960 method_10221(T value);
                        class_6880<T> method_47983(T value);
                        class_2378<T> method_40276();
                    }"""),
            Map.entry("net/minecraft/class_2370.java", """
                    package net.minecraft;
                    import java.util.*;
                    public class class_2370<T> implements class_2378<T> {
                        private final Map<class_2960, T> byId = new LinkedHashMap<>();
                        private final Map<T, class_5321<T>> keys = new IdentityHashMap<>();
                        public class_2370(class_5321<? extends class_2378<T>> key, com.mojang.serialization.Lifecycle lifecycle) {}
                        public void register(class_5321<T> key, T value) { byId.put(key.location(), value); keys.put(value, key); }
                        public T method_10223(class_2960 id) { return byId.get(id); }
                        public class_2960 method_10221(T value) { class_5321<T> key = keys.get(value); return key == null ? null : key.location(); }
                        public class_6880<T> method_47983(T value) {
                            class_5321<T> key = keys.get(value);
                            return key == null ? new class_6880.Direct<>(value) : new class_6880.Reference<>(key);
                        }
                        public class_2378<T> method_40276() { return this; }
                        public Iterator<T> iterator() { return byId.values().iterator(); }
                    }"""),
            Map.entry("net/minecraft/class_1799.java", """
                    package net.minecraft;
                    public class class_1799 {
                        public final java.util.List<String> enchantments = new java.util.ArrayList<>();
                        public void method_7978(class_6880<class_1887> holder, int level) {
                            enchantments.add(holder.method_40230().orElseThrow().location() + "=" + level);
                        }
                    }"""),
            Map.entry("net/minecraft/server/MinecraftServer.java", """
                    package net.minecraft.server;
                    public class MinecraftServer {
                        private final net.minecraft.class_5455 access;
                        public MinecraftServer(net.minecraft.class_5455 access) { this.access = access; }
                        public net.minecraft.class_5455 method_30611() { return access; }
                    }"""),
            Map.entry("net/fabricmc/loader/api/FabricLoader.java", """
                    package net.fabricmc.loader.api;
                    public final class FabricLoader {
                        public static Object game;
                        private static final FabricLoader INSTANCE = new FabricLoader();
                        public static FabricLoader getInstance() { return INSTANCE; }
                        public Object getGameInstance() { return game; }
                        public Object getEnvironmentType() { return "SERVER"; }
                    }"""),
            Map.entry("test/World.java", """
                    package test;
                    import java.lang.reflect.Method;
                    import java.util.Optional;
                    import net.minecraft.*;
                    public final class World {
                        public static void start(String[] ids) throws Exception {
                            class_2370<class_1887> registry = new class_2370<>(class_7924.field_41265, com.mojang.serialization.Lifecycle.stable());
                            for (String id : ids) registry.register(class_5321.method_29179(class_7924.field_41265, class_2960.method_12829(id)), new class_1887(id));
                            Method lookup = class_5455.class.getMethods()[0];
                            Object access = java.lang.reflect.Proxy.newProxyInstance(class_5455.class.getClassLoader(), new Class<?>[] {class_5455.class},
                                    (proxy, method, args) -> method.equals(lookup) && class_7924.field_41265.equals(args[0])
                                            ? Optional.of(registry) : Optional.empty());
                            net.fabricmc.loader.api.FabricLoader.game = new net.minecraft.server.MinecraftServer((class_5455) access);
                        }
                    }"""));

    // Stands in for the class LegacyEnchantmentBridge generates, with the two members the view uses.
    private static final String STAND_IN_SOURCE = """
            package com.retromod.generated;
            public class LegacyEnchantment {
                private static final java.util.Map<String, LegacyEnchantment> VIEWS = new java.util.HashMap<>();
                public String id;
                public LegacyEnchantment(String id) { this.id = id; }
                public static LegacyEnchantment fromKey(net.minecraft.resources.ResourceKey<?> key) {
                    return VIEWS.computeIfAbsent(key.location().toString(), LegacyEnchantment::new);
                }
                @Override public String toString() { return "stand-in " + id; }
            }""";
}
