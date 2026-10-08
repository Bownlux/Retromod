/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Bridges Forge's supplier constructors for fluid blocks and buckets onto NeoForge 1.21.
 *
 * <p>Forge let a mod build {@code LiquidBlock}, {@code BucketItem} and {@code MobBucketItem} from
 * suppliers, because its fluids registered after its blocks and items. NeoForge 1.21 removed those
 * constructors and registers fluids before blocks and items, so the supplier can be read when the
 * block or item is built. Each supplier is read and cast to the type the modern constructor takes.
 * A direct {@code new} goes through a generated factory; a mod subclass's {@code super(...)}
 * converts its arguments through a static call and reads them back, since its uninitialized self
 * cannot be passed to a helper. A supplier whose fluid is not registered yet fails the same way
 * the modern constructor would.
 */
public final class LegacyFluidSupplierBridge {

    static final String HELPER = "com/retromod/generated/LegacyFluidSuppliers";

    private static final String SUPPLIER = "Ljava/util/function/Supplier;";

    /** A Forge supplier constructor and the NeoForge constructor that replaced it. */
    record Constructor(String owner, String oldDesc, String newDesc) {
        String simpleName() {
            return owner.substring(owner.lastIndexOf('/') + 1);
        }

        String factoryDesc() {
            return oldDesc.substring(0, oldDesc.lastIndexOf(')') + 1) + "L" + owner + ";";
        }
    }

    static List<Constructor> candidates() {
        String blockProps = "Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;";
        String itemProps = "Lnet/minecraft/world/item/Item$Properties;";
        String fluid = "Lnet/minecraft/world/level/material/Fluid;";
        return List.of(
            new Constructor("net/minecraft/world/level/block/LiquidBlock", "(" + SUPPLIER + blockProps + ")V",
                    "(Lnet/minecraft/world/level/material/FlowingFluid;" + blockProps + ")V"),
            new Constructor("net/minecraft/world/item/BucketItem", "(" + SUPPLIER + itemProps + ")V",
                    "(" + fluid + itemProps + ")V"),
            new Constructor("net/minecraft/world/item/MobBucketItem",
                    "(" + SUPPLIER + SUPPLIER + SUPPLIER + itemProps + ")V",
                    "(Lnet/minecraft/world/entity/EntityType;" + fluid + "Lnet/minecraft/sounds/SoundEvent;"
                            + itemProps + ")V"));
    }

    private LegacyFluidSupplierBridge() {}

    /** Registers the constructors this host replaced; the host classes must be readable. */
    public static void register(RetromodTransformer transformer) {
        List<Constructor> replaced = new ArrayList<>();
        for (Constructor constructor : candidates()) {
            ClassNode owner = ClassResourceInspector.read(constructor.owner());
            if (owner != null && !hasMethod(owner, constructor.oldDesc()) && hasMethod(owner, constructor.newDesc())) {
                replaced.add(constructor);
            }
        }
        if (!replaced.isEmpty()) registerRedirects(transformer, replaced);
    }

    /** Unconditional registration for transform-shape tests. */
    static void registerRedirects(RetromodTransformer transformer, List<Constructor> constructors) {
        transformer.registerSyntheticClass(HELPER, generateHelper(constructors));
        for (Constructor constructor : constructors) {
            transformer.registerConstructorRedirect(constructor.owner(), constructor.oldDesc(), HELPER,
                    "new" + constructor.simpleName(), constructor.factoryDesc());
            Type[] newParams = Type.getArgumentTypes(constructor.newDesc());
            transformer.registerSuperConstructorAdapter(constructor.owner(), constructor.oldDesc(),
                    constructor.newDesc(), mv -> {
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "stash" + constructor.simpleName(),
                                constructor.oldDesc(), false);
                        for (int i = 0; i < newParams.length; i++) {
                            mv.visitLdcInsn(i);
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "take", "(I)Ljava/lang/Object;", false);
                            mv.visitTypeInsn(Opcodes.CHECKCAST, newParams[i].getInternalName());
                        }
                    });
        }
    }

    private static boolean hasMethod(ClassNode owner, String desc) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals("<init>") && method.desc.equals(desc)) return true;
        }
        return false;
    }

    static byte[] generateHelper(List<Constructor> constructors) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                // Minecraft is not on Retromod's classpath when this class is generated.
                return "java/lang/Object";
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                HELPER, null, "java/lang/Object", null);
        // Registration can run on several threads, so each keeps its own converted arguments.
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "STASH",
                "Ljava/lang/ThreadLocal;", null, null).visitEnd();
        MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(Opcodes.NEW, "java/lang/ThreadLocal");
        clinit.visitInsn(Opcodes.DUP);
        clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/ThreadLocal", "<init>", "()V", false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, HELPER, "STASH", "Ljava/lang/ThreadLocal;");
        clinit.visitInsn(Opcodes.RETURN);
        end(clinit);

        MethodVisitor take = begin(cw, "take", "(I)Ljava/lang/Object;");
        take.visitFieldInsn(Opcodes.GETSTATIC, HELPER, "STASH", "Ljava/lang/ThreadLocal;");
        take.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/ThreadLocal", "get", "()Ljava/lang/Object;", false);
        take.visitTypeInsn(Opcodes.CHECKCAST, "[Ljava/lang/Object;");
        take.visitVarInsn(Opcodes.ILOAD, 0);
        take.visitInsn(Opcodes.AALOAD);
        take.visitInsn(Opcodes.ARETURN);
        end(take);

        for (Constructor constructor : constructors) {
            Type[] oldParams = Type.getArgumentTypes(constructor.oldDesc());
            Type[] newParams = Type.getArgumentTypes(constructor.newDesc());

            MethodVisitor stash = begin(cw, "stash" + constructor.simpleName(), constructor.oldDesc());
            stash.visitFieldInsn(Opcodes.GETSTATIC, HELPER, "STASH", "Ljava/lang/ThreadLocal;");
            stash.visitLdcInsn(newParams.length);
            stash.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
            for (int i = 0; i < newParams.length; i++) {
                stash.visitInsn(Opcodes.DUP);
                stash.visitLdcInsn(i);
                loadConverted(stash, oldParams[i], newParams[i], i);
                stash.visitInsn(Opcodes.AASTORE);
            }
            stash.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/ThreadLocal", "set", "(Ljava/lang/Object;)V", false);
            stash.visitInsn(Opcodes.RETURN);
            end(stash);

            MethodVisitor factory = begin(cw, "new" + constructor.simpleName(), constructor.factoryDesc());
            factory.visitTypeInsn(Opcodes.NEW, constructor.owner());
            factory.visitInsn(Opcodes.DUP);
            for (int i = 0; i < newParams.length; i++) loadConverted(factory, oldParams[i], newParams[i], i);
            factory.visitMethodInsn(Opcodes.INVOKESPECIAL, constructor.owner(), "<init>", constructor.newDesc(), false);
            factory.visitInsn(Opcodes.ARETURN);
            end(factory);
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Every parameter is a reference, so parameter {@code i} sits in slot {@code i}. */
    private static void loadConverted(MethodVisitor mv, Type old, Type current, int slot) {
        mv.visitVarInsn(Opcodes.ALOAD, slot);
        if (old.getDescriptor().equals(SUPPLIER) && !current.getDescriptor().equals(SUPPLIER)) {
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Supplier", "get",
                    "()Ljava/lang/Object;", true);
            mv.visitTypeInsn(Opcodes.CHECKCAST, current.getInternalName());
        }
    }

    private static MethodVisitor begin(ClassWriter cw, String name, String desc) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        return mv;
    }

    private static void end(MethodVisitor mv) {
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
