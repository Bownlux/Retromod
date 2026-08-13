/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;

/** Repairs legacy Mixin members whose old descriptor can be preserved as a bridge overload. */
final class MixinLegacyMemberBridge {

    private static final String MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String SHADOW_DESC = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String INVOKER_DESC = "Lorg/spongepowered/asm/mixin/gen/Invoker;";
    private static final String ACCESSOR_DESC = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
    private static final String UNIQUE_DESC = "Lorg/spongepowered/asm/mixin/Unique;";

    private static final String LIVING_ENTITY = "net/minecraft/world/entity/LivingEntity";
    private static final String MOB_EFFECT = "net/minecraft/world/effect/MobEffect";
    private static final String MOB_EFFECT_INSTANCE = "net/minecraft/world/effect/MobEffectInstance";
    private static final String HOLDER = "net/minecraft/core/Holder";
    private static final String REGISTRY = "net/minecraft/core/Registry";
    private static final String BUILT_IN_REGISTRIES = "net/minecraft/core/registries/BuiltInRegistries";
    private static final String POSE = "net/minecraft/world/entity/Pose";
    private static final String GUI = "net/minecraft/client/gui/Gui";
    private static final String HUD = "net/minecraft/client/gui/Hud";
    private static final String MINECRAFT = "net/minecraft/client/Minecraft";
    private static final String ENTITY = "net/minecraft/world/entity/Entity";
    private static final String PLAYER = "net/minecraft/world/entity/player/Player";
    private static final String CAMERA_PLAYER_DESC = "()L" + PLAYER + ";";
    private static final String GUI_GRAPHICS_EXTRACTOR =
            "net/minecraft/client/gui/GuiGraphicsExtractor";
    private static final String DELTA_TRACKER = "net/minecraft/client/DeltaTracker";
    private static final String CALLBACK_INFO =
            "org/spongepowered/asm/mixin/injection/callback/CallbackInfo";
    private static final String HOTBAR_EXTRACT_DESC =
            "(L" + GUI_GRAPHICS_EXTRACTOR + ";L" + DELTA_TRACKER + ";)V";
    private static final String HOTBAR_HANDLER_DESC =
            "(L" + GUI_GRAPHICS_EXTRACTOR + ";L" + DELTA_TRACKER + ";L"
                    + CALLBACK_INFO + ";)V";
    private static final String HOTBAR_SLOT_DESC =
            "(L" + GUI_GRAPHICS_EXTRACTOR + ";IIL" + DELTA_TRACKER + ";L" + PLAYER
                    + ";Lnet/minecraft/world/item/ItemStack;I)V";
    private static final String INJECT_DESC =
            "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String CODEC = "com/mojang/serialization/Codec";
    private static final String MAP_CODEC = "com/mojang/serialization/MapCodec";
    private static final String MAP_CODEC_CODEC = "com/mojang/serialization/MapCodec$MapCodecCodec";
    private static final String SMITHING_TRANSFORM_INTERMEDIARY = "net/minecraft/class_8060";
    private static final String INGREDIENT_INTERMEDIARY = "net/minecraft/class_1856";
    private static final String INVENTORY_SCREEN_INTERMEDIARY = "net/minecraft/class_490";
    private static final String OLD_EFFECTS_INVENTORY_SCREEN_INTERMEDIARY = "net/minecraft/class_485";
    private static final String CONTAINER_SCREEN_INTERMEDIARY = "net/minecraft/class_465";
    private static final String CHAT_OPTIONS_SCREEN =
            "net/minecraft/client/gui/screens/options/ChatOptionsScreen";
    private static final String SIMPLE_OPTIONS_SCREEN =
            "net/minecraft/client/gui/screens/SimpleOptionsSubScreen";
    private static final String SIMPLE_OPTIONS_SCREEN_PLACEHOLDER =
            "com/retromod/polyfill/minecraft/mixin/embedded/ChatOptionsScreenStub";
    private static final String OPTIONS_SCREEN =
            "net/minecraft/client/gui/screens/options/OptionsSubScreen";
    private static final String CHAT_OPTIONS_OLD_CTOR =
            "(Lnet/minecraft/client/gui/screens/Screen;"
            + "Lnet/minecraft/client/Options;"
            + "Lnet/minecraft/network/chat/Component;"
            + "[Lnet/minecraft/client/OptionInstance;)V";
    private static final String CHAT_OPTIONS_NEW_CTOR =
            "(Lnet/minecraft/client/gui/screens/Screen;"
            + "Lnet/minecraft/client/Options;"
            + "Lnet/minecraft/network/chat/Component;)V";

