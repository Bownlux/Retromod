/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** An old mod's subscriber classes with legacy listeners are subscribed from its @Mod constructor, as Forge 1.20.1 did. */
class LegacyAutoSubscribeBridgeTest {

    static final String LEGACY = "L" + LegacyEventBaseBridge.LEGACY_SUBSCRIBE + ";";
    static final String OBJECT = "java/lang/Object";
    static final String EB7 = "Lnet/minecraftforge/eventbus/api/listener/SubscribeEvent;";
    private static final String OLD = "Lnet/minecraftforge/eventbus/api/SubscribeEvent;";
    private static final String HOST_EVENT = "net/minecraftforge/event/server/ServerStartedEvent";
    private static final String MOD_EVENT = "mod/MyEvent";
    private static final String BUS = "Lnet/minecraftforge/fml/common/Mod$EventBusSubscriber$Bus;";
    private static final String DIST = "Lnet/minecraftforge/api/distmarker/Dist;";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @BeforeEach
    void forge262() {
        register("26.2", true, false);
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
    }

    private void register(String target, boolean forge, boolean neoForge) {
        RetromodVersion.TARGET_MC_VERSION = target;
        McReflect.setForceForge(forge);
        McReflect.setForceNeoForge(neoForge);
        transformer.clearRedirectsForTesting();
        new Forge_26_1_to_26_2().registerRedirects(transformer);
    }

    @Test
    @DisplayName("A subscriber with a legacy static listener joins its mod's constructor tail with its bus and sides, and FML no longer sees it")
    void subscribersJoinTheConstructor() {
        byte[] mod = ModConstructorHooksTest.modClass("mod/MyMod", "mymod", OBJECT, (writer, owner) -> { });
        byte[] game = subscriber("mod/GameEvents", List.of(), handler("onMine", MOD_EVENT, LEGACY));
        byte[] client = subscriber("mod/ClientModEvents", List.of("bus", enumValue(BUS, "MOD"),
                "value", List.of((Object) enumValue(DIST, "CLIENT"))),
                handler("onMine", MOD_EVENT, LEGACY), handler("onStart", HOST_EVENT, EB7));
        byte[] hostOnly = subscriber("mod/HostEvents", List.of(),
                handler("onStart", HOST_EVENT, EB7), handler("onStartAgain", HOST_EVENT, EB7));
        byte[] elsewhere = subscriber("mod/OtherModEvents", List.of("modid", "othermod"),
                handler("onMine", MOD_EVENT, LEGACY));

        ModConstructorHooks.JarHooks hooks = ModConstructorHooks.forJar(transformer,
                List.of(mod, game, client, hostOnly, elsewhere));
        assertNotNull(hooks);
        assertEquals(List.of("mod.ClientModEvents MOD CLIENT\nmod.GameEvents FORGE CLIENT,DEDICATED_SERVER"),
                subscribeLists(hooks.apply(mod)), "one call at the constructor tail, sorted by class name");

        assertFalse(hasAutoSubscriber(hooks.apply(game)), "the constructor subscribes it, so FML must not");
        assertFalse(hasAutoSubscriber(hooks.apply(client)), "a mixed class is subscribed whole, so no listener runs twice");
        assertSame(hostOnly, hooks.apply(hostOnly), "a class with only EventBus 7 listeners stays with FML");
        assertSame(elsewhere, hooks.apply(elsewhere), "a class naming a mod outside this jar was never subscribed");
    }

