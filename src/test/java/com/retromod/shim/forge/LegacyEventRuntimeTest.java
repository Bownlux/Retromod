/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.common.RebuiltApiClasses;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** The rebuilt event base keeps Forge 1.20.1's rules, and each mod's embedded copy answers for the others. */
class LegacyEventRuntimeTest {

    private static final String EVENT = LegacyEventBaseAdapter.EVENT;
    private static final String CANCELABLE = "Lnet/minecraftforge/eventbus/api/Cancelable;";
    private static final String HAS_RESULT = "L" + EVENT + "$HasResult;";
    private static final String MUTABLE_EVENT = "net/minecraftforge/eventbus/api/event/MutableEvent";
    private static final String RESULT = LegacyEventBaseBridge.RESULT;
    private static final Path STUBS = Path.of("target", "legacyapi-classes");
    private static final String SECOND = "com/retromod/embedded/second/";

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
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("A @Cancelable event cancels, one that is not refuses, and @HasResult events report a result")
    void cancelAndResultRules() throws Exception {
        ClassLoader mod = modLoader(host(), "");
        Object cancelable = create(mod, "mod.CancelEvent");
        Object plain = create(mod, "mod.PlainEvent");
        Object withResult = create(mod, "mod.ResultEvent");

        assertEquals(true, call(cancelable, "isCancelable"));
        call(cancelable, "setCanceled", true);
        assertEquals(true, call(cancelable, "isCanceled"));
        InvocationTargetException refused = assertThrows(InvocationTargetException.class,
                () -> call(plain, "setCanceled", true));
        assertInstanceOf(UnsupportedOperationException.class, refused.getCause());

        assertEquals(true, call(withResult, "hasResult"));
        assertEquals(false, call(plain, "hasResult"));
        assertEquals("DEFAULT", ((Enum<?>) call(withResult, "getResult")).name(), "a new event starts at DEFAULT");
        call(withResult, "setResult", hostResult(mod, "DENY"));
        assertEquals("DENY", ((Enum<?>) call(withResult, "getResult")).name());
    }

    @Test
    @DisplayName("The phase only moves to a lower priority, and delivery skips a cancelled event unless the listener asks")
    void phaseAndDelivery() throws Exception {
        ClassLoader mod = modLoader(host(), "");
        Object event = create(mod, "mod.CancelEvent");
        assertNull(call(event, "getPhase"));

        call(event, "setPhase", priority(mod, "", "HIGH"));
        assertThrows(InvocationTargetException.class, () -> call(event, "setPhase", priority(mod, "", "HIGHEST")));
        assertThrows(InvocationTargetException.class, () -> call(event, "setPhase", priority(mod, "", "HIGH")));
        call(event, "setPhase", priority(mod, "", "LOW"));
        assertEquals("LOW", ((Enum<?>) call(event, "getPhase")).name());

        Object fresh = create(mod, "mod.CancelEvent");
        Method deliver = fresh.getClass().getMethod("retromod$deliver", boolean.class, byte.class);
        assertEquals(true, deliver.invoke(fresh, false, (byte) 64));
        assertEquals("HIGH", ((Enum<?>) call(fresh, "getPhase")).name(), "the game bus moves the phase as it delivers");
        assertEquals(true, deliver.invoke(fresh, false, (byte) 127));
        assertEquals("HIGH", ((Enum<?>) call(fresh, "getPhase")).name(), "the phase never moves back up");
        call(fresh, "setCanceled", true);
        assertEquals(false, deliver.invoke(fresh, false, Byte.MIN_VALUE), "a cancelled event skips the listener");
        assertEquals(true, deliver.invoke(fresh, true, Byte.MIN_VALUE), "unless it receives cancelled events");
    }

