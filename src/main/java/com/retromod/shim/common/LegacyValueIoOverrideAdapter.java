/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.FuzzyMethodResolver;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.RetromodLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;

/** Keeps a mod's block entity and entity save code working on hosts that save through ValueOutput and ValueInput. */
public final class LegacyValueIoOverrideAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    static final String COMPOUND = "net/minecraft/nbt/CompoundTag";
    static final String PROVIDER = "net/minecraft/core/HolderLookup$Provider";
    static final String OUTPUT = "net/minecraft/world/level/storage/ValueOutput";
    static final String INPUT = "net/minecraft/world/level/storage/ValueInput";
    static final String TAG_OUTPUT = "net/minecraft/world/level/storage/TagValueOutput";
    static final String TAG_INPUT = "net/minecraft/world/level/storage/TagValueInput";
    static final String REPORTER = "net/minecraft/util/ProblemReporter";
    static final String BLOCK_ENTITY = "net/minecraft/world/level/block/entity/BlockEntity";
    static final String ENTITY = "net/minecraft/world/entity/Entity";
    static final String BLOCK_ENTITY_TYPE = "net/minecraft/world/level/block/entity/BlockEntityType";
    static final String FOOD_DATA = "net/minecraft/world/food/FoodData";
    static final String LEVEL = "net/minecraft/world/level/Level";
    static final String REGISTRY_ACCESS = "net/minecraft/core/RegistryAccess";
    static final String FROZEN = REGISTRY_ACCESS + "$Frozen";
    static final String REGISTRY = "net/minecraft/core/Registry";
    static final String BUILT_INS = "net/minecraft/core/registries/BuiltInRegistries";
    static final String MAP_CODEC = "com/mojang/serialization/MapCodec";
    static final String CODEC = "com/mojang/serialization/Codec";
    static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";

    private static final String L_COMPOUND = "L" + COMPOUND + ";";
    private static final String L_PROVIDER = "L" + PROVIDER + ";";
    private static final String L_OUTPUT = "L" + OUTPUT + ";";
    private static final String L_INPUT = "L" + INPUT + ";";
    private static final String L_TAG_OUTPUT = "L" + TAG_OUTPUT + ";";
    private static final String L_REPORTER = "L" + REPORTER + ";";
    private static final String L_BLOCK_ENTITY = "L" + BLOCK_ENTITY + ";";
    private static final String L_ENTITY = "L" + ENTITY + ";";
    private static final String L_BLOCK_ENTITY_TYPE = "L" + BLOCK_ENTITY_TYPE + ";";

    static final String MOJANG_HELPER = "com/retromod/generated/LegacyValueIoOverrides";
    static final String INTERMEDIARY_HELPER = "com/retromod/generated/LegacyValueIoOverridesIntermediary";

    /** One pre-1.21.6 override shape and the host method that replaced it. */
    record Shape(String base, String oldName, String oldDesc, String newName, String newDesc,
                 boolean save, boolean entity) {}

    // 1.20.5 to 1.21.5 shapes. Entities never passed registries, block entities did.
    static final Shape BLOCK_ENTITY_SAVE = new Shape(BLOCK_ENTITY, "saveAdditional", "(" + L_COMPOUND + L_PROVIDER + ")V",
            "saveAdditional", "(" + L_OUTPUT + ")V", true, false);
    static final Shape BLOCK_ENTITY_LOAD = new Shape(BLOCK_ENTITY, "loadAdditional", "(" + L_COMPOUND + L_PROVIDER + ")V",
            "loadAdditional", "(" + L_INPUT + ")V", false, false);
    static final Shape ENTITY_SAVE = new Shape(ENTITY, "addAdditionalSaveData", "(" + L_COMPOUND + ")V",
            "addAdditionalSaveData", "(" + L_OUTPUT + ")V", true, true);
    static final Shape ENTITY_LOAD = new Shape(ENTITY, "readAdditionalSaveData", "(" + L_COMPOUND + ")V",
            "readAdditionalSaveData", "(" + L_INPUT + ")V", false, true);

    /** Where an entry point's registries come from: its own argument, the entity, the built-in ones, or nowhere. */
    enum Registries { ARGUMENT, ENTITY, BUILT_IN, NONE }

    /** One pre-1.21.6 save or load entry point a mod calls, and the host method that replaced it. */
    record Call(String base, String name, String oldDesc, String newDesc, boolean isStatic, Registries registries) {
        /** The helper takes the receiver first, unless the method is static, then the old arguments. */
        String helperDesc() {
            return isStatic ? oldDesc : "(L" + base + ";" + oldDesc.substring(1);
        }
    }

    static final List<Call> CALLS = List.of(
            new Call(BLOCK_ENTITY, "loadWithComponents", "(" + L_COMPOUND + L_PROVIDER + ")V", "(" + L_INPUT + ")V",
                    false, Registries.ARGUMENT),
            new Call(BLOCK_ENTITY, "loadCustomOnly", "(" + L_COMPOUND + L_PROVIDER + ")V", "(" + L_INPUT + ")V",
                    false, Registries.ARGUMENT),
            new Call(BLOCK_ENTITY, "saveWithId", "(" + L_PROVIDER + ")" + L_COMPOUND, "(" + L_OUTPUT + ")V",
                    false, Registries.ARGUMENT),
            new Call(BLOCK_ENTITY, "addEntityType", "(" + L_COMPOUND + L_BLOCK_ENTITY_TYPE + ")V",
                    "(" + L_OUTPUT + L_BLOCK_ENTITY_TYPE + ")V", true, Registries.NONE),
            new Call(ENTITY, "saveWithoutId", "(" + L_COMPOUND + ")" + L_COMPOUND, "(" + L_OUTPUT + ")V",
                    false, Registries.ENTITY),
            new Call(ENTITY, "save", "(" + L_COMPOUND + ")Z", "(" + L_OUTPUT + ")Z", false, Registries.ENTITY),
            new Call(ENTITY, "saveAsPassenger", "(" + L_COMPOUND + ")Z", "(" + L_OUTPUT + ")Z", false, Registries.ENTITY),
            new Call(ENTITY, "load", "(" + L_COMPOUND + ")V", "(" + L_INPUT + ")V", false, Registries.ENTITY),
            // Food data holds only numbers, so it saves without registries and loads with the built-in ones.
            new Call(FOOD_DATA, "addAdditionalSaveData", "(" + L_COMPOUND + ")V", "(" + L_OUTPUT + ")V",
                    false, Registries.NONE),
            new Call(FOOD_DATA, "readAdditionalSaveData", "(" + L_COMPOUND + ")V", "(" + L_INPUT + ")V",
                    false, Registries.BUILT_IN));

    /** One namespace: the helper's name, how Mojang names read in it, and the shapes as its mods spell them. */
    record Names(String helper, SimpleRemapper remapper, List<Shape> shapes, List<Call> calls) {
        String type(String mojang) {
            return remapper.mapType(mojang);
        }

        String desc(String mojangDesc) {
            return remapper.mapMethodDesc(mojangDesc);
        }
    }

    static final Names MOJANG = names(MOJANG_HELPER, Map.of());
    static final Names INTERMEDIARY = names(INTERMEDIARY_HELPER, intermediaryNames());
    private static final List<Names> NAMESPACES = List.of(MOJANG, INTERMEDIARY);

    /** Whether a host class declares a method: true, false, or null when the class cannot be read. */
    interface HostMethods {
        Boolean declares(String owner, String name, String desc);
    }

    /** What one pass knows about the mod's jar and the host. */
    record Context(List<Names> namespaces, BiPredicate<String, String> inheritsFrom,
                   Function<String, byte[]> jarClass, HostMethods host, boolean entityShapes) {}

    private LegacyValueIoOverrideAdapter() {}

    private static Names names(String helper, Map<String, String> mapping) {
        SimpleRemapper remapper = new SimpleRemapper(mapping);
        List<Shape> shapes = new ArrayList<>();
        for (Shape shape : List.of(BLOCK_ENTITY_SAVE, BLOCK_ENTITY_LOAD, ENTITY_SAVE, ENTITY_LOAD)) {
            shapes.add(new Shape(remapper.mapType(shape.base()),
                    remapper.mapMethodName(shape.base(), shape.oldName(), shape.oldDesc()),
                    remapper.mapMethodDesc(shape.oldDesc()),
                    remapper.mapMethodName(shape.base(), shape.newName(), shape.newDesc()),
                    remapper.mapMethodDesc(shape.newDesc()), shape.save(), shape.entity()));
        }
        List<Call> calls = new ArrayList<>();
        for (Call call : CALLS) {
            calls.add(new Call(remapper.mapType(call.base()), remapper.mapMethodName(call.base(), call.name(),
                    call.oldDesc()), remapper.mapMethodDesc(call.oldDesc()), remapper.mapMethodDesc(call.newDesc()),
                    call.isStatic(), call.registries()));
        }
        return new Names(helper, remapper, List.copyOf(shapes), List.copyOf(calls));
    }

    /** The intermediary names a Fabric host from 1.21.6 to 1.21.11 runs. */
    private static Map<String, String> intermediaryNames() {
        // Checked against the 1.21.1, 1.21.6 and 1.21.11 intermediary tables.
        Map<String, String> names = new HashMap<>();
        names.put(MOJANG_HELPER, INTERMEDIARY_HELPER);
        names.put(COMPOUND, "net/minecraft/class_2487");
        names.put(PROVIDER, "net/minecraft/class_7225$class_7874");
        names.put(OUTPUT, "net/minecraft/class_11372");
        names.put(INPUT, "net/minecraft/class_11368");
        names.put(TAG_OUTPUT, "net/minecraft/class_11362");
        names.put(TAG_INPUT, "net/minecraft/class_11352");
        names.put(REPORTER, "net/minecraft/class_8942");
        names.put(BLOCK_ENTITY, "net/minecraft/class_2586");
        names.put(ENTITY, "net/minecraft/class_1297");
        names.put(LEVEL, "net/minecraft/class_1937");
        names.put(REGISTRY_ACCESS, "net/minecraft/class_5455");
        names.put(FROZEN, "net/minecraft/class_5455$class_6890");
        names.put(REGISTRY, "net/minecraft/class_2378");
        names.put(BUILT_INS, "net/minecraft/class_7923");
        names.put(BLOCK_ENTITY_TYPE, "net/minecraft/class_2591");
        names.put(FOOD_DATA, "net/minecraft/class_1702");
        // Each save method and entry point kept its id when its parameters changed.
        Map<String, String> ids = new HashMap<>();
        ids.put(BLOCK_ENTITY + ".saveAdditional", "method_11007");
        ids.put(BLOCK_ENTITY + ".loadAdditional", "method_11014");
        ids.put(BLOCK_ENTITY + ".loadWithComponents", "method_58690");
        ids.put(BLOCK_ENTITY + ".loadCustomOnly", "method_58691");
        ids.put(BLOCK_ENTITY + ".saveWithId", "method_38243");
        ids.put(BLOCK_ENTITY + ".addEntityType", "method_38238");
        ids.put(ENTITY + ".addAdditionalSaveData", "method_5652");
        ids.put(ENTITY + ".readAdditionalSaveData", "method_5749");
        ids.put(ENTITY + ".saveWithoutId", "method_5647");
        ids.put(ENTITY + ".save", "method_5662");
        ids.put(ENTITY + ".saveAsPassenger", "method_5786");
        ids.put(ENTITY + ".load", "method_5651");
        ids.put(FOOD_DATA + ".addAdditionalSaveData", "method_7582");
        ids.put(FOOD_DATA + ".readAdditionalSaveData", "method_7584");
        for (Shape shape : List.of(BLOCK_ENTITY_SAVE, ENTITY_SAVE, BLOCK_ENTITY_LOAD, ENTITY_LOAD)) {
            String id = ids.get(shape.base() + "." + shape.oldName());
            names.put(shape.base() + "." + shape.oldName() + shape.oldDesc(), id);
            names.put(shape.base() + "." + shape.newName() + shape.newDesc(), id);
        }
        for (Call call : CALLS) {
            String id = ids.get(call.base() + "." + call.name());
            names.put(call.base() + "." + call.name() + call.oldDesc(), id);
            names.put(call.base() + "." + call.name() + call.newDesc(), id);
        }
        names.put(TAG_OUTPUT + ".buildResult()" + L_COMPOUND, "method_71475");
        names.put(TAG_OUTPUT + ".createWithContext(" + L_REPORTER + L_PROVIDER + ")" + L_TAG_OUTPUT, "method_71459");
        names.put(TAG_OUTPUT + ".createWithoutContext(" + L_REPORTER + ")" + L_TAG_OUTPUT, "method_71458");
        names.put(TAG_INPUT + ".create(" + L_REPORTER + L_PROVIDER + L_COMPOUND + ")" + L_INPUT, "method_71417");
        names.put(OUTPUT + ".store(L" + MAP_CODEC + ";Ljava/lang/Object;)V", "method_71460");
        names.put(INPUT + ".read(L" + MAP_CODEC + ";)Ljava/util/Optional;", "method_71418");
        names.put(INPUT + ".lookup()" + L_PROVIDER, "method_71414");
        names.put(COMPOUND + ".merge(" + L_COMPOUND + ")" + L_COMPOUND, "method_10543");
        names.put(BLOCK_ENTITY + ".getLevel()L" + LEVEL + ";", "method_10997");
        names.put(LEVEL + ".registryAccess()L" + REGISTRY_ACCESS + ";", "method_30349");
        names.put(ENTITY + ".registryAccess()L" + REGISTRY_ACCESS + ";", "method_56673");
        names.put(REGISTRY_ACCESS + ".fromRegistryOfRegistries(L" + REGISTRY + ";)L" + FROZEN + ";", "method_40302");
        names.put(REPORTER + ".DISCARDING", "field_60348");
        names.put(COMPOUND + ".CODEC", "field_25128");
        names.put(BUILT_INS + ".REGISTRY", "field_41167");
        return names;
    }

    /** Registers the Mojang-named helper when the host saves through ValueOutput and ValueInput. */
    public static void register(RetromodTransformer transformer) {
        register(transformer, MOJANG);
    }

    /** The same for a Fabric host from 1.21.6 to 1.21.11, which still runs intermediary names. */
    public static void registerIntermediary(RetromodTransformer transformer) {
        register(transformer, INTERMEDIARY);
    }

    private static void register(RetromodTransformer transformer, Names names) {
        if (!hostSavesThroughValueIo(names)) {
            LOGGER.debug("ValueIO override bridge not needed: the host keeps the CompoundTag save methods");
            return;
        }
        transformer.registerSyntheticClass(names.helper(), generateHelper(names));
        // Calls to the old entry points go to the helper, which keeps each Mojang name. Mods also
        // call them on their own subclasses, and a super call reaches the host through the hierarchy.
        for (int i = 0; i < CALLS.size(); i++) {
            Call call = names.calls().get(i);
            transformer.registerInheritedMethodRedirect(call.base(), call.name(), call.oldDesc(),
                    names.helper(), CALLS.get(i).name(), call.helperDesc());
        }
        // NeoForge's chain and the Forge chain both register this, so the notice prints once.
        if (transformer.firstRegistrationNotice(LegacyValueIoOverrideAdapter.class.getName())) {
            LOGGER.debug(RetromodLog.DETAIL, "Bridged pre-1.21.6 block entity and entity save code onto ValueOutput and ValueInput");
        }
    }

    /** Whether a shim registered a helper for this pass, so the adapter has work to do. */
    public static boolean isRegistered(Predicate<String> registeredSynthetic) {
        return registeredSynthetic.test(MOJANG_HELPER) || registeredSynthetic.test(INTERMEDIARY_HELPER);
    }

    /** The host's own {@code BlockEntity} decides; offline, the target version does. */
    static boolean hostSavesThroughValueIo(Names names) {
        ClassNode blockEntity = ClassResourceInspector.read(names.type(BLOCK_ENTITY));
        if (blockEntity == null) return offlineNeedsBridge(RetromodVersion.TARGET_MC_VERSION);
        return hostReplacedShape(blockEntity, names.shapes().get(0));
    }

    /** Whether a host class has the shape's new method and not its old one. */
    static boolean hostReplacedShape(ClassNode host, Shape shape) {
        return declared(host, shape.newName(), shape.newDesc()) != null
                && declared(host, shape.oldName(), shape.oldDesc()) == null;
    }

    /** 1.21.6 moved every save method here to ValueOutput and ValueInput. */
    static boolean offlineNeedsBridge(String targetVersion) {
        return targetVersion != null && RetromodVersion.compareMcVersions(targetVersion, "1.21.6") >= 0;
    }

    /** Entity overrides are bridged for mods built for 1.20.5 or newer, or of unknown version. */
    static boolean bridgesEntities(String modSourceVersion) {
        // Older bodies call item and container methods that only the 1.20.5 bridge rebuilt, and it
        // stops at 1.21.5 hosts, so running them would crash a save that today only loses data.
        return modSourceVersion == null || RetromodVersion.compareMcVersions(modSourceVersion, "1.20.5") >= 0;
    }

    /** Post-remap pass over one class, with what the transformer knows about its jar and the host. */
    public static byte[] apply(byte[] classBytes, Predicate<String> registeredSynthetic, String modSourceVersion) {
        if (classBytes == null) return null;
        List<Names> namespaces = new ArrayList<>();
        for (Names names : NAMESPACES) {
            if (registeredSynthetic.test(names.helper()) && mentionsOldShape(classBytes, names)) namespaces.add(names);
        }
        if (namespaces.isEmpty()) return classBytes;
        return apply(classBytes, new Context(namespaces, RetromodTransformer::currentJarClassInheritsFrom,
                RetromodTransformer::readJarOrLibraryClassRemapped, LegacyValueIoOverrideAdapter::hostDeclares,
                bridgesEntities(modSourceVersion)));
    }

    private static boolean mentionsOldShape(byte[] classBytes, Names names) {
        String text = new String(classBytes, StandardCharsets.ISO_8859_1);
        for (Shape shape : names.shapes()) {
            if (text.contains(shape.oldName()) && text.contains(shape.oldDesc())) return true;
        }
        return false;
    }

    /** Repairs the class's super calls to each old shape, then gives each old override its new twin. */
    static byte[] apply(byte[] classBytes, Context context) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        if ((node.access & Opcodes.ACC_INTERFACE) != 0 || node.name.startsWith("net/minecraft/")) return classBytes;
        boolean changed = false;
        for (Names names : context.namespaces()) {
            for (Shape shape : names.shapes()) {
                if (shape.entity() && !context.entityShapes()) continue;
                // Super calls first, so the old body the new override runs already reaches the host.
                for (MethodNode method : node.methods) {
                    changed |= repairSuperCalls(method, shape, names, context);
                }
                changed |= addOverride(node, shape, names, context);
            }
        }
        if (!changed) return classBytes;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** Points each {@code super} call that reaches the host's removed method at its replacement. */
    private static boolean repairSuperCalls(MethodNode method, Shape shape, Names names, Context context) {
        boolean changed = false;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESPECIAL
                    || !call.name.equals(shape.oldName()) || !call.desc.equals(shape.oldDesc())
                    || !context.inheritsFrom().test(call.owner, shape.base())
                    || modClassDeclaresOld(call.owner, shape, context.jarClass())) {
                continue;
            }
            if (shape.save()) {
                repairSuperSave(method, call, shape, names);
            } else {
                repairSuperLoad(method, call, shape, names);
            }
            call.name = shape.newName();
            call.desc = shape.newDesc();
            changed = true;
        }
        return changed;
    }

    /** {@code super.save(tag[, registries])}: the parent saves into a fresh output that is merged into the tag. */
    private static void repairSuperSave(MethodNode method, MethodInsnNode call, Shape shape, Names names) {
        // The merge happens where the call was, so the mod's later writes still overwrite the parent's.
        int outputSlot = method.maxLocals;
        int tagSlot = method.maxLocals + 1;
        method.maxLocals += 2;
        InsnList before = new InsnList();
        if (shape.entity()) {
            // [this, tag]: an entity's registries come from its level.
            before.add(new InsnNode(Opcodes.SWAP));
            before.add(new InsnNode(Opcodes.DUP_X1));
            before.add(helperCall(names, "superOutput", "(" + L_ENTITY + ")" + L_OUTPUT));
        } else {
            before.add(helperCall(names, "superOutput", "(" + L_PROVIDER + ")" + L_OUTPUT));
        }
        // [this, tag, output]: both are kept for the merge, and [this, output] is left for the call.
        before.add(new InsnNode(Opcodes.DUP));
        before.add(new VarInsnNode(Opcodes.ASTORE, outputSlot));
        before.add(new InsnNode(Opcodes.SWAP));
        before.add(new VarInsnNode(Opcodes.ASTORE, tagSlot));
        method.instructions.insertBefore(call, before);
        InsnList after = new InsnList();
        after.add(new VarInsnNode(Opcodes.ALOAD, tagSlot));
        after.add(new VarInsnNode(Opcodes.ALOAD, outputSlot));
        after.add(helperCall(names, "mergeSuperOutput", "(" + L_COMPOUND + L_OUTPUT + ")V"));
        method.instructions.insert(call, after);
    }

    /** {@code super.load(tag[, registries])}: the parent reads the same tag through an input. */
    private static void repairSuperLoad(MethodNode method, MethodInsnNode call, Shape shape, Names names) {
        InsnList before = new InsnList();
        if (shape.entity()) {
            // [this, tag] becomes [this, this, tag] for superInput(entity, tag).
            before.add(new InsnNode(Opcodes.SWAP));
            before.add(new InsnNode(Opcodes.DUP_X1));
            before.add(new InsnNode(Opcodes.SWAP));
            before.add(helperCall(names, "superInput", "(" + L_ENTITY + L_COMPOUND + ")" + L_INPUT));
        } else {
            before.add(new InsnNode(Opcodes.SWAP));
            before.add(helperCall(names, "superInput", "(" + L_PROVIDER + L_COMPOUND + ")" + L_INPUT));
        }
        method.instructions.insertBefore(call, before);
    }

    /** Whether {@code owner}, or a mod class above it, still declares the old method itself. */
    private static boolean modClassDeclaresOld(String owner, Shape shape, Function<String, byte[]> jarClass) {
        String current = owner;
        while (current != null && !current.startsWith("net/minecraft/") && !current.startsWith("java/")) {
            byte[] bytes = jarClass.apply(current);
            // A parent no jar, library or class loader shows is unknown, so it is taken to declare the method.
            if (bytes == null) return true;
            ClassNode parent = new ClassNode();
            new ClassReader(bytes).accept(parent, ClassReader.SKIP_CODE);
            if (declared(parent, shape.oldName(), shape.oldDesc()) != null) return true;
            current = parent.superName;
        }
        return false;
    }

    /** Adds the new method beside an old override, so the host's save or load runs the old body. */
    private static boolean addOverride(ClassNode node, Shape shape, Names names, Context context) {
        MethodNode old = declared(node, shape.oldName(), shape.oldDesc());
        if (old == null || (old.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) != 0
                || declared(node, shape.newName(), shape.newDesc()) != null
                || !context.inheritsFrom().test(node.name, shape.base())) {
            return false;
        }
        if (isMixin(node) && !mixinMayGainOverride(node, old, shape, context.host())) return false;
        node.methods.add(override(node, old, shape, names));
        LOGGER.debug("Routed {}.{} to its pre-1.21.6 override", node.name, shape.newName());
        return true;
    }

    /** Whether a mixin, whose methods merge into its targets, may carry the new method. */
    private static boolean mixinMayGainOverride(ClassNode node, MethodNode old, Shape shape, HostMethods host) {
        // Only beside a plain old method, and only for targets that do not declare the new one,
        // which the merged method would replace.
        if (hasAnnotations(old)) return false;
        List<String> targets = mixinTargets(node);
        if (targets.isEmpty()) return false;
        for (String target : targets) {
            if (!Boolean.FALSE.equals(host.declares(target, shape.newName(), shape.newDesc()))) return false;
        }
        return true;
    }

    private static boolean isMixin(ClassNode node) {
        return annotation(node.invisibleAnnotations, MIXIN) != null || annotation(node.visibleAnnotations, MIXIN) != null;
    }

    /** The internal names in a {@code @Mixin}'s {@code value} and {@code targets}. */
    private static List<String> mixinTargets(ClassNode node) {
        AnnotationNode mixin = annotation(node.invisibleAnnotations, MIXIN);
        if (mixin == null) mixin = annotation(node.visibleAnnotations, MIXIN);
        List<String> targets = new ArrayList<>();
        if (mixin == null || mixin.values == null) return targets;
        for (int i = 0; i + 1 < mixin.values.size(); i += 2) {
            if (!(mixin.values.get(i + 1) instanceof List<?> list)) continue;
            for (Object value : list) {
                if (value instanceof Type type) targets.add(type.getInternalName());
                else if (value instanceof String name) targets.add(name.replace('.', '/'));
            }
        }
        return targets;
    }

    private static AnnotationNode annotation(List<AnnotationNode> annotations, String desc) {
        if (annotations == null) return null;
        for (AnnotationNode annotation : annotations) {
            if (annotation.desc.equals(desc)) return annotation;
        }
        return null;
    }

    private static boolean hasAnnotations(MethodNode method) {
        return (method.invisibleAnnotations != null && !method.invisibleAnnotations.isEmpty())
                || (method.visibleAnnotations != null && !method.visibleAnnotations.isEmpty());
    }

    /** {@code newName(output or input)}, which hands the old override a compound and copies back what it saved. */
    private static MethodNode override(ClassNode node, MethodNode old, Shape shape, Names names) {
        int access = (old.access & Opcodes.ACC_PUBLIC) != 0 ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PROTECTED;
        MethodNode method = new MethodNode(access, shape.newName(), shape.newDesc(), null, null);
        InsnList code = method.instructions;
        if (shape.save()) {
            // The old body writes into the output's own compound, or into one stored back after.
            code.add(new VarInsnNode(Opcodes.ALOAD, 1));
            code.add(helperCall(names, "saveTarget", "(" + L_OUTPUT + ")" + L_COMPOUND));
            code.add(new VarInsnNode(Opcodes.ASTORE, 2));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new VarInsnNode(Opcodes.ALOAD, 2));
            if (!shape.entity()) {
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(helperCall(names, "registries", "(" + L_BLOCK_ENTITY + ")" + L_PROVIDER));
            }
            code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, shape.oldName(), shape.oldDesc(), false));
            code.add(new VarInsnNode(Opcodes.ALOAD, 1));
            code.add(new VarInsnNode(Opcodes.ALOAD, 2));
            code.add(helperCall(names, "finishSave", "(" + L_OUTPUT + L_COMPOUND + ")V"));
        } else {
            // The old body reads the whole compound, with the registries the input was made with.
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new VarInsnNode(Opcodes.ALOAD, 1));
            code.add(helperCall(names, "loadSource", "(" + L_INPUT + ")" + L_COMPOUND));
            if (!shape.entity()) {
                code.add(new VarInsnNode(Opcodes.ALOAD, 1));
                code.add(helperCall(names, "registries", "(" + L_INPUT + ")" + L_PROVIDER));
            }
            code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, shape.oldName(), shape.oldDesc(), false));
        }
        code.add(new InsnNode(Opcodes.RETURN));
        return method;
    }

    private static MethodInsnNode helperCall(Names names, String name, String mojangDesc) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, names.helper(), name, names.desc(mojangDesc), false);
    }

    private static MethodNode declared(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return method;
        }
        return null;
    }

    /** Reads the running host, or offline the indexed target jar. */
    static Boolean hostDeclares(String owner, String name, String desc) {
        ClassNode host = ClassResourceInspector.read(owner);
        if (host != null) return declared(host, name, desc) != null;
        FuzzyMethodResolver index = RetromodTransformer.getInstance().getFuzzyResolver();
        if (index == null || !index.isIndexed() || !index.hasClass(owner)) return null;
        return index.getDeclaredMethods(owner).stream()
                .anyMatch(method -> method.name().equals(name) && method.descriptor().equals(desc));
    }

    /** The helper in the given namespace: written in Mojang names, then renamed. */
    static byte[] generateHelper(Names names) {
        byte[] mojang = generateMojangHelper();
        if (names == MOJANG) return mojang;
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(mojang).accept(new ClassRemapper(writer, names.remapper()), 0);
        return writer.toByteArray();
    }

    private static byte[] generateMojangHelper() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                // Minecraft is not on Retromod's classpath when this class is generated.
                return "java/lang/Object";
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                MOJANG_HELPER, null, "java/lang/Object", null);
        emitSaveTarget(cw);
        emitFinishSave(cw);
        emitLoadSource(cw);
        emitRegistries(cw);
        emitSuperCalls(cw);
        emitEntryPoints(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code saveTarget(output)}: a tag output's own compound, which the host saves, else a new one. */
    private static void emitSaveTarget(ClassWriter cw) {
        MethodVisitor mv = begin(cw, "saveTarget", "(" + L_OUTPUT + ")" + L_COMPOUND);
        Label fresh = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, TAG_OUTPUT);
        mv.visitJumpInsn(Opcodes.IFEQ, fresh);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.CHECKCAST, TAG_OUTPUT);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TAG_OUTPUT, "buildResult", "()" + L_COMPOUND, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(fresh);
        newCompound(mv);
        mv.visitInsn(Opcodes.ARETURN);
        end(mv);
    }

    /** {@code finishSave(output, tag)}: any other output gets the saved keys stored into it. */
    private static void emitFinishSave(ClassWriter cw) {
        MethodVisitor mv = begin(cw, "finishSave", "(" + L_OUTPUT + L_COMPOUND + ")V");
        Label done = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, TAG_OUTPUT);
        mv.visitJumpInsn(Opcodes.IFNE, done);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        wholeCompoundCodec(mv);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, OUTPUT, "store", "(L" + MAP_CODEC + ";Ljava/lang/Object;)V", true);
        mv.visitLabel(done);
        mv.visitInsn(Opcodes.RETURN);
        end(mv);
    }

    /** {@code loadSource(input)}: every key of the input as one compound, or an empty one. */
    private static void emitLoadSource(ClassWriter cw) {
        MethodVisitor mv = begin(cw, "loadSource", "(" + L_INPUT + ")" + L_COMPOUND);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        wholeCompoundCodec(mv);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, INPUT, "read", "(L" + MAP_CODEC + ";)Ljava/util/Optional;", true);
        newCompound(mv);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Optional", "orElse", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, COMPOUND);
        mv.visitInsn(Opcodes.ARETURN);
        end(mv);
    }

    /** {@code registries(input)} and {@code registries(blockEntity)}, the provider the old methods took. */
    private static void emitRegistries(ClassWriter cw) {
        MethodVisitor input = begin(cw, "registries", "(" + L_INPUT + ")" + L_PROVIDER);
        input.visitVarInsn(Opcodes.ALOAD, 0);
        input.visitMethodInsn(Opcodes.INVOKEINTERFACE, INPUT, "lookup", "()" + L_PROVIDER, true);
        input.visitInsn(Opcodes.ARETURN);
        end(input);

        // A save got its level's registries. A block entity with no level falls back to the
        // built-in ones, which cannot encode data-driven content such as enchantments.
        MethodVisitor mv = begin(cw, "registries", "(" + L_BLOCK_ENTITY + ")" + L_PROVIDER);
        Label none = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BLOCK_ENTITY, "getLevel", "()L" + LEVEL + ";", false);
        mv.visitInsn(Opcodes.DUP);
        mv.visitJumpInsn(Opcodes.IFNULL, none);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LEVEL, "registryAccess", "()L" + REGISTRY_ACCESS + ";", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(none);
        mv.visitInsn(Opcodes.POP);
        pushBuiltInRegistries(mv);
        mv.visitInsn(Opcodes.ARETURN);
        end(mv);
    }

    /** The outputs and inputs a repaired {@code super} call hands the parent, and the merge after a save. */
    private static void emitSuperCalls(ClassWriter cw) {
        MethodVisitor output = begin(cw, "superOutput", "(" + L_PROVIDER + ")" + L_OUTPUT);
        output.visitFieldInsn(Opcodes.GETSTATIC, REPORTER, "DISCARDING", L_REPORTER);
        output.visitVarInsn(Opcodes.ALOAD, 0);
        output.visitMethodInsn(Opcodes.INVOKESTATIC, TAG_OUTPUT, "createWithContext",
                "(" + L_REPORTER + L_PROVIDER + ")" + L_TAG_OUTPUT, false);
        output.visitInsn(Opcodes.ARETURN);
        end(output);

        MethodVisitor entityOutput = begin(cw, "superOutput", "(" + L_ENTITY + ")" + L_OUTPUT);
        entityOutput.visitVarInsn(Opcodes.ALOAD, 0);
        entityOutput.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ENTITY, "registryAccess", "()L" + REGISTRY_ACCESS + ";", false);
        entityOutput.visitMethodInsn(Opcodes.INVOKESTATIC, MOJANG_HELPER, "superOutput", "(" + L_PROVIDER + ")" + L_OUTPUT, false);
        entityOutput.visitInsn(Opcodes.ARETURN);
        end(entityOutput);

        MethodVisitor merge = begin(cw, "mergeSuperOutput", "(" + L_COMPOUND + L_OUTPUT + ")V");
        merge.visitVarInsn(Opcodes.ALOAD, 0);
        merge.visitVarInsn(Opcodes.ALOAD, 1);
        merge.visitTypeInsn(Opcodes.CHECKCAST, TAG_OUTPUT);
        merge.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TAG_OUTPUT, "buildResult", "()" + L_COMPOUND, false);
        merge.visitMethodInsn(Opcodes.INVOKEVIRTUAL, COMPOUND, "merge", "(" + L_COMPOUND + ")" + L_COMPOUND, false);
        merge.visitInsn(Opcodes.POP);
        merge.visitInsn(Opcodes.RETURN);
        end(merge);

        MethodVisitor input = begin(cw, "superInput", "(" + L_PROVIDER + L_COMPOUND + ")" + L_INPUT);
        input.visitFieldInsn(Opcodes.GETSTATIC, REPORTER, "DISCARDING", L_REPORTER);
        input.visitVarInsn(Opcodes.ALOAD, 0);
        input.visitVarInsn(Opcodes.ALOAD, 1);
        input.visitMethodInsn(Opcodes.INVOKESTATIC, TAG_INPUT, "create",
                "(" + L_REPORTER + L_PROVIDER + L_COMPOUND + ")" + L_INPUT, false);
        input.visitInsn(Opcodes.ARETURN);
        end(input);

        MethodVisitor entityInput = begin(cw, "superInput", "(" + L_ENTITY + L_COMPOUND + ")" + L_INPUT);
        entityInput.visitVarInsn(Opcodes.ALOAD, 0);
        entityInput.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ENTITY, "registryAccess", "()L" + REGISTRY_ACCESS + ";", false);
        entityInput.visitVarInsn(Opcodes.ALOAD, 1);
        entityInput.visitMethodInsn(Opcodes.INVOKESTATIC, MOJANG_HELPER, "superInput",
                "(" + L_PROVIDER + L_COMPOUND + ")" + L_INPUT, false);
        entityInput.visitInsn(Opcodes.ARETURN);
        end(entityInput);
    }

    /** One static method per old entry point, named after it, which calls the host's new one. */
    private static void emitEntryPoints(ClassWriter cw) {
        for (Call call : CALLS) {
            MethodVisitor mv = begin(cw, call.name(), call.helperDesc());
            Type[] args = Type.getArgumentTypes(call.helperDesc());
            if (call.newDesc().startsWith("(" + L_INPUT)) {
                emitLoadCall(mv, call, args);
            } else {
                emitSaveCall(mv, call, args);
            }
            end(mv);
        }
    }

    /** {@code receiver.newMethod(TagValueInput.create(DISCARDING, registries, tag))}. */
    private static void emitLoadCall(MethodVisitor mv, Call call, Type[] args) {
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETSTATIC, REPORTER, "DISCARDING", L_REPORTER);
        pushRegistries(mv, call, args);
        mv.visitVarInsn(Opcodes.ALOAD, slotOf(args, COMPOUND));
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, TAG_INPUT, "create",
                "(" + L_REPORTER + L_PROVIDER + L_COMPOUND + ")" + L_INPUT, false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, call.base(), call.name(), call.newDesc(), false);
        mv.visitInsn(Opcodes.RETURN);
    }

    /** The host saves into a fresh output, whose keys go into the caller's tag or come back as a new one. */
    private static void emitSaveCall(MethodVisitor mv, Call call, Type[] args) {
        int output = 0;
        for (Type arg : args) output += arg.getSize();
        mv.visitFieldInsn(Opcodes.GETSTATIC, REPORTER, "DISCARDING", L_REPORTER);
        if (call.registries() == Registries.NONE) {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, TAG_OUTPUT, "createWithoutContext",
                    "(" + L_REPORTER + ")" + L_TAG_OUTPUT, false);
        } else {
            pushRegistries(mv, call, args);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, TAG_OUTPUT, "createWithContext",
                    "(" + L_REPORTER + L_PROVIDER + ")" + L_TAG_OUTPUT, false);
        }
        mv.visitVarInsn(Opcodes.ASTORE, output);
        // The new method takes the output where the old one took the tag, and keeps its other arguments.
        if (!call.isStatic()) mv.visitVarInsn(Opcodes.ALOAD, 0);
        for (Type param : Type.getArgumentTypes(call.newDesc())) {
            String type = param.getInternalName();
            mv.visitVarInsn(Opcodes.ALOAD, type.equals(OUTPUT) ? output : slotOf(args, type));
        }
        mv.visitMethodInsn(call.isStatic() ? Opcodes.INVOKESTATIC : Opcodes.INVOKEVIRTUAL, call.base(), call.name(),
                call.newDesc(), false);
        int tag = slotOf(args, COMPOUND);
        if (tag < 0) {
            // saveWithId(registries) returned a new tag.
            mv.visitVarInsn(Opcodes.ALOAD, output);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TAG_OUTPUT, "buildResult", "()" + L_COMPOUND, false);
            mv.visitInsn(Opcodes.ARETURN);
            return;
        }
        mv.visitVarInsn(Opcodes.ALOAD, tag);
        mv.visitVarInsn(Opcodes.ALOAD, output);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TAG_OUTPUT, "buildResult", "()" + L_COMPOUND, false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, COMPOUND, "merge", "(" + L_COMPOUND + ")" + L_COMPOUND, false);
        mv.visitInsn(Opcodes.POP);
        // saveWithoutId returned the tag it filled, and save and saveAsPassenger whether they saved.
        Type result = Type.getReturnType(call.oldDesc());
        if (result.getSort() == Type.BOOLEAN) {
            mv.visitInsn(Opcodes.IRETURN);
        } else if (result.getSort() == Type.OBJECT) {
            mv.visitVarInsn(Opcodes.ALOAD, tag);
            mv.visitInsn(Opcodes.ARETURN);
        } else {
            mv.visitInsn(Opcodes.RETURN);
        }
    }

    /** Pushes the registries an entry point works with. */
    private static void pushRegistries(MethodVisitor mv, Call call, Type[] args) {
        switch (call.registries()) {
            case ARGUMENT -> mv.visitVarInsn(Opcodes.ALOAD, slotOf(args, PROVIDER));
            case ENTITY -> {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ENTITY, "registryAccess", "()L" + REGISTRY_ACCESS + ";", false);
            }
            default -> pushBuiltInRegistries(mv);
        }
    }

    /** The local slot of the first argument of that type, or -1. */
    private static int slotOf(Type[] args, String internalName) {
        int slot = 0;
        for (Type arg : args) {
            if (arg.getSort() == Type.OBJECT && arg.getInternalName().equals(internalName)) return slot;
            slot += arg.getSize();
        }
        return -1;
    }

    /** {@code RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)}. */
    private static void pushBuiltInRegistries(MethodVisitor mv) {
        mv.visitFieldInsn(Opcodes.GETSTATIC, BUILT_INS, "REGISTRY", "L" + REGISTRY + ";");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRY_ACCESS, "fromRegistryOfRegistries",
                "(L" + REGISTRY + ";)L" + FROZEN + ";", true);
    }

    /** Pushes {@code MapCodec.assumeMapUnsafe(CompoundTag.CODEC)}, which reads or writes a whole map as one compound. */
    private static void wholeCompoundCodec(MethodVisitor mv) {
        mv.visitFieldInsn(Opcodes.GETSTATIC, COMPOUND, "CODEC", "L" + CODEC + ";");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, MAP_CODEC, "assumeMapUnsafe",
                "(L" + CODEC + ";)L" + MAP_CODEC + ";", false);
    }

    private static void newCompound(MethodVisitor mv) {
        mv.visitTypeInsn(Opcodes.NEW, COMPOUND);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, COMPOUND, "<init>", "()V", false);
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
