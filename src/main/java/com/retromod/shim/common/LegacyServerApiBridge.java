/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.mixin.MixinLazyStaticInitializer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.objectweb.asm.Opcodes.*;

/**
 * Bridges common server-side members that block and entity mods built for 1.21.1 still call and 1.21.2 to 26.3
 * changed: container drops on removal, entity sync and fall fields, explosions and sounds on a level, vectors on a
 * buffer, and trim templates. Each one registers only where the host shows the change.
 */
public final class LegacyServerApiBridge {

    static final String HELPER = "com/retromod/generated/LegacyServerApis";
    private static final String CONTAINERS = "net/minecraft/world/Containers";
    private static final String ENTITY = "net/minecraft/world/entity/Entity";
    private static final String LEVEL = "net/minecraft/world/level/Level";
    private static final String SERVER_LEVEL = "net/minecraft/server/level/ServerLevel";
    private static final String BUF = "net/minecraft/network/FriendlyByteBuf";
    private static final String VEC3 = "net/minecraft/world/phys/Vec3";
    private static final String STATE = "Lnet/minecraft/world/level/block/state/BlockState;";
    private static final String POS = "Lnet/minecraft/core/BlockPos;";
    private static final String TEMPLATE = "net/minecraft/world/item/SmithingTemplateItem";
    private static final String ITEM_PROPS = "net/minecraft/world/item/Item$Properties";
    private static final String DROP_DESC = "(" + STATE + STATE + "L" + LEVEL + ";" + POS + ")V";
    private static final String OLD_TRIM = "(Lnet/minecraft/resources/ResourceKey;[Lnet/minecraft/world/flag/FeatureFlag;)L" + TEMPLATE + ";";
    private static final String EXPLOSION = "Lnet/minecraft/world/level/Explosion;";
    private static final String STAMPED_TRIM = "(Lnet/minecraft/resources/ResourceKey;[Lnet/minecraft/world/flag/FeatureFlag;L"
            + ITEM_PROPS + ";)L" + TEMPLATE + ";";
    private static final String PLAYER = "Lnet/minecraft/world/entity/player/Player;";

    private LegacyServerApiBridge() {}

    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
        // Target goals and conditions took a predicate before 1.21.2 and a selector since.
        LegacyTargetSelectorBridge.register(t);
        // Entity lookups, item predicates, boats, loot tables, teleports and other members reshaped from 1.21.2 to 26.3.
        LegacyCommonMembersBridge.register(t);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        if (t.getSyntheticClasses().containsKey(HELPER)) return;
        ClassNode containers = host.apply(CONTAINERS);
        ClassNode entity = host.apply(ENTITY);
        ClassNode level = host.apply(LEVEL);
        ClassNode buf = host.apply(BUF);
        ClassNode template = host.apply(TEMPLATE);
        // Offline with no host to read, nothing is assumed: each rule needs the host's own shape.
        boolean drops = containers != null && !hasMethod(containers, "dropContentsOnDestroy", DROP_DESC);
        // 1.21.11 replaced hasImpulse with needsSync; 26.3 replaced hurtMarked, which markHurt sets, with syncVelocity.
        boolean sync = entity != null && !hasField(entity, "hasImpulse", "Z") && hasField(entity, "needsSync", "Z");
        boolean velocity = entity != null && !hasField(entity, "hurtMarked", "Z") && hasField(entity, "syncVelocity", "Z");
        boolean fall = entity != null && hasField(entity, "fallDistance", "D");
        boolean vectors = buf != null && !hasMethodNamed(buf, "readVec3");
        boolean trims = template != null && !hasMethod(template, "createArmorTrimTemplate", OLD_TRIM)
                && hasMethod(template, "createArmorTrimTemplate", "(L" + ITEM_PROPS + ";)L" + TEMPLATE + ";");
        List<String[]> explodes = level == null ? List.of() : voidExplodes(level);
        if (!(drops || sync || velocity || fall || vectors || trims || !explodes.isEmpty() || level != null)) return;
        t.registerSyntheticClass(HELPER, generate(drops, sync, velocity, fall, vectors, trims, explodes));

