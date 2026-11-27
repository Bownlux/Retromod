/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodTransformer.MethodKey;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.forge.shieldhost.DamageContainer;
import com.retromod.shim.forge.shieldhost.DamageSource;
import com.retromod.shim.forge.shieldhost.FloatShieldEvent;
import com.retromod.shim.forge.shieldhost.ICancellableEvent;
import com.retromod.shim.forge.shieldhost.IntShieldEvent;
import com.retromod.shim.forge.shieldhost.LivingEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Forge 1.20.1's ShieldBlockEvent runs on NeoForge's LivingShieldBlockEvent with Forge's meaning (#415). */
class LegacyShieldBlockEventBridgeTest {

    private static final String FORGE = LegacyShieldBlockEventBridge.FORGE_EVENT;
    private static final String NEO = LegacyShieldBlockEventBridge.NEO_EVENT;
    private static final String ENTITY = "net/minecraft/world/entity/LivingEntity";
    private static final String SOURCE = "net/minecraft/world/damagesource/DamageSource";

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("The host's own event decides the shape; a host without it gets nothing")
    void readsTheHostShape() throws Exception {
        LegacyShieldBlockEventBridge.Shape floatShape = LegacyShieldBlockEventBridge.shapeOf(hostNode(FloatShieldEvent.class));
        assertEquals(new LegacyShieldBlockEventBridge.Shape('F', true, false), floatShape, "NeoForge 1.21.1");
        LegacyShieldBlockEventBridge.Shape intShape = LegacyShieldBlockEventBridge.shapeOf(hostNode(IntShieldEvent.class));
        assertEquals(new LegacyShieldBlockEventBridge.Shape('I', false, true), intShape, "NeoForge 1.21.11 to 26.3");
        RetromodVersion.TARGET_MC_VERSION = "1.20.1";
        assertNull(LegacyShieldBlockEventBridge.shapeOf(null), "a 1.20.1 host still has Forge's event");
        RetromodVersion.TARGET_MC_VERSION = "1.21.5";
        assertNull(LegacyShieldBlockEventBridge.shapeOf(null), "an unchecked version is read at run time");
    }