    /**
     * Worldgen type wrappers whose registrar takes a {@code MapCodec} on current hosts.
     * Each one keeps a private constructor and a private static {@code register}, so a mod can
     * only reach them through an {@code @Invoker} mixin. Verified against 26.2: these are exactly
     * the wrapper classes that pair a private constructor with a
     * {@code register(String, MapCodec)}. The remaining registries of this family are functional
     * interfaces that a mod registers with a lambda, so they never need a bridge.
     */
    private static final List<String> MAP_CODEC_TYPE_WRAPPERS = List.of(
            "net/minecraft/world/level/levelgen/feature/treedecorators/TreeDecoratorType",
            "net/minecraft/world/level/levelgen/feature/trunkplacers/TrunkPlacerType",
            "net/minecraft/world/level/levelgen/feature/foliageplacers/FoliagePlacerType",
            "net/minecraft/world/level/levelgen/feature/rootplacers/RootPlacerType",
            "net/minecraft/world/level/levelgen/feature/featuresize/FeatureSizeType",
            "net/minecraft/world/level/levelgen/feature/stateproviders/BlockStateProviderType");

    private static final String OLD_HAS_EFFECT = "(L" + MOB_EFFECT + ";)Z";
    private static final String NEW_HAS_EFFECT = "(L" + HOLDER + ";)Z";
    private static final String OLD_GET_EFFECT = "(L" + MOB_EFFECT + ";)L" + MOB_EFFECT_INSTANCE + ";";
    private static final String NEW_GET_EFFECT = "(L" + HOLDER + ";)L" + MOB_EFFECT_INSTANCE + ";";
    private static final String OLD_EFFECT_FROM_INSTANCE = "()L" + MOB_EFFECT + ";";
    private static final String NEW_EFFECT_FROM_INSTANCE = "()L" + HOLDER + ";";
    private static final String OLD_POSE_CTOR = "(Ljava/lang/String;I)L" + POSE + ";";

    private MixinLegacyMemberBridge() {}

    static boolean apply(ClassNode classNode) {
        List<String> targets = mixinTargets(classNode);
        boolean modified = false;
        if (targets.contains(LIVING_ENTITY)) {
            modified |= bridgeMobEffectMembers(classNode);
        }
        if (targets.contains(GUI)) {
            modified |= repairDoubleHotbarMixin(classNode, targets);
            modified |= bridgeGuiCameraPlayer(classNode);
        }
        if (targets.contains(SMITHING_TRANSFORM_INTERMEDIARY)) {
            modified |= bridgeOptionalSmithingIngredients(classNode);
        }
        if (targets.contains(INVENTORY_SCREEN_INTERMEDIARY)) {
            modified |= rebaseLegacyInventoryScreenMixin(classNode);
        }
        if (targets.size() == 1 && targets.contains(CHAT_OPTIONS_SCREEN)) {
            modified |= rebaseLegacyChatOptionsMixin(classNode);
        }
        // Each bridge below calls a private member of the target, which is only legal once the
        // mixin has been merged into it.
        if (!mergesIntoTarget(classNode)) return modified;

        if (targets.contains(POSE)) {
            modified |= bridgePoseConstructor(classNode);
        }
        for (String wrapper : MAP_CODEC_TYPE_WRAPPERS) {
            if (targets.contains(wrapper)) {
                modified |= bridgeCodecRegistrar(classNode, wrapper);
            }
        }
        return modified;
    }