    @Test
    @DisplayName("Each mod of a jar subscribes the classes that belong to it, as Forge 1.20.1 decided")
    void eachModGetsItsOwnSubscribers() {
        byte[] first = subscriberMod("mod/FirstMod", "first", handler("onMine", MOD_EVENT, LEGACY));
        byte[] second = ModConstructorHooksTest.modClass("mod/SecondMod", "second", OBJECT, (writer, owner) -> { });
        byte[] named = subscriber("mod/SecondEvents", List.of("modid", "second"), handler("onMine", MOD_EVENT, LEGACY));
        byte[] shared = subscriber("mod/SharedEvents", List.of(), handler("onMine", MOD_EVENT, LEGACY));

        ModConstructorHooks.JarHooks hooks = ModConstructorHooks.forJar(transformer, List.of(first, second, named, shared));
        assertNotNull(hooks);
        assertEquals(List.of("mod.FirstMod FORGE CLIENT,DEDICATED_SERVER\nmod.SharedEvents FORGE CLIENT,DEDICATED_SERVER"),
                subscribeLists(hooks.apply(first)), "a @Mod class subscribes itself, and an unnamed class joins every mod");
        assertEquals(List.of("mod.SecondEvents FORGE CLIENT,DEDICATED_SERVER\nmod.SharedEvents FORGE CLIENT,DEDICATED_SERVER"),
                subscribeLists(hooks.apply(second)));
    }

    @Test
    @DisplayName("stripLenientAutoSubscriber still counts only EventBus 7 listeners, and leaves a class the constructor subscribes to it")
    void lenientStripCountsOnlyEventBus7() {
        byte[] allLegacy = subscriber("mod/AllLegacy", List.of(),
                handler("onMine", MOD_EVENT, LEGACY), handler("onMineAgain", MOD_EVENT, LEGACY));
        List<String> companions = new ArrayList<>();
        byte[] stripped = ForgeEventBusSynthetics.stripLenientAutoSubscriber(allLegacy, (name, bytes) -> companions.add(name));
        assertFalse(hasAutoSubscriber(stripped), "no EventBus 7 listener, so FML would refuse the class");
        assertEquals(List.of(), companions, "a legacy listener never gets a companion");

        byte[] mod = ModConstructorHooksTest.modClass("mod/MyMod", "mymod", OBJECT, (writer, owner) -> { });
        byte[] mixed = subscriber("mod/Mixed", List.of(),
                handler("onMine", MOD_EVENT, LEGACY), handler("onStart", HOST_EVENT, EB7));
        byte[] handedOver = ModConstructorHooks.forJar(transformer, List.of(mod, mixed)).apply(mixed);
        assertSame(handedOver, ForgeEventBusSynthetics.stripLenientAutoSubscriber(handedOver,
                (name, bytes) -> companions.add(name)), "nothing is left for the strip to change");
        assertEquals(List.of(), companions, "the one EventBus 7 listener is not hooked a second time");
    }

    @Test
    @DisplayName("The jar plan hands over exactly the classes the class pass gives a legacy static listener")
    void planAgreesWithTheClassPass() {
        byte[] mod = ModConstructorHooksTest.modClass("mod/MyMod", "mymod", OBJECT, (writer, owner) -> { });
        byte[] old = subscriber("mod/OldEvents", List.of(), handler("onMine", MOD_EVENT, OLD), handler("onStart", HOST_EVENT, OLD));
        ModConstructorHooks.JarHooks hooks = ModConstructorHooks.forJar(transformer, List.of(mod, old));
        boolean planned = hooks != null && !subscribeLists(hooks.apply(mod)).isEmpty();
        boolean marked = hasLegacyStatic(transformer.transformClass(old, "mod/OldEvents.class"));
        assertEquals(marked, planned, "the plan previews the same pre-remap pass the class goes through");
    }

    @Test
    @DisplayName("Nothing is planned on NeoForge, on an EventBus 6 Forge host, or for a Fabric target; Forge 26.3 plans the same")
    void hostGate() {
        List<byte[]> jar = List.of(ModConstructorHooksTest.modClass("mod/MyMod", "mymod", OBJECT, (writer, owner) -> { }),
                subscriber("mod/GameEvents", List.of(), handler("onMine", MOD_EVENT, LEGACY)));
        register("26.2", false, true);
        assertNull(ModConstructorHooks.forJar(transformer, jar), "NeoForge keeps its own event API");
        register("1.21.1", true, false);
        assertNull(ModConstructorHooks.forJar(transformer, jar), "EventBus 6 still subscribes old listeners itself");
        register("26.2", false, false);
        assertNull(ModConstructorHooks.forJar(transformer, jar), "Fabric and Quilt have no Forge event bus");
        register("26.3", true, false);
        assertNotNull(ModConstructorHooks.forJar(transformer, jar), "the shim chain carries the bridge to Forge 26.3");
    }

