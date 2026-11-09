/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.common.RebuiltApiClasses;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.commons.SimpleRemapper;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.objectweb.asm.Opcodes.*;

/** Forge 26.2's capability and attach event shapes for tests, on the EventBus 7 test host. */
final class CapabilityHostFixtures {

    static final String ATTACH = "net/minecraftforge/event/AttachCapabilitiesEvent";
    static final String ENTITY = "net/minecraft/world/entity/Entity";
    static final String PLAYER = "net/minecraft/world/entity/player/Player";
    static final String ITEM_STACK = "net/minecraft/world/item/ItemStack";
    static final String BLOCK_ENTITY = "net/minecraft/world/level/block/entity/BlockEntity";
    static final String LEVEL = "net/minecraft/world/level/Level";
    static final String LEVEL_CHUNK = "net/minecraft/world/level/chunk/LevelChunk";
    static final String OLD_SUBSCRIBE = "Lnet/minecraftforge/eventbus/api/SubscribeEvent;";
    static final String OLD_PRIORITY = "Lnet/minecraftforge/eventbus/api/EventPriority;";
    static final String OLD_BUS = "net/minecraftforge/eventbus/api/IEventBus";
    static final String SUBSCRIBE7 = "net.minecraftforge.eventbus.api.listener.SubscribeEvent";
    static final String REGISTER_EVENT = LegacyCapabilityEventBridge.REGISTER_EVENT;
    static final String CAPABILITY_MANAGER = LegacyCapabilityEventBridge.CAPABILITY_MANAGER;
    static final String CAPABILITY_PROVIDER = LegacyCapabilityEventBridge.CAPABILITY_PROVIDER;

    /** Each kind Forge 26.2 fires and the holder it carries. */
    static final Map<String, String> KINDS = Map.of("Entities", ENTITY, "ItemStacks", ITEM_STACK,
            "BlockEntities", BLOCK_ENTITY, "Levels", LEVEL, "LevelChunks", LEVEL_CHUNK);

    private static final String STAND_INS = "com/retromod/shim/forge/caphost/";
    private static final String EB7 = "com/retromod/shim/forge/eb7/";
    // The test stand-ins under the host names they serve; the markers only name a compiled stub.
    private static final Map<String, String> HOST_NAMES = Map.ofEntries(
            Map.entry(EB7 + "BusGroup", EventBus7Host.BUS_GROUP),
            Map.entry(EB7 + "EventBus", EventBus7Host.EVENT_BUS),
            Map.entry(EB7 + "Event", "net/minecraftforge/eventbus/internal/Event"),
            Map.entry(EB7 + "EventListener", "net/minecraftforge/eventbus/api/listener/EventListener"),
            Map.entry(STAND_INS + "InheritableEvent", "net/minecraftforge/eventbus/api/event/InheritableEvent"),
            Map.entry(STAND_INS + "MutableEvent", "net/minecraftforge/eventbus/api/event/MutableEvent"),
            Map.entry(STAND_INS + "Identifier", "net/minecraft/resources/Identifier"),
            Map.entry(STAND_INS + "IModBusEvent", "net/minecraftforge/fml/event/IModBusEvent"),
            Map.entry(STAND_INS + "ModContainer", "net/minecraftforge/fml/ModContainer"),
            Map.entry(STAND_INS + "ModList", "net/minecraftforge/fml/ModList"),
            Map.entry(STAND_INS + "ModLoadingContext", "net/minecraftforge/fml/ModLoadingContext"),
            Map.entry(STAND_INS + "NewRegistryEvent", "net/minecraftforge/registries/NewRegistryEvent"),
            Map.entry(STAND_INS + "Capability", "net/minecraftforge/common/capabilities/Capability"),
            Map.entry(STAND_INS + "CapabilityManager", CAPABILITY_MANAGER),
            Map.entry(STAND_INS + "AutoRegisterCapability", "net/minecraftforge/common/capabilities/AutoRegisterCapability"));
    private static final List<String> DEFINED = List.of("Identifier", "IModBusEvent", "ModContainer", "ModList",
            "ModLoadingContext", "NewRegistryEvent", "Capability", "CapabilityManager", "AutoRegisterCapability");

    private CapabilityHostFixtures() {}

    // ---- host classes ----

