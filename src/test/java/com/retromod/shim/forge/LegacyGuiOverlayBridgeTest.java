/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Old mods' HUD overlay API, which Forge 26.2 removed, is rebuilt inside each mod on Forge 26.2 and 26.3 only. */
class LegacyGuiOverlayBridgeTest {

    static final String OLD_OVERLAY = "net/minecraftforge/client/gui/overlay/";
    static final String OLD_EVENT = "net/minecraftforge/client/event/";
    static final String ROOT = LegacyGuiOverlayBridge.ROOT;
    static final String FORGE_GUI = OLD_OVERLAY + "ForgeGui";
    static final String I_GUI_OVERLAY = OLD_OVERLAY + "IGuiOverlay";
    static final String NAMED = OLD_OVERLAY + "NamedGuiOverlay";
    static final String VANILLA = OLD_OVERLAY + "VanillaGuiOverlay";
    static final String PRE = OLD_EVENT + "RenderGuiOverlayEvent$Pre";
    static final String REGISTER = OLD_EVENT + "RegisterGuiOverlaysEvent";
    static final String DEBUG_TEXT = OLD_EVENT + "CustomizeGuiOverlayEvent$DebugText";
    static final String LAYER = LegacyGuiOverlayBridge.LAYER;
    static final String FORGE_LAYER = LegacyGuiOverlayBridge.FORGE_LAYER;
    static final String LAYERED_DRAW = OLD_OVERLAY + "ForgeLayeredDraw";
    static final String OLD_SUBSCRIBE = "Lnet/minecraftforge/eventbus/api/SubscribeEvent;";
    static final String LEGACY_SUBSCRIBE = "L" + LegacyEventBaseBridge.LEGACY_SUBSCRIBE + ";";
    static final String RESOURCE_LOCATION = "net/minecraft/resources/ResourceLocation";
    static final String GUI_GRAPHICS = "net/minecraft/client/gui/GuiGraphics";
    static final String DELTA = "net/minecraft/client/DeltaTracker";
    static final String MINECRAFT = "net/minecraft/client/Minecraft";
    private static final Path LEGACY_CLASSES = Path.of("target", "legacyapi-classes");

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @TempDir
    Path dir;

    @BeforeEach
    void forge262() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    // ---- the old overlay API on Forge 26.2 and 26.3 ----

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("An old overlay class names only the rebuilt API, casts Minecraft.gui through ForgeGui's helpers, and still verifies")
    void redirectsOldOverlayApi(String host) throws Exception {
        registerHost(host);
        byte[] transformed = transformer.transformClass(overlaysClass("mod/Overlays"), "mod/Overlays.class");
        ClassNode node = node(transformed);

        String text = new String(transformed, StandardCharsets.ISO_8859_1);
        assertFalse(text.contains(OLD_OVERLAY + "ForgeGui") || text.contains(OLD_OVERLAY + "IGuiOverlay")
                || text.contains(OLD_OVERLAY + "VanillaGuiOverlay") || text.contains(OLD_OVERLAY + "NamedGuiOverlay")
                || text.contains(OLD_EVENT + "RenderGuiOverlayEvent") || text.contains(REGISTER),
                "no removed Forge overlay name is left");
        assertEquals(List.of(ROOT + "gui/overlay/IGuiOverlay"), node.interfaces);
        assertTrue(method(node, "render").desc.startsWith("(L" + ROOT + "gui/overlay/ForgeGui;"), method(node, "render").desc);

        // The listener goes to Retromod's dispatch, which keeps the old cancel, receiveCanceled and priority rules.
        MethodNode onPre = method(node, "onPre");
        assertEquals("(L" + ROOT + "event/RenderGuiOverlayEvent$Pre;)V", onPre.desc);
        assertEquals(List.of(LEGACY_SUBSCRIBE), annotations(onPre));
        assertEquals(List.of(ROOT + "event/RenderGuiOverlayEvent$Pre.getOverlay", ROOT + "gui/overlay/VanillaGuiOverlay.type",
                ROOT + "event/RenderGuiOverlayEvent$Pre.setCanceled"), calls(onPre));

        MethodNode register = method(node, "register");
        assertEquals(List.of(ROOT + "gui/overlay/VanillaGuiOverlay.id", "mod/Overlays.<init>",
                ROOT + "event/RegisterGuiOverlaysEvent.registerAbove"), calls(register));
        assertEquals("(Lnet/minecraft/resources/Identifier;Ljava/lang/String;L" + ROOT + "gui/overlay/IGuiOverlay;)V",
                callNamed(register, "registerAbove").desc);

        // ((ForgeGui) Minecraft.getInstance().gui).leftHeight, guarded by instanceof, as Zeta writes it.
        MethodNode heights = method(node, "leftHeight");
        assertEquals(List.of(), typeInsns(heights), "no exact cast or check of the rebuilt ForgeGui is left");
        assertTrue(calls(heights).containsAll(List.of(ROOT + "gui/overlay/ForgeGui.isInstance", ROOT + "gui/overlay/ForgeGui.cast")),
                calls(heights).toString());
        assertTrue(fields(heights).contains(ROOT + "gui/overlay/ForgeGui.leftHeight"), fields(heights).toString());
        verify(transformed);
    }