    @Test
    @DisplayName("The runtime helper subscribes each class on its bus for this side only, initialized, once per game bus")
    void runtimeSubscribes(@TempDir Path dir) throws Exception {
        ClassLoader server = fakeHost(dir.resolve("server"), "DEDICATED_SERVER");
        Object theMod = server.loadClass("mod.TheMod").getConstructor().newInstance();
        subscribe(server, theMod, String.join("\n",
                "mod.ClientEvents MOD CLIENT",
                "mod.GameEvents FORGE CLIENT,DEDICATED_SERVER",
                "mod.Missing FORGE CLIENT,DEDICATED_SERVER",
                "mod.Broken FORGE DEDICATED_SERVER",
                "mod.ModEvents MOD CLIENT,DEDICATED_SERVER"));
        assertEquals(List.of("game mod.GameEvents", "mod mod.ModEvents"), registered(server),
                "a client class is skipped on a server, and a class that fails does not stop the rest");
        assertEquals(List.of("GameEvents", "ModEvents"), staticList(server, "mod.Seen", "INITIALIZED"),
                "each class was initialized first, and the client one never loaded");

        // A second @Mod class of the same jar shares the copy: the game bus takes a class once, each mod bus its own.
        subscribe(server, theMod, "mod.GameEvents FORGE CLIENT,DEDICATED_SERVER\nmod.ModEvents MOD CLIENT,DEDICATED_SERVER");
        assertEquals(List.of("game mod.GameEvents", "mod mod.ModEvents", "mod mod.ModEvents"), registered(server));

        ClassLoader client = fakeHost(dir.resolve("client"), "CLIENT");
        subscribe(client, client.loadClass("mod.TheMod").getConstructor().newInstance(), "mod.ClientEvents MOD CLIENT");
        assertEquals(List.of("mod mod.ClientEvents"), registered(client));
    }

