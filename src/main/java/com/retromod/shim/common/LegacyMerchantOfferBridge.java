/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;

import static org.objectweb.asm.Opcodes.*;

/** Keeps the trade offer constructors that priced a trade in item stacks, which 1.20.5 replaced with item costs. */
public final class LegacyMerchantOfferBridge {

    static final String HELPER = "com/retromod/generated/LegacyMerchantOffers";

    private static final String OFFER = "net/minecraft/world/item/trading/MerchantOffer";
    private static final String COST = "net/minecraft/world/item/trading/ItemCost";
    private static final String STACK = "net/minecraft/world/item/ItemStack";
    private static final String S = "L" + STACK + ";";
    private static final String C = "L" + COST + ";";
    private static final String OPTIONAL = "Ljava/util/Optional;";

    /** Each old constructor, the current one it becomes, and whether it had a second price. */
    record Shape(String oldDesc, String newDesc, boolean secondPrice) {}

    static final List<Shape> SHAPES = List.of(
            // (price, result, maxUses, xp, priceMultiplier)
            new Shape("(" + S + S + "IIF)V", "(" + C + S + "IIF)V", false),
            // (price, secondPrice, result, maxUses, xp, priceMultiplier)
            new Shape("(" + S + S + S + "IIF)V", "(" + C + OPTIONAL + S + "IIF)V", true),
            // (price, secondPrice, result, uses, maxUses, xp, priceMultiplier)
            new Shape("(" + S + S + S + "IIIF)V", "(" + C + OPTIONAL + S + "IIIF)V", true),
            // (price, secondPrice, result, uses, maxUses, xp, priceMultiplier, demand)
            new Shape("(" + S + S + S + "IIIFI)V", "(" + C + OPTIONAL + S + "IIIFI)V", true));

    private LegacyMerchantOfferBridge() {}

    /** Routes each old constructor the host no longer has to a factory in the generated helper. */
    public static void register(RetromodTransformer transformer) {
        ClassNode offer = ClassResourceInspector.read(OFFER);
        boolean registered = false;
        for (Shape shape : SHAPES) {
            if (!removedOnHost(offer, shape.oldDesc())) continue;
            transformer.registerConstructorRedirect(OFFER, shape.oldDesc(), HELPER, "create", factoryDesc(shape.oldDesc()));
            registered = true;
        }
        if (registered) transformer.registerSyntheticClass(HELPER, generate());
    }

    // Offline, where the host cannot be read, the version decides.
    private static boolean removedOnHost(ClassNode offer, String desc) {
        if (offer == null) return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "1.20.5") >= 0;
        return offer.methods.stream().noneMatch(method -> method.name.equals("<init>") && method.desc.equals(desc));
    }

    private static String factoryDesc(String constructorDesc) {
        return constructorDesc.substring(0, constructorDesc.length() - 1) + "L" + OFFER + ";";
    }

    /** The helper: one {@code create} per old constructor, and the stack-to-cost conversions they share. */
    static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, HELPER, null, "java/lang/Object", null);

        // cost(stack) is new ItemCost(stack.getItem(), stack.getCount()). The stack's components
        // are not carried over: an old price was an item and a count.
        MethodVisitor cost = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "cost", "(" + S + ")" + C, null, null);
        cost.visitCode();
        cost.visitTypeInsn(NEW, COST);
        cost.visitInsn(DUP);
        cost.visitVarInsn(ALOAD, 0);
        cost.visitMethodInsn(INVOKEVIRTUAL, STACK, "getItem", "()Lnet/minecraft/world/item/Item;", false);
        cost.visitVarInsn(ALOAD, 0);
        cost.visitMethodInsn(INVOKEVIRTUAL, STACK, "getCount", "()I", false);
        cost.visitMethodInsn(INVOKESPECIAL, COST, "<init>", "(Lnet/minecraft/world/level/ItemLike;I)V", false);
        cost.visitInsn(ARETURN);
        cost.visitMaxs(0, 0);
        cost.visitEnd();

        // secondCost(stack) is empty for an empty stack, which is how old code wrote "no second price".
        MethodVisitor second = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "secondCost", "(" + S + ")" + OPTIONAL, null, null);
        second.visitCode();
        Label present = new Label();
        second.visitVarInsn(ALOAD, 0);
        second.visitMethodInsn(INVOKEVIRTUAL, STACK, "isEmpty", "()Z", false);
        second.visitJumpInsn(IFEQ, present);
        second.visitMethodInsn(INVOKESTATIC, "java/util/Optional", "empty", "()" + OPTIONAL, false);
        second.visitInsn(ARETURN);
        second.visitLabel(present);
        second.visitFrame(F_SAME, 0, null, 0, null);
        second.visitVarInsn(ALOAD, 0);
        second.visitMethodInsn(INVOKESTATIC, HELPER, "cost", "(" + S + ")" + C, false);
        second.visitMethodInsn(INVOKESTATIC, "java/util/Optional", "of", "(Ljava/lang/Object;)" + OPTIONAL, false);
        second.visitInsn(ARETURN);
        second.visitMaxs(0, 0);
        second.visitEnd();

        for (Shape shape : SHAPES) writeCreate(cw, shape);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code create(...)} converts the one or two prices and passes the rest through unchanged. */
    private static void writeCreate(ClassWriter cw, Shape shape) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "create", factoryDesc(shape.oldDesc()), null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, OFFER);
        mv.visitInsn(DUP);
        Type[] args = Type.getArgumentTypes(shape.oldDesc());
        int slot = 0;
        for (int i = 0; i < args.length; i++) {
            mv.visitVarInsn(args[i].getOpcode(ILOAD), slot);
            slot += args[i].getSize();
            if (i == 0) mv.visitMethodInsn(INVOKESTATIC, HELPER, "cost", "(" + S + ")" + C, false);
            if (i == 1 && shape.secondPrice()) {
                mv.visitMethodInsn(INVOKESTATIC, HELPER, "secondCost", "(" + S + ")" + OPTIONAL, false);
            }
        }
        mv.visitMethodInsn(INVOKESPECIAL, OFFER, "<init>", shape.newDesc(), false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