    /** The five holders, a Player under Entity, CapabilityProvider, and Forge 26.2's attach event with its five kinds. */
    static void defineAttachEvents(EventBus7Host host) {
        for (String holder : List.of(ENTITY, ITEM_STACK, BLOCK_ENTITY, LEVEL, LEVEL_CHUNK, CAPABILITY_PROVIDER)) {
            host.define(holder, plainClass(holder, "java/lang/Object"));
        }
        host.define(PLAYER, plainClass(PLAYER, ENTITY));
        host.define(ATTACH, attachBase());
        KINDS.forEach((kind, holder) -> host.define(ATTACH + "$" + kind, attachKind(kind, holder)));
    }

    /** FML's mod list and loading context, NewRegistryEvent and the capability manager, as Forge 26.2 shapes them. */
    static void defineCapabilityHost(EventBus7Host host, boolean withRegistrar) throws IOException {
        Remapper names = new SimpleRemapper(HOST_NAMES);
        for (String standIn : DEFINED) {
            String source = standIn.equals("CapabilityManager") && !withRegistrar ? "BareCapabilityManager" : standIn;
            host.define(HOST_NAMES.get(STAND_INS + standIn), remap(read(STAND_INS + source),
                    source.equals(standIn) ? names : withRename(STAND_INS + source, CAPABILITY_MANAGER)));
        }
    }

    // A remapper that also gives the stand-in the name it serves.
    private static Remapper withRename(String standIn, String hostName) {
        Map<String, String> all = new HashMap<>(HOST_NAMES);
        all.put(standIn, hostName);
        return new SimpleRemapper(all);
    }

    /** An instance of the host class, made with its public no-argument constructor. */
    static Object create(EventBus7Host host, String internalName) throws Exception {
        return host.type(internalName).getConstructor().newInstance();
    }

    /** A Forge 26.2 attach event of one kind for a fresh holder. */
    static Object attachEvent(EventBus7Host host, String kind) throws Exception {
        Object holder = create(host, KINDS.get(kind));
        return host.type(ATTACH + "$" + kind).getConstructor(holder.getClass()).newInstance(holder);
    }

    static Object busOf(EventBus7Host host, String kind) throws Exception {
        return host.type(ATTACH + "$" + kind).getField("BUS").get(null);
    }

    // ---- a translated mod ----

