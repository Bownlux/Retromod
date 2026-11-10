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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.retromod.shim.forge.CapabilityHostFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Forge 1.20.1's RegisterCapabilitiesEvent on Forge 26.2, which removed it, and the two capability members it dropped. */
class LegacyRegisterCapabilitiesEventTest {

    private static final String A = "com/retromod/embedded/a/";
    private static final String B = "com/retromod/embedded/b/";
    private static final String STAND_IN = LegacyCapabilityEventBridge.STAND_IN;
    private static final String REGISTRATION = LegacyCapabilityEventBridge.REGISTRATION;
    private static final String SLOTS = "retromod.shared.legacy-capabilities.";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @BeforeEach
    void forge262() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        transformer.clearRedirectsForTesting();
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        clearSharedSlots();
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
        clearSharedSlots();
    }

    @Test
    @DisplayName("The event, INSTANCE and areCapsCompatible are bridged on a 26.2-shaped host and left alone on a 1.21.11-shaped one")
    void onlyWhereTheHostLacksThem() {
        transformer.clearRedirectsForTesting();
        LegacyCapabilityEventBridge.register(transformer, host(Map.of(
                ForgeHostShape.ANCHOR, List.of(), CAPABILITY_MANAGER, List.of(), "net/minecraftforge/registries/NewRegistryEvent",
                List.of(), CAPABILITY_PROVIDER, List.of(), ATTACH + "$Entities", List.of())));
        assertEquals(STAND_IN, transformer.getClassRedirects().get(REGISTER_EVENT));
        assertTrue(fieldRedirected(), "CapabilityManager.INSTANCE");
        assertEquals(2, compatRedirects(), "both areCapsCompatible overloads");

        transformer.clearRedirectsForTesting();
        LegacyCapabilityEventBridge.register(transformer, host(Map.of(
                ForgeHostShape.ANCHOR, List.of(), REGISTER_EVENT, List.of(), CAPABILITY_MANAGER, List.of("INSTANCE"),
                "net/minecraftforge/registries/NewRegistryEvent", List.of(), CAPABILITY_PROVIDER,
                List.of("areCapsCompatible(L" + CAPABILITY_PROVIDER + ";)Z"), ATTACH + "$Entities", List.of())));
        assertNull(transformer.getClassRedirects().get(REGISTER_EVENT), "Forge 1.21.11 still posts its own event");
        assertFalse(fieldRedirected());
        assertEquals(0, compatRedirects());
    }

    @Test
    @DisplayName("The stand-in is a final MutableEvent that implements IModBusEvent, with the old constructor and register")
    void standInShape() {
        ClassNode standIn = node(transformer.getSyntheticClasses().get(STAND_IN));
        assertEquals("net/minecraftforge/eventbus/api/event/MutableEvent", standIn.superName);
        assertEquals(List.of("net/minecraftforge/fml/event/IModBusEvent"), standIn.interfaces);
        assertTrue((standIn.access & ACC_FINAL) != 0);
        assertTrue(standIn.methods.stream().anyMatch(m -> m.name.equals("<init>") && m.desc.equals("()V")));
        assertTrue(standIn.methods.stream().anyMatch(m -> m.name.equals("register") && m.desc.equals("(Ljava/lang/Class;)V")));
    }

    @Test
    @DisplayName("register(Class) registers a capability once, skips an @AutoRegisterCapability type, and keeps the internal name")
    void registerMarksOnce() throws Exception {
        EventBus7Host host = new EventBus7Host();
        defineCapabilityHost(host, true);
        ClassLoader mod = mod(host, transformer, A, Map.of("mod/ICounter", capabilityInterface("mod/ICounter", false),
                "mod/IAuto", capabilityInterface("mod/IAuto", true)));
        for (int event = 0; event < 2; event++) {
            Object registering = copy(mod, A, STAND_IN).getConstructor().newInstance();
            register(registering, mod.loadClass("mod.ICounter"));
            register(registering, mod.loadClass("mod.IAuto"));
        }
        assertEquals(List.of("mod/ICounter"), registered(host), "Forge registers the annotated type itself, a moment later");
    }

    @Test
    @DisplayName("Without the package-private registrar, register(Class) changes nothing and throws nothing")
    void missingRegistrarIsHarmless() throws Exception {
        EventBus7Host host = new EventBus7Host();
        defineCapabilityHost(host, false);
        ClassLoader mod = mod(host, transformer, A, Map.of("mod/ICounter", capabilityInterface("mod/ICounter", false)));
        Object registering = copy(mod, A, STAND_IN).getConstructor().newInstance();
        assertDoesNotThrow(() -> register(registering, mod.loadClass("mod.ICounter")));
    }

    @ParameterizedTest(name = "mod order {0}")
    @ValueSource(strings = {"moda,plain,modb,minecraft", "modb,minecraft,plain,moda"})
    @DisplayName("NewRegistryEvent posts every jar's event to each listening mod, in mod order, once")
    void postsInModOrder(String order) throws Exception {
        EventBus7Host host = new EventBus7Host();
        defineCapabilityHost(host, true);
        ClassLoader modA = mod(host, transformer, A, Map.of("moda/ICounter", capabilityInterface("moda/ICounter", false)));
        ClassLoader modB = mod(host, transformer, B, Map.of());
        Map<String, Object> groups = Map.of("moda", host.modGroup("moda"), "modb", host.modGroup("modb"),
                "plain", host.modGroup("plain"));
        Map<String, Object> containers = new java.util.HashMap<>();
        for (String id : List.of("moda", "modb", "plain", "minecraft")) containers.put(id, container(host, id, groups.get(id)));
        List<Object> mods = modList(host);
        for (String id : order.split(",")) mods.add(containers.get(id));

        // Each jar's @Mod constructor starts the registration; a second @Mod in jar A adds nothing.
        watch(host, modA, A, containers.get("moda"));
        watch(host, modB, B, containers.get("modb"));
        watch(host, modA, A, containers.get("moda"));

        List<String> notes = new ArrayList<>();
        Class<?> counter = modA.loadClass("moda.ICounter");
        listen(host, groups.get("moda"), copy(modA, A, STAND_IN), event -> {
            notes.add("a on moda");
            register(event, counter);
        });
        // A library's listener sits on its user's group, with the library's own copy of the event.
        listen(host, groups.get("moda"), copy(modB, B, STAND_IN), event -> notes.add("b on moda"));
        listen(host, groups.get("modb"), copy(modB, B, STAND_IN), event -> notes.add("b on modb"));

        Class<?> newRegistries = host.type("net/minecraftforge/registries/NewRegistryEvent");
        Object bus = newRegistries.getField("BUS").get(null);
        assertEquals(List.of((byte) -127), EventBus7Host.priorities(bus), "one copy listens for all of them, at LOWEST");
        // An old mod's own NewRegistryEvent listener, added at NORMAL by a constructor that ran after the first watch.
        bus.getClass().getMethod("addListener", byte.class, Consumer.class)
                .invoke(bus, (byte) 0, (Consumer<Object>) event -> notes.add("new registries"));
        post(bus, newRegistries.getConstructor().newInstance());

        // Forge 1.20.1 finished every NewRegistryEvent listener before any RegisterCapabilitiesEvent listener ran.
        List<String> expected = order.startsWith("moda")
                ? List.of("new registries", "a on moda", "b on moda", "b on modb")
                : List.of("new registries", "b on modb", "b on moda", "a on moda");
        assertEquals(expected, notes);
        assertEquals(List.of("moda/ICounter"), registered(host));
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("areCapsCompatible answers true and CapabilityManager.INSTANCE links")
    void smallRemovalsLink(String target) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = target;
        transformer.clearRedirectsForTesting();
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        EventBus7Host host = new EventBus7Host();
        defineAttachEvents(host);
        defineCapabilityHost(host, true);
        ClassNode transformed = node(transformer.transformClass(caller(), "mod/Caller.class"));
        for (MethodNode method : transformed.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                assertFalse(insn instanceof MethodInsnNode call && call.name.equals("areCapsCompatible")
                        && call.owner.equals(ITEM_STACK), "the removed ItemStack member is not called");
                assertFalse(insn instanceof FieldInsnNode read && read.name.equals("INSTANCE"), "INSTANCE is not read");
            }
        }

        ClassLoader mod = mod(host, transformer, A, Map.of("mod/Caller", caller()));
        Class<?> caller = mod.loadClass("mod.Caller");
        Object stack = create(host, ITEM_STACK);
        Object provider = create(host, CAPABILITY_PROVIDER);
        assertEquals(true, caller.getMethod("compare", stack.getClass(), provider.getClass()).invoke(null, stack, provider));
        assertTrue(host.type(CAPABILITY_MANAGER).isInstance(caller.getMethod("manager").invoke(null)));
    }

    // ---- helpers ----

    private static void clearSharedSlots() {
        System.getProperties().keySet().removeIf(key -> key.toString().startsWith(SLOTS));
    }

    private boolean fieldRedirected() {
        return transformer.getFieldRedirects().keySet().stream()
                .anyMatch(key -> key.owner().equals(CAPABILITY_MANAGER) && key.name().equals("INSTANCE"));
    }

    private long compatRedirects() {
        return transformer.getMethodRedirects().keySet().stream()
                .filter(key -> key.owner().equals(ITEM_STACK) && key.name().equals("areCapsCompatible")).count();
    }

    /** A host that has each class named, each with the members listed as name or name plus descriptor. */
    private static Function<String, ClassNode> host(Map<String, List<String>> classes) {
        return name -> {
            List<String> members = classes.get(name);
            if (members == null) return null;
            ClassNode node = new ClassNode();
            node.name = name;
            for (String member : members) {
                int paren = member.indexOf('(');
                if (paren < 0) {
                    node.fields.add(new FieldNode(ACC_PUBLIC | ACC_STATIC, member, "Ljava/lang/Object;", null, null));
                } else {
                    node.methods.add(new MethodNode(ACC_PUBLIC, member.substring(0, paren), member.substring(paren), null, null));
                }
            }
            return node;
        };
    }

    private static Object container(EventBus7Host host, String modId, Object group) throws Exception {
        Class<?> busGroup = host.type(EventBus7Host.BUS_GROUP);
        return host.type("net/minecraftforge/fml/ModContainer").getConstructor(String.class, busGroup)
                .newInstance(modId, group);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> modList(EventBus7Host host) throws Exception {
        return (List<Object>) host.type("net/minecraftforge/fml/ModList").getField("MODS").get(null);
    }

    /** What the @Mod constructor's HEAD call does while the mod constructs. */
    private static void watch(EventBus7Host host, ClassLoader mod, String prefix, Object container) throws Exception {
        host.type("net/minecraftforge/fml/ModLoadingContext").getField("active").set(null, container);
        copy(mod, prefix, REGISTRATION).getMethod("watch").invoke(null);
    }

    private static void listen(EventBus7Host host, Object group, Class<?> event, Consumer<Object> listener) throws Exception {
        Class<?> busGroup = host.type(EventBus7Host.BUS_GROUP);
        Object bus = host.type("net/minecraftforge/fml/event/IModBusEvent").getMethod("getBus", busGroup, Class.class)
                .invoke(null, group, event);
        bus.getClass().getMethod("addListener", byte.class, Consumer.class).invoke(bus, (byte) 0, listener);
    }

    private static void register(Object event, Class<?> capability) {
        try {
            event.getClass().getMethod("register", Class.class).invoke(event, capability);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("register threw", failure.getCause());
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> registered(EventBus7Host host) throws Exception {
        return (List<String>) host.type(CAPABILITY_MANAGER).getField("REGISTERED").get(null);
    }

    private static void post(Object bus, Object event) throws Exception {
        for (java.lang.reflect.Method method : bus.getClass().getMethods()) {
            if (method.getName().equals("post") && method.getParameterCount() == 1) {
                method.invoke(bus, event);
                return;
            }
        }
        fail("the stand-in bus has no post");
    }

    /** {@code interface name}, annotated {@code @AutoRegisterCapability} when asked. */
    private static byte[] capabilityInterface(String name, boolean autoRegistered) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, name, null, "java/lang/Object", null);
        if (autoRegistered) {
            cw.visitAnnotation("Lnet/minecraftforge/common/capabilities/AutoRegisterCapability;", true).visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code stack.areCapsCompatible(provider)} and a read of {@code CapabilityManager.INSTANCE}, as Forge 1.20.1 mods make them. */
    private static byte[] caller() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/Caller", null, "java/lang/Object", null);
        MethodVisitor compare = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "compare",
                "(L" + ITEM_STACK + ";L" + CAPABILITY_PROVIDER + ";)Z", null, null);
        compare.visitCode();
        compare.visitVarInsn(ALOAD, 0);
        compare.visitVarInsn(ALOAD, 1);
        compare.visitMethodInsn(INVOKEVIRTUAL, ITEM_STACK, "areCapsCompatible", "(L" + CAPABILITY_PROVIDER + ";)Z", false);
        compare.visitInsn(IRETURN);
        compare.visitMaxs(0, 0);
        compare.visitEnd();
        MethodVisitor manager = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "manager", "()Ljava/lang/Object;", null, null);
        manager.visitCode();
        manager.visitFieldInsn(GETSTATIC, CAPABILITY_MANAGER, "INSTANCE", "L" + CAPABILITY_MANAGER + ";");
        manager.visitInsn(ARETURN);
        manager.visitMaxs(0, 0);
        manager.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
