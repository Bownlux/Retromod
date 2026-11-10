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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.io.ByteArrayOutputStream;
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
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Old cancel calls on Forge events, which Forge 26.2 and 26.3 events no longer declare, reach LegacyCancel. */
class LegacyCancelBridgeTest {

    private static final String CANCEL = LegacyCancelBridge.CANCEL;
    private static final String INTERACT = "net/minecraftforge/event/entity/player/PlayerInteractEvent";
    private static final String RIGHT_CLICK = INTERACT + "$RightClickBlock";
    private static final String KEY_PRESSED = "net/minecraftforge/client/event/ScreenEvent$KeyPressed";
    private static final String ZETA_CLICK = "org/violetmoon/zeta/event/play/ZRightClickBlock";
    private static final String NEO_CLICK = "net/neoforged/neoforge/event/entity/player/PlayerInteractEvent$RightClickBlock";
    private static final String HANDLERS = "mod/Handlers";
    private static final String SOUND = "net/minecraftforge/event/PlayLevelSoundEvent";
    private static final String SOUND_AT_ENTITY = SOUND + "$AtEntity";

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
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("Cancel calls on Forge events become LegacyCancel calls, and the class still verifies")
    void cancelCallsReachTheHelper(String host) throws Exception {
        ClassNode out = transformOn(host, handlers());

        assertEquals(List.of("INVOKESTATIC " + CANCEL + ".setCanceled(Ljava/lang/Object;Z)V",
                "INVOKESTATIC " + CANCEL + ".isCanceled(Ljava/lang/Object;)Z",
                "INVOKESTATIC " + CANCEL + ".isCancelable(Ljava/lang/Object;)Z",
                "INVOKESTATIC " + CANCEL + ".cancel(Ljava/lang/Object;)V"), calls(out, "onClick"));
        assertEquals(List.of("INVOKESTATIC " + CANCEL + ".setCanceled(Ljava/lang/Object;Z)V"), calls(out, "onKey"),
                "ScreenEvent.KeyPressed is an interface on Forge 26.2, called with INVOKEINTERFACE");
        assertEquals(List.of("INVOKESTATIC " + CANCEL + ".setCanceled(Ljava/lang/Object;Z)V"), calls(out, "onParent"),
                "a call compiled against the parent PlayerInteractEvent");
        assertEquals("H_INVOKESTATIC " + CANCEL + ".isCanceled(Ljava/lang/Object;)Z", methodReference(out, "reference"),
                "RightClickBlock::isCanceled points at the helper");
        verify(out);
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("A mod's own event and a NeoForge event keep their own cancel methods")
    void modAndNeoForgeOwnersAreUntouched(String host) throws Exception {
        ClassNode out = transformOn(host, handlers());

        assertEquals(List.of("INVOKEINTERFACE " + ZETA_CLICK + ".setCanceled(Z)V"), calls(out, "onZeta"),
                "Zeta's ZRightClickBlock forwards to the Forge event itself");
        assertEquals(List.of("INVOKEVIRTUAL " + NEO_CLICK + ".setCanceled(Z)V"), calls(out, "onNeo"));
    }

    @ParameterizedTest(name = "a mod built for {0}")
    @ValueSource(strings = {"1.21.11", "26.2"})
    @DisplayName("A mod built for EventBus 7 is left as it is")
    void eventBus7ModsAreUntouched(String source) throws Exception {
        ClassNode out = transformOn("26.2", source, handlers());

        assertEquals(List.of("INVOKEVIRTUAL " + RIGHT_CLICK + ".setCanceled(Z)V", "INVOKEVIRTUAL " + RIGHT_CLICK
                + ".isCanceled()Z", "INVOKEVIRTUAL " + RIGHT_CLICK + ".isCancelable()Z", "INVOKEVIRTUAL " + RIGHT_CLICK
                + ".cancel()V"), calls(out, "onClick"));
    }

    @Test
    @DisplayName("A mod built for Forge 1.21.1 still has its calls rewritten")
    void forge1211ModsAreRewritten() throws Exception {
        ClassNode out = transformOn("26.2", "[1.21.1,1.22)", handlers());

        assertEquals("INVOKESTATIC " + CANCEL + ".cancel(Ljava/lang/Object;)V", calls(out, "onClick").get(3),
                "EventBus 6.2's cancel() too");
    }

    @Test
    @DisplayName("Nothing registers for a Forge 1.20.1 target, a NeoForge target, or a host without CancellableEventBus")
    void registersOnlyWhereTheHostCancelsByReturnValue() {
        RetromodVersion.TARGET_MC_VERSION = "1.20.1";
        LegacyCancelBridge.register(transformer);
        assertFalse(transformer.getSyntheticClasses().containsKey(CANCEL), "an EventBus 6 target keeps its own cancel");

        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceNeoForge(true);
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        LegacyCancelBridge.register(transformer);
        assertFalse(transformer.getSyntheticClasses().containsKey(CANCEL), "NeoForge keeps LegacyEventCalls");
        McReflect.setForceNeoForge(false);

        Map<String, ClassNode> eventBus6 = host(Map.of(ForgeHostShape.ANCHOR, List.of(), LegacyEventBaseAdapter.EVENT, List.of()));
        assertFalse(LegacyCancelBridge.hostCancelsByReturnValue(eventBus6::get));
        Map<String, ClassNode> noCancellableBus = host(Map.of(ForgeHostShape.ANCHOR, List.of()));
        assertFalse(LegacyCancelBridge.hostCancelsByReturnValue(noCancellableBus::get));
        Map<String, ClassNode> eventBus7 = host(Map.of(ForgeHostShape.ANCHOR, List.of(),
                LegacyCancelBridge.CANCELLABLE_BUS, List.of()));
        assertTrue(LegacyCancelBridge.hostCancelsByReturnValue(eventBus7::get));
    }

    @Test
    @DisplayName("On a running host, a Forge event that declares the member, or inherits it, keeps the call")
    void runningHostDecidesFromItsOwnClasses() {
        Map<String, ClassNode> classes = host(Map.of(ForgeHostShape.ANCHOR, List.of(),
                INTERACT, List.of("setCanceled(Z)V"), RIGHT_CLICK, List.of()));
        classes.get(RIGHT_CLICK).superName = INTERACT;
        Function<String, ClassNode> host = classes::get;

        assertFalse(LegacyCancelBridge.hostLacks(RIGHT_CLICK, "setCanceled", "(Z)V", host), "inherited from its parent");
        assertTrue(LegacyCancelBridge.hostLacks(RIGHT_CLICK, "isCanceled", "()Z", host));
        assertTrue(LegacyCancelBridge.hostLacks(KEY_PRESSED, "setCanceled", "(Z)V", host), "a class the host does not have");

        byte[] rewritten = LegacyCancelBridge.apply(handlers(), null, host);
        ClassNode out = node(rewritten);
        assertEquals("INVOKEVIRTUAL " + RIGHT_CLICK + ".setCanceled(Z)V", calls(out, "onClick").get(0));
        assertEquals("INVOKESTATIC " + CANCEL + ".isCanceled(Ljava/lang/Object;)Z", calls(out, "onClick").get(1));
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("The bundled table answers which events have a cancellable bus and which parents hand them listeners")
    void offlineTableMatchesTheHost(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        Function<String, ClassNode> offline = name -> null;

        assertTrue(LegacyCancelBridge.hasCancellableBus(RIGHT_CLICK, offline));
        assertTrue(LegacyCancelBridge.hasCancellableBus("net/minecraftforge/event/entity/living/LivingChangeTargetEvent", offline));
        assertFalse(LegacyCancelBridge.hasCancellableBus(INTERACT, offline), "PlayerInteractEvent posts on a plain bus");
        assertFalse(LegacyCancelBridge.hasCancellableBus("net/minecraftforge/event/entity/ProjectileImpactEvent", offline));
        assertEquals(Set.of(INTERACT + "$EntityInteractSpecific", INTERACT + "$LeftClickBlock", RIGHT_CLICK,
                INTERACT + "$RightClickItem"), LegacyCancelBridge.cancellableChildren(INTERACT, offline));
        // Forge 26.3 split the key mapping event into one cancellable event per key.
        String keyMapping = "net/minecraftforge/client/event/InputEvent$InteractionKeyMappingTriggered";
        assertEquals(host.equals("26.2"), LegacyCancelBridge.hasCancellableBus(keyMapping, offline));
        assertEquals(host.equals("26.3"), LegacyCancelBridge.hasCancellableBus(keyMapping + "$UseItem", offline));
        // EventBus 7 builds a cancellable bus for the Cancellable sound events, though their BUS field names a plain one.
        assertTrue(LegacyCancelBridge.hasCancellableBus(SOUND_AT_ENTITY, offline));
        assertTrue(LegacyCancelBridge.hasCancellableBus("net/minecraftforge/event/PlayLevelSoundEvent$AtPosition", offline));
        assertFalse(LegacyCancelBridge.hasCancellableBus(LegacyCancelBridge.NAME_TAG, offline),
                "Forge fires the name tag event without reading a cancel");
        assertEquals(Set.of(LegacyCancelBridge.NAME_TAG), LegacyCancelBridge.section(host).plain());
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("The table names the cancellable children of parents nothing posts, such as Forge 26.3's key mapping event")
    void buslessParentsNameTheirChildren(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        Function<String, ClassNode> offline = name -> null;
        String keyMapping = "net/minecraftforge/client/event/InputEvent$InteractionKeyMappingTriggered";
        String overlay = "net/minecraftforge/client/event/RenderBlockScreenEffectEvent";

        Set<String> keyChildren = host.equals("26.3")
                ? Set.of(keyMapping + "$Attack", keyMapping + "$PickBlock", keyMapping + "$UseItem") : Set.of();
        assertEquals(keyChildren, LegacyCancelBridge.buslessChildren(keyMapping, offline),
                "Forge 26.2 still posts the key mapping event itself");
        Set<String> overlayChildren = host.equals("26.3") ? Set.of(overlay + "$BlockOverlayEvent",
                overlay + "$FireOverlayEvent", overlay + "$WaterOverlayEvent") : Set.of();
        assertEquals(overlayChildren, LegacyCancelBridge.buslessChildren(overlay, offline));
        assertEquals(Set.of("net/minecraftforge/client/event/RenderHighlightEvent$Block",
                "net/minecraftforge/client/event/RenderHighlightEvent$Entity"),
                LegacyCancelBridge.buslessChildren("net/minecraftforge/client/event/RenderHighlightEvent", offline));
    }

    @Test
    @DisplayName("The table has nothing for a target before Forge 26.2, and a running host's own BUS field wins")
    void tableAndRunningHost() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        assertNull(LegacyCancelBridge.section("1.21.1"));
        assertFalse(LegacyCancelBridge.hasCancellableBus(RIGHT_CLICK, name -> null));

        Map<String, ClassNode> classes = host(Map.of(ForgeHostShape.ANCHOR, List.of(), RIGHT_CLICK, List.of()));
        classes.get(RIGHT_CLICK).fields.add(new FieldNode(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "BUS",
                "L" + LegacyCancelBridge.CANCELLABLE_BUS + ";", null, null));
        assertTrue(LegacyCancelBridge.hasCancellableBus(RIGHT_CLICK, classes::get));
        assertFalse(LegacyCancelBridge.hasCancellableBus(INTERACT, classes::get));

        // A Cancellable event whose BUS field names a plain EventBus still posts on a cancellable bus.
        String plainBus = "Lnet/minecraftforge/eventbus/api/bus/EventBus;";
        Map<String, ClassNode> sounds = host(Map.of(ForgeHostShape.ANCHOR, List.of(), SOUND, List.of(),
                SOUND_AT_ENTITY, List.of(), LegacyCancelBridge.NAME_TAG, List.of(), INTERACT, List.of()));
        sounds.get(SOUND).interfaces.add("net/minecraftforge/eventbus/api/event/characteristic/Cancellable");
        sounds.get(SOUND_AT_ENTITY).superName = SOUND;
        sounds.get(LegacyCancelBridge.NAME_TAG).interfaces.add("net/minecraftforge/eventbus/api/event/characteristic/Cancellable");
        for (String event : List.of(SOUND_AT_ENTITY, LegacyCancelBridge.NAME_TAG, INTERACT)) {
            sounds.get(event).fields.add(new FieldNode(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "BUS", plainBus, null, null));
        }
        assertTrue(LegacyCancelBridge.hasCancellableBus(SOUND_AT_ENTITY, sounds::get));
        assertFalse(LegacyCancelBridge.hasCancellableBus(LegacyCancelBridge.NAME_TAG, sounds::get));
        assertFalse(LegacyCancelBridge.hasCancellableBus(INTERACT, sounds::get), "a plain event on a plain bus");
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("The Forge runtime, CLI batch and AOT paths rewrite cancel calls the same way, outer and nested, and embed LegacyCancel")
    void everyPathRewrites(String host, @TempDir Path dir) throws Exception {
        Path input = dir.resolve("cancels.jar");
        Files.write(input, modJar());

        Map<String, Map<String, String>> outputs = new LinkedHashMap<>();
        outputs.put("Forge runtime", summary(forgeRuntime(input, dir, host)));
        Path batchIn = Files.createDirectory(dir.resolve("batch-in"));
        Path batchOut = Files.createDirectory(dir.resolve("batch-out"));
        Files.copy(input, batchIn.resolve("cancels.jar"));
        CliHarness.batch(batchIn, batchOut, host);
        outputs.put("CLI batch", summary(onlyJar(batchOut)));
        Path aot = dir.resolve("aot.jar");
        CliHarness.aot(input, aot, host);
        outputs.put("AOT", summary(aot));

        String helper = "embedded " + CANCEL;
        Map<String, String> expected = new TreeMap<>(Map.of(
                HANDLERS + ".onClick", List.of(helper + ".setCanceled", helper + ".isCanceled", helper + ".isCancelable",
                        helper + ".cancel").toString(),
                "nested:lib/LibHandlers.onClick", List.of(helper + ".setCanceled", helper + ".isCanceled",
                        helper + ".isCancelable", helper + ".cancel").toString()));
        for (Map.Entry<String, Map<String, String>> output : outputs.entrySet()) {
            assertEquals(expected, output.getValue(), output.getKey());
        }
    }

    // ---- transform helpers ----

    private ClassNode transformOn(String host, byte[] classBytes) {
        return transformOn(host, null, classBytes);
    }

    /** One class through the Forge 26.2 shim on {@code host}, for a mod built for {@code source} (null when unknown). */
    private ClassNode transformOn(String host, String source, byte[] classBytes) {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        transformer.setModSourceVersion(source);
        assertTrue(transformer.getSyntheticClasses().containsKey(CANCEL), "the helper is registered on Forge " + host);
        return node(transformer.transformClass(classBytes, HANDLERS + ".class"));
    }

    /** What RetromodForge.loadForgeShims registers on the host, then one runtime transform. */
    private Path forgeRuntime(Path input, Path dir, String host) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (Set.of("forge", "common").contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, host)) {
                ShimRegistration.register(shim, transformer);
            }
        }
        Path out = new ForgeModTransformer(host).transformMod(input, Files.createDirectory(dir.resolve("runtime")));
        assertNotNull(out, "the Forge runtime wrote a jar");
        return out;
    }

