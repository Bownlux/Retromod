/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** #408 (Monke Madness, Forge 1.18.2 on Forge 1.20.1): Forge 1.19 renamed IItemRenderProperties and its methods. */
class LegacyRenderPropertiesBridgeTest {

    private static final String OLD = LegacyRenderPropertiesBridge.OLD_ITEM;
    private static final String NEW = LegacyRenderPropertiesBridge.ITEM_EXT;
    private static final String RENDERER = "Lnet/minecraft/client/renderer/BlockEntityWithoutLevelRenderer;";
    private static final String HUMANOID = "Lnet/minecraft/client/model/HumanoidModel;";
    private static final String ARMOR = "(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;"
            + "Lnet/minecraft/world/entity/EquipmentSlot;" + HUMANOID + ")" + HUMANOID;

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyRenderPropertiesBridge.register(transformer);
    }

    @AfterEach
    void tearDown() {
        transformer.clearJarClassBytesProvider();
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("an item renderer under the old name answers getCustomRenderer and implements the client extensions")
    void rendererAnswersTheNewName() {
        String name = "test/mm/PanFluteRenderProperties";
        ClassNode out = transform(name, implementing(name, "java/lang/Object", OLD, "getItemStackRenderer", "()" + RENDERER));
        assertEquals(List.of(NEW), out.interfaces);
        MethodNode bridge = method(out, "getCustomRenderer", "()" + RENDERER);
        assertEquals(List.of(name + ".getItemStackRenderer"), calls(bridge), "the bridge must ask the mod's own renderer");
        assertNotNull(method(out, "getItemStackRenderer", "()" + RENDERER), "the old method stays for the mod's own calls");
    }

    @Test
    @DisplayName("an old armor model hook that answered null gives the host's model back instead")
    void armorHookNeverAnswersNull() {
        String name = "test/quark/HatItem$1";
        ClassNode out = transform(name, implementing(name, "java/lang/Object", OLD, "getArmorModel", ARMOR));
        MethodNode bridge = method(out, "getHumanoidArmorModel", ARMOR);
        assertEquals(List.of(name + ".getArmorModel", "java/util/Objects.requireNonNullElse"), calls(bridge));
        assertTrue(instructions(bridge).stream().anyMatch(i -> i instanceof TypeInsnNode t
                && t.getOpcode() == CHECKCAST && t.desc.equals("net/minecraft/client/model/HumanoidModel")));
        new ClassReader(write(out)).accept(new CheckClassAdapter(new ClassWriter(0), false), 0);
    }

    @Test
    @DisplayName("a subclass that only overrides the old method gets the bridge through its parent in the jar")
    void subclassInTheJarIsCovered() {
        String parent = "test/mod/BaseProperties";
        String child = "test/mod/ChildProperties";
        byte[] parentBytes = implementing(parent, "java/lang/Object", OLD, "getItemStackRenderer", "()" + RENDERER);
        transformer.setJarClassBytesProvider(Map.of(parent, parentBytes)::get);
        ClassNode out = transform(child, implementing(child, parent, null, "getItemStackRenderer", "()" + RENDERER));
        assertNotNull(method(out, "getCustomRenderer", "()" + RENDERER));
    }

    @Test
    @DisplayName("calls through the old interface, its DUMMY, and RenderProperties.get reach the client extensions")
    void oldCallsMove() {
        String name = "test/mod/Caller";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "run", "(Lnet/minecraft/world/item/Item;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, LegacyRenderPropertiesBridge.OLD_LOOKUP, "get",
                "(Lnet/minecraft/world/item/Item;)L" + OLD + ";", false);
        mv.visitMethodInsn(INVOKEINTERFACE, OLD, "getItemStackRenderer", "()" + RENDERER, true);
        mv.visitInsn(POP);
        mv.visitFieldInsn(GETSTATIC, OLD, "DUMMY", "L" + OLD + ";");
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode out = transform(name, cw.toByteArray());
        MethodNode run = method(out, "run", "(Lnet/minecraft/world/item/Item;)V");
        assertEquals(List.of(NEW + ".of", NEW + ".getCustomRenderer"), calls(run));
        FieldInsnNode dummy = instructions(run).stream().filter(FieldInsnNode.class::isInstance)
                .map(FieldInsnNode.class::cast).findFirst().orElseThrow();
        assertEquals(NEW + ".DEFAULT", dummy.owner + "." + dummy.name);
    }

    @Test
    @DisplayName("an old one-argument getFont call asks the client extensions for the item count font, in the host loader's names")
    void oneArgumentGetFont() {
        String stack = "Lnet/minecraft/world/item/ItemStack;";
        String font = "Lnet/minecraft/client/gui/Font;";
        String helper = LegacyRenderPropertiesBridge.FONT_HELPER;
        String name = "test/jei/RenderHelper";
        // PuzzlesLib forwards through the old interface; JEI calls the new one, which Forge 1.19 no longer answers in one argument.
        for (String owner : List.of(OLD, NEW)) {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null);
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "font", "(L" + owner + ";" + stack + ")" + font, null, null);
            mv.visitCode();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEINTERFACE, owner, "getFont", "(" + stack + ")" + font, true);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
            cw.visitEnd();
            MethodInsnNode call = (MethodInsnNode) instructions(method(transform(name, cw.toByteArray()), "font",
                    "(L" + NEW + ";" + stack + ")" + font)).stream().filter(MethodInsnNode.class::isInstance).findFirst().orElseThrow();
            assertEquals(helper + ".getFont(Ljava/lang/Object;" + stack + ")" + font, call.owner + "." + call.name + call.desc, owner);
        }

        for (String ext : List.of(NEW, LegacyRenderPropertiesBridge.NEO_ITEM_EXT)) {
            ClassNode generated = new ClassNode();
            new ClassReader(LegacyRenderPropertiesBridge.fontHelper(ext)).accept(generated, 0);
            List<AbstractInsnNode> body = instructions(method(generated, "getFont", "(Ljava/lang/Object;" + stack + ")" + font));
            assertTrue(body.stream().anyMatch(i -> i instanceof FieldInsnNode f && f.owner.equals(ext + "$FontContext")
                    && f.name.equals("ITEM_COUNT")), "the old call asked about the item, which the item count font answers");
            assertTrue(body.stream().anyMatch(i -> i instanceof MethodInsnNode m && m.owner.equals(ext) && m.name.equals("getFont")
                    && m.desc.equals("(" + stack + "L" + ext + "$FontContext;)" + font)));
            new ClassReader(LegacyRenderPropertiesBridge.fontHelper(ext)).accept(new CheckClassAdapter(new ClassWriter(0), false), 0);
        }
        assertNotNull(transformer.getSyntheticClasses().get(helper), "the helper travels with the mod");
    }

    @Test
    @DisplayName("a block's old render properties keep their methods under the new interface")
    void blockPropertiesMove() {
        String name = "test/create/ReducedDestroyEffects";
        String destroy = "(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;"
                + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/client/particle/ParticleEngine;)Z";
        ClassNode out = transform(name, implementing(name, "java/lang/Object", LegacyRenderPropertiesBridge.OLD_BLOCK,
                "addDestroyEffects", destroy));
        assertEquals(List.of(LegacyRenderPropertiesBridge.BLOCK_EXT), out.interfaces);
        assertNotNull(method(out, "addDestroyEffects", destroy));
    }

    /** A class with one public method {@code name(desc)} returning null or false, listing {@code iface} when given. */
    private static byte[] implementing(String className, String superName, String iface, String name, String desc) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, className, null, superName, iface == null ? null : new String[]{iface});
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, superName, "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC, name, desc, null, null);
        m.visitCode();
        if (desc.endsWith(")Z")) {
            m.visitInsn(ICONST_0);
            m.visitInsn(IRETURN);
        } else {
            m.visitInsn(ACONST_NULL);
            m.visitInsn(ARETURN);
        }
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private ClassNode transform(String name, byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(transformer.transformClass(bytes, name)).accept(cn, 0);
        return cn;
    }

    private static byte[] write(ClassNode node) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElse(null);
    }

    private static List<AbstractInsnNode> instructions(MethodNode method) {
        List<AbstractInsnNode> out = new ArrayList<>();
        method.instructions.forEach(out::add);
        return out;
    }

    private static List<String> calls(MethodNode method) {
        assertNotNull(method, "the method must exist");
        return instructions(method).stream().filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast)
                .map(call -> call.owner + "." + call.name).toList();
    }
}
