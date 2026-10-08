/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.core.FuzzyMethodResolver;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Keeps first-person hand mixins written against {@code ItemInHandRenderer} working on hosts
 * that reorganized it.
 *
 * <p>26.1 removed {@code Minecraft.getBlockRenderer()}. A held-block call through it becomes a
 * mixin-owned fallback that submits the block's item model, so the block still shows in hand
 * instead of the frame dying with {@code NoSuchMethodError}.
 *
 * <p>26.3 split {@code ItemInHandRenderer} into {@code FirstPersonHandsAndItems}, which ticks
 * the hand state, and {@code FirstPersonHandsAndItemsRenderer}, which draws from a
 * {@code FirstPersonHandsAndItemsRenderState} and a {@code PlayerRenderState} instead of the
 * live player. A mixin that redirects the per-arm call inside {@code submitHandsWithItems} is
 * moved to the new renderer: its handler receives the two render states, keeps them for its
 * shadows, and runs the original body with the local player in place of the removed player
 * argument. Old-shape shadows become mixin-owned bridges that pass the kept states on, and the
 * removed {@code renderItem} is rebuilt from the item model resolver as 26.2 implemented it.
 *
 * <p>The retarget only runs when every member it relies on is on the host. A mixin that injects
 * into the removed tick or item-swap logic is not moved, because that logic now lives in a
 * different class with different state.
 */
final class MixinFirstPersonRendererAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger("retromod-mixin");

    private static final String MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String SHADOW_DESC = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String UNIQUE_DESC = "Lorg/spongepowered/asm/mixin/Unique;";
    private static final String REDIRECT_DESC = "Lorg/spongepowered/asm/mixin/injection/Redirect;";

    static final String OLD_RENDERER = "net/minecraft/client/renderer/ItemInHandRenderer";
    static final String RENDERER = "net/minecraft/client/renderer/FirstPersonHandsAndItemsRenderer";
    private static final String PLAYER_STATE = "net/minecraft/client/renderer/state/level/PlayerRenderState";
    private static final String HANDS_STATE =
            "net/minecraft/client/renderer/state/level/FirstPersonHandsAndItemsRenderState";
    private static final String MINECRAFT = "net/minecraft/client/Minecraft";
    private static final String LOCAL_PLAYER = "net/minecraft/client/player/LocalPlayer";
    private static final String CLIENT_PLAYER = "net/minecraft/client/player/AbstractClientPlayer";
    private static final String ITEM_STACK = "net/minecraft/world/item/ItemStack";
    private static final String DISPLAY_CONTEXT = "net/minecraft/world/item/ItemDisplayContext";
    private static final String STACK_STATE = "net/minecraft/client/renderer/item/ItemStackRenderState";
    private static final String MODEL_RESOLVER = "net/minecraft/client/renderer/item/ItemModelResolver";
    private static final String LIVING_ENTITY = "net/minecraft/world/entity/LivingEntity";
    private static final String BLOCK_STATE = "net/minecraft/world/level/block/state/BlockState";
    private static final String POSE_STACK = "com/mojang/blaze3d/vertex/PoseStack";
    private static final String COLLECTOR = "net/minecraft/client/renderer/SubmitNodeCollector";
    private static final String CLIENT_LEVEL = "net/minecraft/client/multiplayer/ClientLevel";

    private static final String L_POSE = "L" + POSE_STACK + ";";
    private static final String L_COLLECTOR = "L" + COLLECTOR + ";";
    private static final String L_STACK = "L" + ITEM_STACK + ";";
    private static final String L_HANDS = "L" + HANDS_STATE + ";";
    private static final String L_PLAYER_STATE = "L" + PLAYER_STATE + ";";
    private static final String ARM_TAIL = "FFLnet/minecraft/world/InteractionHand;F" + L_STACK + "F"
            + L_POSE + L_COLLECTOR + "I)V";
    static final String OLD_ARM = "(L" + CLIENT_PLAYER + ";" + ARM_TAIL;
    static final String NEW_ARM = "(" + L_PLAYER_STATE + L_HANDS + ARM_TAIL;
    static final String OLD_HANDS = "(F" + L_POSE + L_COLLECTOR + "L" + LOCAL_PLAYER + ";I)V";
    static final String NEW_HANDS = "(F" + L_POSE + L_COLLECTOR + L_PLAYER_STATE + L_HANDS + ")V";
    private static final String PLAYER_ARM_TAIL = L_POSE + L_COLLECTOR + "IFFLnet/minecraft/world/entity/HumanoidArm;";
    private static final String OLD_PLAYER_ARM = "(" + PLAYER_ARM_TAIL + ")V";
    private static final String NEW_PLAYER_ARM = "(" + PLAYER_ARM_TAIL + L_PLAYER_STATE + ")V";
    private static final String OLD_MAP = "(" + L_POSE + L_COLLECTOR + "I" + L_STACK + ")V";
    private static final String NEW_MAP = "(" + L_POSE + L_COLLECTOR + "I" + L_STACK + "Z" + L_HANDS + ")V";
    private static final String OLD_RENDER_ITEM = "(L" + LIVING_ENTITY + ";" + L_STACK + "L" + DISPLAY_CONTEXT + ";"
            + L_POSE + L_COLLECTOR + "I)V";
    private static final String UPDATE_FOR_TOP_ITEM = "(L" + STACK_STATE + ";" + L_STACK + "L" + DISPLAY_CONTEXT
            + ";Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/ItemOwner;I)V";
    private static final String STATE_SUBMIT = "(" + L_POSE + L_COLLECTOR + "III)V";

    /** The held-block call a 1.21.x first-person mixin made through the removed block renderer. */
    static final String HELD_BLOCK_DESC = "(L" + BLOCK_STATE + ";" + L_POSE + L_COLLECTOR + "IL"
            + CLIENT_LEVEL + ";L" + CLIENT_PLAYER + ";)V";
    private static final String HELD_BLOCK_HELPER = "retromod$renderHeldBlock";
    private static final String LIGHT_COORDS = "net/minecraft/util/LightCoordsUtil";
    private static final String WITH_EMISSION_DESC = "(II)I";

    private static final String PLAYER_STATE_FIELD = "retromod$playerState";
    private static final String HANDS_STATE_FIELD = "retromod$handsState";
    private static final String LEGACY_SUFFIX = "$retromodLegacy";

    private MixinFirstPersonRendererAdapter() {}

    /**
     * Whether the removed-target check must leave this mixin for the post-remap retarget. The
     * check runs before the remap and would otherwise disable every {@code ItemInHandRenderer}
     * mixin on 26.3; {@link #adapt} disables the ones it cannot move.
     */
    static boolean movesAfterRemap(String resolvedTarget, java.util.function.Predicate<String> hostHasClass) {
        return OLD_RENDERER.equals(resolvedTarget) && hostHasClass.test(RENDERER.replace('/', '.'));
    }

    /** Applies the held-block fallback and, on a split host, the renderer retarget. */
    static byte[] adapt(byte[] classBytes, FuzzyMethodResolver host) {
        return adapt(classBytes, host, com.retromod.core.EnvironmentDetector::hostClassExists);
    }

    static byte[] adapt(byte[] classBytes, FuzzyMethodResolver host,
            java.util.function.Predicate<String> hostHasClass) {
        if (classBytes == null) return classBytes;
        if (host == null || !host.isIndexed()) {
            // The removed-target check deferred this mixin on the strength of the class probe. With
            // no indexed host there is nothing to retarget against, so disable it as that check
            // would have, instead of leaving Mixin to fail on the missing ItemInHandRenderer.
            if (isOldRendererMixin(classBytes) && !hostHasClass.test(OLD_RENDERER.replace('/', '.'))
                    && movesAfterRemap(OLD_RENDERER, hostHasClass)) {
                return disabled(classBytes);
            }
            return classBytes;
        }
        boolean retarget = isOldRendererMixin(classBytes) && hostSplitRenderer(host);
        byte[] working = retarget ? remapRenderer(classBytes) : classBytes;

        ClassNode classNode = new ClassNode();
        new ClassReader(working).accept(classNode, 0);
        if (!isMixin(classNode)) return classBytes;

        boolean changed = false;
        if (!host.hasMethod(MINECRAFT, "getBlockRenderer",
                "()Lnet/minecraft/client/renderer/block/BlockRenderDispatcher;")) {
            changed |= replaceHeldBlockCalls(classNode, host);
        }
        if (retarget && !retargetToSplitRenderer(classNode)) {
            // Without a recognized handler the mixin cannot run on the split renderer. Its
            // target is gone, and the removed-target check deferred to this step, so disable it.
            return disabled(classBytes);
        }
        if (retarget) {
            // The original targets a class the host no longer has, so it is not a safe fallback.
            byte[] moved = write(classNode, null, "split first-person renderer");
            return moved != null ? moved : disabled(classBytes);
        }
        return changed ? write(classNode, classBytes, "held-block fallback") : classBytes;
    }

    /** Only the annotation changes, so the original frames are kept and no frame computation can fail. */
    private static byte[] disabled(byte[] classBytes) {
        ClassNode original = new ClassNode();
        new ClassReader(classBytes).accept(original, 0);
        disable(original);
        ClassWriter writer = new ClassWriter(0);
        original.accept(writer);
        LOGGER.info("Disabled first-person Mixin {}: ItemInHandRenderer is gone and the mixin "
                + "could not be moved to the split renderer", original.name);
        return writer.toByteArray();
    }

    private static byte[] write(ClassNode classNode, byte[] original, String what) {
        try {
            ClassWriter writer = new com.retromod.util.SafeClassWriter(ClassWriter.COMPUTE_FRAMES);
            classNode.accept(writer);
            LOGGER.info("Adapted first-person Mixin {} for the {}", classNode.name, what);
            return writer.toByteArray();
        } catch (Throwable t) {
            LOGGER.debug("Could not verify first-person adaptation of {} ({})", classNode.name, t.toString());
            return original;
        }
    }

    // ---- held block ------------------------------------------------------------------------

    /**
     * Rewrites {@code minecraft.getBlockRenderer()} cast to a mod interface and called with the
     * held-block shape. The {@code Minecraft} receiver stays on the stack and becomes the first
     * argument of the mixin-owned fallback.
     */
    private static boolean replaceHeldBlockCalls(ClassNode classNode, FuzzyMethodResolver host) {
        if (!host.hasMethod(MODEL_RESOLVER, "updateForTopItem", UPDATE_FOR_TOP_ITEM)
                || !host.hasMethod(STACK_STATE, "submit", STATE_SUBMIT)) {
            return false;
        }
        boolean replaced = false;
        for (MethodNode method : classNode.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode get) || !MINECRAFT.equals(get.owner)
                        || !"getBlockRenderer".equals(get.name)) {
                    continue;
                }
                AbstractInsnNode next = get.getNext();
                if (!(next instanceof TypeInsnNode cast) || cast.getOpcode() != Opcodes.CHECKCAST) continue;
                MethodInsnNode call = findHeldBlockCall(cast, cast.desc);
                if (call == null) continue;
                method.instructions.remove(get);
                method.instructions.remove(cast);
                method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, classNode.name,
                        HELD_BLOCK_HELPER, "(L" + MINECRAFT + ";" + HELD_BLOCK_DESC.substring(1), false));
                replaced = true;
            }
        }
        if (replaced) {
            classNode.methods.add(heldBlockHelper(
                    host.hasMethod(LIGHT_COORDS, "lightCoordsWithEmission", WITH_EMISSION_DESC)));
        }
        return replaced;
    }

    private static MethodInsnNode findHeldBlockCall(AbstractInsnNode from, String castType) {
        for (AbstractInsnNode insn = from.getNext(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(castType)) {
                return HELD_BLOCK_DESC.equals(call.desc) ? call : null;
            }
            if (insn instanceof JumpInsnNode || insn instanceof LabelNode) return null;
        }
        return null;
    }

    /**
     * Submits the block's item model with no display transform, offset to the block renderer's
     * unit-cube origin. When the host has {@code LightCoordsUtil.lightCoordsWithEmission}, the
     * light is raised by the block's own emission first, as the removed held-block call did, so a
     * glowstone or a lantern stays lit in a dark place. Blocks without an item and the mod's
     * per-quad block tint are not reproduced.
     */
    private static MethodNode heldBlockHelper(boolean emissive) {
        MethodNode helper = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, HELD_BLOCK_HELPER,
                "(L" + MINECRAFT + ";" + HELD_BLOCK_DESC.substring(1), null, null);
        helper.visibleAnnotations = new ArrayList<>(List.of(new AnnotationNode(UNIQUE_DESC)));
        InsnList code = helper.instructions;
        LabelNode done = new LabelNode();
        // slots: 0 minecraft, 1 state, 2 pose, 3 collector, 4 light, 5 level, 6 player
        code.add(new TypeInsnNode(Opcodes.NEW, ITEM_STACK));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, BLOCK_STATE, "getBlock",
                "()Lnet/minecraft/world/level/block/Block;", false));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, ITEM_STACK, "<init>",
                "(Lnet/minecraft/world/level/ItemLike;)V", false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 7));
        code.add(new VarInsnNode(Opcodes.ALOAD, 7));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "isEmpty", "()Z", false));
        code.add(new JumpInsnNode(Opcodes.IFNE, done));
        if (emissive) {
            code.add(new VarInsnNode(Opcodes.ILOAD, 4));
            code.add(new VarInsnNode(Opcodes.ALOAD, 1));
            code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, BLOCK_STATE, "getLightEmission", "()I", false));
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LIGHT_COORDS, "lightCoordsWithEmission",
                    WITH_EMISSION_DESC, false));
            code.add(new VarInsnNode(Opcodes.ISTORE, 4));
        }
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, POSE_STACK, "pushPose", "()V", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new LdcInsnNode(0.5F));
        code.add(new LdcInsnNode(0.5F));
        code.add(new LdcInsnNode(0.5F));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, POSE_STACK, "translate", "(FFF)V", false));
        submitItemModel(code, 0, 7, DisplayContextSource.NONE, 5, 6, 2, 3, 4);
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, POSE_STACK, "popPose", "()V", false));
        code.add(done);
        code.add(new InsnNode(Opcodes.RETURN));
        helper.maxLocals = 9;
        helper.maxStack = 8;
        return helper;
    }

    private enum DisplayContextSource { NONE, LOCAL }

    /**
     * Emits {@code minecraft.getItemModelResolver().updateForTopItem(...)} on a fresh render
     * state followed by {@code submit(pose, collector, light, NO_OVERLAY, 0)}, the body of
     * 26.2's {@code ItemInHandRenderer.renderItem}. The context comes from a local or is NONE.
     */
    private static void submitItemModel(InsnList code, int minecraftSlot, int stackSlot,
            DisplayContextSource context, int levelOrEntitySlot, int ownerSlot, int poseSlot,
            int collectorSlot, int lightSlot) {
        int stateSlot = 8;
        code.add(new TypeInsnNode(Opcodes.NEW, STACK_STATE));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, STACK_STATE, "<init>", "()V", false));
        code.add(new VarInsnNode(Opcodes.ASTORE, stateSlot));
        if (minecraftSlot < 0) {
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MINECRAFT, "getInstance",
                    "()L" + MINECRAFT + ";", false));
        } else {
            code.add(new VarInsnNode(Opcodes.ALOAD, minecraftSlot));
        }
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, MINECRAFT, "getItemModelResolver",
                "()L" + MODEL_RESOLVER + ";", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, stateSlot));
        code.add(new VarInsnNode(Opcodes.ALOAD, stackSlot));
        if (context == DisplayContextSource.NONE) {
            code.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETSTATIC, DISPLAY_CONTEXT, "NONE",
                    "L" + DISPLAY_CONTEXT + ";"));
            code.add(new VarInsnNode(Opcodes.ALOAD, levelOrEntitySlot));
            code.add(new VarInsnNode(Opcodes.ALOAD, ownerSlot));
            code.add(new InsnNode(Opcodes.ICONST_0));
        } else {
            // renderItem(entity, stack, context, ...): level and seed come from the entity.
            code.add(new VarInsnNode(Opcodes.ALOAD, 3));
            code.add(new VarInsnNode(Opcodes.ALOAD, levelOrEntitySlot));
            code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LIVING_ENTITY, "level",
                    "()Lnet/minecraft/world/level/Level;", false));
            code.add(new VarInsnNode(Opcodes.ALOAD, levelOrEntitySlot));
            code.add(new VarInsnNode(Opcodes.ALOAD, levelOrEntitySlot));
            code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LIVING_ENTITY, "getId", "()I", false));
            code.add(new VarInsnNode(Opcodes.ALOAD, 3));
            code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, DISPLAY_CONTEXT, "ordinal", "()I", false));
            code.add(new InsnNode(Opcodes.IADD));
        }
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, MODEL_RESOLVER, "updateForTopItem",
                UPDATE_FOR_TOP_ITEM, false));
        code.add(new VarInsnNode(Opcodes.ALOAD, stateSlot));
        code.add(new VarInsnNode(Opcodes.ALOAD, poseSlot));
        code.add(new VarInsnNode(Opcodes.ALOAD, collectorSlot));
        code.add(new VarInsnNode(Opcodes.ILOAD, lightSlot));
        code.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETSTATIC,
                "net/minecraft/client/renderer/texture/OverlayTexture", "NO_OVERLAY", "I"));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, STACK_STATE, "submit", STATE_SUBMIT, false));
    }

    // ---- 26.3 split renderer ---------------------------------------------------------------

    private static boolean hostSplitRenderer(FuzzyMethodResolver host) {
        return !host.hasClass(OLD_RENDERER) && host.hasClass(RENDERER)
                && host.hasMethod(RENDERER, "submitHandsWithItems", NEW_HANDS)
                && host.hasMethod(RENDERER, "submitArmWithItem", NEW_ARM)
                && host.hasMethod(RENDERER, "renderPlayerArm", NEW_PLAYER_ARM)
                && host.hasMethod(RENDERER, "renderMap", NEW_MAP)
                && host.hasField(HANDS_STATE, "mainHandItem", L_STACK)
                && host.hasMethod(MODEL_RESOLVER, "updateForTopItem", UPDATE_FOR_TOP_ITEM)
                && host.hasMethod(STACK_STATE, "submit", STATE_SUBMIT);
    }

    private static boolean isOldRendererMixin(byte[] classBytes) {
        ClassNode probe = new ClassNode();
        new ClassReader(classBytes).accept(probe, ClassReader.SKIP_CODE);
        AnnotationNode mixin = mixinAnnotation(probe);
        if (mixin == null || mixin.values == null) return false;
        List<String> targets = new ArrayList<>();
        for (int i = 0; i + 1 < mixin.values.size(); i += 2) {
            Object value = mixin.values.get(i + 1);
            if (!(value instanceof List<?> list)) continue;
            for (Object entry : list) {
                if (entry instanceof Type type) targets.add(type.getInternalName());
                if (entry instanceof String name) targets.add(name.replace('.', '/'));
            }
        }
        return targets.size() == 1 && OLD_RENDERER.equals(targets.get(0));
    }

    private static byte[] remapRenderer(byte[] classBytes) {
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(classBytes).accept(
                new ClassRemapper(writer, new SimpleRemapper(OLD_RENDERER, RENDERER)), 0);
        return writer.toByteArray();
    }

    /**
     * Moves the arm redirect onto the split renderer. Returns {@code false} when the mixin has
     * no handler with the expected arm redirect shape, in which case nothing is changed.
     */
    private static boolean retargetToSplitRenderer(ClassNode classNode) {
        MethodNode handler = null;
        AnnotationNode redirect = null;
        for (MethodNode method : classNode.methods) {
            AnnotationNode candidate = annotation(method, REDIRECT_DESC);
            if (candidate != null && redirectsArmCall(candidate)
                    && method.desc.equals("(L" + RENDERER + ";" + OLD_ARM.substring(1))
                    && (method.access & Opcodes.ACC_STATIC) == 0) {
                if (handler != null) return false;
                handler = method;
                redirect = candidate;
            }
        }
        if (handler == null || !onlyKnownShadowsAndInjectors(classNode, handler)) return false;

        dropUnusedRemovedShadowFields(classNode);
        classNode.fields.add(uniqueField(PLAYER_STATE_FIELD, L_PLAYER_STATE));
        classNode.fields.add(uniqueField(HANDS_STATE_FIELD, L_HANDS));
        bridgeShadows(classNode);

        String originalName = handler.name;
        handler.name = originalName + LEGACY_SUFFIX;
        removeAnnotation(handler, REDIRECT_DESC);
        addAnnotation(handler, UNIQUE_DESC);
        handler.access = (handler.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PRIVATE;
        redirectRendererArmCalls(classNode, handler);

        setValue(redirect, "method", new ArrayList<>(List.of("submitHandsWithItems" + NEW_HANDS)));
        for (AnnotationNode at : atPoints(redirect)) {
            setValue(at, "target", "L" + RENDERER + ";submitArmWithItem" + NEW_ARM);
        }
        classNode.methods.add(splitHandler(classNode.name, originalName, handler, redirect));
        return true;
    }

    private static boolean redirectsArmCall(AnnotationNode redirect) {
        for (AnnotationNode at : atPoints(redirect)) {
            // Annotation text is not remapped with the class, so the old owner is still named.
            if (!(value(at, "target") instanceof String target)) continue;
            for (String owner : new String[]{OLD_RENDERER, RENDERER}) {
                if (target.equals("L" + owner + ";renderArmWithItem" + OLD_ARM)
                        || target.equals("L" + owner + ";submitArmWithItem" + OLD_ARM)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Declines a mixin that also injects elsewhere in the old class, since that code moved. */
    private static boolean onlyKnownShadowsAndInjectors(ClassNode classNode, MethodNode handler) {
        for (MethodNode method : classNode.methods) {
            if (method == handler) continue;
            for (List<AnnotationNode> list : Arrays.asList(method.visibleAnnotations, method.invisibleAnnotations)) {
                if (list == null) continue;
                for (AnnotationNode annotation : list) {
                    if (annotation.desc.startsWith("Lorg/spongepowered/asm/mixin/injection/")
                            || annotation.desc.startsWith("Lcom/llamalad7/mixinextras/injector/")
                            || annotation.desc.equals("Lorg/spongepowered/asm/mixin/Overwrite;")) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** The hand-item fields moved to {@code FirstPersonHandsAndItems}; drop unread shadows of them. */
    private static void dropUnusedRemovedShadowFields(ClassNode classNode) {
        classNode.fields.removeIf(field -> hasAnnotation(field.visibleAnnotations, SHADOW_DESC)
                && (field.name.equals("mainHandItem") || field.name.equals("offHandItem"))
                && !fieldReferenced(classNode, field));
    }

    private static boolean fieldReferenced(ClassNode classNode, FieldNode field) {
        for (MethodNode method : classNode.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode access && access.owner.equals(classNode.name)
                        && access.name.equals(field.name)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Turns each old-shape shadow into a mixin-owned bridge onto the split renderer. */
    private static void bridgeShadows(ClassNode classNode) {
        List<MethodNode> added = new ArrayList<>();
        for (MethodNode method : classNode.methods) {
            if (!hasAnnotation(method.visibleAnnotations, SHADOW_DESC)) continue;
            InsnList body = null;
            if (method.name.equals("renderPlayerArm") && method.desc.equals(OLD_PLAYER_ARM)) {
                body = forwardWithState(classNode.name, method.desc, "renderPlayerArm", NEW_PLAYER_ARM,
                        false, true);
                added.add(shadow("renderPlayerArm", NEW_PLAYER_ARM));
            } else if ((method.name.equals("renderArmWithItem") || method.name.equals("submitArmWithItem"))
                    && method.desc.equals(OLD_ARM)) {
                body = armBridge(classNode.name);
                added.add(shadow("submitArmWithItem", NEW_ARM));
            } else if (method.name.equals("renderMap") && method.desc.equals(OLD_MAP)) {
                body = mapBridge(classNode.name);
                added.add(shadow("renderMap", NEW_MAP));
            } else if (method.name.equals("renderItem") && method.desc.equals(OLD_RENDER_ITEM)) {
                body = new InsnList();
                LabelNode done = new LabelNode();
                body.add(new VarInsnNode(Opcodes.ALOAD, 2));
                body.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "isEmpty", "()Z", false));
                body.add(new JumpInsnNode(Opcodes.IFNE, done));
                // slots: 0 this, 1 entity, 2 stack, 3 context, 4 pose, 5 collector, 6 light
                submitItemModel(body, -1, 2, DisplayContextSource.LOCAL, 1, 1, 4, 5, 6);
                body.add(done);
                body.add(new InsnNode(Opcodes.RETURN));
            }
            if (body == null) continue;
            removeAnnotation(method, SHADOW_DESC);
            addAnnotation(method, UNIQUE_DESC);
            method.access &= ~Opcodes.ACC_ABSTRACT;
            method.instructions = body;
            method.tryCatchBlocks = new ArrayList<>();
            method.localVariables = null;
            method.maxLocals = 16;
            method.maxStack = 16;
        }
        for (MethodNode shadow : added) {
            if (findMethod(classNode, shadow.name, shadow.desc) == null) classNode.methods.add(shadow);
        }
    }

    /** {@code this.target(args..., [playerState], [handsState])} for a same-prefix bridge. */
    private static InsnList forwardWithState(String owner, String oldDesc, String name, String newDesc,
            boolean dropFirst, boolean appendPlayerState) {
        InsnList body = new InsnList();
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        int slot = 1;
        Type[] args = Type.getArgumentTypes(oldDesc);
        for (int i = 0; i < args.length; i++) {
            if (!(dropFirst && i == 0)) body.add(new VarInsnNode(args[i].getOpcode(Opcodes.ILOAD), slot));
            slot += args[i].getSize();
        }
        if (appendPlayerState) loadState(body, owner, PLAYER_STATE_FIELD, L_PLAYER_STATE);
        body.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, name, newDesc, false));
        body.add(new InsnNode(Opcodes.RETURN));
        return body;
    }

    private static InsnList armBridge(String owner) {
        InsnList body = new InsnList();
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        loadState(body, owner, PLAYER_STATE_FIELD, L_PLAYER_STATE);
        loadState(body, owner, HANDS_STATE_FIELD, L_HANDS);
        int slot = 2; // skip this and the removed player
        for (Type arg : Arrays.copyOfRange(Type.getArgumentTypes(OLD_ARM), 1, Type.getArgumentTypes(OLD_ARM).length)) {
            body.add(new VarInsnNode(arg.getOpcode(Opcodes.ILOAD), slot));
            slot += arg.getSize();
        }
        body.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "submitArmWithItem", NEW_ARM, false));
        body.add(new InsnNode(Opcodes.RETURN));
        return body;
    }

    private static InsnList mapBridge(String owner) {
        InsnList body = new InsnList();
        LabelNode offHand = new LabelNode();
        LabelNode call = new LabelNode();
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.add(new VarInsnNode(Opcodes.ALOAD, 1));
        body.add(new VarInsnNode(Opcodes.ALOAD, 2));
        body.add(new VarInsnNode(Opcodes.ILOAD, 3));
        body.add(new VarInsnNode(Opcodes.ALOAD, 4));
        // The main-hand flag only picks which hand's map data to draw.
        body.add(new VarInsnNode(Opcodes.ALOAD, 4));
        loadState(body, owner, HANDS_STATE_FIELD, L_HANDS);
        body.add(new FieldInsnNode(Opcodes.GETFIELD, HANDS_STATE, "mainHandItem", L_STACK));
        body.add(new JumpInsnNode(Opcodes.IF_ACMPNE, offHand));
        body.add(new InsnNode(Opcodes.ICONST_1));
        body.add(new JumpInsnNode(Opcodes.GOTO, call));
        body.add(offHand);
        body.add(new InsnNode(Opcodes.ICONST_0));
        body.add(call);
        loadState(body, owner, HANDS_STATE_FIELD, L_HANDS);
        body.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "renderMap", NEW_MAP, false));
        body.add(new InsnNode(Opcodes.RETURN));
        return body;
    }

    private static void loadState(InsnList body, String owner, String field, String desc) {
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.add(new FieldInsnNode(Opcodes.GETFIELD, owner, field, desc));
    }

    /** Calls the old body's pass-through on the renderer receiver now reach the arm bridge. */
    private static void redirectRendererArmCalls(ClassNode classNode, MethodNode legacy) {
        for (AbstractInsnNode insn : legacy.instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(RENDERER)
                    && call.desc.equals(OLD_ARM)
                    && (call.name.equals("renderArmWithItem") || call.name.equals("submitArmWithItem"))) {
                call.owner = classNode.name;
                call.name = findMethod(classNode, "renderArmWithItem", OLD_ARM) != null
                        ? "renderArmWithItem" : "submitArmWithItem";
                if (findMethod(classNode, call.name, OLD_ARM) == null) {
                    MethodNode bridge = new MethodNode(Opcodes.ACC_PRIVATE, call.name, OLD_ARM, null, null);
                    bridge.visibleAnnotations = new ArrayList<>(List.of(new AnnotationNode(UNIQUE_DESC)));
                    bridge.instructions = armBridge(classNode.name);
                    bridge.maxLocals = 16;
                    bridge.maxStack = 16;
                    classNode.methods.add(bridge);
                    if (findMethod(classNode, "submitArmWithItem", NEW_ARM) == null) {
                        classNode.methods.add(shadow("submitArmWithItem", NEW_ARM));
                    }
                }
            }
        }
    }

    /**
     * The new redirect handler keeps both render states for the shadows, then runs the original
     * body with the local player standing in for the removed player argument.
     */
    private static MethodNode splitHandler(String owner, String name, MethodNode legacy,
            AnnotationNode redirect) {
        String desc = "(L" + RENDERER + ";" + NEW_ARM.substring(1);
        MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE, name, desc, null,
                legacy.exceptions == null ? null : legacy.exceptions.toArray(new String[0]));
        handler.visibleAnnotations = new ArrayList<>(List.of(redirect));
        InsnList code = handler.instructions;
        // slots: 0 this, 1 renderer, 2 player state, 3 hands state, 4.. the shared arm arguments
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, PLAYER_STATE_FIELD, L_PLAYER_STATE));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, HANDS_STATE_FIELD, L_HANDS));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MINECRAFT, "getInstance", "()L" + MINECRAFT + ";", false));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, MINECRAFT, "player", "L" + LOCAL_PLAYER + ";"));
        int slot = 4;
        Type[] shared = Type.getArgumentTypes(OLD_ARM);
        for (int i = 1; i < shared.length; i++) {
            code.add(new VarInsnNode(shared[i].getOpcode(Opcodes.ILOAD), slot));
            slot += shared[i].getSize();
        }
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, owner, legacy.name, legacy.desc, false));
        code.add(new InsnNode(Opcodes.RETURN));
        handler.maxLocals = slot;
        handler.maxStack = 16;
        return handler;
    }

    /** Points the mixin at an absent placeholder, the same way the removed-target check does. */
    private static void disable(ClassNode classNode) {
        AnnotationNode mixin = mixinAnnotation(classNode);
        if (mixin.values == null) mixin.values = new ArrayList<>();
        for (int i = mixin.values.size() - 2; i >= 0; i -= 2) {
            Object key = mixin.values.get(i);
            if ("value".equals(key) || "targets".equals(key)) {
                mixin.values.remove(i + 1);
                mixin.values.remove(i);
            }
        }
        String simple = classNode.name.substring(classNode.name.lastIndexOf('/') + 1);
        mixin.values.add("targets");
        mixin.values.add(new ArrayList<>(List.of("retromod/stripped/" + simple)));
    }

    // ---- small helpers ---------------------------------------------------------------------

    private static MethodNode shadow(String name, String desc) {
        MethodNode shadow = new MethodNode(Opcodes.ACC_PRIVATE, name, desc, null, null);
        shadow.visibleAnnotations = new ArrayList<>(List.of(new AnnotationNode(SHADOW_DESC)));
        // Mixin replaces a shadow body with the target's, so this only satisfies the verifier.
        shadow.instructions.add(new InsnNode(Opcodes.RETURN));
        shadow.maxLocals = Type.getArgumentsAndReturnSizes(desc) >> 2;
        return shadow;
    }

    private static FieldNode uniqueField(String name, String desc) {
        FieldNode field = new FieldNode(Opcodes.ACC_PRIVATE, name, desc, null, null);
        field.visibleAnnotations = new ArrayList<>(List.of(new AnnotationNode(UNIQUE_DESC)));
        return field;
    }

    private static boolean isMixin(ClassNode classNode) {
        return mixinAnnotation(classNode) != null;
    }

    private static AnnotationNode mixinAnnotation(ClassNode classNode) {
        for (List<AnnotationNode> list : Arrays.asList(classNode.visibleAnnotations, classNode.invisibleAnnotations)) {
            if (list == null) continue;
            for (AnnotationNode annotation : list) if (MIXIN_DESC.equals(annotation.desc)) return annotation;
        }
        return null;
    }

    private static AnnotationNode annotation(MethodNode method, String desc) {
        for (List<AnnotationNode> list : Arrays.asList(method.visibleAnnotations, method.invisibleAnnotations)) {
            if (list == null) continue;
            for (AnnotationNode annotation : list) if (desc.equals(annotation.desc)) return annotation;
        }
        return null;
    }

    private static boolean hasAnnotation(List<AnnotationNode> list, String desc) {
        if (list == null) return false;
        for (AnnotationNode annotation : list) if (desc.equals(annotation.desc)) return true;
        return false;
    }

    private static void removeAnnotation(MethodNode method, String desc) {
        if (method.visibleAnnotations != null) method.visibleAnnotations.removeIf(a -> desc.equals(a.desc));
        if (method.invisibleAnnotations != null) method.invisibleAnnotations.removeIf(a -> desc.equals(a.desc));
    }

    private static void addAnnotation(MethodNode method, String desc) {
        if (method.visibleAnnotations == null) method.visibleAnnotations = new ArrayList<>();
        method.visibleAnnotations.add(new AnnotationNode(desc));
    }

    private static MethodNode findMethod(ClassNode classNode, String name, String desc) {
        for (MethodNode method : classNode.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return method;
        }
        return null;
    }

    private static List<AnnotationNode> atPoints(AnnotationNode injector) {
        Object at = value(injector, "at");
        List<AnnotationNode> points = new ArrayList<>();
        if (at instanceof AnnotationNode single) points.add(single);
        if (at instanceof List<?> list) {
            for (Object entry : list) if (entry instanceof AnnotationNode node) points.add(node);
        }
        return points;
    }

    private static Object value(AnnotationNode annotation, String key) {
        if (annotation.values == null) return null;
        for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }

    private static void setValue(AnnotationNode annotation, String key, Object newValue) {
        if (annotation.values == null) annotation.values = new ArrayList<>();
        for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) {
                annotation.values.set(i + 1, newValue);
                return;
            }
        }
        annotation.values.add(key);
        annotation.values.add(newValue);
    }
}
