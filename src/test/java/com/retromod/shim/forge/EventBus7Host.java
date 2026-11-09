/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.common.RebuiltApiClasses;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.commons.SimpleRemapper;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.objectweb.asm.Opcodes.*;

/** A Forge 26.2 host for tests: working EventBus 7 and FML stand-ins under their host names, and the compiled stubs. */
final class EventBus7Host extends ClassLoader {

    private static final Path COMPILED = Path.of("target", "legacyapi-classes");
    private static final String STAND_INS = "com/retromod/shim/forge/eb7/";
    static final String BUS_GROUP = "net/minecraftforge/eventbus/api/bus/BusGroup";
    static final String EVENT_BUS = "net/minecraftforge/eventbus/api/bus/EventBus";

    // Markers name host types in the stand-ins' descriptors; the compiled stub serves each of them.
    private static final Map<String, String> HOST_NAMES = Map.of(
            STAND_INS + "Event", "net/minecraftforge/eventbus/internal/Event",
            STAND_INS + "EventListener", "net/minecraftforge/eventbus/api/listener/EventListener",
            STAND_INS + "IModLoadingState", "net/minecraftforge/fml/IModLoadingState",
            STAND_INS + "ModLoader", "net/minecraftforge/fml/ModLoader",
            STAND_INS + "BusGroup", BUS_GROUP,
            STAND_INS + "BusGroup$Group", BUS_GROUP + "$Group",
            STAND_INS + "EventBus", EVENT_BUS,
            STAND_INS + "EventBus$Bus", EVENT_BUS + "$Bus",
            STAND_INS + "EventBus$Handle", EVENT_BUS + "$Handle");
    private static final List<String> WITH_BEHAVIOR = List.of("ModLoader", "BusGroup", "BusGroup$Group", "EventBus", "EventBus$Bus",
            "EventBus$Handle");

    private final Map<String, byte[]> classes = new HashMap<>();

    EventBus7Host() throws IOException {
        this(null);
    }