    /** One mod: its own relocated copy of every rebuilt class and the bus bridge, and its classes after the transform. */
    static ClassLoader mod(EventBus7Host host, RetromodTransformer transformer, String prefix,
            Map<String, byte[]> modClasses) {
        Map<String, byte[]> embedded = new HashMap<>();
        transformer.getSyntheticClasses().forEach((name, bytes) -> {
            if (name.startsWith(RebuiltApiClasses.ROOT) || name.equals(ForgeEventBusSynthetics.PRI)
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
        return new LegacyEventRuntimeTest.Loader(host, classes);
    }

    /** The mod's copy of a rebuilt class, by its name before the embed moved it. */
    static Class<?> copy(ClassLoader mod, String prefix, String rebuilt) throws ClassNotFoundException {
        return mod.loadClass((prefix + rebuilt).replace('/', '.'));
    }

    /** What EventBus 7's register does with an object: each @SubscribeEvent method on its event's BUS at its priority. */
    @SuppressWarnings("unchecked")
    static void subscribeAsEventBus7(Object listener) throws Exception {
        for (Method method : listener.getClass().getDeclaredMethods()) {
            Annotation subscribe = null;
            for (Annotation annotation : method.getDeclaredAnnotations()) {
                if (annotation.annotationType().getName().equals(SUBSCRIBE7)) subscribe = annotation;
            }
            if (subscribe == null) continue;
            byte priority = (byte) subscribe.annotationType().getMethod("priority").invoke(subscribe);
            Object bus = method.getParameterTypes()[0].getField("BUS").get(null);
            method.setAccessible(true);
            Object receiver = java.lang.reflect.Modifier.isStatic(method.getModifiers()) ? null : listener;
            Consumer<Object> call = event -> {
                try {
                    method.invoke(receiver, event);
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException(failure.getCause());
                }
            };
            bus.getClass().getMethod("addListener", byte.class, Consumer.class).invoke(bus, priority, call);
        }
    }

    // ---- class bytes ----

    static byte[] plainClass(String name, String superName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, superName, null);
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

    /** {@code abstract class AttachCapabilitiesEvent extends MutableEvent implements InheritableEvent}, holding its object. */
    private static byte[] attachBase() {
        String mutable = "net/minecraftforge/eventbus/api/event/MutableEvent";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER | ACC_ABSTRACT, ATTACH, null, mutable,
                new String[]{"net/minecraftforge/eventbus/api/event/InheritableEvent"});
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "obj", "Ljava/lang/Object;", null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_PROTECTED, "<init>", "(Ljava/lang/Object;)V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, mutable, "<init>", "()V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitFieldInsn(PUTFIELD, ATTACH, "obj", "Ljava/lang/Object;");
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor get = cw.visitMethod(ACC_PUBLIC, "getObject", "()Ljava/lang/Object;", null, null);
        get.visitCode();
        get.visitVarInsn(ALOAD, 0);
        get.visitFieldInsn(GETFIELD, ATTACH, "obj", "Ljava/lang/Object;");
        get.visitInsn(ARETURN);
        get.visitMaxs(0, 0);
        get.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code AttachCapabilitiesEvent$Kind(Holder)} with a static BUS on the game group and a typed getObject beside its bridge. */
    private static byte[] attachKind(String kind, String holder) {
        String name = ATTACH + "$" + kind;
        String bus = EventBus7Host.EVENT_BUS;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, ATTACH, null);
        cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "BUS", "L" + bus + ";", null, null).visitEnd();
        MethodVisitor clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitFieldInsn(GETSTATIC, EventBus7Host.BUS_GROUP, "DEFAULT", "L" + EventBus7Host.BUS_GROUP + ";");
        clinit.visitLdcInsn(Type.getObjectType(name));
        clinit.visitMethodInsn(INVOKESTATIC, bus, "create", "(L" + EventBus7Host.BUS_GROUP + ";Ljava/lang/Class;)L" + bus + ";", true);
        clinit.visitFieldInsn(PUTSTATIC, name, "BUS", "L" + bus + ";");
        clinit.visitInsn(RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(L" + holder + ";)V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitMethodInsn(INVOKESPECIAL, ATTACH, "<init>", "(Ljava/lang/Object;)V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor typed = cw.visitMethod(ACC_PUBLIC, "getObject", "()L" + holder + ";", null, null);
        typed.visitCode();
        typed.visitVarInsn(ALOAD, 0);
        typed.visitMethodInsn(INVOKESPECIAL, ATTACH, "getObject", "()Ljava/lang/Object;", false);
        typed.visitTypeInsn(CHECKCAST, holder);
        typed.visitInsn(ARETURN);
        typed.visitMaxs(0, 0);
        typed.visitEnd();
        MethodVisitor bridge = cw.visitMethod(ACC_PUBLIC | ACC_BRIDGE | ACC_SYNTHETIC, "getObject", "()Ljava/lang/Object;",
                null, null);
        bridge.visitCode();
        bridge.visitVarInsn(ALOAD, 0);
        bridge.visitMethodInsn(INVOKEVIRTUAL, name, "getObject", "()L" + holder + ";", false);
        bridge.visitInsn(ARETURN);
        bridge.visitMaxs(0, 0);
        bridge.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** An old {@code @SubscribeEvent} with an optional EventPriority constant and receiveCanceled. */
    static void oldSubscribe(MethodVisitor method, String priority, boolean receiveCanceled) {
        AnnotationVisitor subscribe = method.visitAnnotation(OLD_SUBSCRIBE, true);
        if (priority != null) subscribe.visitEnum("priority", OLD_PRIORITY, priority);
        if (receiveCanceled) subscribe.visit("receiveCanceled", true);
        subscribe.visitEnd();
    }

    static byte[] remap(byte[] bytes, Remapper remapper) {
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(bytes).accept(new ClassRemapper(writer, remapper), 0);
        return writer.toByteArray();
    }

    private static byte[] read(String internalName) throws IOException {
        try (InputStream in = CapabilityHostFixtures.class.getClassLoader().getResourceAsStream(internalName + ".class")) {
            if (in == null) throw new IOException("missing test class " + internalName);
            return in.readAllBytes();
        }
    }
}
