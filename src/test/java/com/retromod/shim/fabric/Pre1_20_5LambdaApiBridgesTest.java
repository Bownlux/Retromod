/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Regression coverage for the 1.20.1 Fabric APIs whose callbacks changed shape in 1.20.5, as
 * Palladium uses them on a Fabric 1.21.1 host (#262): pack suppliers, pack resources, extended
 * screen handlers, channel-based server networking, predicate-based resource conditions and
 * loot table event callbacks.
 * Minecraft is not on the test classpath, so each case transforms a fixture shaped like the
 * mod's bytecode.
 */
class Pre1_20_5LambdaApiBridgesTest {

    private static final Handle METAFACTORY = new Handle(H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory",
            "metafactory", "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                    + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                    + "Ljava/lang/invoke/CallSite;", false);

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("An old ResourcesSupplier lambda is built as a Function and wrapped")
    void oldPackSupplierLambdaIsWrapped() {
        Pre1_20_5PackApiBridge.registerSupplierAdapter(transformer);
        String sam = Pre1_20_5PackApiBridge.OLD_SUPPLIER_DESC;

        ClassNode out = transform(lambdaSite("test/PackSource", Pre1_20_5PackApiBridge.RESOURCES_SUPPLIER,
                Pre1_20_5PackApiBridge.OLD_SUPPLIER_NAME, sam));

        InvokeDynamicInsnNode indy = only(out, InvokeDynamicInsnNode.class);
        assertEquals("apply", indy.name);
        assertEquals("()Ljava/util/function/Function;", indy.desc);
        assertEquals(Type.getMethodType("(Ljava/lang/Object;)Ljava/lang/Object;"), indy.bsmArgs[0]);
        assertEquals(Type.getMethodType(sam), indy.bsmArgs[2], "the metafactory must still cast to the old types");
        MethodInsnNode wrap = (MethodInsnNode) indy.getNext();
        assertEquals(Pre1_20_5PackApiBridge.FACTORY, wrap.owner);
        assertEquals(Pre1_20_5PackApiBridge.WRAP_SUPPLIER_DESC, wrap.desc);
        assertWellFormed(Pre1_20_5PackApiBridge.generateSupplierAdapter());
        assertWellFormed(Pre1_20_5PackApiBridge.generateFactory());
    }

    @Test
    @DisplayName("A ResourcesSupplier lambda written for the new interface is untouched")
    void modernPackSupplierLambdaIsLeftAlone() {
        Pre1_20_5PackApiBridge.registerSupplierAdapter(transformer);
        String sam = "(Lnet/minecraft/class_9224;)Lnet/minecraft/class_3262;";

        ClassNode out = transform(lambdaSite("test/ModernPackSource",
                Pre1_20_5PackApiBridge.RESOURCES_SUPPLIER, "method_52424", sam));

        InvokeDynamicInsnNode indy = only(out, InvokeDynamicInsnNode.class);
        assertEquals("method_52424", indy.name);
        assertEquals(Type.getMethodType(sam), indy.bsmArgs[0]);
        assertFalse(indy.getNext() instanceof MethodInsnNode, "no wrapper call may follow a modern lambda");
    }

    @Test
    @DisplayName("Old AbstractPackResources subclasses get a base with both constructors")
    void packResourcesSubclassIsRebased() {
        Pre1_20_5PackApiBridge.registerPackResourcesBase(transformer);
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/AddonResources", null,
                Pre1_20_5PackApiBridge.PACK_RESOURCES_BASE, null);
        MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>", "(Ljava/lang/String;)V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitVarInsn(ALOAD, 1);
        ctor.visitInsn(ICONST_1);
        ctor.visitMethodInsn(INVOKESPECIAL, Pre1_20_5PackApiBridge.PACK_RESOURCES_BASE, "<init>",
                Pre1_20_5PackApiBridge.OLD_RESOURCES_CTOR, false);
        ctor.visitInsn(RETURN);
        ctor.visitMaxs(3, 2);
        ctor.visitEnd();
        cw.visitEnd();

        ClassNode out = transform(cw.toByteArray());

        assertEquals(Pre1_20_5PackApiBridge.LEGACY_PACK_RESOURCES, out.superName);
        MethodInsnNode superCall = only(out, MethodInsnNode.class);
        assertEquals(Pre1_20_5PackApiBridge.LEGACY_PACK_RESOURCES, superCall.owner);
        assertEquals(Pre1_20_5PackApiBridge.OLD_RESOURCES_CTOR, superCall.desc);
        ClassNode base = read(Pre1_20_5PackApiBridge.generatePackResourcesBase());
        assertTrue(hasMethod(base, "<init>", Pre1_20_5PackApiBridge.OLD_RESOURCES_CTOR));
        assertTrue(hasMethod(base, "<init>", Pre1_20_5PackApiBridge.MODERN_RESOURCES_CTOR),
                "a subclass written for the new constructor must still link");
    }

    @Test
    @DisplayName("Old extended screen handler types, factories and providers are bridged")
    void oldExtendedScreenHandlersAreBridged() {
        Pre1_20_5ScreenHandlerBridge.registerRedirects(transformer);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/Menus", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "type",
                "()L" + Pre1_20_5ScreenHandlerBridge.HANDLER_TYPE + ";", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, Pre1_20_5ScreenHandlerBridge.HANDLER_TYPE);
        mv.visitInsn(DUP);
        visitLambda(mv, Pre1_20_5ScreenHandlerBridge.FACTORY_IFACE, "create", Pre1_20_5ScreenHandlerBridge.OLD_CREATE);
        mv.visitMethodInsn(INVOKESPECIAL, Pre1_20_5ScreenHandlerBridge.HANDLER_TYPE, "<init>",
                Pre1_20_5ScreenHandlerBridge.OLD_CTOR, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        addTarget(cw, Pre1_20_5ScreenHandlerBridge.OLD_CREATE);
        cw.visitEnd();

        ClassNode out = transform(cw.toByteArray());

        InvokeDynamicInsnNode indy = only(out, InvokeDynamicInsnNode.class);
        assertEquals(Type.getMethodType(Pre1_20_5ScreenHandlerBridge.ERASED_CREATE), indy.bsmArgs[0],
                "the lambda must implement the erased create(int, Inventory, Object)");
        assertEquals(Type.getMethodType(Pre1_20_5ScreenHandlerBridge.OLD_CREATE), indy.bsmArgs[2]);
        assertTrue(calls(out).stream().anyMatch(c -> c.owner.equals(Pre1_20_5ScreenHandlerBridge.FACTORY)),
                "the one-argument constructor must go through the factory that adds a codec");

        ClassWriter provider = new ClassWriter(0);
        provider.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/MenuProvider", null, "java/lang/Object",
                new String[] {Pre1_20_5ScreenHandlerBridge.MENU_PROVIDER});
        provider.visitEnd();
        ClassNode providerOut = transform(provider.toByteArray());
        assertEquals(List.of(Pre1_20_5ScreenHandlerBridge.LEGACY_MENU_PROVIDER), providerOut.interfaces);

        ClassNode legacyProvider = read(Pre1_20_5ScreenHandlerBridge.generateMenuProvider());
        assertEquals(List.of(Pre1_20_5ScreenHandlerBridge.MENU_PROVIDER), legacyProvider.interfaces);
        assertTrue(hasMethod(legacyProvider, Pre1_20_5ScreenHandlerBridge.GET_DATA,
                Pre1_20_5ScreenHandlerBridge.GET_DATA_DESC), "old providers need the new data getter");
        assertWellFormed(Pre1_20_5ScreenHandlerBridge.generateCodec());
        assertWellFormed(Pre1_20_5ScreenHandlerBridge.generateFactory());
        assertWellFormed(Pre1_20_5ScreenHandlerBridge.generateMenuProvider());
    }

    @Test
    @DisplayName("A screen handler factory lambda written for the typed API is untouched")
    void modernScreenHandlerFactoryIsLeftAlone() {
        Pre1_20_5ScreenHandlerBridge.registerRedirects(transformer);
        String typed = Pre1_20_5ScreenHandlerBridge.ERASED_CREATE;

        ClassNode out = transform(lambdaSite("test/TypedMenus", Pre1_20_5ScreenHandlerBridge.FACTORY_IFACE,
                "create", typed));

        assertEquals(Type.getMethodType(typed), only(out, InvokeDynamicInsnNode.class).bsmArgs[0]);
    }

    @Test
    @DisplayName("Channel-based server networking calls and handler lambdas link on intermediary hosts")
    void oldServerNetworkingIsLinked() {
        Pre1_20_5ServerNetworkingBridge.registerRedirects(transformer);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/Network", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "register", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(ACONST_NULL);
        visitLambda(mv, Pre1_20_5ServerNetworkingBridge.HANDLER, "receive",
                Pre1_20_5ServerNetworkingBridge.OLD_RECEIVE);
        mv.visitMethodInsn(INVOKESTATIC, Pre1_20_5ServerNetworkingBridge.SERVER_NETWORKING,
                "registerGlobalReceiver",
                "(Lnet/minecraft/class_2960;L" + Pre1_20_5ServerNetworkingBridge.HANDLER + ";)Z", false);
        mv.visitInsn(POP);
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ACONST_NULL);
        mv.visitMethodInsn(INVOKESTATIC, Pre1_20_5ServerNetworkingBridge.SERVER_NETWORKING, "send",
                Pre1_20_5ServerNetworkingBridge.OLD_SEND, false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        addTarget(cw, Pre1_20_5ServerNetworkingBridge.OLD_RECEIVE);
        cw.visitEnd();

        ClassNode out = transform(cw.toByteArray());

        assertEquals(Type.getMethodType(Pre1_20_5ServerNetworkingBridge.ERASED_RECEIVE),
                only(out, InvokeDynamicInsnNode.class).bsmArgs[0],
                "the bridge calls receive with Object parameters");
        List<String> bridged = calls(out).stream()
                .filter(c -> c.owner.equals(Pre1_20_5ServerNetworkingBridge.BRIDGE)).map(c -> c.name).toList();
        assertEquals(List.of("registerServerGlobalReceiver", "serverSend"), bridged);
    }

    @Test
    @DisplayName("UUID-keyed attribute modifiers are built and looked up through identifiers")
    void uuidAttributeModifiersAreBridged() {
        Pre1_20_5AttributeModifierBridge.registerRedirects(transformer);
        String instanceDesc = "(Ljava/util/UUID;)L" + Pre1_20_5AttributeModifierBridge.MODIFIER + ";";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/Modifiers", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "use",
                "(L" + Pre1_20_5AttributeModifierBridge.INSTANCE + ";)V", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, Pre1_20_5AttributeModifierBridge.MODIFIER);
        mv.visitInsn(DUP);
        mv.visitInsn(ACONST_NULL);
        mv.visitLdcInsn("Slow falling");
        mv.visitLdcInsn(-0.07);
        mv.visitInsn(ACONST_NULL);
        mv.visitMethodInsn(INVOKESPECIAL, Pre1_20_5AttributeModifierBridge.MODIFIER, "<init>",
                Pre1_20_5AttributeModifierBridge.UUID_NAME_CTOR, false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(ACONST_NULL);
        mv.visitMethodInsn(INVOKEVIRTUAL, Pre1_20_5AttributeModifierBridge.INSTANCE, "method_6199", instanceDesc, false);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode out = transform(cw.toByteArray());

        List<String> helperCalls = calls(out).stream()
                .filter(c -> c.owner.equals(Pre1_20_5AttributeModifierBridge.HELPER))
                .map(c -> c.name + c.desc).toList();
        assertEquals(List.of(
                "create(Ljava/util/UUID;Ljava/lang/String;DL" + Pre1_20_5AttributeModifierBridge.OPERATION
                        + ";)L" + Pre1_20_5AttributeModifierBridge.MODIFIER + ";",
                "getModifier(L" + Pre1_20_5AttributeModifierBridge.INSTANCE + ";Ljava/util/UUID;)L"
                        + Pre1_20_5AttributeModifierBridge.MODIFIER + ";"), helperCalls);
        ClassNode helper = read(Pre1_20_5AttributeModifierBridge.generateHelper());
        assertTrue(hasMethod(helper, "idFor", "(Ljava/util/UUID;)L" + Pre1_20_5AttributeModifierBridge.ID + ";"));
        assertWellFormed(Pre1_20_5AttributeModifierBridge.generateHelper());
    }

    @Test
    @DisplayName("A modifier id built from a UUID reads back as the same UUID")
    void attributeModifierIdsRoundTripTheirUuid() {
        java.util.UUID uuid = java.util.UUID.fromString("a5b6cf2a-2f7c-31ef-9022-7c3e7d5e6aba");
        assertEquals(uuid, com.retromod.shim.fabric.embedded.LegacyAttributeIds.uuidOf(
                uuid.toString(), "minecraft:" + uuid));
        assertEquals(com.retromod.shim.fabric.embedded.LegacyAttributeIds.uuidOf("speed", "mymod:speed"),
                com.retromod.shim.fabric.embedded.LegacyAttributeIds.uuidOf("speed", "mymod:speed"),
                "a named id must map to a stable UUID");
    }

    @Test
    @DisplayName("JSON text helpers without a lookup provider call the provider form")
    void componentJsonHelpersGainAProvider() {
        Pre1_20_5ComponentJsonBridge.registerRedirects(transformer, Pre1_20_5ComponentJsonBridge.CALLS);
        Pre1_20_5ComponentJsonBridge.Call fromJson = Pre1_20_5ComponentJsonBridge.CALLS.get(0);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/Powers", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "name", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(ACONST_NULL);
        mv.visitMethodInsn(INVOKESTATIC, Pre1_20_5ComponentJsonBridge.SERIALIZER, fromJson.name(),
                fromJson.oldDesc(), false);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        MethodInsnNode call = only(transform(cw.toByteArray()), MethodInsnNode.class);

        assertEquals(Pre1_20_5ComponentJsonBridge.HELPER, call.owner);
        MethodInsnNode delegate = calls(read(Pre1_20_5ComponentJsonBridge.generateHelper(
                List.of(fromJson)))).get(0);
        assertEquals("(Lcom/google/gson/JsonElement;Lnet/minecraft/class_7225$class_7874;)Lnet/minecraft/class_5250;",
                delegate.desc, "the helper must pass a lookup provider to the 1.20.5 form");
        assertWellFormed(Pre1_20_5ComponentJsonBridge.generateHelper(Pre1_20_5ComponentJsonBridge.CALLS));
    }

    @Test
    @DisplayName("A predicate-based resource condition registers through the support class")
    void predicateResourceConditionIsRedirected() {
        Pre1_20_5ResourceConditionBridge.registerRedirects(transformer);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/Conditions", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "init", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ACONST_NULL);
        mv.visitMethodInsn(INVOKESTATIC, Pre1_20_5ResourceConditionBridge.CONDITIONS, "register",
                Pre1_20_5ResourceConditionBridge.OLD_REGISTER, false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        MethodInsnNode call = only(transform(cw.toByteArray()), MethodInsnNode.class);

        assertEquals(Pre1_20_5ResourceConditionBridge.SUPPORT, call.owner);
        assertEquals(Pre1_20_5ResourceConditionBridge.SUPPORT_REGISTER, call.desc);
        assertTrue(transformer.getSyntheticClasses().containsKey(Pre1_20_5ResourceConditionBridge.SUPPORT),
                "the support class must be embedded into the mod that calls it");
    }

    @Test
    @DisplayName("A typed resource condition registration is untouched")
    void typedResourceConditionIsLeftAlone() {
        Pre1_20_5ResourceConditionBridge.registerRedirects(transformer);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/TypedConditions", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "init", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(ACONST_NULL);
        mv.visitMethodInsn(INVOKESTATIC, Pre1_20_5ResourceConditionBridge.CONDITIONS, "register",
                Pre1_20_5ResourceConditionBridge.NEW_REGISTER, false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        MethodInsnNode call = only(transform(cw.toByteArray()), MethodInsnNode.class);

        assertEquals(Pre1_20_5ResourceConditionBridge.CONDITIONS, call.owner);
    }

    @Test
    @DisplayName("Registering a resource condition without Fabric API explains what failed")
    void resourceConditionFailureNamesTheCondition() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> com.retromod.shim.fabric.embedded.LegacyResourceConditionSupport.register(
                        "palladium:feature_flag_enabled", json -> true));
        assertTrue(failure.getMessage().contains("palladium:feature_flag_enabled"), failure.getMessage());
    }

    @Test
    @DisplayName("An old loot table callback lambda is built against the old shape and wrapped")
    void oldLootTableCallbackIsWrapped() {
        Pre1_20_5LootTableEventsBridge.registerRedirects(transformer, Pre1_20_5LootTableEventsBridge.CALLBACKS);
        Pre1_20_5LootTableEventsBridge.Callback modify = Pre1_20_5LootTableEventsBridge.CALLBACKS.get(0);
        byte[] input = lambdaSite("test/LootEvents", modify.iface(), modify.sam(), modify.oldDesc());

        byte[] output = transformer.transformClass(input, "test/LootEvents");
        ClassNode out = read(output);

        InvokeDynamicInsnNode indy = only(out, InvokeDynamicInsnNode.class);
        assertEquals("()L" + modify.callbackIface() + ";", indy.desc);
        assertEquals(Type.getMethodType(modify.erasedDesc()), indy.bsmArgs[0]);
        assertEquals(Type.getMethodType(modify.redirectedOldDesc()), indy.bsmArgs[2],
                "the lambda must still be instantiated with its old parameter types");
        MethodInsnNode wrap = (MethodInsnNode) indy.getNext();
        assertEquals(modify.adapter(), wrap.owner);
        assertEquals(modify.wrapDesc(), wrap.desc);
        assertFalse(new String(output, java.nio.charset.StandardCharsets.ISO_8859_1)
                        .contains(Pre1_20_5LootTableEventsBridge.LOOT_DATA_MANAGER + ";"),
                "no reference to the removed LootDataManager may remain");
        for (Pre1_20_5LootTableEventsBridge.Callback callback : Pre1_20_5LootTableEventsBridge.CALLBACKS) {
            assertWellFormed(Pre1_20_5LootTableEventsBridge.generateCallbackInterface(callback));
            assertWellFormed(Pre1_20_5LootTableEventsBridge.generateAdapter(callback));
        }
        assertWellFormed(Pre1_20_5LootTableEventsBridge.generateStandIn());
    }

    @Test
    @DisplayName("The loot table adapter passes the key's identifier and nulls the removed arguments")
    void lootTableAdapterMapsArguments() {
        Pre1_20_5LootTableEventsBridge.Callback modify = Pre1_20_5LootTableEventsBridge.CALLBACKS.get(0);
        MethodNode method = read(Pre1_20_5LootTableEventsBridge.generateAdapter(modify)).methods.stream()
                .filter(m -> m.name.equals(modify.sam()) && m.desc.equals(modify.newDesc()))
                .findFirst().orElseThrow();
        List<Integer> opcodes = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn.getOpcode() >= 0) opcodes.add(insn.getOpcode());
            if (insn.getOpcode() == RETURN) break;
        }
        assertEquals(List.of(ALOAD, GETFIELD, ACONST_NULL, ACONST_NULL, ALOAD, INVOKEVIRTUAL, ALOAD, ALOAD,
                INVOKEINTERFACE, RETURN), opcodes);
        List<String> caught = method.tryCatchBlocks.stream().map(block -> block.type).toList();
        assertTrue(caught.contains("java/lang/LinkageError"),
                "a callback that reaches a removed API must not stop the data pack load");
        assertTrue(caught.contains("java/lang/NullPointerException"),
                "a callback that reads the missing resource manager must not stop the data pack load");
    }

    @Test
    @DisplayName("A loot table callback that reads the missing resource manager is skipped and reported")
    void lootTableCallbackReadingNullArgumentsIsSkipped() throws Exception {
        Pre1_20_5LootTableEventsBridge.Callback loaded = Pre1_20_5LootTableEventsBridge.CALLBACKS.get(2);
        StubLoader loader = new StubLoader();
        loader.add("net/minecraft/class_3300", stubInterface("net/minecraft/class_3300"));
        loader.add("net/minecraft/class_2378", stubInterface("net/minecraft/class_2378"));
        loader.add(loaded.iface(), currentLoadedInterface(loaded));
        loader.add(loaded.callbackIface(), Pre1_20_5LootTableEventsBridge.generateCallbackInterface(loaded));
        loader.add(loaded.adapter(), Pre1_20_5LootTableEventsBridge.generateAdapter(loaded));
        Class<?> callbackType = loader.loadClass(loaded.callbackIface().replace('/', '.'));
        java.lang.reflect.Method wrap = loader.loadClass(loaded.adapter().replace('/', '.'))
                .getMethod("wrap", callbackType);
        java.lang.reflect.Method fire = loader.loadClass(loaded.iface().replace('/', '.'))
                .getMethod(loaded.sam(), loader.loadClass("net.minecraft.class_3300"),
                        loader.loadClass("net.minecraft.class_2378"));
        // Reads the LootDataManager argument, which the current event cannot supply.
        Object readsDataManager = wrap.invoke(null, callback(callbackType, args -> args[1].hashCode()));

        String report = captureErr(() -> fire.invoke(readsDataManager, null, null));

        assertTrue(report.contains("[Retromod]") && report.contains("NullPointerException"),
                "the skipped callback must be reported with its cause: " + report);
    }

    @Test
    @DisplayName("Each loot table callback that hits a removed API is skipped and reported once")
    void brokenLootTableCallbacksAreSkippedAndEachReported() throws Exception {
        Pre1_20_5LootTableEventsBridge.Callback loaded = Pre1_20_5LootTableEventsBridge.CALLBACKS.get(2);
        StubLoader loader = new StubLoader();
        loader.add("net/minecraft/class_3300", stubInterface("net/minecraft/class_3300"));
        loader.add("net/minecraft/class_2378", stubInterface("net/minecraft/class_2378"));
        loader.add(loaded.iface(), currentLoadedInterface(loaded));
        loader.add(loaded.callbackIface(), Pre1_20_5LootTableEventsBridge.generateCallbackInterface(loaded));
        loader.add(loaded.adapter(), Pre1_20_5LootTableEventsBridge.generateAdapter(loaded));
        Class<?> callbackType = loader.loadClass(loaded.callbackIface().replace('/', '.'));
        Class<?> adapterType = loader.loadClass(loaded.adapter().replace('/', '.'));
        java.lang.reflect.Method wrap = adapterType.getMethod("wrap", callbackType);
        java.lang.reflect.Method fire = loader.loadClass(loaded.iface().replace('/', '.'))
                .getMethod(loaded.sam(), loader.loadClass("net.minecraft.class_3300"),
                        loader.loadClass("net.minecraft.class_2378"));

        List<Object[]> received = new ArrayList<>();
        Object working = wrap.invoke(null, callback(callbackType, args -> received.add(args)));
        Object first = wrap.invoke(null, callback(callbackType, args -> {
            throw new NoClassDefFoundError("net/minecraft/class_5270");
        }));
        Object second = wrap.invoke(null, callback(callbackType, args -> {
            throw new NoSuchMethodError("net/minecraft/class_60.method_51195");
        }));

        Object resourceManager = java.lang.reflect.Proxy.newProxyInstance(loader,
                new Class<?>[] {loader.loadClass("net.minecraft.class_3300")}, (proxy, method, args) -> null);
        String report = captureErr(() -> {
            for (int round = 0; round < 2; round++) {
                fire.invoke(working, resourceManager, null);
                fire.invoke(first, resourceManager, null);
                fire.invoke(second, resourceManager, null);
            }
        });

        assertEquals(2, received.size(), "a working callback must run every time");
        assertSame(resourceManager, received.get(0)[0], "the resource manager is passed through");
        assertNull(received.get(0)[1], "the removed LootDataManager has no current value");
        List<String> lines = report.lines().filter(line -> line.startsWith("[Retromod]")).toList();
        assertEquals(2, lines.size(),
                "each broken callback is reported once, and one must not hide another: " + report);
        assertTrue(lines.get(0).contains("class_5270") && lines.get(1).contains("class_60"), report);
    }

    @Test
    @DisplayName("A loot table callback written for the current interface is untouched")
    void modernLootTableCallbackIsLeftAlone() {
        Pre1_20_5LootTableEventsBridge.registerRedirects(transformer, Pre1_20_5LootTableEventsBridge.CALLBACKS);
        Pre1_20_5LootTableEventsBridge.Callback modify = Pre1_20_5LootTableEventsBridge.CALLBACKS.get(0);

        ClassNode out = transform(lambdaSite("test/ModernLootEvents", modify.iface(), modify.sam(),
                modify.newDesc()));

        InvokeDynamicInsnNode indy = only(out, InvokeDynamicInsnNode.class);
        assertEquals("()L" + modify.iface() + ";", indy.desc);
        assertNull(indy.getNext() instanceof MethodInsnNode call && call.owner.equals(modify.adapter())
                ? call : null, "a current-shape lambda must not be wrapped");
    }

    // ---- fixtures ------------------------------------------------------------------------

    /** Defines host stand-ins next to the generated classes, so the adapter can run here. */
    private static final class StubLoader extends ClassLoader {
        private final java.util.Map<String, byte[]> classes = new java.util.HashMap<>();

        StubLoader() {
            super(Pre1_20_5LambdaApiBridgesTest.class.getClassLoader());
        }

        void add(String internalName, byte[] bytes) {
            classes.put(internalName.replace('/', '.'), bytes);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classes.get(name);
            if (bytes == null) throw new ClassNotFoundException(name);
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private static byte[] stubInterface(String internalName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, internalName, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The current Fabric API callback interface, reduced to its one abstract method. */
    private static byte[] currentLoadedInterface(Pre1_20_5LootTableEventsBridge.Callback callback) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, callback.iface(), null, "java/lang/Object", null);
        cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, callback.sam(), callback.newDesc(), null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** An old-shape callback whose body is {@code body}, as a mod lambda would be. */
    private static Object callback(Class<?> callbackType, java.util.function.Consumer<Object[]> body) {
        return java.lang.reflect.Proxy.newProxyInstance(callbackType.getClassLoader(),
                new Class<?>[] {callbackType}, (proxy, method, args) -> {
                    body.accept(args);
                    return null;
                });
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static String captureErr(ThrowingRunnable action) throws Exception {
        java.io.PrintStream original = System.err;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setErr(original);
        }
        return captured.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** A class whose static method builds one lambda of {@code iface} implemented by {@code target}. */
    private static byte[] lambdaSite(String owner, String iface, String samName, String samDesc) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, owner, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "make", "()L" + iface + ";", null, null);
        mv.visitCode();
        visitLambda(mv, iface, samName, samDesc, owner);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        addTarget(cw, samDesc);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void visitLambda(MethodVisitor mv, String iface, String samName, String samDesc) {
        visitLambda(mv, iface, samName, samDesc, null);
    }

    private static void visitLambda(MethodVisitor mv, String iface, String samName, String samDesc,
            String implOwner) {
        Type sam = Type.getMethodType(samDesc);
        Handle impl = new Handle(H_INVOKESTATIC, implOwner == null ? "test/Impl" : implOwner, "target",
                samDesc, false);
        mv.visitInvokeDynamicInsn(samName, "()L" + iface + ";", METAFACTORY, sam, impl, sam);
    }

    private static void addTarget(ClassWriter cw, String desc) {
        MethodVisitor target = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, "target", desc, null, null);
        target.visitCode();
        Type returnType = Type.getReturnType(desc);
        if (returnType.getSort() == Type.VOID) {
            target.visitInsn(RETURN);
        } else {
            target.visitInsn(ACONST_NULL);
            target.visitInsn(ARETURN);
        }
        target.visitMaxs(0, 0);
        target.visitEnd();
    }

    private ClassNode transform(byte[] input) {
        return read(transformer.transformClass(input, new ClassReader(input).getClassName()));
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static void assertWellFormed(byte[] bytes) {
        // Data-flow checks would need Minecraft on the classpath, so only the structure is checked.
        assertDoesNotThrow(() -> new ClassReader(bytes).accept(
                new CheckClassAdapter(new ClassWriter(0), false), 0));
    }

    private static boolean hasMethod(ClassNode node, String name, String desc) {
        return node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc));
    }

    private static <T extends AbstractInsnNode> T only(ClassNode node, Class<T> type) {
        List<T> found = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (type.isInstance(insn)) found.add(type.cast(insn));
            }
        }
        assertEquals(1, found.size(), "expected exactly one " + type.getSimpleName() + " in " + node.name);
        return found.get(0);
    }

    private static List<MethodInsnNode> calls(ClassNode node) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call);
            }
        }
        return calls;
    }
}