    @Test
    @DisplayName("The runtime helper uses only members the generated event bus declares")
    void helperLinksToTheGeneratedBus() {
        // The copies this host registered, which are what the embedder places beside the helper.
        Map<String, byte[]> synthetics = transformer.getSyntheticClasses();
        Map<String, ClassNode> generated = Map.of(
                ForgeEventBusSynthetics.LEB, ModConstructorHooksTest.node(synthetics.get(ForgeEventBusSynthetics.LEB)),
                ForgeEventBusSynthetics.LEB_IMPL, ModConstructorHooksTest.node(synthetics.get(ForgeEventBusSynthetics.LEB_IMPL)));
        ClassNode helper = ModConstructorHooksTest.node(synthetics.get(LegacyAutoSubscribeBridge.SUBSCRIBER));
        int checked = 0;
        for (MethodNode method : helper.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && generated.containsKey(call.owner)) {
                    assertTrue(generated.get(call.owner).methods.stream().anyMatch(m -> m.name.equals(call.name)
                            && m.desc.equals(call.desc) && ((m.access & ACC_STATIC) != 0) == (call.getOpcode() == INVOKESTATIC)),
                            call.owner + "." + call.name + call.desc + " is generated");
                    checked++;
                } else if (insn instanceof FieldInsnNode field && generated.containsKey(field.owner)) {
                    FieldNode declared = generated.get(field.owner).fields.stream()
                            .filter(f -> f.name.equals(field.name)).findFirst().orElseThrow();
                    assertEquals(declared.desc, field.desc, field.name);
                    checked++;
                }
            }
        }
        assertEquals(3, checked, "GAME_BUS, of(BusGroup) and register(Object)");
    }

    // ---- fixtures ----

    record Handler(String name, String event, String annotation) {}

    static Handler handler(String name, String event, String annotation) {
        return new Handler(name, event, annotation);
    }

    static String[] enumValue(String desc, String constant) {
        return new String[]{desc, constant};
    }

    /** A class with {@code @Mod.EventBusSubscriber(values)} and one static listener per handler. */
    static byte[] subscriber(String name, List<Object> values, Handler... handlers) {
        return subscriberClass(name, null, values, handlers);
    }

    /** A @Mod class that is also its own subscriber. */
    private static byte[] subscriberMod(String name, String modId, Handler... handlers) {
        return subscriberClass(name, modId, List.of(), handlers);
    }

    private static byte[] subscriberClass(String name, String modId, List<Object> values, Handler... handlers) {
        ClassNode node = ModConstructorHooksTest.node(ModConstructorHooksTest.modClass(name, modId, OBJECT, (writer, owner) -> {
            for (Handler handler : handlers) {
                MethodVisitor listener = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, handler.name(),
                        "(L" + handler.event() + ";)V", null, null);
                listener.visitAnnotation(handler.annotation(), true).visitEnd();
                listener.visitCode();
                listener.visitInsn(RETURN);
                listener.visitMaxs(0, 1);
                listener.visitEnd();
            }
        }));
        AnnotationNode annotation = new AnnotationNode(LegacyAutoSubscribeBridge.AUTO_SUBSCRIBER);
        annotation.values = values.isEmpty() ? null : new ArrayList<>(values);
        if (node.visibleAnnotations == null) node.visibleAnnotations = new ArrayList<>();
        node.visibleAnnotations.add(annotation);
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** The list strings passed to the helper's subscribe call in each constructor, in order. */
    static List<String> subscribeLists(byte[] modClass) {
        List<String> lists = new ArrayList<>();
        for (MethodNode method : ModConstructorHooksTest.node(modClass).methods) {
            if (!method.name.equals("<init>")) continue;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.endsWith(LegacyAutoSubscribeBridge.SUBSCRIBER)
                        && call.name.equals("subscribe")) {
                    assertEquals(LegacyAutoSubscribeBridge.SUBSCRIBE_DESC, call.desc);
                    assertEquals(RETURN, call.getNext().getOpcode(), "the call is the constructor's last step");
                    lists.add((String) ((LdcInsnNode) call.getPrevious()).cst);
                }
            }
        }
        return lists;
    }

    static boolean hasAutoSubscriber(byte[] classBytes) {
        List<AnnotationNode> annotations = ModConstructorHooksTest.node(classBytes).visibleAnnotations;
        return annotations != null && annotations.stream().anyMatch(a -> a.desc.equals(LegacyAutoSubscribeBridge.AUTO_SUBSCRIBER));
    }

    private static boolean hasLegacyStatic(byte[] classBytes) {
        for (MethodNode method : ModConstructorHooksTest.node(classBytes).methods) {
            if ((method.access & ACC_STATIC) == 0 || method.visibleAnnotations == null) continue;
            if (method.visibleAnnotations.stream().anyMatch(a -> a.desc.equals(LEGACY))) return true;
        }
        return false;
    }

    // ---- runtime ----

    /** Stand-ins for Forge 26.2 and the generated bus that record each subscription, plus a mod's own classes. */
    private static final Map<String, String> FAKE_HOST = Map.ofEntries(
            Map.entry("net/minecraftforge/api/distmarker/Dist", "package net.minecraftforge.api.distmarker;"
                    + " public enum Dist { CLIENT, DEDICATED_SERVER }"),
            Map.entry("net/minecraftforge/fml/loading/FMLEnvironment", "package net.minecraftforge.fml.loading;"
                    + " public class FMLEnvironment { public static net.minecraftforge.api.distmarker.Dist dist; }"),
            Map.entry("net/minecraftforge/eventbus/api/bus/BusGroup", "package net.minecraftforge.eventbus.api.bus;"
                    + " public interface BusGroup {}"),
            Map.entry("net/minecraftforge/fml/ModContainer", "package net.minecraftforge.fml; public class ModContainer {"
                    + " public net.minecraftforge.eventbus.api.bus.BusGroup getModBusGroup() { return null; } }"),
            Map.entry("net/minecraftforge/fml/ModLoadingContext", "package net.minecraftforge.fml;"
                    + " public class ModLoadingContext { public static ModLoadingContext get() { return new ModLoadingContext(); }"
                    + " public ModContainer getContainer() { return new ModContainer(); } }"),
            Map.entry("com/retromod/shim/forge/embedded/LegacyEventBus", "package com.retromod.shim.forge.embedded;"
                    + " public interface LegacyEventBus { void register(Object listener); }"),
            Map.entry("com/retromod/shim/forge/embedded/LegacyEventBusImpl", "package com.retromod.shim.forge.embedded;"
                    + " public final class LegacyEventBusImpl implements LegacyEventBus {"
                    + " public static final java.util.List<String> REGISTERED = new java.util.ArrayList<>();"
                    + " public static final LegacyEventBus GAME_BUS = new LegacyEventBusImpl(\"game\");"
                    + " private final String bus; private LegacyEventBusImpl(String bus) { this.bus = bus; }"
                    + " public static LegacyEventBus of(net.minecraftforge.eventbus.api.bus.BusGroup group) {"
                    + " return new LegacyEventBusImpl(\"mod\"); }"
                    + " public void register(Object listener) { REGISTERED.add(bus + \" \" + ((Class<?>) listener).getName()); } }"),
            Map.entry("mod/Seen", "package mod; public class Seen {"
                    + " public static final java.util.List<String> INITIALIZED = new java.util.ArrayList<>(); }"),
            Map.entry("mod/TheMod", "package mod; public class TheMod {}"),
            Map.entry("mod/GameEvents", "package mod; public class GameEvents { static { Seen.INITIALIZED.add(\"GameEvents\"); } }"),
            Map.entry("mod/ModEvents", "package mod; public class ModEvents { static { Seen.INITIALIZED.add(\"ModEvents\"); } }"),
            Map.entry("mod/ClientEvents", "package mod; public class ClientEvents { static { Seen.INITIALIZED.add(\"ClientEvents\"); } }"),
            Map.entry("mod/Broken", "package mod; public class Broken { static { if (Boolean.TRUE) throw new IllegalStateException(\"broken\"); } }"));

    /** A mod's class loader over the fake host, with the helper defined in it as the embedder would place it. */
    private ClassLoader fakeHost(Path dir, String side) throws Exception {
        Path sources = Files.createDirectories(dir.resolve("src"));
        Path classes = Files.createDirectories(dir.resolve("classes"));
        List<String> args = new ArrayList<>(List.of("-d", classes.toString(), "-nowarn"));
        for (Map.Entry<String, String> source : FAKE_HOST.entrySet()) {
            Path file = sources.resolve(source.getKey() + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            args.add(file.toString());
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "the test needs a JDK to compile the fake host");
        assertEquals(0, compiler.run(null, null, null, args.toArray(String[]::new)), "the fake host compiles");

        Map<String, byte[]> own = new HashMap<>();
        try (var files = Files.walk(classes)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                String name = classes.relativize(file).toString().replace('\\', '/');
                own.put(name.substring(0, name.length() - ".class".length()), Files.readAllBytes(file));
            }
        }
        own.put(LegacyAutoSubscribeBridge.SUBSCRIBER, transformer.getSyntheticClasses().get(LegacyAutoSubscribeBridge.SUBSCRIBER));
        ClassLoader loader = new LegacyEventRuntimeTest.Loader(getClass().getClassLoader(), own);
        Class<?> dist = loader.loadClass("net.minecraftforge.api.distmarker.Dist");
        loader.loadClass("net.minecraftforge.fml.loading.FMLEnvironment").getField("dist")
                .set(null, dist.getField(side).get(null));
        return loader;
    }

    private static void subscribe(ClassLoader loader, Object mod, String list) throws Exception {
        Method subscribe = loader.loadClass(LegacyAutoSubscribeBridge.SUBSCRIBER.replace('/', '.'))
                .getMethod("subscribe", Object.class, String.class);
        subscribe.invoke(null, mod, list);
    }

    private static List<String> registered(ClassLoader loader) throws Exception {
        return staticList(loader, "com.retromod.shim.forge.embedded.LegacyEventBusImpl", "REGISTERED");
    }

    @SuppressWarnings("unchecked")
    private static List<String> staticList(ClassLoader loader, String owner, String field) throws Exception {
        return List.copyOf((List<String>) loader.loadClass(owner).getField(field).get(null));
    }
}
