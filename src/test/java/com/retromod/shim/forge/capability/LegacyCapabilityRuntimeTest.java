/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.forge.capability;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Runs the Forge capability stand-ins the way a migrated mod sees them (#306, #308): under Forge's
 * class names, beside stub Minecraft and NeoForge classes, with a game bus that delivers the attach
 * event to the mod's listener.
 */
class LegacyCapabilityRuntimeTest {

    private RetromodTransformer transformer;
    private ModWorld world;

    @BeforeEach
    void setUp() throws Exception {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyCapabilitySynthetics.register(transformer);
        world = new ModWorld(transformer.getSyntheticClasses());
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    void registersTheCapabilityApiUnderForgeNames() {
        Map<String, byte[]> synthetics = transformer.getSyntheticClasses();
        for (String forgeName : List.of(LegacyCapabilitySynthetics.CAPABILITY,
                LegacyCapabilitySynthetics.LAZY_OPTIONAL, LegacyCapabilitySynthetics.PROVIDER,
                LegacyCapabilitySynthetics.SERIALIZABLE, LegacyCapabilitySynthetics.NBT_SERIALIZABLE,
                LegacyCapabilitySynthetics.ATTACH_EVENT,
                "net/minecraftforge/common/capabilities/CapabilityToken",
                "net/minecraftforge/common/capabilities/CapabilityManager",
                "net/minecraftforge/common/capabilities/ForgeCapabilities")) {
            assertTrue(synthetics.containsKey(forgeName), forgeName + " must be supplied as a synthetic");
        }
        assertEquals("java/util/function/Supplier",
                transformer.getClassRedirects().get("net/minecraftforge/common/util/NonNullSupplier"),
                "LazyOptional.of(NonNullSupplier) must resolve to the stand-in's Supplier form");
    }

    @Test
    void attachedProviderAnswersAnEntityQuery() throws Exception {
        Object capability = world.capabilityFor("test.ModData");
        Object entity = world.newEntity();
        List<Object> attachedTo = new ArrayList<>();
        world.listen(event -> {
            Object holder = world.call(event, "getObject");
            attachedTo.add(holder);
            world.call(event, "addCapability", world.newResourceLocation(),
                    world.provider(capability, "stored data"));
        });

        Object first = world.get(entity, capability);
        Object second = world.get(entity, capability);

        assertEquals(Optional.of("stored data"), world.call(first, "resolve"),
                "the provider the listener attached must answer the entity's query");
        assertEquals(Optional.of("stored data"), world.call(second, "resolve"));
        assertEquals(List.of(entity), attachedTo, "the attach event fires once per holder");
    }

    @Test
    void otherCapabilitiesAndNonHoldersStayEmpty() throws Exception {
        Object wanted = world.capabilityFor("test.ModData");
        Object other = world.capabilityFor("test.OtherData");
        Object entity = world.newEntity();
        int[] posts = {0};
        world.listen(event -> {
            posts[0]++;
            world.call(event, "addCapability", world.newResourceLocation(), world.provider(wanted, "x"));
        });

        assertEquals(Optional.empty(), world.call(world.get(entity, other), "resolve"),
                "a provider answers only for its own capability");
        assertEquals(Optional.empty(), world.call(world.get(new Object(), wanted), "resolve"),
                "an object Forge never made a capability holder gets nothing");
        assertEquals(1, posts[0], "only the entity is offered to attach listeners");
    }

    @Test
    void sameTokenTypeResolvesToOneCapability() throws Exception {
        assertSame(world.capabilityFor("test.ModData"), world.capabilityFor("test.ModData"),
                "every new CapabilityToken<ModData>(){} in a mod must name the same capability");
        assertNotSame(world.capabilityFor("test.ModData"), world.capabilityFor("test.OtherData"));
    }

    @Test
    void lazyOptionalResolvesOnceAndInvalidates() throws Exception {
        Class<?> lazy = world.load(LegacyCapabilitySynthetics.LAZY_OPTIONAL);
        int[] calls = {0};
        Supplier<Object> supplier = () -> {
            calls[0]++;
            return "value";
        };
        Object optional = lazy.getMethod("of", Supplier.class).invoke(null, supplier);
        assertEquals("value", world.call(optional, "orElse", "fallback"));
        assertEquals("value", world.call(optional, "orElse", "fallback"));
        assertEquals(1, calls[0], "the supplier is resolved once and cached, as on Forge");
        world.call(optional, "invalidate");
        assertEquals("fallback", world.call(optional, "orElse", "fallback"));
        assertFalse((Boolean) world.call(optional, "isPresent"));
    }

    /** A class loader holding the stand-ins plus stub Minecraft and NeoForge classes. */
    public static final class ModWorld extends ClassLoader {
        static final List<Consumer<Object>> LISTENERS = new ArrayList<>();
        private final Map<String, byte[]> classes = new HashMap<>();

        ModWorld(Map<String, byte[]> synthetics) {
            super(ModWorld.class.getClassLoader());
            LISTENERS.clear();
            classes.putAll(synthetics);
            classes.put("net/neoforged/bus/api/Event", stubClass("net/neoforged/bus/api/Event", ACC_PROTECTED));
            classes.put("net/minecraft/core/Direction", stubClass("net/minecraft/core/Direction", ACC_PUBLIC));
            classes.put("net/minecraft/nbt/Tag", stubClass("net/minecraft/nbt/Tag", ACC_PUBLIC));
            classes.put("net/minecraft/resources/ResourceLocation",
                    stubClass("net/minecraft/resources/ResourceLocation", ACC_PUBLIC));
            classes.put("net/minecraft/world/entity/Entity", stubClass("net/minecraft/world/entity/Entity", ACC_PUBLIC));
            classes.put("test/TestBus", testBus());
            classes.put("net/neoforged/neoforge/common/NeoForge", neoForge());
        }

        void listen(ThrowingConsumer listener) {
            LISTENERS.add(event -> {
                try {
                    listener.accept(event);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        }

        Class<?> load(String internalName) throws ClassNotFoundException {
            return loadClass(internalName.replace('/', '.'));
        }

        Object capabilityFor(String typeName) throws Exception {
            Class<?> token = defineToken(typeName);
            Object instance = token.getDeclaredConstructor().newInstance();
            return load("net/minecraftforge/common/capabilities/CapabilityManager")
                    .getMethod("get", load("net/minecraftforge/common/capabilities/CapabilityToken"))
                    .invoke(null, instance);
        }

        Object newEntity() throws Exception {
            return load("net/minecraft/world/entity/Entity").getDeclaredConstructor().newInstance();
        }

        Object newResourceLocation() throws Exception {
            return load("net/minecraft/resources/ResourceLocation").getDeclaredConstructor().newInstance();
        }

        Object provider(Object capability, Object value) throws Exception {
            Class<?> providerType = load(LegacyCapabilitySynthetics.PROVIDER);
            Class<?> lazy = load(LegacyCapabilitySynthetics.LAZY_OPTIONAL);
            Object present = lazy.getMethod("of", Supplier.class).invoke(null, (Supplier<Object>) () -> value);
            Object empty = lazy.getMethod("empty").invoke(null);
            return Proxy.newProxyInstance(this, new Class<?>[]{providerType},
                    (proxy, method, args) -> method.getName().equals("getCapability")
                            ? (args[0] == capability ? present : empty)
                            : method.getName().equals("hashCode") ? System.identityHashCode(proxy)
                            : method.getName().equals("equals") ? proxy == args[0] : "provider");
        }

        Object get(Object holder, Object capability) throws Exception {
            Class<?> runtime = load(LegacyCapabilitySynthetics.RUNTIME);
            Class<?> capabilityType = load(LegacyCapabilitySynthetics.CAPABILITY);
            return runtime.getMethod("get", Object.class, capabilityType, Object.class)
                    .invoke(null, holder, capability, null);
        }

        Object call(Object target, String name, Object... args) throws Exception {
            for (Method method : target.getClass().getMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == args.length) {
                    return method.invoke(target, args);
                }
            }
            throw new NoSuchMethodException(name);
        }

        private Class<?> defineToken(String typeName) {
            String name = "test/Token_" + typeName.replace('.', '_') + "_" + System.nanoTime();
            String token = "net/minecraftforge/common/capabilities/CapabilityToken";
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, ACC_PUBLIC, name, "L" + token + "<L" + typeName.replace('.', '/') + ";>;", token, null);
            MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
            init.visitCode();
            init.visitVarInsn(ALOAD, 0);
            init.visitMethodInsn(INVOKESPECIAL, token, "<init>", "()V", false);
            init.visitInsn(RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();
            cw.visitEnd();
            byte[] bytes = cw.toByteArray();
            return defineClass(name.replace('/', '.'), bytes, 0, bytes.length);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded != null) return loaded;
                byte[] bytes = classes.get(name.replace('.', '/'));
                if (bytes != null) return defineClass(name, bytes, 0, bytes.length);
                return super.loadClass(name, resolve);
            }
        }

        private static byte[] stubClass(String name, int constructorAccess) {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null);
            MethodVisitor init = cw.visitMethod(constructorAccess, "<init>", "()V", null, null);
            init.visitCode();
            init.visitVarInsn(ALOAD, 0);
            init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            init.visitInsn(RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }

        /** {@code class TestBus { public void post(Event e) { ModWorld.deliver(e); } }}. */
        private static byte[] testBus() {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, ACC_PUBLIC, "test/TestBus", null, "java/lang/Object", null);
            MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
            init.visitCode();
            init.visitVarInsn(ALOAD, 0);
            init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            init.visitInsn(RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();
            MethodVisitor post = cw.visitMethod(ACC_PUBLIC, "post", "(Lnet/neoforged/bus/api/Event;)V", null, null);
            post.visitCode();
            post.visitVarInsn(ALOAD, 1);
            post.visitMethodInsn(INVOKESTATIC, "com/retromod/shim/forge/capability/LegacyCapabilityRuntimeTest$ModWorld",
                    "deliver", "(Ljava/lang/Object;)V", false);
            post.visitInsn(RETURN);
            post.visitMaxs(0, 0);
            post.visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }

        /** {@code class NeoForge { public static final Object EVENT_BUS = new TestBus(); }}. */
        private static byte[] neoForge() {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, ACC_PUBLIC, "net/neoforged/neoforge/common/NeoForge", null, "java/lang/Object", null);
            cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "EVENT_BUS", "Ljava/lang/Object;", null, null).visitEnd();
            MethodVisitor clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
            clinit.visitCode();
            clinit.visitTypeInsn(NEW, "test/TestBus");
            clinit.visitInsn(DUP);
            clinit.visitMethodInsn(INVOKESPECIAL, "test/TestBus", "<init>", "()V", false);
            clinit.visitFieldInsn(PUTSTATIC, "net/neoforged/neoforge/common/NeoForge", "EVENT_BUS", "Ljava/lang/Object;");
            clinit.visitInsn(RETURN);
            clinit.visitMaxs(0, 0);
            clinit.visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }

        public static void deliver(Object event) {
            for (Consumer<Object> listener : LISTENERS) listener.accept(event);
        }
    }

    @FunctionalInterface
    interface ThrowingConsumer {
        void accept(Object value) throws Exception;
    }
}