        if (drops) t.registerMethodRedirect(CONTAINERS, "dropContentsOnDestroy", DROP_DESC, HELPER, "dropContentsOnDestroy", DROP_DESC);
        // Each old flag goes to the field that took its job, and the fall distance became a double.
        if (sync) {
            t.registerTypedFieldStaticBridge(ENTITY, "hasImpulse", "Z", HELPER, "needsSync", "(Ljava/lang/Object;)Z",
                    "setNeedsSync", "(Ljava/lang/Object;Z)V");
        }
        if (velocity) {
            t.registerTypedFieldStaticBridge(ENTITY, "hurtMarked", "Z", HELPER, "syncVelocity", "(Ljava/lang/Object;)Z",
                    "setSyncVelocity", "(Ljava/lang/Object;Z)V");
        }
        if (fall) {
            t.registerTypedFieldStaticBridge(ENTITY, "fallDistance", "F", HELPER, "fallDistance", "(Ljava/lang/Object;)F",
                    "setFallDistance", "(Ljava/lang/Object;F)V");
        }
        if (vectors) {
            t.registerMethodRedirect(BUF, "readVec3", "()L" + VEC3 + ";", HELPER, "readVec3", "(L" + BUF + ";)L" + VEC3 + ";", true);
            t.registerMethodRedirect(BUF, "writeVec3", "(L" + VEC3 + ";)V", HELPER, "writeVec3", "(L" + BUF + ";L" + VEC3 + ";)V", true);
        }
        if (trims) t.registerMethodRedirect(TEMPLATE, "createArmorTrimTemplate", OLD_TRIM, HELPER, "createArmorTrimTemplate", OLD_TRIM);
        // explode no longer returns the explosion; the call runs and the old result reads as null.
        for (String[] explode : explodes) {
            for (String owner : new String[] {LEVEL, SERVER_LEVEL}) {
                t.registerMethodRedirect(owner, "explode", explode[0], HELPER, "explode", explode[1], true);
            }
        }
        if (level != null) registerPlayerWidening(t, level, "playSound");
        registerSmallRenames(t, host);
    }

    private static final String DUST = "net/minecraft/core/particles/DustParticleOptions";
    private static final String STACK = "net/minecraft/world/item/ItemStack";
    static final String SMALL = "com/retromod/generated/LegacyServerMembers";

    /** Dust colors and crafting remainders, each where the host lost the old form. */
    private static void registerSmallRenames(RetromodTransformer t, Function<String, ClassNode> host) {
        ClassNode dust = host.apply(DUST);
        ClassNode stack = host.apply(STACK);
        boolean dusts = dust != null && !hasMethod(dust, "<init>", "(Lorg/joml/Vector3f;F)V") && hasMethod(dust, "<init>", "(IF)V");
        // 1.21.5 moved the remainder onto the item as a stack, and 26.1 made it a template.
        RemainderShape shape = stack != null && !hasMethodNamed(stack, "getCraftingRemainingItem")
                ? remainderShape(host.apply(ITEM)) : null;
        boolean remainders = shape != null;
        if (!(dusts || remainders)) return;
        // A cooking recipe reads its result's remainder while 26.3 loads recipes, before item components are bound.
        boolean earlyStacks = shape == RemainderShape.TEMPLATE && MixinLazyStaticInitializer.hostBindsComponentsLate(
                RetromodVersion.TARGET_MC_VERSION, t.getFuzzyResolver());
        if (earlyStacks) RebuiltApiClasses.registerTree(t, LegacyItemStackCodecsBridge.HELPER);
        t.registerSyntheticClass(SMALL, generateSmall(dusts, shape, earlyStacks));
        if (dusts) t.registerConstructorRedirect(DUST, "(Lorg/joml/Vector3f;F)V", SMALL, "dust", "(Lorg/joml/Vector3f;F)L" + DUST + ";");
        if (remainders) {
            t.registerMethodRedirect(STACK, "getCraftingRemainingItem", "()L" + STACK + ";", SMALL, "craftingRemainder",
                    "(L" + STACK + ";)L" + STACK + ";", true);
            t.registerMethodRedirect(STACK, "hasCraftingRemainingItem", "()Z", SMALL, "hasCraftingRemainder", "(L" + STACK + ";)Z", true);
            t.registerInheritedMethodRedirect("net/minecraft/world/item/Item", "hasCraftingRemainingItem", "()Z", SMALL,
                    "itemHasCraftingRemainder", "(Lnet/minecraft/world/item/Item;)Z");
        }
    }

    /** How the host's item answers its crafting remainder: a stack (1.21.5 to 1.21.11) or a template (26.1 and newer). */
    enum RemainderShape { STACK, TEMPLATE }

    private static final String ITEM = "net/minecraft/world/item/Item";
    private static final String STACK_TEMPLATE = "net/minecraft/world/item/ItemStackTemplate";

    /** The host item's remainder getter shape, or null when it has neither and nothing is bridged. */
    static RemainderShape remainderShape(ClassNode item) {
        if (item == null) return null;
        if (hasMethod(item, "getCraftingRemainder", "()L" + STACK_TEMPLATE + ";")) return RemainderShape.TEMPLATE;
        if (hasMethod(item, "getCraftingRemainder", "()L" + STACK + ";")) return RemainderShape.STACK;
        return null;
    }

    static byte[] generateSmall(boolean dusts, boolean remainders) {
        return generateSmall(dusts, remainders ? RemainderShape.TEMPLATE : null, false);
    }

    static byte[] generateSmall(boolean dusts, boolean remainders, boolean earlyStacks) {
        return generateSmall(dusts, remainders ? RemainderShape.TEMPLATE : null, earlyStacks);
    }

    /** As above for the host's remainder shape; with {@code earlyStacks} a remainder read before binding waits for the item's components. */
    static byte[] generateSmall(boolean dusts, RemainderShape remainders, boolean earlyStacks) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, SMALL, null, "java/lang/Object", null);
        if (dusts) {
            // The dust color is one opaque RGB int now.
            MethodVisitor m = start(cw, "dust", "(Lorg/joml/Vector3f;F)L" + DUST + ";");
            m.visitTypeInsn(NEW, DUST);
            m.visitInsn(DUP);
            m.visitInsn(FCONST_1);
            for (String axis : new String[] {"x", "y", "z"}) {
                m.visitVarInsn(ALOAD, 0);
                m.visitMethodInsn(INVOKEVIRTUAL, "org/joml/Vector3f", axis, "()F", false);
            }
            m.visitMethodInsn(INVOKESTATIC, "net/minecraft/util/ARGB", "colorFromFloat", "(FFFF)I", false);
            m.visitVarInsn(FLOAD, 1);
            m.visitMethodInsn(INVOKESPECIAL, DUST, "<init>", "(IF)V", false);
            m.visitInsn(ARETURN);
            end(m);
        }
        if (remainders == RemainderShape.STACK) {
            generateStackRemainders(cw);
        } else if (remainders == RemainderShape.TEMPLATE) {
            String template = STACK_TEMPLATE;
            MethodVisitor m = start(cw, "craftingRemainder", "(L" + STACK + ";)L" + STACK + ";");
            Label none = new Label();
            m.visitVarInsn(ALOAD, 0);
            m.visitMethodInsn(INVOKEVIRTUAL, STACK, "getItem", "()Lnet/minecraft/world/item/Item;", false);
            m.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/item/Item", "getCraftingRemainder", "()L" + template + ";", false);
            m.visitInsn(DUP);
            m.visitJumpInsn(IFNULL, none);
            if (earlyStacks) {
                m.visitMethodInsn(INVOKESTATIC, LegacyItemStackCodecsBridge.HELPER, "create", "(L" + template + ";)L" + STACK + ";", false);
            } else {
                m.visitMethodInsn(INVOKEVIRTUAL, template, "create", "()L" + STACK + ";", false);
            }
            m.visitInsn(ARETURN);
            m.visitLabel(none);
            m.visitInsn(POP);
            m.visitFieldInsn(GETSTATIC, STACK, "EMPTY", "L" + STACK + ";");
            m.visitInsn(ARETURN);
            end(m);
            MethodVisitor item = start(cw, "itemHasCraftingRemainder", "(Lnet/minecraft/world/item/Item;)Z");
            Label empty = new Label();
            item.visitVarInsn(ALOAD, 0);
            item.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/item/Item", "getCraftingRemainder", "()L" + template + ";", false);
            item.visitJumpInsn(IFNULL, empty);
            item.visitInsn(ICONST_1);
            item.visitInsn(IRETURN);
            item.visitLabel(empty);
            item.visitInsn(ICONST_0);
            item.visitInsn(IRETURN);
            end(item);
            MethodVisitor has = start(cw, "hasCraftingRemainder", "(L" + STACK + ";)Z");
            Label no = new Label();
            has.visitVarInsn(ALOAD, 0);
            has.visitMethodInsn(INVOKEVIRTUAL, STACK, "getItem", "()Lnet/minecraft/world/item/Item;", false);
            has.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/item/Item", "getCraftingRemainder", "()L" + template + ";", false);
            has.visitJumpInsn(IFNULL, no);
            has.visitInsn(ICONST_1);
            has.visitInsn(IRETURN);
            has.visitLabel(no);
            has.visitInsn(ICONST_0);
            has.visitInsn(IRETURN);
            end(has);
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** 1.21.5 to 1.21.11: the item answers its remainder as a stack, which is empty when there is none. */
    private static void generateStackRemainders(ClassWriter cw) {
        String getter = "()L" + STACK + ";";
        MethodVisitor m = start(cw, "craftingRemainder", "(L" + STACK + ";)L" + STACK + ";");
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKEVIRTUAL, STACK, "getItem", "()L" + ITEM + ";", false);
        m.visitMethodInsn(INVOKEVIRTUAL, ITEM, "getCraftingRemainder", getter, false);
        m.visitInsn(ARETURN);
        end(m);
        MethodVisitor item = start(cw, "itemHasCraftingRemainder", "(L" + ITEM + ";)Z");
        item.visitVarInsn(ALOAD, 0);
        item.visitMethodInsn(INVOKEVIRTUAL, ITEM, "getCraftingRemainder", getter, false);
        emitNotEmpty(item);
        MethodVisitor has = start(cw, "hasCraftingRemainder", "(L" + STACK + ";)Z");
        has.visitVarInsn(ALOAD, 0);
        has.visitMethodInsn(INVOKEVIRTUAL, STACK, "getItem", "()L" + ITEM + ";", false);
        has.visitMethodInsn(INVOKEVIRTUAL, ITEM, "getCraftingRemainder", getter, false);
        emitNotEmpty(has);
    }

    /** Returns {@code !stack.isEmpty()} for the stack on top and ends the method. */
    private static void emitNotEmpty(MethodVisitor m) {
        Label empty = new Label();
        m.visitMethodInsn(INVOKEVIRTUAL, STACK, "isEmpty", "()Z", false);
        m.visitJumpInsn(IFNE, empty);
        m.visitInsn(ICONST_1);
        m.visitInsn(IRETURN);
        m.visitLabel(empty);
        m.visitInsn(ICONST_0);
        m.visitInsn(IRETURN);
        end(m);
    }

    /** Each host {@code explode} that returns nothing, as its old form returning the explosion and the helper's form. */
    static List<String[]> voidExplodes(ClassNode level) {
        List<String[]> found = new ArrayList<>();
        level.methods.stream().filter(m -> m.name.equals("explode") && m.desc.endsWith(")V") && (m.access & ACC_STATIC) == 0)
                .forEach(m -> {
                    String args = m.desc.substring(1, m.desc.indexOf(')'));
                    found.add(new String[] {"(" + args + ")" + EXPLOSION, "(L" + LEVEL + ";" + args + ")" + EXPLOSION});
                });
        return found;
    }

    /** A call that passed a player where the host now takes any entity keeps its arguments, under the new descriptor. */
    private static void registerPlayerWidening(RetromodTransformer t, ClassNode level, String name) {
        String entity = "L" + ENTITY + ";";
        for (var method : level.methods) {
            if (!method.name.equals(name) || !method.desc.startsWith("(" + entity)) continue;
            String old = "(" + PLAYER + method.desc.substring(entity.length() + 1);
            if (hasMethod(level, name, old)) continue;
            // A client level call names ClientLevel, which took the same widening.
            for (String owner : new String[] {LEVEL, SERVER_LEVEL, "net/minecraft/client/multiplayer/ClientLevel"}) {
                t.registerMethodRedirect(owner, name, old, owner, name, method.desc);
            }
        }
    }

    private static boolean hasMethod(ClassNode node, String name, String desc) {
        return node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc));
    }

    private static boolean hasMethodNamed(ClassNode node, String name) {
        return node.methods.stream().anyMatch(m -> m.name.equals(name));
    }

    private static boolean hasField(ClassNode node, String name, String desc) {
        return node.fields.stream().anyMatch(f -> f.name.equals(name) && f.desc.equals(desc));
    }

    /** A getter and setter for one public boolean field on the entity, named after the field. */
    private static void emitFlag(ClassWriter cw, String field, String setter) {
        MethodVisitor get = start(cw, field, "(Ljava/lang/Object;)Z");
        get.visitVarInsn(ALOAD, 0);
        get.visitTypeInsn(CHECKCAST, ENTITY);
        get.visitFieldInsn(GETFIELD, ENTITY, field, "Z");
        get.visitInsn(IRETURN);
        end(get);
        MethodVisitor set = start(cw, setter, "(Ljava/lang/Object;Z)V");
        set.visitVarInsn(ALOAD, 0);
        set.visitTypeInsn(CHECKCAST, ENTITY);
        set.visitVarInsn(ILOAD, 1);
        set.visitFieldInsn(PUTFIELD, ENTITY, field, "Z");
        set.visitInsn(RETURN);
        end(set);
    }

    static byte[] generate(boolean drops, boolean sync, boolean velocity, boolean fall, boolean vectors, boolean trims,
            List<String[]> explodes) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, HELPER, null, "java/lang/Object", null);
        if (drops) emitDropContentsOnDestroy(cw);
        if (sync) emitFlag(cw, "needsSync", "setNeedsSync");
        if (velocity) emitFlag(cw, "syncVelocity", "setSyncVelocity");
        if (fall) {
            MethodVisitor get = start(cw, "fallDistance", "(Ljava/lang/Object;)F");
            get.visitVarInsn(ALOAD, 0);
            get.visitTypeInsn(CHECKCAST, ENTITY);
            get.visitFieldInsn(GETFIELD, ENTITY, "fallDistance", "D");
            get.visitInsn(D2F);
            get.visitInsn(FRETURN);
            end(get);
            MethodVisitor set = start(cw, "setFallDistance", "(Ljava/lang/Object;F)V");
            set.visitVarInsn(ALOAD, 0);
            set.visitTypeInsn(CHECKCAST, ENTITY);
            set.visitVarInsn(FLOAD, 1);
            set.visitInsn(F2D);
            set.visitFieldInsn(PUTFIELD, ENTITY, "fallDistance", "D");
            set.visitInsn(RETURN);
            end(set);
        }
        if (vectors) emitVectors(cw);
        if (trims) emitTrimTemplate(cw);
        for (String[] explode : explodes) emitExplode(cw, explode[1]);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * {@code dropContentsOnDestroy(state, newState, level, pos)}: when the block changed, a container block entity still
     * there drops its items, and comparators around it update, as the removed helper did.
     */
    private static void emitDropContentsOnDestroy(ClassWriter cw) {
        MethodVisitor m = start(cw, "dropContentsOnDestroy", DROP_DESC);
        Label done = new Label();
        // The same block on both sides is no change. 26.x has no is(Block) on a state, so the blocks are compared directly.
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/level/block/state/BlockState", "getBlock",
                "()Lnet/minecraft/world/level/block/Block;", false);
        m.visitVarInsn(ALOAD, 1);
        m.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/level/block/state/BlockState", "getBlock",
                "()Lnet/minecraft/world/level/block/Block;", false);
        m.visitJumpInsn(IF_ACMPEQ, done);
        Label noContainer = new Label();
        m.visitVarInsn(ALOAD, 2);
        m.visitVarInsn(ALOAD, 3);
        m.visitMethodInsn(INVOKEVIRTUAL, LEVEL, "getBlockEntity", "(" + POS + ")Lnet/minecraft/world/level/block/entity/BlockEntity;", false);
        m.visitVarInsn(ASTORE, 4);
        m.visitVarInsn(ALOAD, 4);
        m.visitTypeInsn(INSTANCEOF, "net/minecraft/world/Container");
        m.visitJumpInsn(IFEQ, noContainer);
        m.visitVarInsn(ALOAD, 2);
        m.visitVarInsn(ALOAD, 3);
        m.visitVarInsn(ALOAD, 4);
        m.visitTypeInsn(CHECKCAST, "net/minecraft/world/Container");
        m.visitMethodInsn(INVOKESTATIC, CONTAINERS, "dropContents", "(L" + LEVEL + ";" + POS + "Lnet/minecraft/world/Container;)V", false);
        m.visitLabel(noContainer);
        m.visitVarInsn(ALOAD, 0);
        m.visitVarInsn(ALOAD, 2);
        m.visitVarInsn(ALOAD, 3);
        m.visitMethodInsn(INVOKESTATIC, CONTAINERS, "updateNeighboursAfterDestroy", "(" + STATE + "L" + LEVEL + ";" + POS + ")V", false);
        m.visitLabel(done);
        m.visitInsn(RETURN);
        end(m);
    }

    /** {@code readVec3(buf)} and {@code writeVec3(buf, vec)} through the vector's stream codec. */
    private static void emitVectors(ClassWriter cw) {
        String codec = "net/minecraft/network/codec/StreamCodec";
        MethodVisitor read = start(cw, "readVec3", "(L" + BUF + ";)L" + VEC3 + ";");
        read.visitFieldInsn(GETSTATIC, VEC3, "STREAM_CODEC", "L" + codec + ";");
        read.visitVarInsn(ALOAD, 0);
        read.visitMethodInsn(INVOKEINTERFACE, codec, "decode", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        read.visitTypeInsn(CHECKCAST, VEC3);
        read.visitInsn(ARETURN);
        end(read);
        MethodVisitor write = start(cw, "writeVec3", "(L" + BUF + ";L" + VEC3 + ";)V");
        write.visitFieldInsn(GETSTATIC, VEC3, "STREAM_CODEC", "L" + codec + ";");
        write.visitVarInsn(ALOAD, 0);
        write.visitVarInsn(ALOAD, 1);
        write.visitMethodInsn(INVOKEINTERFACE, codec, "encode", "(Ljava/lang/Object;Ljava/lang/Object;)V", true);
        write.visitInsn(RETURN);
        end(write);
    }

    /**
     * {@code createArmorTrimTemplate(pattern, flags)}: the host builds the template from item properties. The
     * properties are built inside the template's own constructor call, so the registration id reaches them there.
     */
    private static void emitTrimTemplate(ClassWriter cw) {
        MethodVisitor plain = start(cw, "createArmorTrimTemplate", OLD_TRIM);
        plain.visitVarInsn(ALOAD, 0);
        plain.visitVarInsn(ALOAD, 1);
        plain.visitTypeInsn(NEW, ITEM_PROPS);
        plain.visitInsn(DUP);
        plain.visitMethodInsn(INVOKESPECIAL, ITEM_PROPS, "<init>", "()V", false);
        plain.visitMethodInsn(INVOKESTATIC, HELPER, "createArmorTrimTemplate", STAMPED_TRIM, false);
        plain.visitInsn(ARETURN);
        end(plain);
        // The same with properties the loader's registration bridge already gave the item's id.
        MethodVisitor m = start(cw, "createArmorTrimTemplate", STAMPED_TRIM);
        m.visitVarInsn(ALOAD, 2);
        m.visitVarInsn(ALOAD, 1);
        m.visitMethodInsn(INVOKEVIRTUAL, ITEM_PROPS, "requiredFeatures",
                "([Lnet/minecraft/world/flag/FeatureFlag;)L" + ITEM_PROPS + ";", false);
        m.visitMethodInsn(INVOKESTATIC, TEMPLATE, "createArmorTrimTemplate", "(L" + ITEM_PROPS + ";)L" + TEMPLATE + ";", false);
        m.visitInsn(ARETURN);
        end(m);
    }

    /**
     * Gives each trim template call in a mod class the properties of {@code bridge.itemProps()}, which carry the id
     * the item is being registered under, since the template is built inside the host where no stamp reaches.
     */
    public static byte[] stampTrimTemplates(byte[] classBytes, String bridge) {
        if (classBytes == null || !new String(classBytes, java.nio.charset.StandardCharsets.ISO_8859_1).contains(HELPER)) {
            return classBytes;
        }
        ClassNode node = new ClassNode();
        new org.objectweb.asm.ClassReader(classBytes).accept(node, 0);
        int stamped = 0;
        for (var method : node.methods) {
            for (var insn : method.instructions.toArray()) {
                if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call && call.getOpcode() == INVOKESTATIC
                        && call.owner.equals(HELPER) && call.name.equals("createArmorTrimTemplate") && call.desc.equals(OLD_TRIM)) {
                    method.instructions.insertBefore(call, new org.objectweb.asm.tree.MethodInsnNode(INVOKESTATIC, bridge,
                            "itemProps", "()L" + ITEM_PROPS + ";", false));
                    call.desc = STAMPED_TRIM;
                    stamped++;
                }
            }
        }
        if (stamped == 0) return classBytes;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** {@code explode(level, args...)}: runs the explosion and gives the old caller no explosion object. */
    private static void emitExplode(ClassWriter cw, String desc) {
        MethodVisitor m = start(cw, "explode", desc);
        Type[] args = Type.getArgumentTypes(desc);
        int slot = 0;
        for (Type arg : args) {
            m.visitVarInsn(arg.getOpcode(ILOAD), slot);
            slot += arg.getSize();
        }
        String hostDesc = "(" + desc.substring(desc.indexOf(';') + 1, desc.indexOf(')')) + ")V";
        m.visitMethodInsn(INVOKEVIRTUAL, LEVEL, "explode", hostDesc, false);
        m.visitInsn(ACONST_NULL);
        m.visitInsn(ARETURN);
        end(m);
    }

    private static MethodVisitor start(ClassWriter cw, String name, String desc) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
        m.visitCode();
        return m;
    }

    private static void end(MethodVisitor m) {
        m.visitMaxs(0, 0);
        m.visitEnd();
    }
}
