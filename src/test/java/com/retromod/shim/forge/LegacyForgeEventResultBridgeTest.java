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
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

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

/** Old result calls on Forge events that lost their result on Forge 26.2 and 26.3 reach EventResults; the rest keep the host's. */
class LegacyForgeEventResultBridgeTest {

    private static final String HELPER = LegacyForgeEventResultBridge.EVENT_RESULTS;
    private static final String OLD_RESULT = LegacyEventBaseBridge.OLD_RESULT;
    private static final String L_OLD = "L" + OLD_RESULT + ";";
    private static final String L_RESULT = "L" + LegacyEventBaseBridge.RESULT + ";";
    private static final String PLAYER_EVENTS = "net/minecraftforge/event/entity/player/";
    private static final String INTERACT = PLAYER_EVENTS + "PlayerInteractEvent";
    private static final String RIGHT_CLICK = INTERACT + "$RightClickBlock";
    private static final String BONEMEAL = PLAYER_EVENTS + "BonemealEvent";
    private static final String MOB_SPAWN = "net/minecraftforge/event/entity/living/MobSpawnEvent";
    private static final String NAME_TAG = "net/minecraftforge/client/event/RenderNameTagEvent";
    private static final String CROP_PRE = "net/minecraftforge/event/level/BlockEvent$CropGrowEvent$Pre";
    private static final String BREAK = LegacyForgeEventResultBridge.BREAK;
    private static final String MOB = "net/minecraft/world/entity/Mob";
    private static final String LEVEL_ACCESSOR = "net/minecraft/world/level/ServerLevelAccessor";
    private static final String EVENT_FACTORY = "net/minecraftforge/event/ForgeEventFactory";
    private static final String CALLS = "mod/ResultCalls";

