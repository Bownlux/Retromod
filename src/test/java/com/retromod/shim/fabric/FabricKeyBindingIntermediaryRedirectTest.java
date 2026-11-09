/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.fabric.embedded.KeyBindingShim;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Old Fabric mods build keybinds with intermediary names, and 1.21.9 to 1.21.11 dropped the String-category constructors. */
class FabricKeyBindingIntermediaryRedirectTest {

    private static final String KEY_BINDING = "net/minecraft/class_304";
    private static final String TYPED_DESC =
            "(Ljava/lang/String;Lnet/minecraft/class_3675$class_307;ILjava/lang/String;)V";
    private static final String DEFAULT_DESC = "(Ljava/lang/String;ILjava/lang/String;)V";
    private static final String SHIM = "com/retromod/shim/fabric/embedded/KeyBindingShim";

    private static final String STUBS = "com/retromod/shim/fabric/keystub/";
    private static final Map<String, String> HOST_NAMES = Map.of(
            STUBS + "KeyStubs$Id", "net/minecraft/class_2960",
            STUBS + "KeyStubs$KeyBinding", KEY_BINDING,
            STUBS + "KeyStubs$KeyBinding$Category", KEY_BINDING + "$class_11900");

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("On 1.21.11 both intermediary String-category constructors become KeyBindingShim calls")
    void redirectsIntermediaryConstructorsOnAnIntermediaryHost() {
        ClassNode output = transformOn("1.21.11");

        MethodInsnNode typed = findCall(output, SHIM, "create");
        assertNotNull(typed, "new class_304(String, class_307, int, String) must become KeyBindingShim.create");
        assertEquals(Opcodes.INVOKESTATIC, typed.getOpcode());
        assertEquals("(Ljava/lang/String;Ljava/lang/Object;ILjava/lang/String;)Ljava/lang/Object;", typed.desc);

        MethodInsnNode untyped = findCall(output, SHIM, "createDefault");
        assertNotNull(untyped, "new class_304(String, int, String) must become KeyBindingShim.createDefault");
        assertEquals(Opcodes.INVOKESTATIC, untyped.getOpcode());
        assertEquals("(Ljava/lang/String;ILjava/lang/String;)Ljava/lang/Object;", untyped.desc);

        assertFalse(hasTypeInsn(output, Opcodes.NEW, KEY_BINDING), "no NEW class_304 may remain");
        assertTrue(hasTypeInsn(output, Opcodes.CHECKCAST, KEY_BINDING),
                "the Object-returning factory needs a cast back to class_304");
    }

    @Test
    @DisplayName("On 26.1 the intermediary key is not registered, since the full remap and the 26.1 shims own keybinds")
    void leavesIntermediaryConstructorsAloneOnAnUnobfuscatedHost() {
        ClassNode output = transformOn("26.1");

        assertNull(findCall(output, SHIM, "create"));
        assertNull(findCall(output, SHIM, "createDefault"));
        assertTrue(hasTypeInsn(output, Opcodes.NEW, KEY_BINDING), "the constructor calls stay as they were");
    }

    @Test
    @DisplayName("The shim builds both keybinds and registers one category on an intermediary-named host")
    void shimBuildsKeyBindingsFromIntermediaryNames() throws Exception {
        // McReflect shares the loader so its class cache starts empty and sees the stand-ins.
        HostRenamingLoader loader = new HostRenamingLoader(
                STUBS, HOST_NAMES, KeyBindingShim.class, McReflect.class);
        Class<?> shim = loader.loadClass(KeyBindingShim.class.getName());

        Object typed = shim.getMethod("create", String.class, Object.class, int.class, String.class)
                .invoke(null, "key.jade.config", "KEYSYM", 320, "key.categories.jade");
        Object untyped = shim.getMethod("createDefault", String.class, int.class, String.class)
                .invoke(null, "key.jade.narrate", 321, "key.categories.jade");

        assertNotNull(typed, "create must find class_304 by its intermediary name");
        assertNotNull(untyped, "createDefault must use the (String, int, Category) constructor");
        assertEquals("net.minecraft.class_304", typed.getClass().getName());
        assertEquals("key.jade.config", field(typed, "translationKey"));
        assertEquals(320, field(typed, "keyCode"), "a GLFW host keeps the mod's key code");
        assertEquals("key.jade.narrate", field(untyped, "translationKey"));
        assertEquals(321, field(untyped, "keyCode"));

        Object category = field(typed, "category");
        assertSame(category, field(untyped, "category"), "one category string maps to one category");
        Object id = category.getClass().getMethod("id").invoke(category);
        assertEquals("net.minecraft.class_2960", id.getClass().getName());
        assertEquals("retromod_compat", id.getClass().getMethod("namespace").invoke(id));
        assertEquals("key_categories_jade", id.getClass().getMethod("path").invoke(id));
        Class<?> categoryClass = loader.loadClass("net.minecraft.class_304$class_11900");
        assertEquals(List.of(category), categoryClass.getField("SORT_ORDER").get(null),
                "the category is registered once so it gets a sort slot");
    }

    private ClassNode transformOn(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        new Fabric_1_21_8_to_1_21_9().registerRedirects(transformer);
        ClassNode output = new ClassNode();
        new ClassReader(transformer.transformClass(keyBindingMaker(), "test/KeyBindingMaker")).accept(output, 0);
        return output;
    }

    /** A mod class that makes one keybind with each old intermediary constructor. */
    private static byte[] keyBindingMaker() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/KeyBindingMaker", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "make", "()[Ljava/lang/Object;", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.ICONST_2);
        method.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");

        method.visitInsn(Opcodes.DUP);
        method.visitInsn(Opcodes.ICONST_0);
        method.visitTypeInsn(Opcodes.NEW, KEY_BINDING);
        method.visitInsn(Opcodes.DUP);
        method.visitLdcInsn("key.jade.config");
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitIntInsn(Opcodes.SIPUSH, 320);
        method.visitLdcInsn("key.categories.jade");
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, KEY_BINDING, "<init>", TYPED_DESC, false);
        method.visitInsn(Opcodes.AASTORE);

        method.visitInsn(Opcodes.DUP);
        method.visitInsn(Opcodes.ICONST_1);
        method.visitTypeInsn(Opcodes.NEW, KEY_BINDING);
        method.visitInsn(Opcodes.DUP);
        method.visitLdcInsn("key.jade.narrate");
        method.visitIntInsn(Opcodes.SIPUSH, 321);
        method.visitLdcInsn("key.categories.jade");
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, KEY_BINDING, "<init>", DEFAULT_DESC, false);
        method.visitInsn(Opcodes.AASTORE);

        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        return owner.getClass().getField(name).get(owner);
    }

    private static MethodInsnNode findCall(ClassNode node, String owner, String name) {
        for (var method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && owner.equals(call.owner) && name.equals(call.name)) {
                    return call;
                }
            }
        }
        return null;
    }

    private static boolean hasTypeInsn(ClassNode node, int opcode, String type) {
        for (var method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof TypeInsnNode typeInsn
                        && typeInsn.getOpcode() == opcode && type.equals(typeInsn.desc)) {
                    return true;
                }
            }
        }
        return false;
    }
}
