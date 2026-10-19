/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NeoForge and Forge read a block's id from the deferred register's thread local, which holds a
 * full {@code ResourceKey}. A registered static field's stamp only knows the id, and the same
 * statement may build a block or an item, so the bridge picks the registry from the kind of
 * {@code Properties} it stamps. Runs the generated bridge against stand-in Minecraft classes.
 */
class RegistryIdBridgeStaticFieldTest {

    private static final String IDENTIFIER = "net/minecraft/resources/Identifier";
    private static final String RESOURCE_KEY = "net/minecraft/resources/ResourceKey";
    private static final String REGISTRIES = "net/minecraft/core/registries/Registries";
    private static final String BLOCK_PROPS = "net/minecraft/world/level/block/state/BlockBehaviour$Properties";
    private static final String ITEM_PROPS = "net/minecraft/world/item/Item$Properties";

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {RegistryIdBridgeSynthetic.INTERNAL, RegistryIdBridgeSynthetic.FORGE_INTERNAL})
    @DisplayName("an id set by a static field stamp becomes a block key on block properties and an item key on item properties")
    void keysTheIdByTheProperties(String bridgeName) throws Throwable {
        Stubs stubs = new Stubs();
        Class<?> bridge = stubs.define(bridgeName, bridgeName.equals(RegistryIdBridgeSynthetic.INTERNAL)
                ? RegistryIdBridgeSynthetic.generate() : RegistryIdBridgeSynthetic.generateForge());
        // Looked up from the bridge's own loader, so the test's loader is never bound to one copy
        // of the stand-in classes and each case can define its own.
        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(bridge, MethodHandles.lookup());
        MethodHandle enter = lookup.findStatic(bridge, "enter",
                MethodType.methodType(Object.class, stubs.identifier));
        MethodHandle leave = lookup.findStatic(bridge, "leave",
                MethodType.methodType(void.class, Object.class));
        Object id = stubs.identifier.getConstructor().newInstance();

        Object previous = enter.invoke(id);
        Object blockKey = stubs.keyOf(apply(lookup, bridge, "applyBlock", stubs.blockProps));
        Object itemKey = stubs.keyOf(apply(lookup, bridge, "applyItem", stubs.itemProps));
        leave.invoke(previous);

        assertSame(id, stubs.idOf(blockKey));
        assertSame(stubs.registry("BLOCK"), stubs.registryOf(blockKey), "a block's id is a block key");
        assertSame(id, stubs.idOf(itemKey));
        assertSame(stubs.registry("ITEM"), stubs.registryOf(itemKey), "an item's id is an item key");
        assertNull(stubs.keyOf(apply(lookup, bridge, "applyBlock", stubs.blockProps)),
                "leave restores the empty slot, so later properties stay untouched");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {RegistryIdBridgeSynthetic.INTERNAL, RegistryIdBridgeSynthetic.FORGE_INTERNAL})
    @DisplayName("a stamp nested in another one gives the outer statement its id back")
    void nestedLeaveRestoresTheOuterId(String bridgeName) throws Throwable {
        // ModItems.FOO = new BlockItem(ModBlocks.FOO, new Item.Properties()) runs ModBlocks'
        // initializer before the item's properties exist. If the block's leave cleared the slot,
        // the item would be built with no id.
        Stubs stubs = new Stubs();
        Class<?> bridge = stubs.define(bridgeName, bridgeName.equals(RegistryIdBridgeSynthetic.INTERNAL)
                ? RegistryIdBridgeSynthetic.generate() : RegistryIdBridgeSynthetic.generateForge());
        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(bridge, MethodHandles.lookup());
        MethodHandle enter = lookup.findStatic(bridge, "enter",
                MethodType.methodType(Object.class, stubs.identifier));
        MethodHandle leave = lookup.findStatic(bridge, "leave",
                MethodType.methodType(void.class, Object.class));
        Object itemId = stubs.identifier.getConstructor().newInstance();
        Object blockId = stubs.identifier.getConstructor().newInstance();

        Object beforeItem = enter.invoke(itemId);
        Object beforeBlock = enter.invoke(blockId);
        Object blockKey = stubs.keyOf(apply(lookup, bridge, "applyBlock", stubs.blockProps));
        leave.invoke(beforeBlock);
        Object itemKey = stubs.keyOf(apply(lookup, bridge, "applyItem", stubs.itemProps));
        leave.invoke(beforeItem);

        assertSame(blockId, stubs.idOf(blockKey), "the block statement sees its own id");
        assertNotNull(itemKey, "the item statement must get its id back after the block's, or it is built with none");
        assertSame(itemId, stubs.idOf(itemKey));
        assertSame(stubs.registry("ITEM"), stubs.registryOf(itemKey));
        assertNull(stubs.keyOf(apply(lookup, bridge, "applyItem", stubs.itemProps)),
                "the outer leave empties the slot it found empty");
    }

    private static Object apply(MethodHandles.Lookup lookup, Class<?> bridge, String method, Class<?> props)
            throws Throwable {
        Object instance = props.getConstructor().newInstance();
        return lookup.findStatic(bridge, method, MethodType.methodType(props, props)).invoke(instance);
    }

    /** Just enough of Minecraft for the bridge's static field path to link and run. */
    private static final class Stubs extends ClassLoader {
        final Class<?> identifier;
        final Class<?> resourceKey;
        final Class<?> registries;
        final Class<?> blockProps;
        final Class<?> itemProps;

        Stubs() {
            super(RegistryIdBridgeStaticFieldTest.class.getClassLoader());
            identifier = define(IDENTIFIER, emptyClass(IDENTIFIER));
            resourceKey = define(RESOURCE_KEY, resourceKey());
            registries = define(REGISTRIES, registries());
            blockProps = define(BLOCK_PROPS, properties(BLOCK_PROPS));
            itemProps = define(ITEM_PROPS, properties(ITEM_PROPS));
        }

        Class<?> define(String internalName, byte[] bytes) {
            return defineClass(internalName.replace('/', '.'), bytes, 0, bytes.length);
        }

        Object registry(String name) throws Exception {
            return registries.getField(name).get(null);
        }

        Object keyOf(Object props) throws Exception {
            return props.getClass().getField("id").get(props);
        }

        Object idOf(Object key) throws Exception {
            return resourceKey.getField("location").get(key);
        }

        Object registryOf(Object key) throws Exception {
            return resourceKey.getField("registry").get(key);
        }
    }

    private static byte[] emptyClass(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        constructor(cw, "java/lang/Object");
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code ResourceKey.create(registry, location)} that remembers both. */
    private static byte[] resourceKey() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, RESOURCE_KEY, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC, "registry", "Ljava/lang/Object;", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC, "location", "Ljava/lang/Object;", null, null).visitEnd();
        constructor(cw, "java/lang/Object");
        MethodVisitor create = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "create",
                "(L" + RESOURCE_KEY + ";L" + IDENTIFIER + ";)L" + RESOURCE_KEY + ";", null, null);
        create.visitCode();
        create.visitTypeInsn(Opcodes.NEW, RESOURCE_KEY);
        create.visitInsn(Opcodes.DUP);
        create.visitMethodInsn(Opcodes.INVOKESPECIAL, RESOURCE_KEY, "<init>", "()V", false);
        create.visitVarInsn(Opcodes.ASTORE, 2);
        create.visitVarInsn(Opcodes.ALOAD, 2);
        create.visitVarInsn(Opcodes.ALOAD, 0);
        create.visitFieldInsn(Opcodes.PUTFIELD, RESOURCE_KEY, "registry", "Ljava/lang/Object;");
        create.visitVarInsn(Opcodes.ALOAD, 2);
        create.visitVarInsn(Opcodes.ALOAD, 1);
        create.visitFieldInsn(Opcodes.PUTFIELD, RESOURCE_KEY, "location", "Ljava/lang/Object;");
        create.visitVarInsn(Opcodes.ALOAD, 2);
        create.visitInsn(Opcodes.ARETURN);
        create.visitMaxs(0, 0);
        create.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code Registries.BLOCK} and {@code Registries.ITEM} as two distinct keys. */
    private static byte[] registries() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, REGISTRIES, null, "java/lang/Object", null);
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        init.visitCode();
        for (String name : new String[]{"BLOCK", "ITEM"}) {
            cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, name,
                    "L" + RESOURCE_KEY + ";", null, null).visitEnd();
            init.visitTypeInsn(Opcodes.NEW, RESOURCE_KEY);
            init.visitInsn(Opcodes.DUP);
            init.visitMethodInsn(Opcodes.INVOKESPECIAL, RESOURCE_KEY, "<init>", "()V", false);
            init.visitFieldInsn(Opcodes.PUTSTATIC, REGISTRIES, name, "L" + RESOURCE_KEY + ";");
        }
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code Properties.setId(key)} that keeps the key in a public field and returns itself. */
    private static byte[] properties(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC, "id", "Ljava/lang/Object;", null, null).visitEnd();
        constructor(cw, "java/lang/Object");
        MethodVisitor setId = cw.visitMethod(Opcodes.ACC_PUBLIC, "setId",
                "(L" + RESOURCE_KEY + ";)L" + name + ";", null, null);
        setId.visitCode();
        setId.visitVarInsn(Opcodes.ALOAD, 0);
        setId.visitVarInsn(Opcodes.ALOAD, 1);
        setId.visitFieldInsn(Opcodes.PUTFIELD, name, "id", "Ljava/lang/Object;");
        setId.visitVarInsn(Opcodes.ALOAD, 0);
        setId.visitInsn(Opcodes.ARETURN);
        setId.visitMaxs(0, 0);
        setId.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void constructor(ClassWriter cw, String superName) {
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
    }
}