    /** As {@link #EventBus7Host()}, but on Forge's real EventBus 7 from {@code eventBusJar}; FML's loading state stays a stand-in. */
    EventBus7Host(Path eventBusJar) throws IOException {
        super(EventBus7Host.class.getClassLoader());
        try (Stream<Path> files = Files.walk(COMPILED.resolve("net"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                String name = COMPILED.relativize(file).toString().replace('\\', '/');
                classes.put(name.substring(0, name.length() - ".class".length()), Files.readAllBytes(file));
            }
        }
        // The stand-ins that behave like the host replace their stubs.
        Remapper hostNames = new SimpleRemapper(HOST_NAMES);
        for (String standIn : WITH_BEHAVIOR) {
            if (eventBusJar != null && !standIn.equals("ModLoader")) continue;
            classes.put(HOST_NAMES.get(STAND_INS + standIn), remap(read(STAND_INS + standIn), hostNames));
        }
        classes.put("com/mojang/logging/LogUtils", logUtils());
        if (eventBusJar == null) {
            classes.put("net/minecraftforge/eventbus/api/listener/SubscribeEvent", subscribeAnnotation());
        } else {
            classes.putAll(jarClasses(eventBusJar));
        }
        // Named only in the bridge's other helpers, which reflection over the bridge resolves.
        for (String named : List.of("net/minecraftforge/registries/DeferredRegister",
                "net/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext")) {
            classes.put(named, emptyClass(named));
        }
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                byte[] bytes = classes.get(name.replace('.', '/'));
                loaded = bytes != null ? defineClass(name, bytes, 0, bytes.length) : super.loadClass(name, false);
            }
            if (resolve) resolveClass(loaded);
            return loaded;
        }
    }

    /** Forge's EventBus 7 jar for {@code version} (7.0.1 or 7.0.6): a system property, Prism's libraries, then a local copy. */
    static Path eventBusJar(String version) {
        List<Path> candidates = new java.util.ArrayList<>();
        String configured = System.getProperty("retromod.test.eventbus" + version.replace(".", ""));
        if (configured != null && !configured.isBlank()) candidates.add(Path.of(configured));
        String home = System.getProperty("user.home");
        candidates.add(Path.of(home, "Library/Application Support/PrismLauncher/libraries/net/minecraftforge/eventbus",
                version, "eventbus-" + version + ".jar"));
        if (version.equals("7.0.6")) candidates.add(Path.of(home, ".claude/jobs/3da2a019/tmp/forge263/eventbus-7.0.6.jar"));
        return candidates.stream().filter(Files::isRegularFile).findFirst().orElse(null);
    }

    private static Map<String, byte[]> jarClasses(Path jar) throws IOException {
        Map<String, byte[]> found = new HashMap<>();
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
            for (java.util.zip.ZipEntry entry : java.util.Collections.list(zip.entries())) {
                String name = entry.getName();
                if (!name.endsWith(".class") || name.endsWith("module-info.class") || name.startsWith("META-INF/")) continue;
                found.put(name.substring(0, name.length() - ".class".length()), zip.getInputStream(entry).readAllBytes());
            }
        }
        return found;
    }

    /** Starts a real EventBus 7 group, as FML does before it posts. */
    void startup(Object group) throws Exception {
        type(BUS_GROUP).getMethod("startup").invoke(group);
    }

    /** The event types a real EventBus 7 group has made buses for. */
    java.util.Set<?> busTypes(Object group) throws Exception {
        return ((Map<?, ?>) group.getClass().getMethod("eventBuses").invoke(group)).keySet();
    }

    /** {@code Event.BUS.post(event)} on a host event's own bus, answering whether the post was cancelled. */
    boolean postOnOwnBus(Object event) throws Exception {
        Object bus = event.getClass().getField("BUS").get(null);
        try {
            return (boolean) type(EVENT_BUS).getMethod("post", type("net/minecraftforge/eventbus/internal/Event")).invoke(bus, event);
        } catch (InvocationTargetException failure) {
            throw new AssertionError("post threw", failure.getCause());
        }
    }

    /** Adds a host class, such as a Forge event, before anything loads it. */
    void define(String internalName, byte[] bytes) {
        classes.put(internalName, bytes);
    }

    Class<?> type(String internalName) throws ClassNotFoundException {
        return loadClass(internalName.replace('/', '.'));
    }

    /** {@code BusGroup.DEFAULT}, the game bus's group. */
    Object gameGroup() throws Exception {
        return type(BUS_GROUP).getField("DEFAULT").get(null);
    }

    /** A mod's group, which takes only mod-bus events, as FML builds it. */
    Object modGroup(String modId) throws Exception {
        return type(BUS_GROUP).getMethod("create", String.class, Class.class)
                .invoke(null, modId, type("net/minecraftforge/fml/event/IModBusEvent"));
    }

    /** What the group handed EventBus 7's own registration. */
    @SuppressWarnings("unchecked")
    List<Object> registrations(Object group) throws Exception {
        return (List<Object>) group.getClass().getField("registrations").get(group);
    }

    /** Marks FML's mod loading complete, which starts Forge 1.20.1's game bus. */
    void completeLoading() throws Exception {
        type("net/minecraftforge/fml/ModLoader").getField("complete").setBoolean(null, true);
    }

    /** The bus the stand-in made for the type in the group, or null when none was made. */
    Object existingBus(Object group, Class<?> eventType) throws Exception {
        Class<?> bus = type(EVENT_BUS + "$Bus");
        java.lang.reflect.Field buses = bus.getDeclaredField("BUSES");
        buses.setAccessible(true);
        Map<?, ?> byGroup = (Map<?, ?>) ((Map<?, ?>) buses.get(null)).get(group);
        return byGroup == null ? null : byGroup.get(eventType);
    }

    /**
     * One mod: its own copy of the rebuilt event classes and the bus bridge, relocated under
     * {@code prefix} as the embedder does, and its classes after the transform.
     */
    ClassLoader mod(ClassLoader parent, RetromodTransformer transformer, String prefix, Map<String, byte[]> modClasses) {
        Map<String, byte[]> embedded = new HashMap<>();
        transformer.getSyntheticClasses().forEach((name, bytes) -> {
            if (name.startsWith(RebuiltApiClasses.ROOT + "forge/eventbus/") || name.equals(ForgeEventBusSynthetics.PRI)
                    || LegacyInterfaceEventBases.BASES.containsValue(name)
                    || name.equals(ForgeEventBusSynthetics.NAMESPACES)
                    || name.equals(ForgeEventBusSynthetics.LEB) || name.equals(ForgeEventBusSynthetics.LEB_IMPL)
                    || name.equals(ForgeEventBusSynthetics.RC)) {
                embedded.put(name, bytes);
            }
        });
        Remapper relocation = new Remapper() {
            @Override
            public String map(String internalName) {
                return embedded.containsKey(internalName) ? prefix + internalName : internalName;
            }
        };
        Map<String, byte[]> classes = new HashMap<>();
        embedded.forEach((name, bytes) -> classes.put(prefix + name, remap(bytes, relocation)));
        modClasses.forEach((name, bytes) -> classes.put(name,
                remap(transformer.transformClass(bytes, name + ".class"), relocation)));
        return new LegacyEventRuntimeTest.Loader(parent, classes);
    }

    // ---- calls a translated mod makes on its bus bridge ----

    /** The mod's {@code MinecraftForge.EVENT_BUS}. */
    static Object gameBus(ClassLoader mod) throws Exception {
        return implOf(mod).getField("GAME_BUS").get(null);
    }

    static Object modBus(ClassLoader mod, Object group) throws Exception {
        return implOf(mod).getMethod("of", group.getClass().getInterfaces()[0]).invoke(null, group);
    }

    static Class<?> implOf(ClassLoader mod) throws ClassNotFoundException {
        return mod.loadClass(ForgeEventBusSynthetics.LEB_IMPL.replace('/', '.'));
    }

    static void register(Object bus, Object listener) throws Exception {
        busInterface(bus).getMethod("register", Object.class).invoke(bus, listener);
    }

    static void unregister(Object bus, Object listener) throws Exception {
        busInterface(bus).getMethod("unregister", Object.class).invoke(bus, listener);
    }

    static boolean post(Object bus, Object event) throws Exception {
        try {
            return (boolean) busInterface(bus).getMethod("post", Object.class).invoke(bus, event);
        } catch (InvocationTargetException failure) {
            throw new AssertionError("post threw", failure.getCause());
        }
    }

    /** {@code bus.addListener(EventPriority.priority, receiveCanceled, type, listener)}. */
    static void addListener(ClassLoader mod, Object bus, String priority, boolean receiveCanceled, Class<?> type,
            Consumer<Object> listener) throws Exception {
        Class<?> priorities = mod.loadClass(ForgeEventBusSynthetics.PRI.replace('/', '.'));
        busInterface(bus).getMethod("addListener", priorities, boolean.class, Class.class, Consumer.class)
                .invoke(bus, priorities.getField(priority).get(null), receiveCanceled, type, listener);
    }

    /** The priorities a stand-in bus holds, in the order a post runs them. */
    static Object priorities(Object standInBus) throws Exception {
        return standInBus.getClass().getMethod("priorities").invoke(standInBus);
    }

    static Class<?> busInterface(Object bus) {
        return bus.getClass().getInterfaces()[0];
    }

    static Object create(ClassLoader mod, String name) throws Exception {
        return mod.loadClass(name.replace('/', '.')).getConstructor().newInstance();
    }

    @SuppressWarnings("unchecked")
    static List<String> notes(ClassLoader mod, String listener) throws Exception {
        return (List<String>) mod.loadClass(listener.replace('/', '.')).getField("NOTES").get(null);
    }

    private static byte[] remap(byte[] bytes, Remapper remapper) {
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(bytes).accept(new ClassRemapper(writer, remapper), 0);
        return writer.toByteArray();
    }

    private static byte[] read(String internalName) throws IOException {
        try (InputStream in = EventBus7Host.class.getClassLoader().getResourceAsStream(internalName + ".class")) {
            if (in == null) throw new IOException("missing test class " + internalName);
            return in.readAllBytes();
        }
    }

    private static byte[] emptyClass(String name) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** EventBus 7's {@code @SubscribeEvent}: visible at run time, with a byte priority defaulting to NORMAL. */
    private static byte[] subscribeAnnotation() {
        String name = "net/minecraftforge/eventbus/api/listener/SubscribeEvent";
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE | ACC_ANNOTATION, name, null, "java/lang/Object",
                new String[]{"java/lang/annotation/Annotation"});
        org.objectweb.asm.AnnotationVisitor retention = cw.visitAnnotation("Ljava/lang/annotation/Retention;", true);
        retention.visitEnum("value", "Ljava/lang/annotation/RetentionPolicy;", "RUNTIME");
        retention.visitEnd();
        MethodVisitor priority = cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "priority", "()B", null, null);
        org.objectweb.asm.AnnotationVisitor defaultValue = priority.visitAnnotationDefault();
        defaultValue.visit(null, (byte) 0);
        defaultValue.visitEnd();
        priority.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code LogUtils.getLogger()} over SLF4J, as Minecraft's does. */
    private static byte[] logUtils() {
        String slf4j = com.retromod.util.HostLibraryNames.SLF4J_PACKAGE;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "com/mojang/logging/LogUtils", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "getLogger", "()L" + slf4j + "/Logger;", null, null);
        mv.visitCode();
        mv.visitLdcInsn("Retromod");
        mv.visitMethodInsn(INVOKESTATIC, slf4j + "/LoggerFactory", "getLogger", "(Ljava/lang/String;)L" + slf4j + "/Logger;", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