    @Test
    @DisplayName("One mod's LegacyEventAccess answers for another mod's embedded copy and for host events")
    void accessCrossesCopies() throws Exception {
        ClassLoader host = host();
        ClassLoader first = modLoader(host, "");
        ClassLoader second = modLoader(host, "com/retromod/embedded/second/");
        Class<?> access = first.loadClass(LegacyEventBaseAdapter.ACCESS.replace('/', '.'));
        Object theirs = create(second, "mod.CancelEvent");
        Object theirResult = create(second, "mod.ResultEvent");

        assertEquals(true, invokeStatic(access, "isEvent", theirs));
        assertEquals(false, invokeStatic(access, "isEvent", "text"));
        assertEquals(true, ((Class<?>) invokeStatic(access, "eventRoot")).isInstance(theirs));
        assertEquals(true, invokeStatic(access, "isCancelable", theirs));
        invokeStatic(access, "setCanceled", theirs, true);
        assertEquals(true, call(theirs, "isCanceled"), "the other copy's own flag is set");
        assertEquals(true, invokeStatic(access, "hasResult", theirResult), "the other copy's marker is found by name");
        invokeStatic(access, "setResult", theirResult, hostResult(host, "ALLOW"));
        assertEquals("ALLOW", ((Enum<?>) call(theirResult, "getResult")).name());

        // The other copy has its own priority enum, so the phase crosses by constant name.
        invokeStatic(access, "setPhase", theirs, priority(first, "", "LOW"));
        assertEquals("LOW", ((Enum<?>) call(theirs, "getPhase")).name());
        Object phase = invokeStatic(access, "getPhase", theirs);
        assertEquals(first.loadClass(ForgeEventBusSynthetics.PRI.replace('/', '.')), phase.getClass());

        Object generic = second.loadClass("mod.RegistryEvent").getConstructor(Class.class).newInstance(String.class);
        assertEquals(String.class, invokeStatic(access, "getGenericType", generic));

        Object hostCancellable = create(host, "host.CancellableEvent");
        assertEquals(true, invokeStatic(access, "isCancelable", hostCancellable), "its bus takes a cancel");
        assertEquals(false, invokeStatic(access, "isCanceled", hostCancellable), "EventBus 7 events keep no flag");
        // Outside a listener the cancel is kept beside the event, for the mod's own reads.
        invokeStatic(access, "setCanceled", hostCancellable, true);
        assertEquals(true, invokeStatic(access, "isCanceled", hostCancellable));
        // A host event answers hasResult as Forge 1.20.1 did for it, whatever result it has today.
        assertEquals(true, invokeStatic(access, "hasResult", create(host, "net.minecraftforge.event.entity.player.BonemealEvent")));
        Object hostWithResult = create(host, "host.ResultEvent");
        assertEquals(false, invokeStatic(access, "hasResult", hostWithResult), "not an event Forge 1.20.1 had");
        assertEquals(false, invokeStatic(access, "hasResult", hostCancellable));
        invokeStatic(access, "setResult", hostWithResult, hostResult(host, "ALLOW"));
        assertEquals("ALLOW", ((Enum<?>) call(hostWithResult, "getResult")).name(), "a host result is the event's own");
        // One without a result keeps it beside the event, where a later read finds it.
        invokeStatic(access, "setResult", hostCancellable, hostResult(host, "DENY"));
        assertEquals("DENY", ((Enum<?>) invokeStatic(access, "getResult", hostCancellable)).name());
    }

    @Test
    @DisplayName("Another copy's cancelable event that is not public still cancels through LegacyEventAccess")
    void packagePrivateCancelableFromAnotherCopy() throws Exception {
        ClassLoader host = host();
        Class<?> access = accessOf(modLoader(host, ""));
        Object theirs = createHidden(modLoader(host, SECOND), "mod.HiddenCancelEvent");

        assertEquals(true, invokeStatic(access, "isCancelable", theirs), "its own isCancelable override answers");
        assertEquals(false, invokeStatic(access, "isCanceled", theirs));
        invokeStatic(access, "setCanceled", theirs, true);
        assertEquals(true, invokeStatic(access, "isCanceled", theirs));
        assertNull(invokeStatic(access, "getGenericType", theirs), "a plain legacy event has no generic type");
    }

    @Test
    @DisplayName("Another copy's generic event that is not public reports the type its own override returns")
    void packagePrivateGenericOverrideFromAnotherCopy() throws Exception {
        ClassLoader host = host();
        Class<?> access = accessOf(modLoader(host, ""));
        Object theirs = createHidden(modLoader(host, SECOND), "mod.HiddenRegistryEvent", String.class);

        assertEquals(Integer.class, invokeStatic(access, "getGenericType", theirs),
                "the subclass's getGenericType override wins over the type given to the constructor");
        assertEquals(false, invokeStatic(access, "isCancelable", theirs));
        assertEquals(false, invokeStatic(access, "isCanceled", theirs));
    }

    @Test
    @DisplayName("Another copy's event whose public getter names a missing class still answers")
    void getterNamingMissingClassFromAnotherCopy() throws Exception {
        ClassLoader host = host();
        Class<?> access = accessOf(modLoader(host, ""));
        Object theirs = create(modLoader(host, SECOND), "mod.GetterEvent");

        assertEquals(true, invokeStatic(access, "isCancelable", theirs));
        invokeStatic(access, "setCanceled", theirs, true);
        assertEquals(true, invokeStatic(access, "isCanceled", theirs));
        assertEquals(false, invokeStatic(access, "hasResult", theirs));
        assertNull(invokeStatic(access, "getGenericType", theirs));
    }

