/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.fabric.FabricRegistryIdSynthetic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.*;

/**
 * Moonlight's block sets build Supplementaries' sign post items, then make the id they register them under, so
 * 26.3 refused the items with "Item id not set" before any registration ran.
 */
class DeferredIdStamperTest {

    private static final String RUNTIME = DeferredIdStamper.RUNTIME;
    private static final String ITEM = "net/minecraft/world/item/Item";
    private static final String ITEM_PROPERTIES = "Lnet/minecraft/world/item/Item$Properties;";
    private static final String BLOCK_ITEM = "net/minecraft/world/item/BlockItem";
    private static final String REGISTRY = "net/minecraft/core/Registry";
    private static final String ID = "Lnet/minecraft/resources/Identifier;";
    private static final String NEO_HELPER = "net/neoforged/neoforge/registries/RegisterEvent$RegisterHelper";

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("on a 26.x Fabric host the transform defers the item's id and settles it at the mod's register call")
    void fabricTransformRunsThePass(@TempDir Path dir) throws Exception {
        Map<String, byte[]> mod = BridgeHarness.only(BridgeHarness.compile(dir, SOURCES), "mod/");
        Map<String, byte[]> out = BridgeHarness.transform("26.3", t -> {
            t.registerSyntheticClass(FabricRegistryIdSynthetic.INTERNAL, FabricRegistryIdSynthetic.generate());
            DeferredIdStamper.register(t);
            // Any registered rule, as a real Fabric 26.x host always has, so the transform runs its passes.
            t.registerClassRedirect("old/Unused", "new/Unused");
        }, mod);

        List<String> calls = BridgeHarness.calls(out.get("mod/SignPosts"), "registerAll");
        assertTrue(calls.stream().anyMatch(call -> call.startsWith(RUNTIME + ".deferItem")), calls.toString());
        // The item new left on the stack is handed over right after its constructor.
        int built = indexOf(calls, "net/minecraft/world/item/Item.<init>");
        assertTrue(built >= 0 && calls.get(built + 1).startsWith(RUNTIME + ".builtItem"), calls.toString());
        int settle = indexOf(calls, RUNTIME + ".settle");
        assertTrue(settle >= 0 && calls.get(settle + 1).contains(".register("), calls.toString());
    }

    @Test
    @DisplayName("without the rebuilt class registered, as on hosts before 1.21.2, the transform leaves both alone")
    void withoutTheRuntimeNothingChanges(@TempDir Path dir) throws Exception {
        Map<String, byte[]> mod = BridgeHarness.only(BridgeHarness.compile(dir, SOURCES), "mod/");
        Map<String, byte[]> out = BridgeHarness.transform("26.3", t -> {
            t.registerSyntheticClass(FabricRegistryIdSynthetic.INTERNAL, FabricRegistryIdSynthetic.generate());
            t.registerClassRedirect("old/Unused", "new/Unused");
        }, mod);

        List<String> calls = BridgeHarness.calls(out.get("mod/SignPosts"), "registerAll");
        assertFalse(calls.stream().anyMatch(call -> call.startsWith(RUNTIME)), calls.toString());
    }

    @Test
    @DisplayName("a Minecraft item constructor takes its properties through deferItem after the id bridge's stamp, and binds the item")
    void constructorGetsThePlaceholderAfterTheStamp() {
        byte[] stamped = ConstructorIdStamper.apply(signPostItem(), "com/retromod/shim/forge/embedded/RegistryIdBridge");
        byte[] deferred = DeferredIdStamper.apply(stamped);

        assertEquals(List.of("com/retromod/shim/forge/embedded/RegistryIdBridge.applyItem", RUNTIME + ".deferItem",
                BLOCK_ITEM + ".<init>", RUNTIME + ".builtItem"), calls(deferred, "<init>"));
        verify(deferred);
    }

    @Test
    @DisplayName("Registry.register and the 26.x register bridges settle the value's id right before they run")
    void registrationsSettleFirst() {
        byte[] out = DeferredIdStamper.apply(registrar());

        assertEquals(List.of(RUNTIME + ".settle", REGISTRY + ".register"), calls(out, "plain"));
        assertEquals(List.of(RUNTIME + ".settle", WorldgenTypeBridgeSynthetic.INTERNAL + ".register"), calls(out, "bridged"));
        assertEquals(List.of(RUNTIME + ".settle", "com/retromod/embedded/mod_1/com/retromod/shim/forge/embedded/"
                + "LateVanillaRegistration.register"), calls(out, "late"));
        assertEquals(List.of(RUNTIME + ".settle", NEO_HELPER + ".register"), calls(out, "helper"));
        verify(out);
    }

    @Test
    @DisplayName("a call that takes no id before an Object value, or a String id outside Minecraft's helper, is left alone")
    void otherCallsAreLeftAlone() {
        MethodInsnNode supplier = new MethodInsnNode(INVOKEVIRTUAL, "net/neoforged/neoforge/registries/RegisterEvent",
                "register", "(Lnet/minecraft/resources/ResourceKey;" + ID + "Ljava/util/function/Supplier;)V", false);
        MethodInsnNode namedHelper = new MethodInsnNode(INVOKEINTERFACE, NEO_HELPER, "register",
                "(Ljava/lang/String;Ljava/lang/Object;)V", true);
        MethodInsnNode get = new MethodInsnNode(INVOKEINTERFACE, REGISTRY, "getValue", "(" + ID + ")Ljava/lang/Object;", true);

        assertEquals(false, DeferredIdStamper.isRegistration(supplier));
        assertEquals(false, DeferredIdStamper.isRegistration(namedHelper));
        assertEquals(false, DeferredIdStamper.isRegistration(get));
    }