    private static final String STATIC_GET = "INVOKESTATIC " + HELPER + ".getResult(Ljava/lang/Object;)" + L_RESULT;
    private static final String STATIC_SET = "INVOKESTATIC " + HELPER + ".setResult(Ljava/lang/Object;" + L_RESULT + ")V";

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
    @DisplayName("Result calls on events that lost their result reach EventResults, and the class still verifies")
    void lostResultsReachTheHelper(String host) throws Exception {
        ClassNode out = transformOn(host, "1.20.1", resultCalls(CALLS));

        assertEquals(List.of(STATIC_GET), calls(out, "rightClickGet"));
        assertEquals(List.of(STATIC_SET), calls(out, "rightClickSet"));
        assertEquals(List.of(STATIC_SET), calls(out, "mountSet"));
        assertEquals(List.of(STATIC_SET), calls(out, "mobSpawnSet"), "the abstract MobSpawnEvent, as Zeta calls it");
        assertEquals(List.of(STATIC_GET), calls(out, "finalizeGet"));
        assertEquals(List.of(STATIC_SET), calls(out, "nameTagSet"));
        assertEquals(List.of(STATIC_SET), calls(out, "interactSet"), "EntityInteract, now EntityInteractSpecific");
        assertEquals(List.of("INVOKESTATIC " + HELPER + ".hasResult(Ljava/lang/Object;)Z"), calls(out, "bonemealHas"),
                "no Forge 26.2 event declares hasResult");
        assertEquals("H_INVOKESTATIC " + HELPER + ".getResult(Ljava/lang/Object;)" + L_RESULT, methodReference(out));
        verify(out);
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("Events that kept their result, the use getters, PlayerSleepInBedEvent and a mod's own event keep their calls")
    void keptResultsStayOnTheHost(String host) throws Exception {
        ClassNode out = transformOn(host, "1.20.1", resultCalls(CALLS));

        assertEquals(List.of("INVOKEVIRTUAL " + BONEMEAL + ".setResult(" + L_RESULT + ")V"), calls(out, "bonemealSet"));
        assertEquals(List.of("INVOKEVIRTUAL " + CROP_PRE + ".setResult(" + L_RESULT + ")V"), calls(out, "cropSet"),
                "a HasResult record answers through the interface's default method");
        assertEquals(List.of("INVOKEVIRTUAL " + RIGHT_CLICK + ".setUseBlock(" + L_RESULT + ")V"), calls(out, "useBlockSet"));
        assertEquals(List.of("INVOKEVIRTUAL mod/OwnEvent.setResult(" + L_RESULT + ")V"), calls(out, "ownSet"));
        List<String> sleep = calls(out, "sleepSet");
        assertEquals(1, sleep.size());
        assertTrue(sleep.get(0).startsWith("INVOKEVIRTUAL " + PLAYER_EVENTS + "PlayerSleepInBedEvent.setResult("), sleep.toString());
        assertEquals(List.of("INVOKESTATIC " + EVENT_FACTORY + ".canEntityDespawn(L" + MOB + ";L" + LEVEL_ACCESSOR + ";)"
                + L_RESULT), calls(out, "despawn"), "the old descriptor now matches the host's");
    }

    @ParameterizedTest(name = "mod built for {0}")
    @ValueSource(strings = {"1.20.1", "1.21.1", "unknown", "1.21.6", "26.2"})
    @DisplayName("BreakEvent's result reaches the host only for mods built for Forge 1.21.6 or newer")
    void breakEventKeepsOldValuesBesideTheEvent(String source) {
        ClassNode out = transformOn("26.2", source.equals("unknown") ? null : source, resultCalls(CALLS));

        boolean newer = !source.equals("unknown") && RetromodVersion.compareMcVersions(source, "1.21.6") >= 0;
        String get = newer ? "INVOKEVIRTUAL " + BREAK + ".getResult()" + L_RESULT : STATIC_GET;
        String set = newer ? "INVOKEVIRTUAL " + BREAK + ".setResult(" + L_RESULT + ")V" : STATIC_SET;
        assertEquals(List.of(set, get), calls(out, "breakSetAndGet"));
    }

    @Test
    @DisplayName("A class already built for Forge 26.2 keeps its host calls")
    void nativeClassesAreUntouched() {
        byte[] native262 = nativeCalls();
        ClassNode out = transformOn("26.2", "26.2", native262);
        assertEquals(List.of("INVOKEVIRTUAL " + BONEMEAL + ".getResult()" + L_RESULT), calls(out, "bonemealGet"));
        assertEquals(List.of("INVOKEVIRTUAL " + BREAK + ".setResult(" + L_RESULT + ")V"), calls(out, "breakSet"));
        assertSame(native262, LegacyForgeEventResultBridge.apply(native262, name -> null), "the pass leaves the bytes alone");
    }

    @Test
    @DisplayName("On a running host, a Forge event that declares the member, or inherits it from an interface, keeps the call")
    void runningHostDecidesFromItsOwnClasses() {
        Map<String, ClassNode> classes = host(Map.of(ForgeHostShape.ANCHOR, List.of(),
                BONEMEAL, List.of("getResult()" + L_RESULT, "setResult(" + L_RESULT + ")V"),
                RIGHT_CLICK, List.of("setUseBlock(" + L_RESULT + ")V"),
                "net/minecraftforge/common/util/HasResult$Record", List.of("getResult()" + L_RESULT),
                CROP_PRE, List.of()));
        classes.get(CROP_PRE).interfaces.add("net/minecraftforge/common/util/HasResult$Record");
        Function<String, ClassNode> host = classes::get;

        assertFalse(LegacyForgeEventResultBridge.hostLacks(BONEMEAL, "getResult", "()" + L_RESULT, host));
        assertFalse(LegacyForgeEventResultBridge.hostLacks(CROP_PRE, "getResult", "()" + L_RESULT, host),
                "inherited from HasResult.Record");
        assertTrue(LegacyForgeEventResultBridge.hostLacks(RIGHT_CLICK, "getResult", "()" + L_RESULT, host));
        assertTrue(LegacyForgeEventResultBridge.hostLacks(NAME_TAG, "setResult", "(" + L_RESULT + ")V", host),
                "a class this host does not have");

        // Offline, the list of HasResult events stands in for the host.
        Function<String, ClassNode> offline = name -> null;
        assertFalse(LegacyForgeEventResultBridge.hostLacks(BONEMEAL, "setResult", "(" + L_RESULT + ")V", offline));
        assertTrue(LegacyForgeEventResultBridge.hostLacks(RIGHT_CLICK, "getResult", "()" + L_RESULT, offline));
        assertTrue(LegacyForgeEventResultBridge.hostLacks(BONEMEAL, "hasResult", "()Z", offline));
    }

    @Test
    @DisplayName("Nothing registers for a Forge 1.21.1 target, a NeoForge target, a target that is not Forge, or an EventBus 6 host")
    void registersOnlyWhereTheHostLostEventResult() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        LegacyForgeEventResultBridge.register(transformer);
        assertFalse(transformer.getSyntheticClasses().containsKey(HELPER), "Forge 1.21.1 still has Event.Result");

        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceNeoForge(true);
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        assertFalse(transformer.getSyntheticClasses().containsKey(HELPER), "NeoForge keeps its own results");

        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(false);
        LegacyForgeEventResultBridge.register(transformer);
        assertFalse(transformer.getSyntheticClasses().containsKey(HELPER), "a target that is not Forge");

        McReflect.setForceForge(true);
        Map<String, ClassNode> eventBus6 = host(Map.of(ForgeHostShape.ANCHOR, List.of(), OLD_RESULT, List.of(),
                LegacyEventBaseBridge.HAS_RESULT, List.of()));
        assertFalse(LegacyForgeEventResultBridge.hostLostEventResult(eventBus6::get), "a host that still has Event.Result");
        Map<String, ClassNode> eventBus7 = host(Map.of(ForgeHostShape.ANCHOR, List.of(),
                LegacyEventBaseBridge.HAS_RESULT, List.of()));
        assertTrue(LegacyForgeEventResultBridge.hostLostEventResult(eventBus7::get), "a running Forge 26.2 host");
        assertTrue(LegacyForgeEventResultBridge.hostLostEventResult(), "offline Forge 26.2");
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("The Forge runtime, CLI batch and AOT paths send result calls to EventResults the same way, outer and nested")
    void everyPathRewrites(String host, @TempDir Path dir) throws Exception {
        Path input = dir.resolve("results.jar");
        Files.write(input, modJar());

        Map<String, Map<String, String>> outputs = new LinkedHashMap<>();
        outputs.put("Forge runtime", summary(forgeRuntime(input, dir, host)));
        Path batchIn = Files.createDirectory(dir.resolve("batch-in"));
        Path batchOut = Files.createDirectory(dir.resolve("batch-out"));
        Files.copy(input, batchIn.resolve("results.jar"));
        CliHarness.batch(batchIn, batchOut, host);
        outputs.put("CLI batch", summary(onlyJar(batchOut)));
        Path aot = dir.resolve("aot.jar");
        CliHarness.aot(input, aot, host);
        outputs.put("AOT", summary(aot));

        String helper = "embedded " + HELPER;
        Map<String, String> expected = new TreeMap<>();
        for (String owner : List.of(CALLS, "lib/LibResults")) {
            String key = owner.equals(CALLS) ? owner : "nested:" + owner;
            expected.put(key + ".rightClickGet", List.of(helper + ".getResult").toString());
            expected.put(key + ".nameTagSet", List.of(helper + ".setResult").toString());
            expected.put(key + ".breakSetAndGet", List.of(helper + ".setResult", helper + ".getResult").toString());
            expected.put(key + ".bonemealSet", List.of(INVOKEVIRTUAL + " " + BONEMEAL + ".setResult").toString());
        }
        for (Map.Entry<String, Map<String, String>> output : outputs.entrySet()) {
            assertEquals(expected, output.getValue(), output.getKey());
        }
    }

    // ---- transform helpers ----

    /** One class through the Forge 26.2 shim on {@code host}, for a mod built for {@code source} (null when unknown). */
    private ClassNode transformOn(String host, String source, byte[] classBytes) {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        transformer.setModSourceVersion(source);
        assertTrue(transformer.getSyntheticClasses().containsKey(HELPER), "the helper is registered on Forge " + host);
        return node(transformer.transformClass(classBytes, CALLS + ".class"));
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

    // Each result call in a few methods, named by its owner's embedded copy when it is one.
    private static Map<String, String> summary(Path jar) throws Exception {
        Map<String, String> found = new TreeMap<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            summarize(found, file, CALLS, CALLS);
            Path nested = Files.createTempFile("nested-", ".jar");
            try {
                Files.write(nested, file.getInputStream(file.getJarEntry("META-INF/jarjar/lib.jar")).readAllBytes());
                try (JarFile lib = new JarFile(nested.toFile())) {
                    summarize(found, lib, "lib/LibResults", "nested:lib/LibResults");
                }
            } finally {
                Files.deleteIfExists(nested);
            }
        }
        return found;
    }

    private static void summarize(Map<String, String> found, JarFile jar, String owner, String key) throws Exception {
        JarEntry entry = jar.getJarEntry(owner + ".class");
        assertNotNull(entry, owner + " is missing from " + jar.getName());
        ClassNode node = node(jar.getInputStream(entry).readAllBytes());
        for (String name : List.of("rightClickGet", "nameTagSet", "breakSetAndGet", "bonemealSet")) {
            List<String> calls = new ArrayList<>();
            for (AbstractInsnNode insn : method(node, name).instructions) {
                if (!(insn instanceof MethodInsnNode call)) continue;
                boolean embedded = call.owner.startsWith(SyntheticEmbedder.PREFIX)
                        && jar.getJarEntry(call.owner + ".class") != null;
                String rebuilt = call.owner.substring(Math.max(call.owner.indexOf("com/retromod/legacyapi/"), 0));
                calls.add((embedded ? "embedded " : call.getOpcode() + " ") + rebuilt + "." + call.name);
            }
            found.put(key + "." + name, calls.toString());
        }
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

    private static String methodReference(ClassNode node) {
        for (AbstractInsnNode insn : method(node, "reference").instructions) {
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

    private static void verify(ClassNode node) {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        new ClassReader(writer.toByteArray()).accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
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

    /** Result calls as javac wrote them for Forge 1.20.1, one per method, each on the event the method takes. */
    private static byte[] resultCalls(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        getter(cw, "rightClickGet", RIGHT_CLICK);
        setter(cw, "rightClickSet", RIGHT_CLICK, "setResult");
        setter(cw, "mountSet", "net/minecraftforge/event/entity/EntityMountEvent", "setResult");
        setter(cw, "mobSpawnSet", MOB_SPAWN, "setResult");
        getter(cw, "finalizeGet", MOB_SPAWN + "$FinalizeSpawn");
        setter(cw, "nameTagSet", NAME_TAG, "setResult");
        setter(cw, "interactSet", INTERACT + "$EntityInteract", "setResult");
        setter(cw, "bonemealSet", BONEMEAL, "setResult");
        setter(cw, "cropSet", CROP_PRE, "setResult");
        setter(cw, "useBlockSet", RIGHT_CLICK, "setUseBlock");
        setter(cw, "ownSet", "mod/OwnEvent", "setResult");

        MethodVisitor has = start(cw, "bonemealHas", BONEMEAL);
        has.visitVarInsn(ALOAD, 0);
        has.visitMethodInsn(INVOKEVIRTUAL, BONEMEAL, "hasResult", "()Z", false);
        has.visitInsn(POP);
        finish(has);

        // Player.BedSleepingProblem is the sleep event's own result, not Event.Result.
        String problem = "Lnet/minecraft/world/entity/player/Player$BedSleepingProblem;";
        MethodVisitor sleep = start(cw, "sleepSet", PLAYER_EVENTS + "PlayerSleepInBedEvent");
        sleep.visitVarInsn(ALOAD, 0);
        sleep.visitInsn(ACONST_NULL);
        sleep.visitMethodInsn(INVOKEVIRTUAL, PLAYER_EVENTS + "PlayerSleepInBedEvent", "setResult", "(" + problem + ")V", false);
        finish(sleep);

        MethodVisitor breaks = start(cw, "breakSetAndGet", BREAK);
        breaks.visitVarInsn(ALOAD, 0);
        breaks.visitFieldInsn(GETSTATIC, OLD_RESULT, "DENY", L_OLD);
        breaks.visitMethodInsn(INVOKEVIRTUAL, BREAK, "setResult", "(" + L_OLD + ")V", false);
        breaks.visitVarInsn(ALOAD, 0);
        breaks.visitMethodInsn(INVOKEVIRTUAL, BREAK, "getResult", "()" + L_OLD, false);
        breaks.visitInsn(POP);
        finish(breaks);

        MethodVisitor despawn = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "despawn",
                "(L" + MOB + ";L" + LEVEL_ACCESSOR + ";)Ljava/lang/Object;", null, null);
        despawn.visitCode();
        despawn.visitVarInsn(ALOAD, 0);
        despawn.visitVarInsn(ALOAD, 1);
        despawn.visitMethodInsn(INVOKESTATIC, EVENT_FACTORY, "canEntityDespawn",
                "(L" + MOB + ";L" + LEVEL_ACCESSOR + ";)" + L_OLD, false);
        despawn.visitInsn(ARETURN);
        despawn.visitMaxs(0, 0);
        despawn.visitEnd();

        // static Function<RightClickBlock, Event.Result> reference() { return RightClickBlock::getResult; }
        MethodVisitor reference = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "reference",
                "()Ljava/util/function/Function;", null, null);
        reference.visitCode();
        Handle metafactory = new Handle(H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;", false);
        reference.visitInvokeDynamicInsn("apply", "()Ljava/util/function/Function;", metafactory,
                Type.getMethodType("(Ljava/lang/Object;)Ljava/lang/Object;"),
                new Handle(H_INVOKEVIRTUAL, RIGHT_CLICK, "getResult", "()" + L_OLD, false),
                Type.getMethodType("(L" + RIGHT_CLICK + ";)" + L_OLD));
        reference.visitInsn(ARETURN);
        reference.visitMaxs(0, 0);
        reference.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A class built for Forge 26.2, which already names the host's Result. */
    private static byte[] nativeCalls() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_SUPER, "mod/NativeCalls", null, "java/lang/Object", null);
        MethodVisitor get = start(cw, "bonemealGet", BONEMEAL);
        get.visitVarInsn(ALOAD, 0);
        get.visitMethodInsn(INVOKEVIRTUAL, BONEMEAL, "getResult", "()" + L_RESULT, false);
        get.visitInsn(POP);
        finish(get);
        MethodVisitor set = start(cw, "breakSet", BREAK);
        set.visitVarInsn(ALOAD, 0);
        set.visitFieldInsn(GETSTATIC, LegacyEventBaseBridge.RESULT, "DENY", L_RESULT);
        set.visitMethodInsn(INVOKEVIRTUAL, BREAK, "setResult", "(" + L_RESULT + ")V", false);
        finish(set);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void getter(ClassWriter cw, String name, String owner) {
        MethodVisitor mv = start(cw, name, owner);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, owner, "getResult", "()" + L_OLD, false);
        mv.visitInsn(POP);
        finish(mv);
    }

    private static void setter(ClassWriter cw, String name, String owner, String member) {
        MethodVisitor mv = start(cw, name, owner);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETSTATIC, OLD_RESULT, "DENY", L_OLD);
        mv.visitMethodInsn(INVOKEVIRTUAL, owner, member, "(" + L_OLD + ")V", false);
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

    /** A Forge 1.20.1 mod with the result calls, and a nested library with the same calls. */
    private static byte[] modJar() throws Exception {
        Map<String, byte[]> lib = new LinkedHashMap<>();
        lib.put("META-INF/mods.toml", toml("resultlib"));
        lib.put("lib/LibResults.class", resultCalls("lib/LibResults"));
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put("META-INF/mods.toml", toml("resultmod"));
        mod.put(CALLS + ".class", resultCalls(CALLS));
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