    @Test
    @DisplayName("LegacyEvent calls only the priority members the generated LegacyEventPriority declares")
    void priorityMembersMatchTheGeneratedEnum() throws Exception {
        ClassLoader mod = modLoader(host(), "");
        Class<?> priority = mod.loadClass(ForgeEventBusSynthetics.PRI.replace('/', '.'));
        assertEquals(byte.class, priority.getMethod("bits").getReturnType());
        assertTrue(priority.getMethod("values").getReturnType().isArray());
        assertEquals(127, (byte) priority.getMethod("bits").invoke(priority(mod, "", "HIGHEST")));
    }

    // ---- loaders ----

    /** Defines its own classes first and asks its parent for the rest, like a mod's class loader. */
    static final class Loader extends ClassLoader {
        private final Map<String, byte[]> own;

        Loader(ClassLoader parent, Map<String, byte[]> own) {
            super(parent);
            this.own = own;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    byte[] bytes = own.get(name.replace('.', '/'));
                    loaded = bytes != null ? defineClass(name, bytes, 0, bytes.length) : super.loadClass(name, false);
                }
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }
    }

    /** The compiled stubs stand in for Forge 26.2, with three host events built on them. */
    private ClassLoader host() throws IOException {
        Map<String, byte[]> classes = new HashMap<>();
        try (Stream<Path> files = Files.walk(STUBS.resolve("net"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                String name = STUBS.relativize(file).toString().replace('\\', '/');
                classes.put(name.substring(0, name.length() - ".class".length()), Files.readAllBytes(file));
            }
        }
        classes.put("host/CancellableEvent", hostEvent("host/CancellableEvent",
                "net/minecraftforge/eventbus/api/event/characteristic/Cancellable"));
        classes.put("host/ResultEvent", hostEvent("host/ResultEvent", "net/minecraftforge/common/util/HasResult"));
        classes.put("net/minecraftforge/event/entity/player/BonemealEvent", hostEvent(
                "net/minecraftforge/event/entity/player/BonemealEvent", "net/minecraftforge/common/util/HasResult"));
        return new Loader(getClass().getClassLoader(), classes);
    }

    /** One mod: its translated events and its own copy of the rebuilt classes, under {@code prefix} like the embedder. */
    private ClassLoader modLoader(ClassLoader host, String prefix) {
        Map<String, byte[]> embedded = new HashMap<>();
        transformer.getSyntheticClasses().forEach((name, bytes) -> {
            if (name.startsWith(RebuiltApiClasses.ROOT + "forge/eventbus/") || name.equals(ForgeEventBusSynthetics.PRI)) {
                embedded.put(name, bytes);
            }
        });
        Map<String, byte[]> classes = new HashMap<>();
        embedded.forEach((name, bytes) -> classes.put(prefix + name, relocate(bytes, prefix, embedded)));
        Map<String, byte[]> events = Map.of(
                "mod/CancelEvent", LegacyEventBaseAdapterTest.annotated("mod/CancelEvent", CANCELABLE, false),
                "mod/ResultEvent", LegacyEventBaseAdapterTest.annotated("mod/ResultEvent", HAS_RESULT, false),
                "mod/PlainEvent", LegacyEventBaseAdapterTest.eventClass("mod/PlainEvent", EVENT, null),
                "mod/RegistryEvent", LegacyEventBaseAdapterTest.eventClass("mod/RegistryEvent",
                        LegacyEventBaseAdapter.GENERIC_EVENT, null),
                "mod/HiddenCancelEvent", edit(LegacyEventBaseAdapterTest.annotated("mod/HiddenCancelEvent",
                        CANCELABLE, false), LegacyEventRuntimeTest::hide),
                "mod/HiddenRegistryEvent", edit(LegacyEventBaseAdapterTest.eventClass("mod/HiddenRegistryEvent",
                        LegacyEventBaseAdapter.GENERIC_EVENT, null), node -> {
                    hide(node);
                    addGetter(node, "getGenericType", "Ljava/lang/reflect/Type;", Type.getType(Integer.class));
                }),
                "mod/GetterEvent", edit(LegacyEventBaseAdapterTest.annotated("mod/GetterEvent", CANCELABLE, false),
                        node -> addGetter(node, "gone", "Lmissing/Gone;", null)));
        events.forEach((name, bytes) -> classes.put(name,
                relocate(transformer.transformClass(bytes, name + ".class"), prefix, embedded)));
        return new Loader(host, classes);
    }

    private static byte[] relocate(byte[] bytes, String prefix, Map<String, byte[]> embedded) {
        if (prefix.isEmpty()) return bytes;
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(bytes).accept(new ClassRemapper(writer, new Remapper() {
            @Override
            public String map(String internalName) {
                return embedded.containsKey(internalName) ? prefix + internalName : internalName;
            }
        }), 0);
        return writer.toByteArray();
    }

    /** The old event class with {@code change} applied to it. */
    private static byte[] edit(byte[] bytes, Consumer<ClassNode> change) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        change.accept(node);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** Makes the class package-private, as a mod's own internal event can be. */
    private static void hide(ClassNode node) {
        node.access &= ~ACC_PUBLIC;
    }

    /** Adds {@code public returnType name()} returning the constant, or null when it is null. */
    private static void addGetter(ClassNode node, String name, String returnType, Object constant) {
        MethodNode getter = new MethodNode(ACC_PUBLIC, name, "()" + returnType, null, null);
        getter.instructions.add(constant == null ? new InsnNode(ACONST_NULL) : new LdcInsnNode(constant));
        getter.instructions.add(new InsnNode(ARETURN));
        node.methods.add(getter);
    }

    /** A host event class: {@code extends MutableEvent implements marker}, with a result field when it has one. */
    private static byte[] hostEvent(String name, String marker) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, MUTABLE_EVENT, new String[]{marker});
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, MUTABLE_EVENT, "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        if (marker.endsWith("HasResult")) {
            String desc = "L" + RESULT + ";";
            cw.visitField(ACC_PRIVATE, "result", desc, null, null).visitEnd();
            MethodVisitor get = cw.visitMethod(ACC_PUBLIC, "getResult", "()" + desc, null, null);
            get.visitCode();
            get.visitVarInsn(ALOAD, 0);
            get.visitFieldInsn(GETFIELD, name, "result", desc);
            get.visitInsn(ARETURN);
            get.visitMaxs(0, 0);
            get.visitEnd();
            MethodVisitor set = cw.visitMethod(ACC_PUBLIC, "setResult", "(" + desc + ")V", null, null);
            set.visitCode();
            set.visitVarInsn(ALOAD, 0);
            set.visitVarInsn(ALOAD, 1);
            set.visitFieldInsn(PUTFIELD, name, "result", desc);
            set.visitInsn(RETURN);
            set.visitMaxs(0, 0);
            set.visitEnd();
        }
        // A Forge 26.2 cancellable event posts on a CancellableEventBus of its own.
        if (marker.endsWith("Cancellable")) {
            cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "BUS",
                    "Lnet/minecraftforge/eventbus/api/bus/CancellableEventBus;", null, null).visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static Object create(ClassLoader loader, String name) throws Exception {
        return loader.loadClass(name).getConstructor().newInstance();
    }

    /** Builds a class that is not public, as code in its own package would. */
    private static Object createHidden(ClassLoader loader, String name, Object... arguments) throws Exception {
        Constructor<?> constructor = loader.loadClass(name).getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return constructor.newInstance(arguments);
    }

    private static Class<?> accessOf(ClassLoader mod) throws ClassNotFoundException {
        return mod.loadClass(LegacyEventBaseAdapter.ACCESS.replace('/', '.'));
    }

    private static Object hostResult(ClassLoader loader, String constant) throws Exception {
        return loader.loadClass(RESULT.replace('/', '.')).getField(constant).get(null);
    }

    private static Object priority(ClassLoader loader, String prefix, String constant) throws Exception {
        return loader.loadClass((prefix + ForgeEventBusSynthetics.PRI).replace('/', '.')).getField(constant).get(null);
    }

    /** Calls the event's public method of that name, as a mod's own code would. */
    private static Object call(Object target, String name, Object... arguments) throws Exception {
        for (Method method : target.getClass().getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == arguments.length) {
                return method.invoke(target, arguments);
            }
        }
        throw new AssertionError(target.getClass().getName() + " has no " + name);
    }

    private static Object invokeStatic(Class<?> owner, String name, Object... arguments) throws Exception {
        for (Method method : owner.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == arguments.length) {
                return method.invoke(null, arguments);
            }
        }
        throw new AssertionError(owner.getName() + " has no " + name);
    }
}
