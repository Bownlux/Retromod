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
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static com.retromod.shim.forge.NbtHostFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Forge 1.20.1 providers, handlers and NBT calls keep saving and loading through Forge 26.2's registry-taking shape. */
class LegacyNbtSerializableBridgeTest {

    private static final String LEGACY_NBT = LegacyNbtSerializableBridge.LEGACY_NBT;
    private static final String LEGACY_CAPABILITY = LegacyNbtSerializableBridge.LEGACY_CAPABILITY;
    private static final String CONTEXT = LegacyNbtSerializableBridge.CONTEXT;
    private static final String HOST_SAVE = "(" + L_PROVIDER + ")" + L_TAG;
    private static final String HOST_LOAD = "(" + L_PROVIDER + L_TAG + ")V";
    private static final String KEY = "retromod_test:counter";

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
        transformer.setModSourceVersion("1.20.1");
    }

    @AfterEach
    void restore() {
        transformer.setModSourceVersion(null);
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("An old ICapabilitySerializable gains concrete registry-taking methods and Retromod's subinterface")
    void oldProviderGainsTheHostShape() throws Exception {
        byte[] out = transformer.transformClass(oldProvider("mod/Counter", "java/lang/Object", CAP_SERIALIZABLE),
                "mod/Counter.class");
        ClassNode node = node(out);

        assertEquals(List.of(LEGACY_CAPABILITY), node.interfaces);
        assertTrue(node.signature.contains("L" + LEGACY_CAPABILITY + "<" + L_COMPOUND + ">;"),
                "the type argument stays, so the class still reads as a CompoundTag provider: " + node.signature);
        for (String desc : List.of(HOST_SAVE, HOST_LOAD)) {
            MethodNode bridge = method(node, desc.equals(HOST_SAVE) ? "serializeNBT" : "deserializeNBT", desc);
            assertEquals(0, bridge.access & ACC_ABSTRACT, "the class has its own " + desc);
            assertTrue(calls(bridge).contains(CONTEXT + ".enter"), "the bridge makes the registries current");
        }
        assertTrue(calls(method(node, "serializeNBT", "()" + L_COMPOUND)).contains(INBT + ".serializeNBT" + HOST_SAVE),
                "the old call on Forge's interface passes registries");
        assertEquals("", verify(out, loader(mod(Map.of()))));
    }

    @Test
    @DisplayName("A save and load through a Forge 26.2 dispatcher reach the old methods, keep the data, and pass the registries on")
    void roundTripThroughTheDispatcher() throws Exception {
        ClassLoader loader = loader(mod(Map.of("mod/Counter", oldProvider("mod/Counter", "java/lang/Object", CAP_SERIALIZABLE))));
        Object marker = create(loader, COMPOUND);
        Object saving = provider(loader, "mod.Counter", marker);
        Object registries = registries(loader, "save");

        Object saved = call(dispatcher(loader, saving), "serializeNBT", registries);

        Object entry = call(saved, "get", KEY);
        assertSame(marker, call(entry, "get", "value"), "the old serializeNBT wrote the provider's data");
        assertSame(registries, field(field(saving, "inner"), "savedWith"),
                "the old call inside the save got the registries the dispatcher passed");

        Object loading = provider(loader, "mod.Counter", null);
        Object loadRegistries = registries(loader, "load");
        call(dispatcher(loader, loading), "deserializeNBT", loadRegistries, saved);
        assertSame(marker, field(loading, "value"), "the old deserializeNBT read the data back");
        assertSame(loadRegistries, field(field(loading, "inner"), "loadedWith"));
        assertNull(current(loader), "nothing stays current after the save and the load");
    }

    @Test
    @DisplayName("A save nested in another makes its registries current and then restores the outer ones, also when it fails")
    void nestedSavesRestoreTheOuterRegistries() throws Exception {
        ClassLoader loader = loader(mod(Map.of("mod/Counter", oldProvider("mod/Counter", "java/lang/Object", CAP_SERIALIZABLE))));
        Class<?> context = loader.loadClass(CONTEXT.replace('/', '.'));
        Class<?> providerType = loader.loadClass(PROVIDER.replace('/', '.'));
        Object outer = registries(loader, "outer");
        Object inner = registries(loader, "inner");
        Object saving = provider(loader, "mod.Counter", create(loader, COMPOUND));
        Object previous = context.getMethod("enter", providerType).invoke(null, outer);
        try {
            call(dispatcher(loader, saving), "serializeNBT", inner);
            assertSame(inner, field(field(saving, "inner"), "savedWith"));
            assertSame(outer, current(loader), "the outer save's registries are current again");

            setField(field(saving, "inner"), "fail", true);
            assertThrows(IllegalStateException.class, () -> call(dispatcher(loader, saving), "serializeNBT", inner));
            assertSame(outer, current(loader), "a failed save restores them too");
        } finally {
            context.getMethod("exit", Object.class).invoke(null, previous);
        }
        assertNull(current(loader));
    }

    @Test
    @DisplayName("Outside a save an old call takes the running server's registries, and with none it names the call")
    void outsideASaveTheServerAnswers() throws Exception {
        byte[] saver = caller("mod/Saver", "save", "(L" + INBT + ";)" + L_TAG, m -> {
            m.visitVarInsn(ALOAD, 0);
            m.visitMethodInsn(INVOKEINTERFACE, INBT, "serializeNBT", "()" + L_TAG, true);
            m.visitInsn(ARETURN);
        });
        ClassLoader loader = loader(mod(Map.of("mod/Saver", saver)));
        Object recorder = create(loader, RECORDER);
        Method save = loader.loadClass("mod.Saver").getMethod("save", loader.loadClass(INBT.replace('/', '.')));

        IllegalStateException none = assertThrows(IllegalStateException.class, () -> invoke(save, null, recorder));
        assertTrue(none.getMessage().contains("INBTSerializable.serializeNBT() in mod.Saver"), none.getMessage());

        Class<?> frozenType = loader.loadClass("net.minecraft.core.RegistryAccess$Frozen");
        Object frozen = Proxy.newProxyInstance(loader, new Class<?>[]{frozenType}, identity("server registries"));
        Class<?> server = loader.loadClass("net.minecraft.server.MinecraftServer");
        loader.loadClass("net.minecraftforge.server.ServerLifecycleHooks").getField("current")
                .set(null, server.getConstructor(frozenType).newInstance(frozen));
        invoke(save, null, recorder);
        assertSame(frozen, field(recorder, "savedWith"));
    }

    @Test
    @DisplayName("Cataclysm's capability interfaces extend Retromod's, and calls through them link")
    void modInterfacesExtendingTheOldInterfaceLink() throws Exception {
        String iface = "mod/IChargeCapability";
        Map<String, byte[]> classes = new HashMap<>();
        classes.put(iface, modInterface(iface));
        classes.put("mod/ChargeImp", valueHolder("mod/ChargeImp", iface));
        classes.put("mod/ChargeProvider", chargeProvider("mod/ChargeProvider", iface));
        ClassLoader loader = loader(mod(classes));

        assertEquals(List.of(LEGACY_NBT), interfaces(translated(transformer, "", iface, classes.get(iface))));
        Object charge = loader.loadClass("mod.ChargeImp").getConstructor().newInstance();
        Object marker = create(loader, COMPOUND);
        setField(charge, "value", marker);
        Object provider = loader.loadClass("mod.ChargeProvider").getConstructor().newInstance();
        setField(provider, "instance", charge);

        Object saved = call(dispatcher(loader, provider), "serializeNBT", registries(loader, "save"));
        assertSame(marker, call(call(saved, "get", KEY), "get", "value"));
        // Forge's own call on the capability instance, which reaches the old methods through the interface default.
        Method hostSave = loader.loadClass(INBT.replace('/', '.')).getMethod("serializeNBT",
                loader.loadClass(PROVIDER.replace('/', '.')));
        assertSame(marker, call(invoke(hostSave, charge, registries(loader, "direct")), "get", "value"));
    }

    @Test
    @DisplayName("A class already written for registries, or from a mod built for them, is left as it is")
    void newerShapesAreUntouched() {
        byte[] modern = modernProvider("mod/Modern");
        assertEquals(shape(modern), shape(transformer.transformClass(modern, "mod/Modern.class")));

        transformer.setModSourceVersion("1.21.1");
        byte[] old = oldProvider("mod/Counter", "java/lang/Object", CAP_SERIALIZABLE);
        byte[] out = transformer.transformClass(old, "mod/Counter.class");
        assertEquals(List.of(CAP_SERIALIZABLE), interfaces(out));
        assertTrue(node(out).methods.stream().noneMatch(m -> m.desc.startsWith("(" + L_PROVIDER)));
    }

    @Test
    @DisplayName("An ItemStackHandler subclass that is also a provider keeps Forge's interface and gets no bridge to a missing method")
    void handlerProvidersUseTheHandlersOwnSave() throws Exception {
        String name = "mod/LunchBox";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, ITEM_HANDLER, new String[]{CAP_SERIALIZABLE});
        constructor(cw, ITEM_HANDLER);
        cw.visitEnd();
        byte[] out = transformer.transformClass(cw.toByteArray(), name + ".class");
        assertEquals(List.of(CAP_SERIALIZABLE), interfaces(out));
        assertTrue(node(out).methods.stream().noneMatch(m -> m.name.endsWith("NBT")));

        ClassLoader loader = loader(mod(Map.of(name, cw.toByteArray())));
        Object handler = loader.loadClass("mod.LunchBox").getConstructor().newInstance();
        Object items = create(loader, COMPOUND);
        setField(handler, "items", items);
        Object saved = call(dispatcher(loader, handler), "serializeNBT", registries(loader, "save"));
        assertSame(items, call(call(saved, "get", KEY), "get", "Items"));
    }

    @Test
    @DisplayName("Old handler overrides run when Forge saves the handler, and their super calls reach Forge's save once")
    void handlerOverridesRunForHostSaves() throws Exception {
        Map<String, byte[]> classes = Map.of("mod/Crate", oldHandler("mod/Crate"),
                "mod/CrateCap", oldHandler("mod/CrateCap", CAP_SERIALIZABLE));
        ClassNode crate = node(transformer.transformClass(classes.get("mod/Crate"), "mod/Crate.class"));
        assertTrue(calls(method(crate, "serializeNBT", "()" + L_COMPOUND))
                .contains(ITEM_HANDLER + ".serializeNBT(" + L_PROVIDER + ")" + L_COMPOUND));
        method(crate, "serializeNBT", "(" + L_PROVIDER + ")" + L_COMPOUND);
        method(crate, "deserializeNBT", "(" + L_PROVIDER + L_COMPOUND + ")V");
        assertEquals(List.of(CAP_SERIALIZABLE), interfaces(transformer.transformClass(classes.get("mod/CrateCap"), "mod/CrateCap.class")));

        ClassLoader loader = loader(mod(classes));
        for (String name : List.of("mod.Crate", "mod.CrateCap")) {
            Object handler = loader.loadClass(name).getConstructor().newInstance();
            Object extra = create(loader, COMPOUND);
            Object items = create(loader, COMPOUND);
            setField(handler, "extra", extra);
            setField(handler, "items", items);
            Object registries = registries(loader, "save");
            Object saved = call(dispatcher(loader, handler), "serializeNBT", registries);
            Object entry = call(saved, "get", KEY);
            assertSame(extra, call(entry, "get", "extra"), name + "'s own save ran");
            assertSame(items, call(entry, "get", "Items"), name + "'s super save ran");
            assertSame(registries, field(handler, "lastRegistries"));

            Object loading = loader.loadClass(name).getConstructor().newInstance();
            call(dispatcher(loader, loading), "deserializeNBT", registries(loader, "load"), saved);
            assertSame(extra, field(loading, "extra"), name + "'s own load ran");
            assertSame(items, field(loading, "items"), name + "'s super load ran");
        }
    }

    @Test
    @DisplayName("Old handler, entity, energy and interface calls take registries; ItemStack's is left for its own bridge")
    void oldCallSitesPassRegistries() throws Exception {
        String minecart = "net/minecraft/world/entity/vehicle/AbstractMinecart";
        String energy = "net/minecraftforge/energy/EnergyStorage";
        byte[] user = caller("mod/User", "run", "(Ljava/lang/Object;)V", m -> {
            oldCall(m, INVOKEVIRTUAL, ITEM_HANDLER, "serializeNBT", "()" + L_COMPOUND);
            oldCall(m, INVOKEVIRTUAL, ITEM_HANDLER, "deserializeNBT", "(" + L_COMPOUND + ")V");
            oldCall(m, INVOKEVIRTUAL, minecart, "serializeNBT", "()" + L_COMPOUND);
            oldCall(m, INVOKEVIRTUAL, "net/minecraft/world/entity/Entity", "deserializeNBT", "(" + L_COMPOUND + ")V");
            oldCall(m, INVOKEVIRTUAL, energy, "serializeNBT", "()" + L_TAG);
            oldCall(m, INVOKEVIRTUAL, energy, "deserializeNBT", "(" + L_TAG + ")V");
            oldCall(m, INVOKEINTERFACE, CAP_SERIALIZABLE, "deserializeNBT", "(" + L_TAG + ")V");
            oldCall(m, INVOKEVIRTUAL, ITEM_STACK, "serializeNBT", "()" + L_COMPOUND);
            m.visitInsn(RETURN);
        });
        byte[] out = transformer.transformClass(user, "mod/User.class");
        List<String> calls = calls(method(node(out), "run", "(Ljava/lang/Object;)V"));
        List<String> expected = List.of(
                CONTEXT + ".provider", ITEM_HANDLER + ".serializeNBT(" + L_PROVIDER + ")" + L_COMPOUND,
                CONTEXT + ".provider", ITEM_HANDLER + ".deserializeNBT(" + L_PROVIDER + L_COMPOUND + ")V",
                CONTEXT + ".provider", minecart + ".serializeNBT(" + L_PROVIDER + ")" + L_COMPOUND,
                CONTEXT + ".provider", "net/minecraft/world/entity/Entity.deserializeNBT(" + L_PROVIDER + L_COMPOUND + ")V",
                CONTEXT + ".provider", energy + ".serializeNBT" + HOST_SAVE,
                CONTEXT + ".provider", energy + ".deserializeNBT" + HOST_LOAD,
                CONTEXT + ".provider", CAP_SERIALIZABLE + ".deserializeNBT" + HOST_LOAD,
                ITEM_STACK + ".serializeNBT()" + L_COMPOUND);
        assertEquals(expected, calls.stream().map(c -> c.startsWith(CONTEXT) ? CONTEXT + ".provider" : c).toList());
        // A load takes the registries before the tag, so a SWAP follows each lookup there.
        long swaps = node(out).methods.stream().flatMap(m -> java.util.Arrays.stream(m.instructions.toArray()))
                .filter(insn -> insn.getOpcode() == SWAP).count();
        assertEquals(4, swaps);
    }

    @Test
    @DisplayName("A class that reaches two jars' copies of the interface has one save of its own and no clash")
    void twoCopiesMeetInOneClass() throws Exception {
        String lib = "com/retromod/embedded/lib/";
        String mod = "com/retromod/embedded/mod/";
        Map<String, byte[]> classes = new HashMap<>(host());
        classes.putAll(rebuilt(transformer, lib));
        classes.putAll(rebuilt(transformer, mod));
        classes.put("lib/Base", translated(transformer, lib, "lib/Base", oldProvider("lib/Base", "java/lang/Object", CAP_SERIALIZABLE)));
        byte[] sub = translated(transformer, mod, "mod/Sub", relister("mod/Sub", "lib/Base"));
        classes.put("mod/Sub", sub);
        ClassLoader loader = loader(classes);
        assertEquals(List.of(mod + LEGACY_CAPABILITY), interfaces(sub));

        Object marker = create(loader, COMPOUND);
        Object provider = provider(loader, "mod.Sub", marker);
        Object registries = registries(loader, "save");
        Object saved = call(dispatcher(loader, provider), "serializeNBT", registries);
        assertSame(marker, call(call(saved, "get", KEY), "get", "value"));
        // The subclass's bridge is in one jar's copy and the old save it runs, the base's, in the other's.
        assertSame(registries, field(field(provider, "inner"), "savedWith"),
                "the registries one jar's copy made current reach the other's");
    }

    @Test
    @DisplayName("The bridge registers only where Forge's INBTSerializable takes registries, and never on NeoForge")
    void registrationFollowsTheHost() {
        transformer.clearRedirectsForTesting();
        LegacyNbtSerializableBridge.register(transformer, hostWith("()" + L_TAG));
        assertFalse(transformer.getSyntheticClasses().containsKey(LEGACY_NBT), "Forge 1.20.1's own interface");

        LegacyNbtSerializableBridge.register(transformer, hostWith(HOST_SAVE));
        assertTrue(transformer.getSyntheticClasses().containsKey(LEGACY_NBT), "Forge 26.2's interface");

        transformer.clearRedirectsForTesting();
        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(true);
        LegacyNbtSerializableBridge.register(transformer, hostWith(HOST_SAVE));
        assertFalse(transformer.getSyntheticClasses().containsKey(LEGACY_NBT), "NeoForge");
    }

    @Test
    @DisplayName("A provider extending a class in Minecraft's entity packages that is no entity is bridged like any provider")
    void entityPackageClassesThatAreNoEntitiesAreBridged() throws Exception {
        byte[] goal = oldProvider("mod/GoalData", GOAL, INBT);
        ClassNode out = node(transformer.transformClass(goal, "mod/GoalData.class"));
        assertEquals(List.of(LEGACY_NBT), out.interfaces);
        method(out, "serializeNBT", HOST_SAVE);
        method(out, "deserializeNBT", HOST_LOAD);

        ClassLoader loader = loader(mod(Map.of("mod/GoalData", goal)));
        Object marker = create(loader, COMPOUND);
        Object saving = provider(loader, "mod.GoalData", marker);
        Object saved = call(dispatcher(loader, saving), "serializeNBT", registries(loader, "save"));
        assertSame(marker, call(call(saved, "get", KEY), "get", "value"));
    }

    @Test
    @DisplayName("A mod entity that lists the interface again but keeps the entity's own save keeps Forge's interface")
    void entitiesWithoutTheirOwnPairKeepForgesInterface() {
        String mob = "mod/ModMob";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, mob, null, "net/minecraft/world/entity/Mob", new String[]{CAP_SERIALIZABLE});
        MethodVisitor load = cw.visitMethod(ACC_PUBLIC, "deserializeNBT", "(" + L_COMPOUND + ")V", null, null);
        load.visitCode();
        load.visitInsn(RETURN);
        load.visitMaxs(0, 0);
        load.visitEnd();
        cw.visitEnd();
        ClassNode out = node(transformer.transformClass(cw.toByteArray(), mob + ".class"));
        assertEquals(List.of(CAP_SERIALIZABLE), out.interfaces);
        assertTrue(out.methods.stream().noneMatch(m -> m.desc.equals(HOST_SAVE) || m.desc.equals(HOST_LOAD)),
                "no erased bridge to an old save the entity never had");
        method(out, "deserializeNBT", "(" + L_PROVIDER + L_COMPOUND + ")V");
    }

    @Test
    @DisplayName("An old EnergyStorage override runs when Forge saves the storage, and its super calls reach Forge's save")
    void energyOverridesRunForHostSaves() throws Exception {
        ClassLoader loader = loader(mod(Map.of("mod/Battery", battery("mod/Battery"))));
        Object battery = loader.loadClass("mod.Battery").getConstructor().newInstance();
        Object stored = create(loader, COMPOUND);
        Object extra = create(loader, COMPOUND);
        setField(battery, "stored", stored);
        setField(battery, "extra", extra);
        Object registries = registries(loader, "save");

        Object saved = call(call(dispatcher(loader, battery), "serializeNBT", registries), "get", KEY);
        assertSame(stored, call(saved, "get", "base"), "the super save ran");
        assertSame(extra, call(saved, "get", "extra"), "the old override ran");
        assertSame(registries, field(battery, "lastRegistries"));

        Object loading = loader.loadClass("mod.Battery").getConstructor().newInstance();
        Object wrapper = create(loader, COMPOUND);
        call(wrapper, "put", KEY, saved);
        call(dispatcher(loader, loading), "deserializeNBT", registries(loader, "load"), wrapper);
        assertSame(stored, field(loading, "stored"));
        assertSame(extra, field(loading, "extra"));
    }

    /**
     * {@code class name extends EnergyStorage} overriding the old pair: the save wraps the super save as {@code base}
     * beside {@code extra}, and the load hands {@code base} to the super load.
     */
    private static byte[] battery(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, ENERGY_STORAGE, null);
        cw.visitField(ACC_PUBLIC, "extra", L_TAG, null, null).visitEnd();
        constructor(cw, ENERGY_STORAGE);
        MethodVisitor save = cw.visitMethod(ACC_PUBLIC, "serializeNBT", "()" + L_TAG, null, null);
        save.visitCode();
        save.visitTypeInsn(NEW, COMPOUND);
        save.visitInsn(DUP);
        save.visitMethodInsn(INVOKESPECIAL, COMPOUND, "<init>", "()V", false);
        save.visitInsn(DUP);
        save.visitLdcInsn("base");
        save.visitVarInsn(ALOAD, 0);
        save.visitMethodInsn(INVOKESPECIAL, ENERGY_STORAGE, "serializeNBT", "()" + L_TAG, false);
        save.visitMethodInsn(INVOKEVIRTUAL, COMPOUND, "put", "(Ljava/lang/String;" + L_TAG + ")" + L_TAG, false);
        save.visitInsn(POP);
        save.visitInsn(DUP);
        save.visitLdcInsn("extra");
        save.visitVarInsn(ALOAD, 0);
        save.visitFieldInsn(GETFIELD, name, "extra", L_TAG);
        save.visitMethodInsn(INVOKEVIRTUAL, COMPOUND, "put", "(Ljava/lang/String;" + L_TAG + ")" + L_TAG, false);
        save.visitInsn(POP);
        save.visitInsn(ARETURN);
        save.visitMaxs(0, 0);
        save.visitEnd();
        MethodVisitor load = cw.visitMethod(ACC_PUBLIC, "deserializeNBT", "(" + L_TAG + ")V", null, null);
        load.visitCode();
        load.visitVarInsn(ALOAD, 0);
        load.visitVarInsn(ALOAD, 1);
        load.visitTypeInsn(CHECKCAST, COMPOUND);
        load.visitLdcInsn("base");
        load.visitMethodInsn(INVOKEVIRTUAL, COMPOUND, "get", "(Ljava/lang/String;)" + L_TAG, false);
        load.visitMethodInsn(INVOKESPECIAL, ENERGY_STORAGE, "deserializeNBT", "(" + L_TAG + ")V", false);
        load.visitVarInsn(ALOAD, 0);
        load.visitVarInsn(ALOAD, 1);
        load.visitTypeInsn(CHECKCAST, COMPOUND);
        load.visitLdcInsn("extra");
        load.visitMethodInsn(INVOKEVIRTUAL, COMPOUND, "get", "(Ljava/lang/String;)" + L_TAG, false);
        load.visitFieldInsn(PUTFIELD, name, "extra", L_TAG);
        load.visitInsn(RETURN);
        load.visitMaxs(0, 0);
        load.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    // ---- a translated mod on the test host ----

    /** The host stand-ins, the rebuilt classes, and the given Forge 1.20.1 classes after the transform. */
    private Map<String, byte[]> mod(Map<String, byte[]> modClasses) throws Exception {
        Map<String, byte[]> classes = new HashMap<>(host());
        classes.putAll(rebuilt(transformer, ""));
        modClasses.forEach((name, bytes) -> classes.put(name, translated(transformer, "", name, bytes)));
        return classes;
    }

    /** A provider of the old shape holding {@code value}, with a fresh recording host object as its inner part. */
    private static Object provider(ClassLoader loader, String name, Object value) throws Exception {
        Object provider = loader.loadClass(name).getConstructor().newInstance();
        setField(provider, "value", value);
        setField(provider, "inner", create(loader, RECORDER));
        return provider;
    }

    /** A Forge 26.2 dispatcher holding one provider under {@link #KEY}. */
    private static Object dispatcher(ClassLoader loader, Object provider) throws Exception {
        Class<?> serializable = loader.loadClass(INBT.replace('/', '.'));
        Object writers = Array.newInstance(serializable, 1);
        Array.set(writers, 0, provider);
        return loader.loadClass(DISPATCHER.replace('/', '.')).getConstructor(String[].class, writers.getClass())
                .newInstance(new String[]{KEY}, writers);
    }

    private static Object registries(ClassLoader loader, String name) throws Exception {
        return Proxy.newProxyInstance(loader, new Class<?>[]{loader.loadClass(PROVIDER.replace('/', '.'))}, identity(name));
    }

    private static java.lang.reflect.InvocationHandler identity(String name) {
        return (proxy, method, args) -> switch (method.getName()) {
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> name;
            default -> null;
        };
    }

    private static Object current(ClassLoader loader) throws Exception {
        return loader.loadClass(CONTEXT.replace('/', '.')).getMethod("current").invoke(null);
    }

    private static Object create(ClassLoader loader, String internalName) throws Exception {
        return loader.loadClass(internalName.replace('/', '.')).getConstructor().newInstance();
    }

    /** Calls the public method {@code name} that takes as many arguments as given, rethrowing what it throws. */
    private static Object call(Object target, String name, Object... args) throws Exception {
        for (Method method : target.getClass().getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == args.length && !method.isBridge()
                    && accepts(method, args)) {
                return invoke(method, target, args);
            }
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }

    private static boolean accepts(Method method, Object[] args) {
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < args.length; i++) {
            if (args[i] != null && !types[i].isInstance(args[i])) return false;
        }
        return true;
    }

    private static Object invoke(Method method, Object target, Object... args) throws Exception {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            throw new AssertionError(failure.getCause());
        }
    }

    private static Object field(Object target, String name) throws Exception {
        return target.getClass().getField(name).get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        target.getClass().getField(name).set(target, value);
    }

    // ---- Forge 1.20.1 class shapes ----

    /** {@code interface name extends INBTSerializable<CompoundTag>}, declaring nothing, as Cataclysm's are. */
    private static byte[] modInterface(String name) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, name,
                "Ljava/lang/Object;L" + INBT + "<" + L_COMPOUND + ">;", "java/lang/Object", new String[]{INBT});
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A capability instance implementing {@code iface} with the old CompoundTag pair over a {@code value} field. */
    private static byte[] valueHolder(String name, String iface) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", new String[]{iface});
        cw.visitField(ACC_PUBLIC, "value", L_TAG, null, null).visitEnd();
        constructor(cw, "java/lang/Object");
        MethodVisitor save = cw.visitMethod(ACC_PUBLIC, "serializeNBT", "()" + L_COMPOUND, null, null);
        save.visitCode();
        save.visitTypeInsn(NEW, COMPOUND);
        save.visitInsn(DUP);
        save.visitMethodInsn(INVOKESPECIAL, COMPOUND, "<init>", "()V", false);
        save.visitInsn(DUP);
        save.visitLdcInsn("value");
        save.visitVarInsn(ALOAD, 0);
        save.visitFieldInsn(GETFIELD, name, "value", L_TAG);
        save.visitMethodInsn(INVOKEVIRTUAL, COMPOUND, "put", "(Ljava/lang/String;" + L_TAG + ")" + L_TAG, false);
        save.visitInsn(POP);
        save.visitInsn(ARETURN);
        save.visitMaxs(0, 0);
        save.visitEnd();
        MethodVisitor load = cw.visitMethod(ACC_PUBLIC, "deserializeNBT", "(" + L_COMPOUND + ")V", null, null);
        load.visitCode();
        load.visitVarInsn(ALOAD, 0);
        load.visitVarInsn(ALOAD, 1);
        load.visitLdcInsn("value");
        load.visitMethodInsn(INVOKEVIRTUAL, COMPOUND, "get", "(Ljava/lang/String;)" + L_TAG, false);
        load.visitFieldInsn(PUTFIELD, name, "value", L_TAG);
        load.visitInsn(RETURN);
        load.visitMaxs(0, 0);
        load.visitEnd();
        erasedBridges(cw, name);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Cataclysm's provider: its old save and load go through the mod's own capability interface. */
    private static byte[] chargeProvider(String name, String iface) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", new String[]{CAP_PROVIDER, CAP_SERIALIZABLE});
        cw.visitField(ACC_PUBLIC, "instance", "L" + iface + ";", null, null).visitEnd();
        constructor(cw, "java/lang/Object");
        MethodVisitor save = cw.visitMethod(ACC_PUBLIC, "serializeNBT", "()" + L_COMPOUND, null, null);
        save.visitCode();
        save.visitVarInsn(ALOAD, 0);
        save.visitFieldInsn(GETFIELD, name, "instance", "L" + iface + ";");
        save.visitMethodInsn(INVOKEINTERFACE, iface, "serializeNBT", "()" + L_TAG, true);
        save.visitTypeInsn(CHECKCAST, COMPOUND);
        save.visitInsn(ARETURN);
        save.visitMaxs(0, 0);
        save.visitEnd();
        MethodVisitor load = cw.visitMethod(ACC_PUBLIC, "deserializeNBT", "(" + L_COMPOUND + ")V", null, null);
        load.visitCode();
        load.visitVarInsn(ALOAD, 0);
        load.visitFieldInsn(GETFIELD, name, "instance", "L" + iface + ";");
        load.visitVarInsn(ALOAD, 1);
        load.visitMethodInsn(INVOKEINTERFACE, iface, "deserializeNBT", "(" + L_TAG + ")V", true);
        load.visitInsn(RETURN);
        load.visitMaxs(0, 0);
        load.visitEnd();
        erasedBridges(cw, name);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code class name extends superName implements ICapabilitySerializable<CompoundTag>}, adding nothing else. */
    private static byte[] relister(String name, String superName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, "L" + superName + ";L" + CAP_SERIALIZABLE + "<" + L_COMPOUND + ">;",
                superName, new String[]{CAP_SERIALIZABLE});
        constructor(cw, superName);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A provider as a Forge 1.21.1 mod writes it: the registry-taking pair and javac's erased bridges. */
    private static byte[] modernProvider(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", new String[]{CAP_SERIALIZABLE});
        constructor(cw, "java/lang/Object");
        for (boolean erased : new boolean[]{false, true}) {
            String tag = erased ? L_TAG : L_COMPOUND;
            MethodVisitor save = cw.visitMethod(ACC_PUBLIC, "serializeNBT", "(" + L_PROVIDER + ")" + tag, null, null);
            save.visitCode();
            save.visitInsn(ACONST_NULL);
            save.visitInsn(ARETURN);
            save.visitMaxs(0, 0);
            save.visitEnd();
            MethodVisitor load = cw.visitMethod(ACC_PUBLIC, "deserializeNBT", "(" + L_PROVIDER + tag + ")V", null, null);
            load.visitCode();
            load.visitInsn(RETURN);
            load.visitMaxs(0, 0);
            load.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    // Pushes a receiver and, for a load, a tag, makes the old call, and drops what it returns.
    private static void oldCall(MethodVisitor m, int opcode, String owner, String name, String desc) {
        m.visitVarInsn(ALOAD, 0);
        m.visitTypeInsn(CHECKCAST, owner);
        if (name.equals("deserializeNBT")) m.visitInsn(ACONST_NULL);
        m.visitMethodInsn(opcode, owner, name, desc, opcode == INVOKEINTERFACE);
        if (name.equals("serializeNBT")) m.visitInsn(POP);
    }

    /** A Forge host whose INBTSerializable declares the given save. */
    private static java.util.function.Function<String, ClassNode> hostWith(String saveDesc) {
        return name -> {
            if (name.equals(ForgeHostShape.ANCHOR)) return classNode(name);
            if (!name.equals(INBT)) return null;
            ClassNode iface = classNode(name);
            iface.methods.add(new MethodNode(ACC_PUBLIC | ACC_ABSTRACT, "serializeNBT", saveDesc, null, null));
            return iface;
        };
    }

    private static ClassNode classNode(String name) {
        ClassNode node = new ClassNode();
        node.name = name;
        return node;
    }

    // ---- reading results ----

    private static Set<String> shape(byte[] bytes) {
        ClassNode node = node(bytes);
        Set<String> shape = new TreeSet<>(node.interfaces);
        node.methods.forEach(m -> shape.add(m.name + m.desc));
        return shape;
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst()
                .orElseThrow(() -> new AssertionError(node.name + " has no " + name + desc));
    }

    private static List<String> calls(MethodNode method) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) {
                calls.add(call.owner.equals(CONTEXT) ? CONTEXT + "." + call.name : call.owner + "." + call.name + call.desc);
            }
        }
        return calls;
    }
}
