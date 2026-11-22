/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.objectweb.asm.Opcodes.*;

/** Keeps an old Fabric elytra item that implements Fabric API's removed {@code FabricElytraItem} loadable on 26.x. */
public final class LegacyFabricElytraItemBridge {

    static final String OLD = "net/fabricmc/fabric/api/entity/event/v1/FabricElytraItem";
    /** The generated stand-in, embedded per mod. */
    public static final String STAND_IN = "com/retromod/generated/LegacyFabricElytraItem";
    public static final String FLAG = "legacyFabricElytraItem";
    static final String PROPERTIES = "net/minecraft/world/item/Item$Properties";
    static final String GLIDER_DESC = "(L" + PROPERTIES + ";)L" + PROPERTIES + ";";

    private static final String ENTITY = "Lnet/minecraft/world/entity/LivingEntity;";
    private static final String STACK = "Lnet/minecraft/world/item/ItemStack;";

    private LegacyFabricElytraItemBridge() {}

    /**
     * Fabric API dropped the interface when 1.21.2 moved gliding to the glider item component, and
     * nothing on the host calls its methods any more. An item that implements it, such as Deeper
     * Darker's soul elytra, failed with {@code NoClassDefFoundError} while the mod registered its items.
     */
    public static void register(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(STAND_IN, generate());
        transformer.registerClassRedirect(OLD, STAND_IN);
        transformer.enablePostProcess(FLAG);
    }

    /**
     * Gives an item that implements the old interface the glider component and a chest slot, which make an item an
     * elytra now: its constructor's properties gain them right before the super call.
     */
    public static byte[] apply(byte[] classBytes) {
        if (classBytes == null) return null;
        ClassReader reader = new ClassReader(classBytes);
        if (!java.util.Arrays.asList(reader.getInterfaces()).contains(STAND_IN)) return classBytes;
        ClassNode node = new ClassNode();
        reader.accept(node, 0);
        int changed = 0;
        for (MethodNode method : node.methods) {
            if (!method.name.equals("<init>")) continue;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                // The super call that takes the properties last; a this(...) call reaches one in another constructor.
                if (insn instanceof MethodInsnNode call && call.getOpcode() == INVOKESPECIAL && call.name.equals("<init>")
                        && call.owner.equals(node.superName) && call.desc.endsWith("L" + PROPERTIES + ";)V")) {
                    method.instructions.insertBefore(call, new MethodInsnNode(INVOKESTATIC, STAND_IN, "withGlider", GLIDER_DESC, true));
                    changed++;
                    break;
                }
            }
        }
        if (changed == 0) return classBytes;
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** The stand-in: the old default methods, inert, so an item's own overrides and super calls still link. */
    static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, STAND_IN, null, "java/lang/Object", null);

        // Gliding comes from the glider component now, so the old hook reports that it did nothing.
        MethodVisitor use = cw.visitMethod(ACC_PUBLIC, "useCustomElytra", "(" + ENTITY + STACK + "Z)Z", null, null);
        use.visitCode();
        use.visitInsn(ICONST_0);
        use.visitInsn(IRETURN);
        use.visitMaxs(0, 0);
        use.visitEnd();

        MethodVisitor tick = cw.visitMethod(ACC_PUBLIC, "doVanillaElytraTick", "(" + ENTITY + STACK + ")V", null, null);
        tick.visitCode();
        tick.visitInsn(RETURN);
        tick.visitMaxs(0, 0);
        tick.visitEnd();

        // static Properties withGlider(Properties p) { return p.component(GLIDER, Unit.INSTANCE).equippable(CHEST); }
        MethodVisitor glider = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "withGlider", GLIDER_DESC, null, null);
        glider.visitCode();
        glider.visitVarInsn(ALOAD, 0);
        glider.visitFieldInsn(GETSTATIC, "net/minecraft/core/component/DataComponents", "GLIDER",
                "Lnet/minecraft/core/component/DataComponentType;");
        glider.visitFieldInsn(GETSTATIC, "net/minecraft/util/Unit", "INSTANCE", "Lnet/minecraft/util/Unit;");
        glider.visitMethodInsn(INVOKEVIRTUAL, PROPERTIES, "component",
                "(Lnet/minecraft/core/component/DataComponentType;Ljava/lang/Object;)L" + PROPERTIES + ";", false);
        glider.visitFieldInsn(GETSTATIC, "net/minecraft/world/entity/EquipmentSlot", "CHEST", "Lnet/minecraft/world/entity/EquipmentSlot;");
        glider.visitMethodInsn(INVOKEVIRTUAL, PROPERTIES, "equippable", "(Lnet/minecraft/world/entity/EquipmentSlot;)L" + PROPERTIES + ";", false);
        glider.visitInsn(ARETURN);
        glider.visitMaxs(0, 0);
        glider.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
