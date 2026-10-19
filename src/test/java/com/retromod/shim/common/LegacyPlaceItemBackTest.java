/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.retromod.shim.common.Legacy263MemberBridge.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tiny Dragons Reforged, a 1.21.11 Fabric mod, on 26.3 (#336): its dragon menu hands the items
 * back with {@code Inventory.placeItemBackInInventory(stack)} as it closes. 26.3 added a
 * {@code Prediction} for the drop that happens when the inventory is full, so closing the menu
 * failed with {@code NoSuchMethodError}.
 */
class LegacyPlaceItemBackTest {

    private static final String MENU = "test/mod/DragonMenu";

    private RetromodTransformer transformer;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        registerRedirects(transformer, new Members(false, false, false, false, true));
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("closing a menu hands items back with SERVER_ONLY, as 26.3's own menus do")
    void placesBackWithServerOnly() throws Exception {
        assertEquals(List.of("placeItemBackInInventory(stack, SERVER_ONLY)"), run("removed"));
        assertEquals(List.of("placeItemBackInInventory(stack, false, SERVER_ONLY)"), run("removedQuietly"));
    }

    @Test
    @DisplayName("a host whose inventory still takes the old forms keeps the mod's calls")
    void gatedOnTheHostShape() {
        ClassNode prediction = new ClassNode();
        prediction.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "SERVER_ONLY",
                "L" + PREDICTION + ";", null, null));
        assertTrue(placeBackTakesPrediction(inventory(PLACE_BACK_NEW, PLACE_BACK_FLAG_NEW), prediction));
        assertFalse(placeBackTakesPrediction(inventory(PLACE_BACK_OLD, PLACE_BACK_FLAG_OLD), prediction),
                "26.2 still has placeItemBackInInventory(stack)");
    }

    @Test
    @DisplayName("the helper with only the inventory members passes the stack verifier")
    void helperVerifies() throws Exception {
        ClassNode node = new ClassNode();
        new ClassReader(generateHelper(new Members(false, false, false, false, true))).accept(node, 0);
        assertEquals(2, node.methods.size(), "one helper for each old form");
        for (MethodNode method : node.methods) new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
    }

    private static ClassNode inventory(String... placeBackForms) {
        ClassNode node = new ClassNode();
        node.name = INVENTORY;
        for (String desc : placeBackForms) {
            node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "placeItemBackInInventory", desc, null, null));
        }
        return node;
    }

    @SuppressWarnings("unchecked")
    private List<String> run(String method) throws Exception {
        ClassLoader host = hostWith(transformer.transformClass(menu(), MENU));
        Class<?> inventoryClass = host.loadClass(INVENTORY.replace('/', '.'));
        Object inventory = inventoryClass.getConstructor().newInstance();
        Object stack = host.loadClass(ITEM_STACK.replace('/', '.')).getConstructor().newInstance();
        host.loadClass(MENU.replace('/', '.')).getMethod(method, inventoryClass, stack.getClass())
                .invoke(null, inventory, stack);
        return (List<String>) inventoryClass.getField("calls").get(inventory);
    }

    /**
     * A 26.2 menu:
     * <pre>
     * static void removed(Inventory inventory, ItemStack stack) { inventory.placeItemBackInInventory(stack); }
     * static void removedQuietly(Inventory inventory, ItemStack stack) {
     *     inventory.placeItemBackInInventory(stack, false); }
     * </pre>
     */
    private static byte[] menu() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, MENU, null, "java/lang/Object", null);
        for (boolean flag : new boolean[] {false, true}) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    flag ? "removedQuietly" : "removed", "(L" + INVENTORY + ";L" + ITEM_STACK + ";)V", null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            if (flag) mv.visitInsn(Opcodes.ICONST_0);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, INVENTORY, "placeItemBackInInventory",
                    flag ? PLACE_BACK_FLAG_OLD : PLACE_BACK_OLD, false);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private ClassLoader hostWith(byte[] menuClass) throws Exception {
        Map<String, byte[]> classes = new HashMap<>(compile263StandIns());
        classes.put(MENU.replace('/', '.'), menuClass);
        transformer.getSyntheticClasses().forEach((name, bytes) -> classes.put(name.replace('/', '.'), bytes));
        return new ClassLoader(null) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes == null) return ClassLoader.getPlatformClassLoader().loadClass(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }

    /** The 26.3 inventory shape, checked against the 26.3 client jar with javap. */
    private Map<String, byte[]> compile263StandIns() throws Exception {
        Map<String, String> sources = new HashMap<>();
        sources.put("net/minecraft/util/Prediction.java",
                "package net.minecraft.util; public enum Prediction { PREDICTED, SERVER_ONLY }");
        sources.put("net/minecraft/world/item/ItemStack.java",
                "package net.minecraft.world.item; public class ItemStack { public ItemStack() {} }");
        sources.put("net/minecraft/world/entity/player/Inventory.java", """
                package net.minecraft.world.entity.player;
                import java.util.ArrayList;
                import java.util.List;
                import net.minecraft.util.Prediction;
                import net.minecraft.world.item.ItemStack;
                public class Inventory {
                    public final List<String> calls = new ArrayList<>();
                    public Inventory() {}
                    public void placeItemBackInInventory(ItemStack stack, Prediction prediction) {
                        calls.add("placeItemBackInInventory(stack, " + prediction + ")");
                    }
                    public void placeItemBackInInventory(ItemStack stack, boolean sendPacket, Prediction prediction) {
                        calls.add("placeItemBackInInventory(stack, " + sendPacket + ", " + prediction + ")");
                    }
                }""");

        Path src = tempDir.resolve("src");
        Path out = tempDir.resolve("out");
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), "--release", "17"));
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = src.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            args.add(file.toString());
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "the tests need a JDK to compile the 26.3 stand-ins");
        assertEquals(0, compiler.run(null, null, null, args.toArray(new String[0])),
                "the 26.3 stand-ins must compile");

        Map<String, byte[]> classes = new HashMap<>();
        try (var files = Files.walk(out)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".class"))::iterator) {
                String name = out.relativize(file).toString().replace(java.io.File.separatorChar, '.');
                classes.put(name.substring(0, name.length() - ".class".length()), Files.readAllBytes(file));
            }
        }
        return classes;
    }
}