    @Test
    @DisplayName("A Forge listener's calls become helper calls and its event type becomes NeoForge's")
    void rewritesTheListener() throws Exception {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyShieldBlockEventBridge.register(transformer, name -> name.equals(NEO) ? hostNodeOrNull(FloatShieldEvent.class) : null);

        assertEquals(NEO, transformer.getClassRedirects().get(FORGE));
        assertNotNull(transformer.getMethodRedirects().get(new MethodKey(NEO, "setShieldTakesDamage", "(Z)V")));

        ClassNode listener = new ClassNode();
        new ClassReader(transformer.transformClass(forgeListener(), "test/mod/ShieldListener.class")).accept(listener, 0);
        List<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode insn : listener.methods.stream().filter(m -> m.name.equals("onShield")).findFirst().orElseThrow().instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call);
        }
        assertEquals(List.of("getOriginalBlock", "getDamageSource", "setShieldTakesDamage", "setCanceled"),
                calls.stream().map(c -> c.name).toList(), "the class pass first guards the listener against unblocked hits");
        assertEquals(NEO, calls.get(1).owner, "a member NeoForge kept stays a plain call");
        for (MethodInsnNode call : calls.subList(2, 4)) {
            assertEquals(Opcodes.INVOKESTATIC, call.getOpcode(), call.name);
            assertEquals(LegacyShieldBlockEventBridge.HELPER, call.owner, call.name);
        }
        assertEquals("(L" + NEO + ";)V", listener.methods.stream().filter(m -> m.name.equals("onShield"))
                .findFirst().orElseThrow().desc);
    }

    @Test
    @DisplayName("On NeoForge 1.21.1 a Forge cancel stops the block and Forge's shield flag sets the damage")
    void floatHostKeepsForgeMeaning() throws Exception {
        ClassLoader host = host(FloatShieldEvent.class, new LegacyShieldBlockEventBridge.Shape('F', true, false));
        Class<?> helper = host.loadClass(LegacyShieldBlockEventBridge.HELPER.replace('/', '.'));
        Object event = create(helper, host, 6.0F);
        Class<?> eventType = event.getClass();

        assertTrue((Boolean) call(helper, "shieldTakesDamage", event), "the shield takes the hit by default");
        call(helper, "setShieldTakesDamage", event, false);
        assertEquals(0.0F, (Float) eventType.getMethod("shieldDamage").invoke(event));
        assertFalse((Boolean) call(helper, "shieldTakesDamage", event));
        call(helper, "setShieldTakesDamage", event, true);
        assertEquals(6.0F, (Float) eventType.getMethod("shieldDamage").invoke(event), "true gives back the host's amount");
        assertEquals(6.0F, (Float) eventType.getMethod("getBlockedDamage").invoke(event), "the blocked damage is untouched");

        assertFalse((Boolean) call(helper, "isCanceled", event));
        call(helper, "setCanceled", event, true);
        assertFalse((Boolean) eventType.getMethod("getBlocked").invoke(event), "NeoForge reads only setBlocked");
        assertTrue((Boolean) eventType.getMethod("isCanceled").invoke(event), "later listeners are skipped as on Forge");
        assertTrue((Boolean) call(helper, "isCanceled", event));
        call(helper, "setCanceled", event, false);
        assertTrue((Boolean) eventType.getMethod("getBlocked").invoke(event));
    }

    @Test
    @DisplayName("On NeoForge 1.21.11 to 26.3 the int damage uses -1 for the game's own amount")
    void intHostKeepsForgeMeaning() throws Exception {
        ClassLoader host = host(IntShieldEvent.class, new LegacyShieldBlockEventBridge.Shape('I', false, true));
        Class<?> helper = host.loadClass(LegacyShieldBlockEventBridge.HELPER.replace('/', '.'));
        Object event = create(helper, host, 4.0F);
        Class<?> eventType = event.getClass();

        assertEquals(4.0F, eventType.getField("blockedAmount").get(event), "the constructor gets the blocked amount");
        assertTrue((Boolean) call(helper, "shieldTakesDamage", event));
        call(helper, "setShieldTakesDamage", event, false);
        assertEquals(0, eventType.getMethod("shieldDamage").invoke(event));
        call(helper, "setShieldTakesDamage", event, true);
        assertEquals(-1, eventType.getMethod("shieldDamage").invoke(event));
        call(helper, "setCanceled", event, true);
        assertFalse((Boolean) eventType.getMethod("getBlocked").invoke(event));
        assertTrue((Boolean) call(helper, "isCanceled", event));
    }

    @Test
    @DisplayName("A Forge listener returns early for a hit the shield did not block, as Forge never posted one")
    void forgeListenerSkipsUnblockedHits() throws Exception {
        byte[] guarded = LegacyShieldBlockEventBridge.guardListeners(neoListener("test/mod/ForgeShield", false), "1.20.1");
        assertSame(guarded, LegacyShieldBlockEventBridge.guardListeners(guarded, "1.20.1"), "a guarded listener is left as it is");
        ClassLoader host = host(FloatShieldEvent.class, new LegacyShieldBlockEventBridge.Shape('F', true, false),
                Map.of("test.mod.ForgeShield", guarded));
        Class<?> listener = host.loadClass("test.mod.ForgeShield");

        Object unblocked = event(host, false);
        listener.getMethod("onShield", unblocked.getClass()).invoke(null, unblocked);
        assertFalse((Boolean) unblocked.getClass().getMethod("isCanceled").invoke(unblocked), "the listener did not run");

        Object blocked = event(host, true);
        listener.getMethod("onShield", blocked.getClass()).invoke(null, blocked);
        assertTrue((Boolean) blocked.getClass().getMethod("isCanceled").invoke(blocked), "a blocked hit still reaches it");
    }

    @Test
    @DisplayName("A NeoForge mod's own shield listener still sees every hit")
    void neoForgeListenerIsNotGuarded() {
        byte[] neoForge = neoListener("test/mod/NeoShield", true);
        assertSame(neoForge, LegacyShieldBlockEventBridge.guardListeners(neoForge, "1.21.1"));
        assertSame(neoForge, LegacyShieldBlockEventBridge.guardListeners(neoForge, null), "an unknown version is not guessed");
        byte[] forgeCalls = neoListener("test/mod/UnknownShield", false);
        assertNotSame(forgeCalls, LegacyShieldBlockEventBridge.guardListeners(forgeCalls, null),
                "a call to a Forge-only member marks a migrated listener");
    }

    /** A migrated listener that cancels the block through the helper, or a NeoForge one that calls the event itself. */
    private static byte[] neoListener(String name, boolean nativeNeoForge) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "onShield", "(L" + NEO + ";)V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitInsn(Opcodes.ICONST_1);
        if (nativeNeoForge) {
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NEO, "setCanceled", "(Z)V", false);
        } else {
            m.visitMethodInsn(Opcodes.INVOKESTATIC, LegacyShieldBlockEventBridge.HELPER, "setCanceled", "(L" + NEO + ";Z)V", false);
        }
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitInsn(Opcodes.FCONST_0);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NEO, "setShieldDamage", "(F)V", false);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A host event for a 5 damage hit, blocked or not. */
    private static Object event(ClassLoader host, boolean blocked) throws Exception {
        Class<?> entity = host.loadClass(ENTITY.replace('/', '.'));
        Class<?> source = host.loadClass(SOURCE.replace('/', '.'));
        Class<?> container = host.loadClass(LegacyShieldBlockEventBridge.CONTAINER.replace('/', '.'));
        Class<?> event = host.loadClass(NEO.replace('/', '.'));
        Object damage = container.getConstructor(source, float.class).newInstance(source.getConstructor().newInstance(), 5.0F);
        return event.getConstructor(entity, container, boolean.class).newInstance(entity.getConstructor().newInstance(), damage, blocked);
    }

    private static Object create(Class<?> helper, ClassLoader host, float amount) throws Exception {
        Class<?> entity = host.loadClass(ENTITY.replace('/', '.'));
        Class<?> source = host.loadClass(SOURCE.replace('/', '.'));
        Method create = helper.getMethod("create", entity, source, float.class);
        return create.invoke(null, entity.getConstructor().newInstance(), source.getConstructor().newInstance(), amount);
    }

    private static Object call(Class<?> helper, String name, Object event, Object... args) throws Exception {
        for (Method method : helper.getMethods()) {
            if (method.getName().equals(name)) {
                Object[] all = new Object[args.length + 1];
                all[0] = event;
                System.arraycopy(args, 0, all, 1, args.length);
                return method.invoke(null, all);
            }
        }
        throw new NoSuchMethodException(name);
    }

    /** The stand-ins under their host names and the helper written for {@code shape}, in their own loader. */
    private static ClassLoader host(Class<?> event, LegacyShieldBlockEventBridge.Shape shape) throws Exception {
        return host(event, shape, Map.of());
    }

    private static ClassLoader host(Class<?> event, LegacyShieldBlockEventBridge.Shape shape, Map<String, byte[]> extra) throws Exception {
        SimpleRemapper names = names(event);
        Map<String, byte[]> classes = new HashMap<>(extra);
        for (Class<?> type : List.of(event, LivingEntity.class, DamageSource.class, DamageContainer.class, ICancellableEvent.class)) {
            classes.put(names.map(internal(type)).replace('/', '.'), remap(type, names));
        }
        classes.put(LegacyShieldBlockEventBridge.HELPER.replace('/', '.'), LegacyShieldBlockEventBridge.generateHelper(shape));
        return new ClassLoader(ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }

    private static SimpleRemapper names(Class<?> event) {
        Map<String, String> names = new HashMap<>();
        names.put(internal(event), NEO);
        names.put(internal(LivingEntity.class), ENTITY);
        names.put(internal(DamageSource.class), SOURCE);
        names.put(internal(DamageContainer.class), LegacyShieldBlockEventBridge.CONTAINER);
        names.put(internal(ICancellableEvent.class), LegacyShieldBlockEventBridge.CANCELLABLE);
        return new SimpleRemapper(names);
    }

    private static ClassNode hostNode(Class<?> event) throws Exception {
        ClassNode node = new ClassNode();
        new ClassReader(remap(event, names(event))).accept(node, ClassReader.SKIP_CODE);
        return node;
    }

    private static ClassNode hostNodeOrNull(Class<?> event) {
        try {
            return hostNode(event);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] remap(Class<?> type, SimpleRemapper names) throws Exception {
        try (InputStream in = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            ClassWriter writer = new ClassWriter(0);
            new ClassReader(in.readAllBytes()).accept(new ClassRemapper(writer, names), 0);
            return writer.toByteArray();
        }
    }

    private static String internal(Class<?> type) {
        return type.getName().replace('.', '/');
    }

    /** A Forge 1.20.1 listener: it reads the source, spares the shield and cancels the block. */
    private static byte[] forgeListener() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/mod/ShieldListener", null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "onShield", "(L" + FORGE + ";)V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FORGE, "getDamageSource", "()L" + SOURCE + ";", false);
        m.visitInsn(Opcodes.POP);
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitInsn(Opcodes.ICONST_0);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FORGE, "setShieldTakesDamage", "(Z)V", false);
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitInsn(Opcodes.ICONST_1);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FORGE, "setCanceled", "(Z)V", false);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
