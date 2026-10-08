/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.forge.embedded.LegacyCreativeTabContents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * #264: a 1.19.2 item names its creative tab with {@code new Item.Properties().tab(TAB)}, and the
 * tab has to list it again on a 1.19.3+ host, where tabs are filled from a callback.
 */
class LegacyCreativeTabContentsTest {

    private static final String ITEM = "net/minecraft/world/item/Item";
    private static final String BLOCK_ITEM = "net/minecraft/world/item/BlockItem";
    private static final String BLOCK = "net/minecraft/world/level/block/Block";
    private static final String PROPERTIES = "net/minecraft/world/item/Item$Properties";
    private static final String TAB = "net/minecraft/world/item/CreativeModeTab";
    private static final String LEGACY_TAB = CreativeModeTabBridge.GENERATED;
    private static final String TAB_DESC = "(L" + PROPERTIES + ";L" + TAB + ";)L" + PROPERTIES + ";";
    private static final String MOD_ITEM = "test/items/RubyItem";
    private static final String MOD_ITEMS = "test/items/ModItems";

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        CreativeModeTabBridge.register(transformer);
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    /** {@code RubyItem() { super(new Item.Properties().tab(ModTabs.TAB)); }}, after the tab redirect. */
    private static byte[] modItem(boolean tagged) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, MOD_ITEM, null, ITEM, null);
        cw.visitField(ACC_PUBLIC | ACC_STATIC, "TAB", "L" + TAB + ";", null, null).visitEnd();
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitTypeInsn(NEW, PROPERTIES);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, PROPERTIES, "<init>", "()V", false);
        if (tagged) {
            mv.visitFieldInsn(GETSTATIC, MOD_ITEM, "TAB", "L" + TAB + ";");
            mv.visitMethodInsn(INVOKESTATIC, LEGACY_TAB, "tab", TAB_DESC, false);
        }
        mv.visitMethodInsn(INVOKESPECIAL, ITEM, "<init>", "(L" + PROPERTIES + ";)V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code static Item block(Block b, CreativeModeTab tab) { return new BlockItem(b, new Properties().tab(tab)); }} */
    private static byte[] modItems() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, MOD_ITEMS, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "block",
                "(L" + BLOCK + ";L" + TAB + ";)L" + ITEM + ";", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, BLOCK_ITEM);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitTypeInsn(NEW, PROPERTIES);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, PROPERTIES, "<init>", "()V", false);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESTATIC, LEGACY_TAB, "tab", TAB_DESC, false);
        mv.visitMethodInsn(INVOKESPECIAL, BLOCK_ITEM, "<init>", "(L" + BLOCK + ";L" + PROPERTIES + ";)V", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static MethodNode method(byte[] bytes, String name) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        return cn.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    private static MethodInsnNode claimAfter(MethodNode method, String constructorOwner) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.name.equals("<init>")
                    && call.owner.equals(constructorOwner)) {
                AbstractInsnNode claim = call.getNext().getNext().getNext();
                return claim instanceof MethodInsnNode c ? c : null;
            }
        }
        return null;
    }

    @Test
    @DisplayName("an item's super(properties) call reports the item and its properties")
    void superCallIsClaimed() {
        MethodNode ctor = method(LegacyCreativeTabClaimAdapter.apply(modItem(true)), "<init>");
        MethodInsnNode claim = claimAfter(ctor, ITEM);
        assertNotNull(claim, "nothing follows the super call, so the item never reaches its tab");
        assertEquals(CreativeModeTabBridge.TAB_CONTENTS, claim.owner);
        assertEquals("claim", claim.name);
        assertEquals(ALOAD, claim.getPrevious().getPrevious().getOpcode());
        assertEquals(0, ((org.objectweb.asm.tree.VarInsnNode) claim.getPrevious().getPrevious()).var,
                "the claimed object is the item under construction");
    }

    @Test
    @DisplayName("new BlockItem(block, properties) reports the new item")
    void newItemIsClaimed() {
        MethodNode block = method(LegacyCreativeTabClaimAdapter.apply(modItems()), "block");
        MethodInsnNode claim = claimAfter(block, BLOCK_ITEM);
        assertNotNull(claim, "the block item is never reported");
        assertEquals(DUP, claim.getPrevious().getPrevious().getOpcode(),
                "the constructed item has to stay on the stack for the return");
        assertNull(claimAfter(block, PROPERTIES), "Properties() itself is not an item constructor");
    }

    @Test
    @DisplayName("running the adapter twice adds one claim per constructor call")
    void adapterIsIdempotent() {
        byte[] once = LegacyCreativeTabClaimAdapter.apply(modItem(true));
        assertSame(once, LegacyCreativeTabClaimAdapter.apply(once));
    }

    @Test
    @DisplayName("a class that never calls the old tab(...) passes through unchanged")
    void modernItemIsUntouched() {
        byte[] modern = modItem(false);
        assertSame(modern, LegacyCreativeTabClaimAdapter.apply(modern));
    }

    @Test
    @DisplayName("the transform pipeline adds the claim once the tab bridge is registered")
    void transformerRunsTheAdapter() {
        byte[] out = transformer.transformClass(modItem(true), MOD_ITEM);
        assertNotNull(claimAfter(method(out, "<init>"), ITEM),
                "postProcess must run the claim adapter while the 1.19.3 tab bridge is registered");
    }

    @Test
    @DisplayName("the adapted constructor lists the item in its tab when it runs")
    void adaptedItemIsListedInItsTab() throws Exception {
        Map<String, byte[]> classes = new HashMap<>();
        classes.put(ITEM, stubItem());
        classes.put(PROPERTIES, stubClass(PROPERTIES, "java/lang/Object"));
        classes.put(TAB, stubClass(TAB, "java/lang/Object"));
        classes.put(TAB + "$DisplayItemsGenerator", stubInterface(TAB + "$DisplayItemsGenerator"));
        // The verifier checks that the title's MutableComponent is a Component.
        classes.put("net/minecraft/network/chat/Component", stubInterface("net/minecraft/network/chat/Component"));
        classes.put("net/minecraft/network/chat/MutableComponent", stubClass(
                "net/minecraft/network/chat/MutableComponent", "java/lang/Object",
                "net/minecraft/network/chat/Component"));
        classes.put(LEGACY_TAB, transformer.getSyntheticClasses().get(LEGACY_TAB));
        classes.put(MOD_ITEM, LegacyCreativeTabClaimAdapter.apply(modItem(true)));
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name.replace('.', '/'));
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        Class<?> itemClass = loader.loadClass(MOD_ITEM.replace('/', '.'));
        // A mod may pass a tab field that is still null; the item simply joins no tab.
        Object first = itemClass.getConstructor().newInstance();

        Object stubTab = loader.loadClass(TAB.replace('/', '.')).getConstructor().newInstance();
        itemClass.getField("TAB").set(null, stubTab);
        Object second = itemClass.getConstructor().newInstance();
        Object third = itemClass.getConstructor().newInstance();
        assertEquals(List.of(second, third), Arrays.asList(LegacyCreativeTabContents.contents(stubTab)),
                "each tagged item is listed once, in creation order");
        assertFalse(Arrays.asList(LegacyCreativeTabContents.contents(stubTab)).contains(first));
    }

    @Test
    @DisplayName("only items are listed, and a tab id needs the mod's own namespace")
    void onlyItemsAndModNamespaces() {
        Object properties = new Object();
        Object tab = new Object();
        LegacyCreativeTabContents.tag(properties, tab);
        LegacyCreativeTabContents.claim("not an item", properties);
        assertEquals(0, LegacyCreativeTabContents.contents(tab).length);

        assertEquals("pyrologernfriends:tabpnf_mobs", invokeTabId("pyrologernfriends", "tabpnf_mobs"));
        assertEquals("examplemod:my_tab", invokeTabId("examplemod", "My Tab"));
        assertNull(invokeTabId("minecraft", "tab"), "a tab must never land in the minecraft namespace");
        assertNull(invokeTabId(null, "tab"), "no active mod means no id to register under");
    }

    private static String invokeTabId(String namespace, String label) {
        try {
            var tabId = LegacyCreativeTabContents.class.getDeclaredMethod("tabId", String.class, String.class);
            tabId.setAccessible(true);
            return (String) tabId.invoke(null, namespace, label);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static byte[] stubInterface(String name) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, name, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] stubClass(String name, String superName, String... interfaces) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, superName, interfaces);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, superName, "<init>", "()V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code class Item { Item(Item.Properties p) {} }}: keeps no reference, like the real one. */
    private static byte[] stubItem() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, ITEM, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "(L" + PROPERTIES + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