    @Test
    @DisplayName("Retromod's own classes and Minecraft's are returned unchanged")
    void ownAndHostClassesAreSkipped() {
        byte[] own = renamed(registrar(), "com/retromod/shim/common/embedded/Registrar");
        byte[] host = renamed(registrar(), "net/minecraft/data/Registrar");

        assertArrayEquals(own, DeferredIdStamper.apply(own));
        assertArrayEquals(host, DeferredIdStamper.apply(host));
    }

    private static final Map<String, String> SOURCES = Map.of(
            "net/minecraft/resources/Identifier.java", """
                    package net.minecraft.resources;
                    public record Identifier(String namespace, String path) {
                        public static Identifier fromNamespaceAndPath(String namespace, String path) { return new Identifier(namespace, path); }
                    }""",
            "net/minecraft/core/Registry.java", """
                    package net.minecraft.core;
                    import net.minecraft.resources.Identifier;
                    public interface Registry<T> {
                        static <V, T extends V> T register(Registry<V> registry, Identifier id, T value) { return value; }
                    }""",
            "net/minecraft/world/item/Item.java", """
                    package net.minecraft.world.item;
                    public class Item {
                        public Item(Properties properties) {}
                        public static class Properties {}
                    }""",
            "mod/SignPosts.java", """
                    package mod;
                    import java.util.List;
                    import net.minecraft.core.Registry;
                    import net.minecraft.resources.Identifier;
                    import net.minecraft.world.item.Item;
                    public class SignPosts {
                        public static void registerAll(Registry<Item> items, List<String> woods) {
                            for (String wood : woods) {
                                Item item = new Item(new Item.Properties());
                                Registry.register(items, res("way_sign_" + wood), item);
                            }
                        }
                        static Identifier res(String name) { return Identifier.fromNamespaceAndPath("supplementaries", name); }
                    }""");

    @Test
    @DisplayName("for a mod that sets its own ids only the registration calls are touched")
    void ownIdsKeepConstructorsPlain() {
        byte[] stamped = DeferredIdStamper.apply(signPostItem(), false);
        assertEquals(List.of(BLOCK_ITEM + ".<init>"), calls(stamped, "<init>"));

        byte[] out = DeferredIdStamper.apply(registrar(), false);
        assertEquals(List.of(RUNTIME + ".settle", REGISTRY + ".register"), calls(out, "plain"));
    }

    @Test
    @DisplayName("a mixin, whose code Minecraft's own class runs, is left unchanged")
    void mixinsAreSkipped() {
        ClassNode node = read(registrar());
        node.visibleAnnotations = new ArrayList<>(List.of(new org.objectweb.asm.tree.AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;")));
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        byte[] mixin = writer.toByteArray();

        assertArrayEquals(mixin, DeferredIdStamper.apply(mixin));
    }

    /** {@code class SignPostItem extends BlockItem { SignPostItem(Block b, Properties p, Object wood) { super(b, p); } }}. */
    private static byte[] signPostItem() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC, "mod/SignPostItem", null, BLOCK_ITEM, null);
        String block = "Lnet/minecraft/world/level/block/Block;";
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(" + block + ITEM_PROPERTIES + "Ljava/lang/Object;)V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitVarInsn(ALOAD, 2);
        init.visitMethodInsn(INVOKESPECIAL, BLOCK_ITEM, "<init>", "(" + block + ITEM_PROPERTIES + ")V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** One method per registration shape, each registering an item built earlier under an id passed as a local. */
    private static byte[] registrar() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(V21, ACC_PUBLIC, "mod/Registrar", null, "java/lang/Object", null);
        String registry = "Lnet/minecraft/core/Registry;";
        String register = "(" + registry + ID + "Ljava/lang/Object;)Ljava/lang/Object;";
        staticRegistration(cw, "plain", REGISTRY, register, true);
        staticRegistration(cw, "bridged", WorldgenTypeBridgeSynthetic.INTERNAL, register, true);
        staticRegistration(cw, "late", "com/retromod/embedded/mod_1/com/retromod/shim/forge/embedded/LateVanillaRegistration",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);

        MethodVisitor helper = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "helper", "(L" + NEO_HELPER + ";" + ID + ")V", null, null);
        helper.visitCode();
        helper.visitVarInsn(ALOAD, 0);
        helper.visitVarInsn(ALOAD, 1);
        helper.visitInsn(ACONST_NULL);
        helper.visitMethodInsn(INVOKEINTERFACE, NEO_HELPER, "register", "(" + ID + "Ljava/lang/Object;)V", true);
        helper.visitInsn(RETURN);
        helper.visitMaxs(0, 0);
        helper.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void staticRegistration(ClassWriter cw, String name, String owner, String desc, boolean itf) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name,
                "(Lnet/minecraft/core/Registry;" + ID + "L" + ITEM + ";)V", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitVarInsn(ALOAD, 1);
        m.visitVarInsn(ALOAD, 2);
        m.visitMethodInsn(INVOKESTATIC, owner, "register", desc, itf);
        m.visitInsn(POP);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    private static int indexOf(List<String> calls, String prefix) {
        for (int i = 0; i < calls.size(); i++) {
            if (calls.get(i).startsWith(prefix)) return i;
        }
        return -1;
    }

    private static List<String> calls(byte[] bytes, String method) {
        List<String> calls = new ArrayList<>();
        for (MethodNode m : read(bytes).methods) {
            if (!m.name.equals(method)) continue;
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
            }
        }
        return calls;
    }

    private static byte[] renamed(byte[] bytes, String name) {
        ClassNode node = read(bytes);
        node.name = name;
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void verify(byte[] bytes) {
        new ClassReader(bytes).accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