    // Each cancel call in onClick, named by its owner's embedded copy when it is one.
    private static Map<String, String> summary(Path jar) throws Exception {
        Map<String, String> found = new TreeMap<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            found.put(HANDLERS + ".onClick", embeddedCalls(file, file, HANDLERS));
            Path nested = Files.createTempFile("nested-", ".jar");
            try {
                Files.write(nested, file.getInputStream(file.getJarEntry("META-INF/jarjar/lib.jar")).readAllBytes());
                try (JarFile lib = new JarFile(nested.toFile())) {
                    found.put("nested:lib/LibHandlers.onClick", embeddedCalls(lib, lib, "lib/LibHandlers"));
                }
            } finally {
                Files.deleteIfExists(nested);
            }
        }
        return found;
    }

    private static String embeddedCalls(JarFile jar, JarFile embeddedIn, String owner) throws Exception {
        JarEntry entry = jar.getJarEntry(owner + ".class");
        assertNotNull(entry, owner + " is missing from " + jar.getName());
        ClassNode node = node(jar.getInputStream(entry).readAllBytes());
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method(node, "onClick").instructions) {
            if (!(insn instanceof MethodInsnNode call)) continue;
            boolean embedded = call.owner.startsWith(SyntheticEmbedder.PREFIX)
                    && embeddedIn.getJarEntry(call.owner + ".class") != null;
            String rebuilt = call.owner.substring(Math.max(call.owner.indexOf("com/retromod/legacyapi/"), 0));
            calls.add((embedded ? "embedded " : call.getOpcode() + " ") + rebuilt + "." + call.name);
        }
        return calls.toString();
    }

    private static List<String> calls(ClassNode node, String name) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method(node, name).instructions) {
            if (insn instanceof MethodInsnNode call) {
                calls.add(opcode(call.getOpcode()) + " " + call.owner + "." + call.name + call.desc);
            }
        }
        return calls;
    }

    private static String methodReference(ClassNode node, String name) {
        for (AbstractInsnNode insn : method(node, name).instructions) {
            if (insn instanceof InvokeDynamicInsnNode indy && indy.bsmArgs[1] instanceof Handle handle) {
                String tag = handle.getTag() == H_INVOKESTATIC ? "H_INVOKESTATIC" : "H_" + handle.getTag();
                return tag + " " + handle.getOwner() + "." + handle.getName() + handle.getDesc();
            }
        }
        return "no method reference";
    }

    private static String opcode(int opcode) {
        return switch (opcode) {
            case INVOKESTATIC -> "INVOKESTATIC";
            case INVOKEVIRTUAL -> "INVOKEVIRTUAL";
            case INVOKEINTERFACE -> "INVOKEINTERFACE";
            default -> String.valueOf(opcode);
        };
    }

    private static void verify(ClassNode node) throws Exception {
        for (MethodNode method : node.methods) {
            new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
        }
    }

    /** A host whose classes declare the given methods, each written as name plus descriptor. */
    private static Map<String, ClassNode> host(Map<String, List<String>> declared) {
        Map<String, ClassNode> classes = new HashMap<>();
        declared.forEach((name, methods) -> {
            ClassNode node = new ClassNode();
            node.name = name;
            node.superName = "java/lang/Object";
            for (String method : methods) {
                int split = method.indexOf('(');
                node.methods.add(new MethodNode(ACC_PUBLIC, method.substring(0, split), method.substring(split), null, null));
            }
            classes.put(name, node);
        });
        return classes;
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(node.name + " has no " + name));
    }

    private static Path onlyJar(Path dir) throws Exception {
        try (var jars = Files.list(dir)) {
            List<Path> all = jars.filter(path -> path.toString().endsWith(".jar")).toList();
            assertEquals(1, all.size(), "the batch wrote one jar: " + all);
            return all.get(0);
        }
    }

    // ---- fixtures ----

    /**
     * Old handlers as javac wrote them for Forge 1.20.1: cancel calls on RightClickBlock, the KeyPressed screen
     * event, the parent PlayerInteractEvent, Zeta's own event and a NeoForge event, and a method reference.
     */
    static byte[] handlers() {
        return handlers(HANDLERS, true);
    }

    private static byte[] handlers(String name, boolean everyOwner) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        MethodVisitor click = start(cw, "onClick", RIGHT_CLICK);
        click.visitVarInsn(ALOAD, 0);
        click.visitInsn(ICONST_1);
        click.visitMethodInsn(INVOKEVIRTUAL, RIGHT_CLICK, "setCanceled", "(Z)V", false);
        click.visitVarInsn(ALOAD, 0);
        click.visitMethodInsn(INVOKEVIRTUAL, RIGHT_CLICK, "isCanceled", "()Z", false);
        click.visitInsn(POP);
        click.visitVarInsn(ALOAD, 0);
        click.visitMethodInsn(INVOKEVIRTUAL, RIGHT_CLICK, "isCancelable", "()Z", false);
        click.visitInsn(POP);
        click.visitVarInsn(ALOAD, 0);
        click.visitMethodInsn(INVOKEVIRTUAL, RIGHT_CLICK, "cancel", "()V", false);
        finish(click);
        if (everyOwner) {
            setCanceled(cw, "onKey", KEY_PRESSED, true);
            setCanceled(cw, "onParent", INTERACT, false);
            setCanceled(cw, "onZeta", ZETA_CLICK, true);
            setCanceled(cw, "onNeo", NEO_CLICK, false);
            // static Predicate<RightClickBlock> reference() { return RightClickBlock::isCanceled; }
            MethodVisitor reference = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "reference",
                    "()Ljava/util/function/Predicate;", null, null);
            reference.visitCode();
            Handle metafactory = new Handle(H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                            + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                            + "Ljava/lang/invoke/CallSite;", false);
            reference.visitInvokeDynamicInsn("test", "()Ljava/util/function/Predicate;", metafactory,
                    Type.getMethodType("(Ljava/lang/Object;)Z"),
                    new Handle(H_INVOKEVIRTUAL, RIGHT_CLICK, "isCanceled", "()Z", false),
                    Type.getMethodType("(L" + RIGHT_CLICK + ";)Z"));
            reference.visitInsn(ARETURN);
            reference.visitMaxs(0, 0);
            reference.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void setCanceled(ClassWriter cw, String name, String owner, boolean isInterface) {
        MethodVisitor mv = start(cw, name, owner);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(ICONST_1);
        mv.visitMethodInsn(isInterface ? INVOKEINTERFACE : INVOKEVIRTUAL, owner, "setCanceled", "(Z)V", isInterface);
        finish(mv);
    }

    private static MethodVisitor start(ClassWriter cw, String name, String event) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, "(L" + event + ";)V", null, null);
        mv.visitCode();
        return mv;
    }

    private static void finish(MethodVisitor mv) {
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** A Forge 1.20.1 mod with the handlers, and a nested library whose handler cancels too. */
    private static byte[] modJar() throws Exception {
        Map<String, byte[]> lib = new LinkedHashMap<>();
        lib.put("META-INF/mods.toml", toml("cancellib"));
        lib.put("lib/LibHandlers.class", handlers("lib/LibHandlers", false));
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put("META-INF/mods.toml", toml("cancelmod"));
        mod.put(HANDLERS + ".class", handlers());
        mod.put("META-INF/jarjar/lib.jar", jar(lib));
        return jar(mod);
    }

    private static byte[] toml(String modId) {
        return ("modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"" + modId
                + "\"\nversion=\"1.0\"\n[[dependencies." + modId + "]]\nmodId=\"minecraft\"\nmandatory=true\n"
                + "versionRange=\"[1.20.1,1.20.2)\"\nordering=\"NONE\"\nside=\"BOTH\"\n").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] jar(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