    /**
     * Repairs the exact HUD mixin shape shipped by Double Hotbar 1.3.4 (#181).
     *
     * <p>On 26.1 the hotbar implementation is still on {@code Gui}, but its three private
     * methods were renamed from render-oriented names to extract-oriented names. In 26.2 those
     * methods, their fields, and their bytecode moved intact to {@code Hud}. A global
     * {@code Gui} to {@code Hud} redirect would move unrelated mixins, so this repair requires
     * all seven handlers, their exact selectors, the slot shadow, the hotbar sprite shadow, and
     * the slot call inside the drawing handler before changing anything.
     */
    private static boolean repairDoubleHotbarMixin(ClassNode classNode, List<String> targets) {
        if (targets.size() != 1 || !GUI.equals(targets.get(0))) return false;

        FieldNode hotbarSprite = findAnnotatedField(classNode, "HOTBAR_SPRITE",
                "Lnet/minecraft/resources/Identifier;", SHADOW_DESC);
        MethodNode slot = findAnnotatedMethod(
                classNode, "renderSlot", HOTBAR_SLOT_DESC, SHADOW_DESC);
        MethodNode drawItems = findMethod(classNode, "renderHotbarItems", HOTBAR_HANDLER_DESC);
        if (hotbarSprite == null || slot == null || drawItems == null
                || countCalls(drawItems, classNode.name, "renderSlot", HOTBAR_SLOT_DESC) != 1) {
            return false;
        }

        String oldItems = "renderItemHotbar" + HOTBAR_EXTRACT_DESC;
        String newItems = "extractItemHotbar" + HOTBAR_EXTRACT_DESC;
        String oldDecorations = "renderHotbarAndDecorations" + HOTBAR_EXTRACT_DESC;
        String newDecorations = "extractHotbarAndDecorations" + HOTBAR_EXTRACT_DESC;
        List<SelectorRepair> selectors = new ArrayList<>();
        for (String handler : List.of(
                "renderHotbarFrame", "shiftHotbarSelector", "returnHotbarSelector",
                "shiftHotbarItems", "renderHotbarItems")) {
            SelectorRepair repair = exactSelectorRepair(
                    classNode, handler, oldItems, newItems);
            if (repair == null) return false;
            selectors.add(repair);
        }
        for (String handler : List.of("shiftStatusBars", "returnStatusBars")) {
            SelectorRepair repair = exactSelectorRepair(
                    classNode, handler, oldDecorations, newDecorations);
            if (repair == null) return false;
            selectors.add(repair);
        }

        slot.name = "extractSlot";
        for (MethodNode method : classNode.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode call
                        && classNode.name.equals(call.owner)
                        && "renderSlot".equals(call.name)
                        && HOTBAR_SLOT_DESC.equals(call.desc)) {
                    call.name = "extractSlot";
                }
            }
        }
        for (SelectorRepair repair : selectors) repair.apply();

        if (com.retromod.core.RetromodVersion.compareMcVersions(
                    com.retromod.core.RetromodVersion.TARGET_MC_VERSION, "26.2") >= 0) {
            retargetMixin(classNode, GUI, HUD);
        }
        return true;
    }

    private static SelectorRepair exactSelectorRepair(
            ClassNode classNode, String methodName, String oldSelector, String newSelector) {
        MethodNode method = findMethod(classNode, methodName, HOTBAR_HANDLER_DESC);
        AnnotationNode inject = method != null ? findAnnotation(method, INJECT_DESC) : null;
        if (inject == null || inject.values == null) return null;
        for (int i = 0; i + 1 < inject.values.size(); i += 2) {
            if (!"method".equals(inject.values.get(i))) continue;
            Object value = inject.values.get(i + 1);
            if (!(value instanceof List<?> selectors)
                    || selectors.size() != 1 || !oldSelector.equals(selectors.get(0))) {
                return null;
            }
            return new SelectorRepair(inject, i + 1, newSelector);
        }
        return null;
    }

    private static int countCalls(MethodNode method, String owner, String name, String desc) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)
                    && desc.equals(call.desc)) {
                count++;
            }
        }
        return count;
    }

    private static void retargetMixin(ClassNode classNode, String oldTarget, String newTarget) {
        for (List<AnnotationNode> annotations : annotationLists(
                classNode.visibleAnnotations, classNode.invisibleAnnotations)) {
            for (AnnotationNode annotation : annotations) {
                if (!MIXIN_DESC.equals(annotation.desc) || annotation.values == null) continue;
                for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                    if (!"value".equals(annotation.values.get(i))) continue;
                    Object value = annotation.values.get(i + 1);
                    if (!(value instanceof List<?> targets) || targets.size() != 1
                            || !(targets.get(0) instanceof Type type)
                            || !oldTarget.equals(type.getInternalName())) {
                        continue;
                    }
                    annotation.values.set(i + 1,
                            new ArrayList<>(List.of(Type.getObjectType(newTarget))));
                    return;
                }
            }
        }
    }

    private record SelectorRepair(AnnotationNode annotation, int valueIndex, String selector) {
        void apply() {
            annotation.values.set(valueIndex, new ArrayList<>(List.of(selector)));
        }
    }

    /**
     * The effects panel stopped being an InventoryScreen superclass and became a composed helper.
     * Patchouli's mixin does not use that old superclass directly, but Mixin still rejects the
     * class before applying any mods. Rebase it to the nearest stable screen ancestor. Its old
     * constructor already has that ancestor's descriptor, so only the invocation owner changes.
     */
    private static boolean rebaseLegacyInventoryScreenMixin(ClassNode classNode) {
        if (!OLD_EFFECTS_INVENTORY_SCREEN_INTERMEDIARY.equals(classNode.superName)) return false;

        classNode.superName = CONTAINER_SCREEN_INTERMEDIARY;
        for (MethodNode method : classNode.methods) {
            if (!"<init>".equals(method.name)) continue;
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKESPECIAL
                        && "<init>".equals(call.name)
                        && OLD_EFFECTS_INVENTORY_SCREEN_INTERMEDIARY.equals(call.owner)) {
                    call.owner = CONTAINER_SCREEN_INTERMEDIARY;
                }
            }
        }
        return true;
    }

    /**
     * The options screen hierarchy dropped {@code SimpleOptionsSubScreen} and moved the option
     * list out of its superclass constructor. No Chat Reports carries the old array only to pass
     * it to {@code super}, so removing that one load preserves the mixin's own constructor API and
     * every feature method while satisfying the current target hierarchy.
     */
    private static boolean rebaseLegacyChatOptionsMixin(ClassNode classNode) {
        if (!com.retromod.core.RetromodVersion.isUnobfuscatedTarget(
                    com.retromod.core.RetromodVersion.TARGET_MC_VERSION)
                || (!SIMPLE_OPTIONS_SCREEN.equals(classNode.superName)
                    && !SIMPLE_OPTIONS_SCREEN_PLACEHOLDER.equals(classNode.superName))) {
            return false;
        }

        String legacySuper = classNode.superName;
        List<ConstructorRebase> constructors = new ArrayList<>();
        List<MethodInsnNode> initCalls = new ArrayList<>();
        for (MethodNode method : classNode.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call)
                        || call.getOpcode() != Opcodes.INVOKESPECIAL
                        || !legacySuper.equals(call.owner)) {
                    continue;
                }
                if ("<init>".equals(call.name) && CHAT_OPTIONS_OLD_CTOR.equals(call.desc)) {
                    AbstractInsnNode arrayLoad = previousOpcode(call);
                    if (!(arrayLoad instanceof VarInsnNode load)
                            || load.getOpcode() != Opcodes.ALOAD || load.var != 4) {
                        return false;
                    }
                    constructors.add(new ConstructorRebase(method, call, arrayLoad));
                } else if ("init".equals(call.name) && "()V".equals(call.desc)) {
                    initCalls.add(call);
                } else {
                    // A direct call to any other removed-super member needs a semantic bridge.
                    return false;
                }
            }
        }
        if (constructors.isEmpty()) return false;

        classNode.superName = OPTIONS_SCREEN;
        for (ConstructorRebase constructor : constructors) {
            constructor.method().instructions.remove(constructor.removedArgument());
            constructor.call().owner = OPTIONS_SCREEN;
            constructor.call().desc = CHAT_OPTIONS_NEW_CTOR;
        }
        for (MethodInsnNode initCall : initCalls) initCall.owner = OPTIONS_SCREEN;
        return true;
    }

    private static AbstractInsnNode previousOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode previous = instruction.getPrevious();
        while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
        return previous;
    }

    private record ConstructorRebase(
            MethodNode method, MethodInsnNode call, AbstractInsnNode removedArgument) {}

    /**
     * Minecraft 1.21.2 made the smithing transform recipe's template and addition ingredients
     * optional. Patchouli 1.20.1 accesses the old fields through an accessor interface, so Mixin
     * rejects the interface before recipe registration completes. Retype the generated accessors;
     * the version shim adapts Patchouli's old call sites back to Ingredient.
     */
    private static boolean bridgeOptionalSmithingIngredients(ClassNode classNode) {
        if ((classNode.access & Opcodes.ACC_INTERFACE) == 0) return false;
        boolean modified = false;
        modified |= bridgeOptionalSmithingIngredient(
                classNode, "getTemplate", "field_42030");
        modified |= bridgeOptionalSmithingIngredient(
                classNode, "getAddition", "field_42032");
        return modified;
    }

    private static boolean bridgeOptionalSmithingIngredient(ClassNode classNode,
            String oldMethodName, String fieldName) {
        String ingredientDesc = "L" + INGREDIENT_INTERMEDIARY + ";";
        MethodNode accessor = findAnnotatedMethod(
                classNode, oldMethodName, "()" + ingredientDesc, ACCESSOR_DESC);
        if (accessor == null) return false;

        String optionalAccessorName = "retromod$" + oldMethodName + "Optional";
        accessor.name = optionalAccessorName;
        accessor.desc = "()Ljava/util/Optional;";
        accessor.signature = "()Ljava/util/Optional<" + ingredientDesc + ">;";
        // Patchouli relies on name inference plus its refmap. Renaming the accessor would make
        // that inference point at a fictional field, so pin the live intermediary field name.
        AnnotationNode accessorAnnotation = findAnnotation(accessor, ACCESSOR_DESC);
        accessorAnnotation.values = new ArrayList<>(List.of("value", fieldName));

        return true;
    }

    /**
     * Whether a mixin-owned method ends up inside the target class.
     *
     * <p>Only a class mixin is merged. An accessor written as an interface keeps its methods
     * where they are, so a bridge there would be an illegal access at runtime: Mixin widens a
     * private target member only because of the {@code @Invoker} the bridge would remove, and
     * the mod's refmap still names that member with its old descriptor. Both have to move
     * together, so those are left for Mixin to resolve as before.
     */
    private static boolean mergesIntoTarget(ClassNode classNode) {
        return (classNode.access & Opcodes.ACC_INTERFACE) == 0;
    }

    /**
     * Keeps a legacy HUD mixin's camera-player lookup independent of the private target helper.
     * This lets an exact mixin repair follow the {@code Gui} to {@code Hud} move without carrying
     * a private shadow dependency across the target change (#181, Double Hotbar).
     */
    private static boolean bridgeGuiCameraPlayer(ClassNode classNode) {
        MethodNode shadow = findAnnotatedMethod(classNode, "getCameraPlayer",
                CAMERA_PLAYER_DESC, SHADOW_DESC);
        if (shadow == null) return false;

        makeConcreteUnique(shadow, SHADOW_DESC);
        InsnList code = shadow.instructions;
        LabelNode notAPlayer = new LabelNode();
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MINECRAFT, "getInstance",
                "()L" + MINECRAFT + ";", false));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, MINECRAFT, "getCameraEntity",
                "()L" + ENTITY + ";", false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new TypeInsnNode(Opcodes.INSTANCEOF, PLAYER));
        code.add(new JumpInsnNode(Opcodes.IFEQ, notAPlayer));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, PLAYER));
        code.add(new InsnNode(Opcodes.ARETURN));
        code.add(notAPlayer);
        // Written by hand: a bridged class is re-emitted without frame computation.
        code.add(new FrameNode(Opcodes.F_FULL, 2,
                new Object[]{classNode.name, ENTITY}, 0, new Object[0]));
        // The camera can be a non-player entity, and the old method returned null for that.
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new InsnNode(Opcodes.ARETURN));
        shadow.maxStack = 1;
        shadow.maxLocals = 2;
        return true;
    }

    /**
     * Worldgen type registrars now take a {@code MapCodec}, so a mod's {@code @Invoker} keeps its
     * old {@code Codec} descriptor and no longer resolves. The invoker becomes a mixin-owned
     * method that converts the codec and calls the current member, which leaves the mod's own
     * call sites and their {@code Codec} fields untouched.
     *
     * <p>Matching is by descriptor rather than by the {@code @Invoker} name: on Fabric that name
     * is a refmap entry rather than the member name, while a {@code (String, Codec)} method
     * returning the wrapper can only be its registrar.
     */
    private static boolean bridgeCodecRegistrar(ClassNode classNode, String wrapper) {
        String returns = "L" + wrapper + ";";
        boolean modified = false;

        MethodNode registrar = findAnnotatedMethod(classNode, null,
                "(Ljava/lang/String;L" + CODEC + ";)" + returns, INVOKER_DESC);
        if (registrar != null && isStatic(registrar)) {
            makeConcreteUnique(registrar, INVOKER_DESC);
            InsnList code = registrar.instructions;
            emitCodecAsMapCodec(code, 1, new Object[]{"java/lang/String", CODEC});
            code.add(new VarInsnNode(Opcodes.ASTORE, 2));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new VarInsnNode(Opcodes.ALOAD, 2));
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, wrapper, "register",
                    "(Ljava/lang/String;L" + MAP_CODEC + ";)" + returns, false));
            code.add(new InsnNode(Opcodes.ARETURN));
            registrar.maxStack = 2;
            registrar.maxLocals = 3;
            modified = true;
        }

        // Some mods build the wrapper directly instead of going through the registrar.
        MethodNode factory = findAnnotatedMethod(classNode, null,
                "(L" + CODEC + ";)" + returns, INVOKER_DESC);
        if (factory != null && isStatic(factory) && annotationValue(factory, INVOKER_DESC, "<init>")) {
            makeConcreteUnique(factory, INVOKER_DESC);
            InsnList code = factory.instructions;
            emitCodecAsMapCodec(code, 0, new Object[]{CODEC});
            code.add(new VarInsnNode(Opcodes.ASTORE, 1));
            code.add(new TypeInsnNode(Opcodes.NEW, wrapper));
            code.add(new InsnNode(Opcodes.DUP));
            code.add(new VarInsnNode(Opcodes.ALOAD, 1));
            code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, wrapper, "<init>",
                    "(L" + MAP_CODEC + ";)V", false));
            code.add(new InsnNode(Opcodes.ARETURN));
            factory.maxStack = 3;
            factory.maxLocals = 2;
            modified = true;
        }
        return modified;
    }

    /**
     * Leaves a {@code MapCodec} on the stack for the {@code Codec} held in {@code codecSlot}.
     * A codec built by {@code RecordCodecBuilder} is a wrapped {@code MapCodec}, so unwrapping it
     * recovers the exact original and keeps its field names. Any other codec falls back to
     * {@code assumeMapUnsafe}, which reads the whole map rather than nesting the value under a
     * synthetic field, so the mod's existing worldgen JSON still loads as written. That fallback
     * does read and write a {@code value} key under compressed ops, but datapack JSON is not
     * compressed and the common record codec never reaches it.
     *
     * <p>Frames are written by hand: a class that only needed a bridge is re-emitted without
     * frame computation, so a branch here has to carry its own stack map.
     */
    private static void emitCodecAsMapCodec(InsnList code, int codecSlot, Object[] frameLocals) {
        LabelNode assumeMap = new LabelNode();
        LabelNode done = new LabelNode();
        code.add(new VarInsnNode(Opcodes.ALOAD, codecSlot));
        code.add(new TypeInsnNode(Opcodes.INSTANCEOF, MAP_CODEC_CODEC));
        code.add(new JumpInsnNode(Opcodes.IFEQ, assumeMap));
        code.add(new VarInsnNode(Opcodes.ALOAD, codecSlot));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, MAP_CODEC_CODEC));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, MAP_CODEC_CODEC, "codec",
                "()L" + MAP_CODEC + ";", false));
        code.add(new JumpInsnNode(Opcodes.GOTO, done));
        code.add(assumeMap);
        code.add(new FrameNode(Opcodes.F_FULL, frameLocals.length, frameLocals, 0, new Object[0]));
        code.add(new VarInsnNode(Opcodes.ALOAD, codecSlot));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MAP_CODEC, "assumeMapUnsafe",
                "(L" + CODEC + ";)L" + MAP_CODEC + ";", false));
        code.add(done);
        code.add(new FrameNode(Opcodes.F_FULL, frameLocals.length, frameLocals,
                1, new Object[]{MAP_CODEC}));
    }

    private static boolean isStatic(MethodNode method) {
        return (method.access & Opcodes.ACC_STATIC) != 0;
    }

    /**
     * Holder-backed registries changed the LivingEntity effect API without removing MobEffect.
     * Keeping the old overload inside the mixin avoids retyping every mod-owned effect field.
     */
    private static boolean bridgeMobEffectMembers(ClassNode classNode) {
        MethodNode shadow = findAnnotatedMethod(classNode, "hasEffect", OLD_HAS_EFFECT, SHADOW_DESC);
        if (shadow == null) return false;

        makeConcreteUnique(shadow, SHADOW_DESC);
        shadow.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        shadow.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, BUILT_IN_REGISTRIES,
                "MOB_EFFECT", "L" + REGISTRY + ";"));
        shadow.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        shadow.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, REGISTRY,
                "wrapAsHolder", "(Ljava/lang/Object;)L" + HOLDER + ";", true));
        shadow.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LIVING_ENTITY,
                "hasEffect", NEW_HAS_EFFECT, false));
        shadow.instructions.add(new InsnNode(Opcodes.IRETURN));
        shadow.maxStack = 3;
        shadow.maxLocals = 2;

        boolean needsGetEffectBridge = false;
        for (MethodNode method : classNode.methods) {
            for (var insn : method.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode call)) continue;
                if (LIVING_ENTITY.equals(call.owner) && "getEffect".equals(call.name)
                        && OLD_GET_EFFECT.equals(call.desc)) {
                    call.owner = classNode.name;
                    call.name = "retromod$getEffect";
                    needsGetEffectBridge = true;
                } else if (MOB_EFFECT_INSTANCE.equals(call.owner) && "getEffect".equals(call.name)
                        && OLD_EFFECT_FROM_INSTANCE.equals(call.desc)) {
                    call.desc = NEW_EFFECT_FROM_INSTANCE;
                    method.instructions.insert(call, effectHolderUnwrap());
                }
            }
        }
        if (needsGetEffectBridge && findMethod(classNode, "retromod$getEffect", OLD_GET_EFFECT) == null) {
            classNode.methods.add(getEffectBridge());
        }
        return true;
    }

    private static MethodNode getEffectBridge() {
        MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE, "retromod$getEffect",
                OLD_GET_EFFECT, null, null);
        addInvisibleAnnotation(method, UNIQUE_DESC);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, BUILT_IN_REGISTRIES,
                "MOB_EFFECT", "L" + REGISTRY + ";"));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, REGISTRY,
                "wrapAsHolder", "(Ljava/lang/Object;)L" + HOLDER + ";", true));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LIVING_ENTITY,
                "getEffect", NEW_GET_EFFECT, false));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.maxStack = 3;
        method.maxLocals = 2;
        return method;
    }

    private static InsnList effectHolderUnwrap() {
        InsnList out = new InsnList();
        out.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, HOLDER,
                "value", "()Ljava/lang/Object;", true));
        out.add(new TypeInsnNode(Opcodes.CHECKCAST, MOB_EFFECT));
        return out;
    }

    /**
     * Pose gained an id and serialized name. The mixin's old two-argument enum factory can remain
     * as an overload and supply both from the requested enum name and ordinal.
     */
    private static boolean bridgePoseConstructor(ClassNode classNode) {
        MethodNode invoker = findAnnotatedMethod(classNode, null, OLD_POSE_CTOR, INVOKER_DESC);
        if (invoker == null || !annotationValue(invoker, INVOKER_DESC, "<init>")) return false;

        makeConcreteUnique(invoker, INVOKER_DESC);
        invoker.instructions.add(new TypeInsnNode(Opcodes.NEW, POSE));
        invoker.instructions.add(new InsnNode(Opcodes.DUP));
        invoker.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        invoker.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        invoker.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        invoker.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        invoker.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/util/Locale",
                "ROOT", "Ljava/util/Locale;"));
        invoker.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String",
                "toLowerCase", "(Ljava/util/Locale;)Ljava/lang/String;", false));
        invoker.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, POSE, "<init>",
                "(Ljava/lang/String;IILjava/lang/String;)V", false));
        invoker.instructions.add(new InsnNode(Opcodes.ARETURN));
        invoker.maxStack = 6;
        invoker.maxLocals = 2;
        return true;
    }

    private static void makeConcreteUnique(MethodNode method, String annotationToRemove) {
        method.access &= ~(Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE);
        if (isStatic(method)) {
            // Mixin rejects static helper methods unless they are private. These bridges are
            // merged into the target class, where they can reach the private member they adapt.
            method.access &= ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED);
            method.access |= Opcodes.ACC_PRIVATE;
        }
        removeAnnotation(method, annotationToRemove);
        addInvisibleAnnotation(method, UNIQUE_DESC);
        method.instructions.clear();
        method.tryCatchBlocks.clear();
        if (method.localVariables != null) method.localVariables.clear();
    }

    private static MethodNode findAnnotatedMethod(ClassNode classNode, String name, String desc,
            String annotationDesc) {
        for (MethodNode method : classNode.methods) {
            if ((name == null || name.equals(method.name)) && desc.equals(method.desc)
                    && hasAnnotation(method, annotationDesc)) {
                return method;
            }
        }
        return null;
    }

    private static FieldNode findAnnotatedField(ClassNode classNode, String name, String desc,
            String annotationDesc) {
        for (FieldNode field : classNode.fields) {
            if (name.equals(field.name) && desc.equals(field.desc)
                    && findAnnotation(field, annotationDesc) != null) {
                return field;
            }
        }
        return null;
    }

    private static MethodNode findMethod(ClassNode classNode, String name, String desc) {
        for (MethodNode method : classNode.methods) {
            if (name.equals(method.name) && desc.equals(method.desc)) return method;
        }
        return null;
    }

    private static boolean annotationValue(MethodNode method, String desc, String expected) {
        AnnotationNode annotation = findAnnotation(method, desc);
        if (annotation == null || annotation.values == null) return expected.isEmpty();
        for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
            if ("value".equals(annotation.values.get(i))) {
                return expected.equals(annotation.values.get(i + 1));
            }
        }
        return expected.isEmpty();
    }

    private static List<String> mixinTargets(ClassNode classNode) {
        List<String> result = new ArrayList<>();
        for (List<AnnotationNode> annotations : annotationLists(classNode.visibleAnnotations,
                classNode.invisibleAnnotations)) {
            for (AnnotationNode annotation : annotations) {
                if (!MIXIN_DESC.equals(annotation.desc) || annotation.values == null) continue;
                for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                    Object value = annotation.values.get(i + 1);
                    if ("value".equals(annotation.values.get(i)) && value instanceof List<?> values) {
                        for (Object target : values) {
                            if (target instanceof Type type) result.add(type.getInternalName());
                        }
                    } else if ("targets".equals(annotation.values.get(i)) && value instanceof List<?> values) {
                        for (Object target : values) result.add(target.toString().replace('.', '/'));
                    }
                }
            }
        }
        return result;
    }

    private static boolean hasAnnotation(MethodNode method, String desc) {
        return findAnnotation(method, desc) != null;
    }

    private static AnnotationNode findAnnotation(MethodNode method, String desc) {
        for (List<AnnotationNode> annotations : annotationLists(method.visibleAnnotations,
                method.invisibleAnnotations)) {
            for (AnnotationNode annotation : annotations) {
                if (desc.equals(annotation.desc)) return annotation;
            }
        }
        return null;
    }

    private static AnnotationNode findAnnotation(FieldNode field, String desc) {
        for (List<AnnotationNode> annotations : annotationLists(field.visibleAnnotations,
                field.invisibleAnnotations)) {
            for (AnnotationNode annotation : annotations) {
                if (desc.equals(annotation.desc)) return annotation;
            }
        }
        return null;
    }

    private static void removeAnnotation(MethodNode method, String desc) {
        if (method.visibleAnnotations != null) method.visibleAnnotations.removeIf(a -> desc.equals(a.desc));
        if (method.invisibleAnnotations != null) method.invisibleAnnotations.removeIf(a -> desc.equals(a.desc));
    }

    private static void addInvisibleAnnotation(MethodNode method, String desc) {
        if (method.invisibleAnnotations == null) method.invisibleAnnotations = new ArrayList<>();
        if (!hasAnnotation(method, desc)) method.invisibleAnnotations.add(new AnnotationNode(desc));
    }

    @SafeVarargs
    private static List<List<AnnotationNode>> annotationLists(List<AnnotationNode>... lists) {
        List<List<AnnotationNode>> result = new ArrayList<>(lists.length);
        for (List<AnnotationNode> list : lists) result.add(list != null ? list : List.of());
        return result;
    }
}
