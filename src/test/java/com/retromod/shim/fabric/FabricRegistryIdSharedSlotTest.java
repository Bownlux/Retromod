/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.fabric;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every mod embeds its own relocated copy of the registry id bridge.
 *
 * <p>A library can register on behalf of a content mod: NexusLib's {@code registerBlock(name,
 * Supplier)} pushes the id in NexusLib's copy, then runs Bountiful Fares' supplier, whose block
 * constructor reads Bountiful Fares' copy. With a per-copy slot that read came back empty and every
 * Bountiful Fares block died with {@code Block id not set} on 26.1.2.
 */
class FabricRegistryIdSharedSlotTest {

    private static final String IDENTIFIER = "net/minecraft/resources/Identifier";

    @AfterEach
    void clearSharedSlot() {
        System.getProperties().remove(FabricRegistryIdSynthetic.SHARED_SLOT_KEY);
    }

    @Test
    @DisplayName("an id pushed through one mod's copy is visible to another mod's copy")
    void relocatedCopiesShareOneSlot() throws Throwable {
        StubLoader loader = new StubLoader();
        Class<?> identifier = loader.define(IDENTIFIER, stubIdentifier());
        Class<?> library = loader.define("com/retromod/embedded/library/Bridge",
                relocatedBridge("com/retromod/embedded/library/Bridge"));
        Class<?> content = loader.define("com/retromod/embedded/content/Bridge",
                relocatedBridge("com/retromod/embedded/content/Bridge"));

        Object id = identifier.getConstructor().newInstance();
        // Method handles link one method; Class.getMethod would resolve every signature in the
        // bridge, and the Properties types it stamps do not exist without Minecraft.
        MethodHandles.lookup().findStatic(library, "push",
                MethodType.methodType(void.class, identifier)).invoke(id);
        assertSame(id, slotOf(content).get(),
                "the content mod's copy must see the id the library pushed, or its block "
                + "constructor fails with 'Block id not set'");

        MethodHandles.lookup().findStatic(library, "pop", MethodType.methodType(void.class))
                .invoke();
        assertNull(slotOf(content).get(), "pop must clear the shared slot for every copy");
    }

    @Test
    @DisplayName("a static field stamped inside another one's statement puts the outer id back")
    void nestedEnterRestoresTheOuterId() throws Throwable {
        // ModItems.FOO = new BlockItem(ModBlocks.FOO, new Item.Properties()) runs ModBlocks'
        // static initializer before the item's properties exist. If that inner stamp cleared the
        // slot, the item would be built with no id.
        StubLoader loader = new StubLoader();
        Class<?> identifier = loader.define(IDENTIFIER, stubIdentifier());
        Class<?> bridge = loader.define("com/retromod/embedded/mod/Bridge",
                relocatedBridge("com/retromod/embedded/mod/Bridge"));
        // Looked up from the bridge's own loader, so the test's loader is never bound to this
        // copy of the stand-in Identifier and the other cases can define their own.
        var lookup = MethodHandles.privateLookupIn(bridge, MethodHandles.lookup());
        var enter = lookup.findStatic(bridge, "enter", MethodType.methodType(Object.class, identifier));
        var leave = lookup.findStatic(bridge, "leave", MethodType.methodType(void.class, Object.class));
        Object itemId = identifier.getConstructor().newInstance();
        Object blockId = identifier.getConstructor().newInstance();

        Object beforeItem = enter.invoke(itemId);
        Object beforeBlock = enter.invoke(blockId);
        assertSame(blockId, slotOf(bridge).get(), "the block statement sees its own id");
        leave.invoke(beforeBlock);
        assertSame(itemId, slotOf(bridge).get(),
                "the item statement must get its id back after the block class initializes");
        leave.invoke(beforeItem);
        assertNull(beforeItem, "nothing was set before the outer statement");
        assertNull(slotOf(bridge).get(), "the outer leave clears the slot it found empty");
    }

    @Test
    @DisplayName("an unexpected value under the shared key falls back to a private slot")
    void foreignPropertyValueFallsBackToPrivateSlot() throws Exception {
        System.getProperties().put(FabricRegistryIdSynthetic.SHARED_SLOT_KEY, "not a slot");
        StubLoader loader = new StubLoader();
        loader.define(IDENTIFIER, stubIdentifier());
        Class<?> bridge = loader.define("com/retromod/embedded/only/Bridge",
                relocatedBridge("com/retromod/embedded/only/Bridge"));

        assertNotNull(slotOf(bridge),
                "a foreign property value must not break the bridge's class init");
    }

    private static ThreadLocal<?> slotOf(Class<?> bridge) throws Exception {
        Field current = bridge.getDeclaredField("CURRENT");
        current.setAccessible(true);
        return (ThreadLocal<?>) current.get(null);
    }

    private static byte[] relocatedBridge(String name) {
        Map<String, String> rename = new HashMap<>();
        rename.put(FabricRegistryIdSynthetic.INTERNAL, name);
        ClassWriter cw = new ClassWriter(0);
        new ClassReader(FabricRegistryIdSynthetic.generate())
                .accept(new ClassRemapper(cw, new SimpleRemapper(rename)), 0);
        return cw.toByteArray();
    }

    /** A bare {@code Identifier} so {@code push(Identifier)} links without Minecraft. */
    private static byte[] stubIdentifier() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, IDENTIFIER, null, "java/lang/Object", null);
        var init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static final class StubLoader extends ClassLoader {
        StubLoader() {
            super(FabricRegistryIdSharedSlotTest.class.getClassLoader());
        }

        Class<?> define(String internalName, byte[] bytes) {
            return defineClass(internalName.replace('/', '.'), bytes, 0, bytes.length);
        }
    }
}