    @Test
    @DisplayName("Forge 1.21.11, a Forge host that still has the overlay registry, NeoForge, Fabric and Quilt register nothing")
    void noRegistrationOnOlderOrNeoForgeHosts() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        assertNothingRegistered("Forge 1.21.11 keeps its own overlay API", t -> new Forge_26_1_to_26_2().registerRedirects(t));

        RetromodVersion.TARGET_MC_VERSION = "26.2";
        java.util.function.Function<String, ClassNode> stillHasIt = name -> name.equals(ForgeHostShape.ANCHOR)
                || name.equals(FORGE_LAYER) || name.equals(LegacyGuiOverlayBridge.OLD_REGISTER) ? new ClassNode() : null;
        assertNothingRegistered("a host with RegisterGuiOverlaysEvent keeps it", t -> LegacyGuiOverlayBridge.register(t, stillHasIt));

        McReflect.setForceNeoForge(true);
        assertNothingRegistered("NeoForge maps the overlay events onto its own layers", LegacyGuiOverlayBridge::register);

        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(false);
        assertNothingRegistered("Fabric and Quilt are not Forge targets", LegacyGuiOverlayBridge::register);
    }

    @Test
    @DisplayName("DebugText becomes the 1.20.1 shape for a Forge 1.20.1 mod, and stays Forge 26.2's record for a 1.21.1 mod")
    void debugTextRedirectIsLimited() {
        registerHost("26.2");
        transformer.setModSourceVersion("1.20.1");
        MethodNode old = method(node(transformer.transformClass(debugTextClass("mod/OldDebug", "getLeft",
                "()Ljava/util/ArrayList;"), "mod/OldDebug.class")), "onDebug");
        assertEquals("(L" + ROOT + "event/LegacyDebugText;)V", old.desc);
        assertEquals(List.of(ROOT + "event/LegacyDebugText.getLeft"), calls(old));

        transformer.setModSourceVersion("1.21.1");
        MethodNode newer = method(node(transformer.transformClass(debugTextClass("mod/NewDebug", "getText",
                "()Ljava/util/List;"), "mod/NewDebug.class")), "onDebug");
        assertEquals("(L" + DEBUG_TEXT + ";)V", newer.desc, "a Forge 1.21 mod keeps the host record");
        assertEquals(List.of(DEBUG_TEXT + ".getText"), calls(newer));
    }

    @Test
    @DisplayName("Old render-event handlers keep the rebuilt dispatch on a later host that has the old event names again")
    void handlersKeepTheRebuiltDispatchWhereTheHostHasTheNames() {
        Set<String> present = Set.of(ForgeHostShape.ANCHOR, FORGE_LAYER, OLD_EVENT + "RenderGuiOverlayEvent", PRE,
                OLD_EVENT + "RenderGuiOverlayEvent$Post", OLD_EVENT + "RenderGuiEvent", OLD_EVENT + "RenderGuiEvent$Pre",
                OLD_EVENT + "RenderGuiEvent$Post");
        LegacyEventBaseBridge.register(transformer);
        LegacyGuiOverlayBridge.register(transformer, name -> present.contains(name) ? new ClassNode() : null);
        transformer.setModSourceVersion("1.20.1");
        MethodNode onPre = method(node(transformer.transformClass(overlaysClass("mod/Overlays"), "mod/Overlays.class")), "onPre");
        assertEquals("(L" + ROOT + "event/RenderGuiOverlayEvent$Pre;)V", onPre.desc, "a Forge 1.20.1 mod keeps the rebuilt event");
        assertEquals(List.of(LEGACY_SUBSCRIBE), annotations(onPre), "and its handler goes to the rebuilt dispatch");
    }

    @Test
    @DisplayName("A client-only @EventBusSubscriber class whose only handlers are overlay events is subscribed from its mod's constructor")
    void clientSubscriberWithOnlyOverlayHandlers() {
        registerHost("26.2");
        byte[] mod = ModConstructorHooksTest.modClass("mod/HudMod", "hudmod", "java/lang/Object", (w, o) -> { });
        byte[] hud = LegacyAutoSubscribeBridgeTest.subscriber("mod/HudEvents",
                List.of("value", List.of((Object) LegacyAutoSubscribeBridgeTest.enumValue("Lnet/minecraftforge/api/distmarker/Dist;", "CLIENT"))),
                LegacyAutoSubscribeBridgeTest.handler("onPre", PRE, OLD_SUBSCRIBE),
                LegacyAutoSubscribeBridgeTest.handler("onPost", OLD_EVENT + "RenderGuiOverlayEvent$Post", OLD_SUBSCRIBE));
        ModConstructorHooks.JarHooks hooks = ModConstructorHooks.forJar(transformer, List.of(mod, hud));
        assertNotNull(hooks);
        byte[] constructed = hooks.apply(mod);
        assertEquals(List.of("mod.HudEvents FORGE CLIENT"), LegacyAutoSubscribeBridgeTest.subscribeLists(constructed));
        assertTrue(calls(method(node(constructed), "<init>")).contains(LegacyGuiOverlayBridge.HUB + ".attach"),
                "the same constructor lists the jar's copy for the HUD coordinator");
        assertFalse(LegacyAutoSubscribeBridgeTest.hasAutoSubscriber(hooks.apply(hud)), "FML would find no handler it can take");
        ClassNode transformed = node(transformer.transformClass(hud, "mod/HudEvents.class"));
        assertEquals(List.of(LEGACY_SUBSCRIBE), annotations(method(transformed, "onPre")));
        assertEquals(List.of(LEGACY_SUBSCRIBE), annotations(method(transformed, "onPost")));
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("Each wrapped layer sits in the stack ForgeLayeredDraw.init puts it in, and the draw order tables follow Forge's")
    void layerStacksMatchTheHostTree(String host) throws Exception {
        Path universal = universalJar(host);
        Assumptions.assumeTrue(universal != null, "no Forge " + host + " universal jar at " + universalCandidates(host));
        Map<String, List<String>> placed = initTree(universal);
        Map<String, String> stacks = hostStackTable();
        Set<String> wrapped = new java.util.HashSet<>();
        for (List<String> layers : hostLayerTable().values()) {
            int previous = -1;
            for (String layer : layers) {
                wrapped.add(layer);
                String stack = stacks.get(layer);
                assertTrue(placed.getOrDefault(stack, List.of()).contains(layer), layer + " is not in " + stack + ": " + placed);
                assertEquals(stacks.get(layers.get(0)), stack, layers + " share one stack");
                int at = placed.get(stack).indexOf(layer);
                assertTrue(at > previous, layers + " are listed in draw order");
                previous = at;
            }
        }
        assertEquals(wrapped, stacks.keySet(), "the stack table covers exactly the layers old overlays stand for");

        // Forge draws the tree depth first; the second subtitle, in the root, draws only for a hidden HUD.
        List<String> drawn = new ArrayList<>();
        flatten("vanilla_root", placed, drawn);
        assertEquals(drawn.stream().filter(wrapped::contains).distinct().toList(), hostDrawOrder());
    }

    private static void flatten(String stack, Map<String, List<String>> placed, List<String> drawn) {
        for (String child : placed.get(stack)) {
            if (placed.containsKey(child)) {
                flatten(child, placed, drawn);
            } else {
                drawn.add(child);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> hostDrawOrder() throws Exception {
        return (List<String>) Class.forName(LegacyGuiOverlayBridge.HUB.replace('/', '.')).getField("HOST_DRAW_ORDER").get(null);
    }

    @Test
    @DisplayName("A Forge 1.21 layer class that inherits render from a superclass gets an extract calling it virtually")
    void concreteLayerInheritingRenderGetsExtract() throws Exception {
        registerHost("26.2");
        transformer.setModSourceVersion("1.21.1");
        byte[] transformed = transformer.transformClass(inheritingLayer("mod/CompassOverlay", "mod/CompassBase"),
                "mod/CompassOverlay.class");
        ClassNode overlay = node(transformed);
        MethodNode extract = method(overlay, "extract");
        assertEquals(List.of("mod/CompassOverlay.render"), calls(extract));
        assertEquals(INVOKEVIRTUAL, first(extract, MethodInsnNode.class).getOpcode());
        assertFalse(overlay.methods.stream().anyMatch(m -> m.name.equals("render")), "no abstract render hides the inherited one");
        verify(transformed);
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("Every old vanilla overlay maps to layers the host declares and adds, or to none for the debug text and graph")
    void vanillaIdTableIsComplete(String host) throws Exception {
        Path universal = universalJar(host);
        Assumptions.assumeTrue(universal != null, "no Forge " + host + " universal jar at " + universalCandidates(host));
        Map<String, List<String>> table = hostLayerTable();
        List<String> oldPaths = vanillaPaths();
        assertEquals(26, oldPaths.size(), "the rebuilt enum keeps all 26 old overlays");
        assertEquals(Set.copyOf(oldPaths), table.keySet(), "the table names every old overlay and nothing else");

        Map<String, String> fieldsByPath = new HashMap<>();
        Set<String> added = new java.util.HashSet<>();
        readLayeredDraw(universal, fieldsByPath, added);
        for (String path : oldPaths) {
            List<String> layers = table.get(path);
            if (layers.isEmpty()) {
                assertTrue(Set.of("debug_text", "fps_graph").contains(path), path + " needs a host layer");
                continue;
            }
            for (String layer : layers) {
                String field = fieldsByPath.get(layer);
                assertNotNull(field, path + " maps to " + layer + ", which ForgeLayeredDraw does not declare");
                assertTrue(added.contains(field), path + " maps to " + layer + ", which ForgeLayeredDraw.init never adds");
            }
        }
    }

    // ---- Forge 1.21 carry-forward ----

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("A Forge 1.21 LayeredDraw layer becomes a ForgeLayer: implementers gain extract, lambdas and calls are renamed")
    void layeredDrawLayersBecomeForgeLayers(String host) throws Exception {
        registerHost(host);
        transformer.setModSourceVersion("1.21.1");
        String extractDesc = "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;L" + DELTA + ";)V";

        ClassNode overlay = node(transformer.transformClass(layerClass("mod/CompassOverlay", false), "mod/CompassOverlay.class"));
        assertEquals(List.of(FORGE_LAYER), overlay.interfaces);
        MethodNode extract = method(overlay, "extract");
        assertEquals(extractDesc, extract.desc);
        assertEquals(List.of("mod/CompassOverlay.render"), calls(extract), "extract runs the old render");

        ClassNode hudOverlay = node(transformer.transformClass(layerClass("mod/IHudOverlay", true), "mod/IHudOverlay.class"));
        assertEquals(extractDesc, method(hudOverlay, "extract").desc, "an interface over the old layer gains a default extract");
        assertEquals(0, method(hudOverlay, "extract").access & ACC_ABSTRACT);

        // An interface over the old layer that names no render, as InventoryHUD's IHudOverlay, keeps the inherited one.
        ClassNode bare = node(transformer.transformClass(bareLayerInterface("mod/IBareOverlay", LAYER), "mod/IBareOverlay.class"));
        assertEquals(ACC_ABSTRACT, method(bare, "render").access & ACC_ABSTRACT, "the old abstract render comes back");
        assertEquals(List.of("mod/IBareOverlay.render"), calls(method(bare, "extract")));

        byte[] client = transformer.transformClass(layerUserClass("mod/Client"), "mod/Client.class");
        MethodNode add = method(node(client), "addLayers");
        InvokeDynamicInsnNode lambda = first(add, InvokeDynamicInsnNode.class);
        assertEquals("extract", lambda.name, "the lambda implements ForgeLayer's method");
        assertEquals("()L" + FORGE_LAYER + ";", lambda.desc);
        assertTrue(calls(add).contains(LAYERED_DRAW + ".add"), calls(add).toString());
        assertTrue(calls(add).contains(FORGE_LAYER + ".extract"), "a direct render call goes to extract: " + calls(add));
        verify(client);

        // A mod built for Forge 26.2 writes its own ForgeLayer interfaces, which are left as they are.
        transformer.setModSourceVersion("26.2");
        ClassNode own = node(transformer.transformClass(bareLayerInterface("mod/INewOverlay", FORGE_LAYER), "mod/INewOverlay.class"));
        assertEquals(List.of("init"), own.methods.stream().map(m -> m.name).toList());
    }

    @Test
    @DisplayName("A mod-bus subscriber class with an AddGuiOverlayLayersEvent handler is subscribed from the mod's constructor")
    void addGuiOverlayLayersSubscriberIsRehomed() {
        registerHost("26.2");
        byte[] subscriber = LegacyAutoSubscribeBridgeTest.subscriber("mod/KillIcons", LegacyModBusEventsTest.MOD_BUS,
                LegacyAutoSubscribeBridgeTest.handler("registerGuiOverlays", OLD_EVENT + "AddGuiOverlayLayersEvent",
                        OLD_SUBSCRIBE));
        LegacyAutoSubscribeBridge.Subscriber planned = LegacyAutoSubscribeBridge.subscriber("mod/KillIcons", subscriber,
                event -> LegacyModBusEvents.leftModBus(transformer, event));
        assertNotNull(planned, "Forge 26.2 posts AddGuiOverlayLayersEvent on the game bus, which FML refuses for a mod-bus class");
        assertEquals("MOD", planned.bus());
    }

    // ---- every transform path ----

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("The Forge runtime, CLI batch and AOT paths embed the rebuilt API, list each copy from its @Mod, outer and nested")
    void everyPathEmbedsTheRebuiltApi(String host) throws Exception {
        Path input = dir.resolve("overlays.jar");
        Files.write(input, LegacyAutoSubscribePathParityTest.jar(modWithNestedLibrary()));

        Map<String, Map<String, String>> outputs = new LinkedHashMap<>();
        outputs.put("Forge runtime", summary(forgeRuntime(input, host)));
        Path batchIn = Files.createDirectory(dir.resolve("batch-in"));
        Path batchOut = Files.createDirectory(dir.resolve("batch-out"));
        Files.copy(input, batchIn.resolve("overlays.jar"));
        CliHarness.batch(batchIn, batchOut, host);
        outputs.put("CLI batch", summary(LegacyAutoSubscribePathParityTest.onlyJar(batchOut)));
        Path aot = dir.resolve("aot.jar");
        CliHarness.aot(input, aot, host);
        outputs.put("AOT", summary(aot));

        String casts = "embedded ForgeGui.isInstance, embedded ForgeGui.cast";
        String listener = "@LegacySubscribe on embedded RenderGuiOverlayEvent$Pre";
        String coordinator = "LegacyHudCoordinator, LegacyHudHeights";
        Map<String, String> expected = new TreeMap<>(Map.of(
                "mod/OverlayMod.<init>", "embedded LegacyOverlayHub.attach",
                "mod/Overlays.leftHeight", casts,
                "mod/Overlays.onPre", listener,
                "coordinator", coordinator,
                "nested:fixturelib/LibMod.<init>", "embedded LegacyOverlayHub.attach",
                "nested:fixturelib/Overlays.leftHeight", casts,
                "nested:fixturelib/Overlays.onPre", listener,
                "nested:coordinator", coordinator));
        for (Map.Entry<String, Map<String, String>> output : outputs.entrySet()) {
            assertEquals(expected, output.getValue(), output.getKey());
        }
    }

    // ---- registration ----

    /** What RetromodForge registers on the host: every Forge and common shim available there. */
    private void registerHost(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (Set.of("forge", "common").contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, host)) {
                ShimRegistration.register(shim, transformer);
            }
        }
    }

    private void assertNothingRegistered(String why, java.util.function.Consumer<RetromodTransformer> registration) {
        transformer.clearRedirectsForTesting();
        registration.accept(transformer);
        assertFalse(transformer.getClassRedirects().containsKey(FORGE_GUI), why);
        assertFalse(transformer.getSyntheticClasses().containsKey(LegacyGuiOverlayBridge.HUB), why);
        ClassNode node = node(transformer.transformClass(overlaysClass("mod/Overlays"), "mod/Overlays.class"));
        assertTrue(typeInsns(method(node, "leftHeight")).contains("CHECKCAST " + FORGE_GUI), why);
    }

    private Path forgeRuntime(Path input, String host) throws Exception {
        registerHost(host);
        Path out = new ForgeModTransformer(host).transformMod(input, Files.createDirectory(dir.resolve("runtime")));
        assertNotNull(out, "the Forge runtime wrote a jar");
        return out;
    }

    // ---- what each path wrote ----

    private static Map<String, String> summary(Path jar) throws Exception {
        Map<String, String> found = new TreeMap<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            describe(file, "mod/OverlayMod", "mod/Overlays", "", found);
            Path nested = Files.createTempFile("nested-", ".jar");
            try {
                Files.write(nested, file.getInputStream(file.getJarEntry("META-INF/jarjar/lib.jar")).readAllBytes());
                try (JarFile lib = new JarFile(nested.toFile())) {
                    describe(lib, "fixturelib/LibMod", "fixturelib/Overlays", "nested:", found);
                }
            } finally {
                Files.deleteIfExists(nested);
            }
        }
        return found;
    }

    private static void describe(JarFile jar, String modClass, String overlays, String prefix, Map<String, String> found)
            throws Exception {
        found.put(prefix + modClass + ".<init>", embeddedCalls(jar, method(node(read(jar, modClass)), "<init>"), "LegacyOverlayHub"));
        ClassNode node = node(read(jar, overlays));
        found.put(prefix + overlays + ".leftHeight", embeddedCalls(jar, method(node, "leftHeight"), "ForgeGui"));
        MethodNode onPre = method(node, "onPre");
        String event = Type.getArgumentTypes(onPre.desc)[0].getInternalName();
        boolean embedded = event.startsWith(SyntheticEmbedder.PREFIX) && jar.getJarEntry(event + ".class") != null;
        // The annotation is the jar's own relocated copy, like the event.
        List<String> marks = annotations(onPre);
        boolean legacy = marks.size() == 1 && marks.get(0).endsWith(LegacyEventBaseBridge.LEGACY_SUBSCRIBE + ";");
        found.put(prefix + overlays + ".onPre", (legacy ? "@LegacySubscribe" : marks)
                + " on " + (embedded ? "embedded " : "missing ") + event.substring(event.lastIndexOf('/') + 1));
        // The coordinator travels in each jar, so a translated jar works without Retromod installed.
        List<String> carried = new ArrayList<>();
        for (String name : List.of("LegacyHudCoordinator", "LegacyHudHeights")) {
            String suffix = ROOT + name + ".class";
            boolean present = jar.stream().anyMatch(e -> e.getName().startsWith(SyntheticEmbedder.PREFIX) && e.getName().endsWith(suffix));
            if (present) carried.add(name);
        }
        found.put(prefix + "coordinator", String.join(", ", carried));
    }

    // The calls to one rebuilt class, each marked embedded when the jar carries the copy it names.
    private static String embeddedCalls(JarFile jar, MethodNode method, String simpleName) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (!(insn instanceof MethodInsnNode call) || !call.owner.endsWith("/" + simpleName)) continue;
            boolean embedded = call.owner.startsWith(SyntheticEmbedder.PREFIX) && jar.getJarEntry(call.owner + ".class") != null;
            calls.add((embedded ? "embedded " : "missing ") + simpleName + "." + call.name);
        }
        return String.join(", ", calls);
    }

    private static byte[] read(JarFile jar, String name) throws Exception {
        JarEntry entry = jar.getJarEntry(name + ".class");
        assertNotNull(entry, name + " is missing from " + jar.getName());
        return jar.getInputStream(entry).readAllBytes();
    }

    // ---- old mod classes ----

    /** A Forge 1.20.1 mod with an overlay class, and a nested library mod with one of its own. */
    private static Map<String, byte[]> modWithNestedLibrary() throws Exception {
        Map<String, byte[]> lib = new LinkedHashMap<>();
        lib.put("META-INF/mods.toml", LegacyAutoSubscribePathParityTest.toml("fixturelib"));
        lib.put("fixturelib/LibMod.class", ModConstructorHooksTest.modClass("fixturelib/LibMod", "fixturelib",
                "java/lang/Object", (w, o) -> { }));
        lib.put("fixturelib/Overlays.class", overlaysClass("fixturelib/Overlays"));
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put("META-INF/mods.toml", LegacyAutoSubscribePathParityTest.toml("overlaymod"));
        mod.put("mod/OverlayMod.class", ModConstructorHooksTest.modClass("mod/OverlayMod", "overlaymod", "java/lang/Object",
                (w, o) -> { }));
        mod.put("mod/Overlays.class", overlaysClass("mod/Overlays"));
        mod.put("META-INF/jarjar/lib.jar", LegacyAutoSubscribePathParityTest.jar(lib));
        return mod;
    }

    /**
     * A Forge 1.20.1 overlay class: it implements IGuiOverlay, cancels the crosshair's Pre from an old listener,
     * registers itself above the hotbar, and reads {@code ((ForgeGui) Minecraft.getInstance().gui).leftHeight}.
     */
    static byte[] overlaysClass(String name) {
        ClassWriter cw = writer();
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", new String[]{I_GUI_OVERLAY});
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor render = cw.visitMethod(ACC_PUBLIC, "render", "(L" + FORGE_GUI + ";L" + GUI_GRAPHICS + ";FII)V", null, null);
        render.visitCode();
        render.visitInsn(RETURN);
        render.visitMaxs(0, 0);
        render.visitEnd();

        // if (event.getOverlay() == VanillaGuiOverlay.CROSSHAIR.type()) event.setCanceled(true);
        MethodVisitor onPre = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "onPre", "(L" + PRE + ";)V", null, null);
        onPre.visitAnnotation(OLD_SUBSCRIBE, true).visitEnd();
        onPre.visitCode();
        Label done = new Label();
        onPre.visitVarInsn(ALOAD, 0);
        onPre.visitMethodInsn(INVOKEVIRTUAL, PRE, "getOverlay", "()L" + NAMED + ";", false);
        onPre.visitFieldInsn(GETSTATIC, VANILLA, "CROSSHAIR", "L" + VANILLA + ";");
        onPre.visitMethodInsn(INVOKEVIRTUAL, VANILLA, "type", "()L" + NAMED + ";", false);
        onPre.visitJumpInsn(IF_ACMPNE, done);
        onPre.visitVarInsn(ALOAD, 0);
        onPre.visitInsn(ICONST_1);
        onPre.visitMethodInsn(INVOKEVIRTUAL, PRE, "setCanceled", "(Z)V", false);
        onPre.visitLabel(done);
        onPre.visitInsn(RETURN);
        onPre.visitMaxs(0, 0);
        onPre.visitEnd();

        // event.registerAbove(VanillaGuiOverlay.HOTBAR.id(), "x", new Overlays());
        MethodVisitor register = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "register", "(L" + REGISTER + ";)V", null, null);
        register.visitCode();
        register.visitVarInsn(ALOAD, 0);
        register.visitFieldInsn(GETSTATIC, VANILLA, "HOTBAR", "L" + VANILLA + ";");
        register.visitMethodInsn(INVOKEVIRTUAL, VANILLA, "id", "()L" + RESOURCE_LOCATION + ";", false);
        register.visitLdcInsn("x");
        register.visitTypeInsn(NEW, name);
        register.visitInsn(DUP);
        register.visitMethodInsn(INVOKESPECIAL, name, "<init>", "()V", false);
        register.visitMethodInsn(INVOKEVIRTUAL, REGISTER, "registerAbove",
                "(L" + RESOURCE_LOCATION + ";Ljava/lang/String;L" + I_GUI_OVERLAY + ";)V", false);
        register.visitInsn(RETURN);
        register.visitMaxs(0, 0);
        register.visitEnd();

        // Gui gui = Minecraft.getInstance().gui; return gui instanceof ForgeGui ? ((ForgeGui) gui).leftHeight : 0;
        MethodVisitor heights = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "leftHeight", "()I", null, null);
        heights.visitCode();
        Label none = new Label();
        heights.visitMethodInsn(INVOKESTATIC, MINECRAFT, "getInstance", "()L" + MINECRAFT + ";", false);
        heights.visitFieldInsn(GETFIELD, MINECRAFT, "gui", "Lnet/minecraft/client/gui/Gui;");
        heights.visitVarInsn(ASTORE, 0);
        heights.visitVarInsn(ALOAD, 0);
        heights.visitTypeInsn(INSTANCEOF, FORGE_GUI);
        heights.visitJumpInsn(IFEQ, none);
        heights.visitVarInsn(ALOAD, 0);
        heights.visitTypeInsn(CHECKCAST, FORGE_GUI);
        heights.visitFieldInsn(GETFIELD, FORGE_GUI, "leftHeight", "I");
        heights.visitInsn(IRETURN);
        heights.visitLabel(none);
        heights.visitInsn(ICONST_0);
        heights.visitInsn(IRETURN);
        heights.visitMaxs(0, 0);
        heights.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A Forge 1.20.1 or 1.21 listener on DebugText that reads one column. */
    private static byte[] debugTextClass(String name, String getter, String getterDesc) {
        ClassWriter cw = writer();
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "onDebug", "(L" + DEBUG_TEXT + ";)V", null, null);
        mv.visitAnnotation(OLD_SUBSCRIBE, true).visitEnd();
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, DEBUG_TEXT, getter, getterDesc, false);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A Forge 1.21.1 layer: a class implementing LayeredDraw.Layer, or an interface over it with a default render. */
    private static byte[] layerClass(String name, boolean isInterface) {
        ClassWriter cw = writer();
        int access = isInterface ? ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT : ACC_PUBLIC | ACC_SUPER;
        cw.visit(V17, access, name, null, "java/lang/Object", new String[]{LAYER});
        if (!isInterface) {
            MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
            init.visitCode();
            init.visitVarInsn(ALOAD, 0);
            init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            init.visitInsn(RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();
        }
        MethodVisitor render = cw.visitMethod(ACC_PUBLIC, "render", "(L" + GUI_GRAPHICS + ";L" + DELTA + ";)V", null, null);
        render.visitCode();
        render.visitInsn(RETURN);
        render.visitMaxs(0, 0);
        render.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A Forge 1.21 layer whose render comes from {@code superName}, a class that is no layer: a class declaring only a constructor. */
    private static byte[] inheritingLayer(String name, String superName) {
        ClassWriter cw = writer();
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, superName, new String[]{LAYER});
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, superName, "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** An interface extending {@code layer} that declares only {@code init()}. */
    private static byte[] bareLayerInterface(String name, String layer) {
        ClassWriter cw = writer();
        cw.visit(V17, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, name, null, "java/lang/Object", new String[]{layer});
        cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "init", "()V", null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code addLayers(draw, layer, g, d)}: adds a lambda layer to ForgeLayeredDraw, then renders {@code layer} directly. */
    private static byte[] layerUserClass(String name) {
        ClassWriter cw = writer();
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        String layerDesc = "(L" + GUI_GRAPHICS + ";L" + DELTA + ";)V";
        MethodVisitor body = cw.visitMethod(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC, "lambda$addLayers$0", layerDesc, null, null);
        body.visitCode();
        body.visitInsn(RETURN);
        body.visitMaxs(0, 0);
        body.visitEnd();

        MethodVisitor add = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "addLayers",
                "(L" + LAYERED_DRAW + ";L" + LAYER + ";L" + GUI_GRAPHICS + ";L" + DELTA + ";)V", null, null);
        add.visitCode();
        add.visitVarInsn(ALOAD, 0);
        add.visitInsn(ACONST_NULL);
        Handle metafactory = new Handle(H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;", false);
        add.visitInvokeDynamicInsn("render", "()L" + LAYER + ";", metafactory, Type.getType(layerDesc),
                new Handle(H_INVOKESTATIC, name, "lambda$addLayers$0", layerDesc, false), Type.getType(layerDesc));
        add.visitMethodInsn(INVOKEVIRTUAL, LAYERED_DRAW, "add",
                "(L" + RESOURCE_LOCATION + ";L" + LAYER + ";)L" + LAYERED_DRAW + ";", false);
        add.visitInsn(POP);
        add.visitVarInsn(ALOAD, 1);
        add.visitVarInsn(ALOAD, 2);
        add.visitVarInsn(ALOAD, 3);
        add.visitMethodInsn(INVOKEINTERFACE, LAYER, "render", layerDesc, true);
        add.visitInsn(RETURN);
        add.visitMaxs(0, 0);
        add.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    // ---- the host's layer ids and the rebuilt classes' table ----

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> hostLayerTable() throws Exception {
        // The rebuilt hub is only data and JDK types up to its table, so it loads on the test classpath.
        return (Map<String, List<String>>) Class.forName(LegacyGuiOverlayBridge.HUB.replace('/', '.'))
                .getField("HOST_LAYERS").get(null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> hostStackTable() throws Exception {
        return (Map<String, String>) Class.forName(LegacyGuiOverlayBridge.HUB.replace('/', '.')).getField("HOST_STACKS").get(null);
    }

    /** The layers ForgeLayeredDraw.init adds to each stack, by path, in the order it adds them. */
    private static Map<String, List<String>> initTree(Path universal) throws Exception {
        Map<String, String> fieldsByPath = new HashMap<>();
        readLayeredDraw(universal, fieldsByPath, new java.util.HashSet<>());
        Map<String, String> pathsByField = new HashMap<>();
        fieldsByPath.forEach((path, field) -> pathsByField.put(field, path));
        ClassNode node;
        try (ZipFile jar = new ZipFile(universal.toFile()); InputStream in = jar.getInputStream(jar.getEntry(LAYERED_DRAW + ".class"))) {
            node = node(in.readAllBytes());
        }
        Map<String, List<String>> placed = new LinkedHashMap<>();
        String stack = null;
        for (AbstractInsnNode insn : method(node, "init").instructions) {
            if (!(insn instanceof FieldInsnNode get) || get.getOpcode() != GETSTATIC || !get.owner.equals(LAYERED_DRAW)) continue;
            // The root instance, or a new stack built with this id; any other id is added to the stack being built.
            if (get.name.equals("instance")) {
                stack = "vanilla_root";
            } else if (nextReal(get) instanceof MethodInsnNode call && call.name.equals("<init>")) {
                stack = pathsByField.get(get.name);
            } else if (stack != null && pathsByField.containsKey(get.name)) {
                placed.computeIfAbsent(stack, any -> new ArrayList<>()).add(pathsByField.get(get.name));
            }
        }
        return placed;
    }

    private static AbstractInsnNode nextReal(AbstractInsnNode insn) {
        AbstractInsnNode next = insn.getNext();
        while (next != null && next.getOpcode() < 0) next = next.getNext();
        return next;
    }

    /** The old paths of the rebuilt VanillaGuiOverlay's constants, read from the compiled enum. */
    private static List<String> vanillaPaths() throws Exception {
        ClassNode node = node(Files.readAllBytes(LEGACY_CLASSES.resolve(ROOT + "gui/overlay/VanillaGuiOverlay.class")));
        List<String> paths = new ArrayList<>();
        for (AbstractInsnNode insn : method(node, "<clinit>").instructions) {
            // Each constant is built from its name and then its path.
            if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String text && text.equals(text.toLowerCase())) {
                paths.add(text);
            }
        }
        return paths;
    }

    /** ForgeLayeredDraw's id fields by path, and the fields its {@code init} reads to build the vanilla tree. */
    private static void readLayeredDraw(Path universal, Map<String, String> fieldsByPath, Set<String> added) throws Exception {
        ClassNode node;
        try (ZipFile jar = new ZipFile(universal.toFile()); InputStream in = jar.getInputStream(
                jar.getEntry(LAYERED_DRAW + ".class"))) {
            node = node(in.readAllBytes());
        }
        String pending = null;
        for (AbstractInsnNode insn : method(node, "<clinit>").instructions) {
            if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String text) pending = text;
            if (insn instanceof FieldInsnNode put && put.getOpcode() == PUTSTATIC && pending != null) {
                fieldsByPath.put(pending, put.name);
                pending = null;
            }
        }
        for (MethodNode method : node.methods) {
            if (!method.name.equals("init")) continue;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode get && get.getOpcode() == GETSTATIC) added.add(get.name);
            }
        }
    }

    private static List<Path> universalCandidates(String host) {
        String home = System.getProperty("user.home");
        return host.equals("26.2")
                ? List.of(Path.of(home, "Library/Application Support/PrismLauncher/libraries/net/minecraftforge/forge/26.2-65.0.0",
                        "forge-26.2-65.0.0-universal.jar"))
                : List.of(Path.of(home, ".claude/jobs/3da2a019/tmp/forge263/forge-26.3-66.1.2-universal.jar"));
    }

    private static Path universalJar(String host) {
        return universalCandidates(host).stream().filter(Files::isRegularFile).findFirst().orElse(null);
    }

    // ---- bytecode helpers ----

    static ClassWriter writer() {
        // The fixtures merge no two class types, so frames need no class hierarchy.
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
    }

    static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(node.name + " has no " + name + ": "
                        + node.methods.stream().map(m -> m.name).toList()));
    }

    static List<String> calls(MethodNode method) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
        }
        return calls;
    }

    private static List<String> fields(MethodNode method) {
        List<String> fields = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof FieldInsnNode field) fields.add(field.owner + "." + field.name);
        }
        return fields;
    }

    private static List<String> typeInsns(MethodNode method) {
        List<String> types = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof TypeInsnNode type && (type.getOpcode() == CHECKCAST || type.getOpcode() == INSTANCEOF)) {
                types.add((type.getOpcode() == CHECKCAST ? "CHECKCAST " : "INSTANCEOF ") + type.desc);
            }
        }
        return types;
    }

    private static MethodInsnNode callNamed(MethodNode method, String name) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.name.equals(name)) return call;
        }
        throw new AssertionError(method.name + " calls no " + name);
    }

    private static <T extends AbstractInsnNode> T first(MethodNode method, Class<T> kind) {
        for (AbstractInsnNode insn : method.instructions) {
            if (kind.isInstance(insn)) return kind.cast(insn);
        }
        throw new AssertionError(method.name + " has no " + kind.getSimpleName());
    }

    static List<String> annotations(MethodNode method) {
        List<String> descs = new ArrayList<>();
        for (List<AnnotationNode> annotations : java.util.Arrays.asList(method.visibleAnnotations, method.invisibleAnnotations)) {
            if (annotations != null) annotations.forEach(annotation -> descs.add(annotation.desc));
        }
        return descs;
    }

    // The checker and the analyzer, so a rewritten instruction that broke the stack shape fails here.
    private static void verify(byte[] classBytes) throws Exception {
        new ClassReader(classBytes).accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
        ClassNode node = node(classBytes);
        for (MethodNode method : node.methods) {
            if (method.instructions.size() > 0) new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
        }
    }

    static String dotted(String internalName) {
        return internalName.replace('/', '.');
    }
}
