/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Replaces {@code @Accessor} methods for Minecraft fields that a later version removed with calls
 * to runtime bridges that reach the same data another way.
 *
 * <p>Mixin resolves an accessor by field name and type while it applies the mixin. A field that no
 * longer exists fails the whole mixin with {@code InvalidAccessorException}, and on NeoForge that
 * is fatal for the target class. The accessor cannot simply become a default method: Mixin treats
 * an interface with any non-accessor method as a mixin for an interface target and rejects it
 * against a class. So the repair has two halves that must agree:
 * <ul>
 *   <li>{@link #removeMigratedAccessors} deletes the dead accessor from the interface mixin, which
 *       leaves a valid (possibly empty) accessor mixin.</li>
 *   <li>{@link #rewriteCallSites} turns every {@code INVOKEINTERFACE} of that accessor, in any class
 *       of the same jar, into an {@code INVOKESTATIC} of the bridge. The accessor is recognised from
 *       the interface's own bytes, so the two halves need no shared state and can run in any order
 *       across worker threads.</li>
 * </ul>
 *
 * <p>A class mixin may declare an accessor as an abstract method, usually to implement one of the
 * mod's own interfaces on the target. There {@link #bridgeClassMixinAccessors} gives the method a
 * body that calls the bridge, because a class mixin is merged into its target and may carry
 * concrete methods.
 *
 * <p>Only an accessor whose target, field name, and type match a rule exactly is changed, and only
 * on hosts at or past the version that removed the field.
 */
public final class MixinAccessorFieldMigration {

    private static final Logger LOGGER = LoggerFactory.getLogger("retromod-mixin");

    private static final String MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String ACCESSOR_DESC = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
    private static final String MUTABLE_DESC = "Lorg/spongepowered/asm/mixin/Mutable;";
    private static final String RUNTIME = "com/retromod/mixin/runtime/";
    private static final String GETTER_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String SETTER_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)V";

    /**
     * One removed field and the bridge that stands in for its accessors.
     *
     * @param target        internal name of the Minecraft class that lost the field
     * @param field         the removed field's name
     * @param fieldDesc     the removed field's descriptor; a primitive is boxed for the bridge
     * @param removedSince  first Minecraft version without the field
     * @param bridge        internal name of the runtime bridge class
     * @param getter        bridge method {@code (Object owner) -> Object}, or null
     * @param setter        bridge method {@code (Object owner, Object value) -> void}, or null
     * @param bridgeClasses every class the embedder must copy for this bridge
     */
    record FieldMigration(String target, String field, String fieldDesc, String removedSince,
                          String bridge, String getter, String setter, List<String> bridgeClasses) {}

    private static final String CLOUD_BRIDGE = RUNTIME + "AreaEffectCloudEffectsBridge";
    private static final String ENTITY_DATA_BRIDGE = RUNTIME + "SynchedEntityDataBridge";
    private static final String ZOMBIE_VILLAGER_BRIDGE = RUNTIME + "ZombieVillagerBridge";
    private static final String ATTRIBUTE_BUILDER_BRIDGE = RUNTIME + "AttributeSupplierBuilderBridge";
    private static final List<String> ATTRIBUTE_BUILDER_CLASSES =
            List.of(ATTRIBUTE_BUILDER_BRIDGE, ATTRIBUTE_BUILDER_BRIDGE + "$BuilderView");
    private static final String ITEM_COMPONENTS_BRIDGE = RUNTIME + "ItemComponentsBridge";
    private static final String MULTIMAP = "Lcom/google/common/collect/Multimap;";

    /**
     * The tool and armor items that kept their default attribute modifiers in a Guava
     * {@code Multimap} field until 1.20.5 moved them into the {@code ItemAttributeModifiers}
     * component, under both their Mojang and intermediary names.
     */
    private static final List<String> MODIFIER_ITEMS = List.of(
            "net/minecraft/world/item/ArmorItem", "net/minecraft/class_1738",
            "net/minecraft/world/item/DiggerItem", "net/minecraft/class_1766",
            "net/minecraft/world/item/SwordItem", "net/minecraft/class_1829",
            "net/minecraft/world/item/TridentItem", "net/minecraft/class_1835");

    /** The {@code Item} fields 1.20.5 moved into default components, as Mojang and intermediary. */
    private static List<FieldMigration> itemComponentMigrations() {
        String[][] fields = {
            {"maxStackSize", "I", "I", "setMaxStackSize"},
            {"maxDamage", "I", "I", "setMaxDamage"},
            {"isFireResistant", "Z", "Z", "setFireResistant"},
            {"rarity", "Lnet/minecraft/world/item/Rarity;", "Lnet/minecraft/class_1814;", "setRarity"},
            {"foodProperties", "Lnet/minecraft/world/food/FoodProperties;", "Lnet/minecraft/class_4174;",
                    "setFoodProperties"},
        };
        List<FieldMigration> migrations = new ArrayList<>();
        for (String[] field : fields) {
            migrations.add(new FieldMigration("net/minecraft/world/item/Item", field[0], field[1], "1.20.5",
                    ITEM_COMPONENTS_BRIDGE, null, field[3], List.of(ITEM_COMPONENTS_BRIDGE)));
            migrations.add(new FieldMigration("net/minecraft/class_1792", field[0], field[2], "1.20.5",
                    ITEM_COMPONENTS_BRIDGE, null, field[3], List.of(ITEM_COMPONENTS_BRIDGE)));
        }
        return migrations;
    }

    private static List<FieldMigration> itemModifierMigrations() {
        List<FieldMigration> migrations = new ArrayList<>();
        for (String item : MODIFIER_ITEMS) {
            migrations.add(new FieldMigration(item, "defaultModifiers", MULTIMAP, "1.20.5",
                    ITEM_COMPONENTS_BRIDGE, "defaultModifiers", "setDefaultModifiers",
                    List.of(ITEM_COMPONENTS_BRIDGE)));
        }
        return migrations;
    }

    /**
     * All of these fields went away or changed type in the 1.20.5 data component, entity data and
     * attribute holder rework. {@code AttributeSupplier.Builder} kept its field under the same name
     * as a Guava builder, so its accessor is listed under the Mojang name and, for Fabric hosts
     * before 26.1, under the intermediary target with both the Mojang and Yarn source names.
     */
    static final List<FieldMigration> MIGRATIONS = withItemModifiers(
            new FieldMigration("net/minecraft/world/entity/AreaEffectCloud", "effects",
                    "Ljava/util/List;", "1.20.5", CLOUD_BRIDGE, "effects", "setEffects",
                    List.of(CLOUD_BRIDGE, CLOUD_BRIDGE + "$WriteThroughEffects")),
            new FieldMigration("net/minecraft/network/syncher/SynchedEntityData", "itemsById",
                    "Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;", "1.20.5", ENTITY_DATA_BRIDGE,
                    "itemsById", null, List.of(ENTITY_DATA_BRIDGE)),
            new FieldMigration("net/minecraft/network/syncher/SynchedEntityData", "lock",
                    "Ljava/util/concurrent/locks/ReadWriteLock;", "1.20.5", ENTITY_DATA_BRIDGE,
                    "lock", null, List.of(ENTITY_DATA_BRIDGE)),
            new FieldMigration("net/minecraft/world/entity/monster/ZombieVillager", "tradeOffers",
                    "Lnet/minecraft/nbt/CompoundTag;", "1.20.5", ZOMBIE_VILLAGER_BRIDGE,
                    "tradeOffersTag", "setTradeOffersTag", List.of(ZOMBIE_VILLAGER_BRIDGE)),
            new FieldMigration("net/minecraft/world/entity/ai/attributes/AttributeSupplier$Builder",
                    "builder", "Ljava/util/Map;", "1.20.5", ATTRIBUTE_BUILDER_BRIDGE, "builder", null,
                    ATTRIBUTE_BUILDER_CLASSES),
            new FieldMigration("net/minecraft/class_5132$class_5133", "builder", "Ljava/util/Map;",
                    "1.20.5", ATTRIBUTE_BUILDER_BRIDGE, "builder", null, ATTRIBUTE_BUILDER_CLASSES),
            new FieldMigration("net/minecraft/class_5132$class_5133", "instances", "Ljava/util/Map;",
                    "1.20.5", ATTRIBUTE_BUILDER_BRIDGE, "builder", null, ATTRIBUTE_BUILDER_CLASSES));

    private static List<FieldMigration> withItemModifiers(FieldMigration... migrations) {
        List<FieldMigration> all = new ArrayList<>(List.of(migrations));
        all.addAll(itemModifierMigrations());
        all.addAll(itemComponentMigrations());
        return List.copyOf(all);
    }

    private MixinAccessorFieldMigration() {}

    /** Whether any rule can apply on this host (cheap pre-check). */
    public static boolean appliesOn(String hostVersion) {
        if (hostVersion == null) return false;
        for (FieldMigration migration : MIGRATIONS) {
            if (RetromodVersion.compareMcVersions(hostVersion, migration.removedSince()) >= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Removes migrated accessors from an interface mixin in place.
     *
     * @return the rules that matched, so the caller can register their bridges
     */
    public static List<FieldMigration> removeMigratedAccessors(ClassNode mixin, String hostVersion) {
        Map<String, FieldMigration> migrated = migratedAccessors(mixin, hostVersion);
        if (migrated.isEmpty()) return List.of();
        mixin.methods.removeIf(method -> {
            FieldMigration rule = migrated.get(method.name + method.desc);
            if (rule == null) return false;
            LOGGER.info("Bridged accessor {}.{} for the removed field {}.{}",
                    mixin.name, method.name, rule.target(), rule.field());
            return true;
        });
        return List.copyOf(migrated.values());
    }

    /**
     * Gives each migrated abstract accessor of a class mixin a body that calls its bridge.
     *
     * @return the rules that matched, so the caller can register their bridges
     */
    public static List<FieldMigration> bridgeClassMixinAccessors(ClassNode mixin, String hostVersion) {
        if ((mixin.access & Opcodes.ACC_INTERFACE) != 0 || hostVersion == null) return List.of();
        Map<String, FieldMigration> migrated = accessorRules(mixin, hostVersion);
        if (migrated.isEmpty()) return List.of();
        for (MethodNode method : mixin.methods) {
            FieldMigration rule = migrated.get(method.name + method.desc);
            if (rule == null) continue;
            makeBridgeCall(method, rule);
            LOGGER.info("Bridged class Mixin accessor {}.{} for the removed field {}.{}",
                    mixin.name, method.name, rule.target(), rule.field());
        }
        return List.copyOf(new java.util.LinkedHashSet<>(migrated.values()));
    }

    /** {@code return (FieldType) Bridge.get(this)} or {@code Bridge.set(this, value)}. */
    private static void makeBridgeCall(MethodNode method, FieldMigration rule) {
        method.access &= ~Opcodes.ACC_ABSTRACT;
        if (method.visibleAnnotations != null) method.visibleAnnotations.removeIf(a -> isAccessorOrMutable(a.desc));
        if (method.invisibleAnnotations != null) method.invisibleAnnotations.removeIf(a -> isAccessorOrMutable(a.desc));
        method.tryCatchBlocks.clear();
        if (method.localVariables != null) method.localVariables.clear();
        boolean getter = Type.getArgumentTypes(method.desc).length == 0;
        InsnList body = new InsnList();
        body.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
        if (getter) {
            body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, rule.bridge(), rule.getter(), GETTER_DESC, false));
            body.add(new TypeInsnNode(Opcodes.CHECKCAST, Type.getType(rule.fieldDesc()).getInternalName()));
            body.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ARETURN));
        } else {
            Type value = Type.getType(rule.fieldDesc());
            body.add(new org.objectweb.asm.tree.VarInsnNode(value.getOpcode(Opcodes.ILOAD), 1));
            box(body, value);
            body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, rule.bridge(), rule.setter(), SETTER_DESC, false));
            body.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
        }
        method.instructions = body;
        method.maxStack = 3;
        method.maxLocals = getter ? 1 : 1 + Type.getType(rule.fieldDesc()).getSize();
    }

    /** Boxes a primitive on the stack, since bridge setters take the value as an Object. */
    private static void box(InsnList instructions, Type value) {
        if (value.getSort() >= Type.ARRAY) return;
        String boxed = switch (value.getSort()) {
            case Type.BOOLEAN -> "java/lang/Boolean";
            case Type.CHAR -> "java/lang/Character";
            case Type.BYTE -> "java/lang/Byte";
            case Type.SHORT -> "java/lang/Short";
            case Type.INT -> "java/lang/Integer";
            case Type.FLOAT -> "java/lang/Float";
            case Type.LONG -> "java/lang/Long";
            default -> "java/lang/Double";
        };
        instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, boxed, "valueOf",
                "(" + value.getDescriptor() + ")L" + boxed + ";", false));
    }

    private static boolean isAccessorOrMutable(String desc) {
        return ACCESSOR_DESC.equals(desc) || MUTABLE_DESC.equals(desc);
    }

    /**
     * Points calls of migrated accessors at their bridges.
     *
     * @param jarClasses reads another class of the same jar, or null when none is available
     * @return the rules whose calls were rewritten, so the caller can register their bridges
     */
    public static List<FieldMigration> rewriteCallSites(
            ClassNode classNode, String hostVersion, Function<String, byte[]> jarClasses) {
        if (jarClasses == null || !appliesOn(hostVersion)) return List.of();
        Map<String, Map<String, FieldMigration>> accessorsByOwner = new HashMap<>();
        List<FieldMigration> used = new ArrayList<>();
        for (MethodNode method : classNode.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call)
                        || call.getOpcode() != Opcodes.INVOKEINTERFACE
                        || call.owner.startsWith("java/")
                        || call.owner.startsWith("net/minecraft/")
                        || !hasAnyAccessorShape(call.desc)) {
                    continue;
                }
                FieldMigration rule = accessorsByOwner
                        .computeIfAbsent(call.owner, owner -> accessorsOf(owner, hostVersion, jarClasses))
                        .get(call.name + call.desc);
                if (rule == null) continue;
                method.instructions.insertBefore(call, bridgeCall(call, rule));
                method.instructions.remove(call);
                if (!used.contains(rule)) used.add(rule);
            }
        }
        return used;
    }

    /**
     * Rewrites accessor calls in one class file and returns the original array when nothing
     * matched. {@code registerBridge} receives each bridge class the rewritten code now needs.
     */
    public static byte[] rewriteCallSites(byte[] classBytes, String hostVersion,
            Function<String, byte[]> jarClasses, java.util.function.Consumer<String> registerBridge) {
        if (classBytes == null || jarClasses == null || !appliesOn(hostVersion)) return classBytes;
        try {
            ClassReader reader = new ClassReader(classBytes);
            if (!callsAccessorShapedInterfaceMethod(reader)) return classBytes;
            ClassNode node = new ClassNode();
            reader.accept(node, 0);
            List<FieldMigration> used = rewriteCallSites(node, hostVersion, jarClasses);
            if (used.isEmpty()) return classBytes;
            for (FieldMigration rule : used) rule.bridgeClasses().forEach(registerBridge);
            // Each replacement keeps the stack shape, so the existing frames stay valid.
            org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(reader, 0);
            node.accept(writer);
            return writer.toByteArray();
        } catch (RuntimeException e) {
            LOGGER.debug("Accessor call rewrite skipped ({}); class left unchanged", e.toString());
            return classBytes;
        }
    }

    /** Declaration-free scan, so ordinary classes are never expanded into a tree. */
    private static boolean callsAccessorShapedInterfaceMethod(ClassReader reader) {
        boolean[] found = {false};
        reader.accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
            @Override
            public org.objectweb.asm.MethodVisitor visitMethod(int access, String name,
                    String descriptor, String signature, String[] exceptions) {
                if (found[0]) return null;
                return new org.objectweb.asm.MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String callName,
                            String callDesc, boolean isInterface) {
                        if (opcode == Opcodes.INVOKEINTERFACE && hasAnyAccessorShape(callDesc)) {
                            found[0] = true;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static boolean hasAnyAccessorShape(String descriptor) {
        for (FieldMigration rule : MIGRATIONS) {
            if (hasAccessorShape(descriptor, rule)) return true;
        }
        return false;
    }

    private static InsnList bridgeCall(MethodInsnNode call, FieldMigration rule) {
        InsnList replacement = new InsnList();
        boolean getter = Type.getArgumentTypes(call.desc).length == 0;
        if (getter) {
            replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, rule.bridge(),
                    rule.getter(), GETTER_DESC, false));
            replacement.add(new TypeInsnNode(Opcodes.CHECKCAST,
                    Type.getType(rule.fieldDesc()).getInternalName()));
        } else {
            box(replacement, Type.getType(rule.fieldDesc()));
            replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, rule.bridge(),
                    rule.setter(), SETTER_DESC, false));
        }
        return replacement;
    }

    private static Map<String, FieldMigration> accessorsOf(
            String owner, String hostVersion, Function<String, byte[]> jarClasses) {
        byte[] bytes = jarClasses.apply(owner);
        if (bytes == null) return Map.of();
        try {
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node,
                    ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return migratedAccessors(node, hostVersion);
        } catch (RuntimeException unreadable) {
            return Map.of();
        }
    }

    /** {@code name + desc} of each accessor in {@code mixin} that a rule replaces on this host. */
    private static Map<String, FieldMigration> migratedAccessors(ClassNode mixin, String hostVersion) {
        if ((mixin.access & Opcodes.ACC_INTERFACE) == 0 || hostVersion == null) return Map.of();
        return accessorRules(mixin, hostVersion);
    }

    /** {@code name + desc} of each abstract accessor in {@code mixin} that a rule replaces. */
    private static Map<String, FieldMigration> accessorRules(ClassNode mixin, String hostVersion) {
        List<String> targets = mixinTargets(mixin);
        if (targets.isEmpty()) return Map.of();
        Map<String, FieldMigration> migrated = new HashMap<>();
        for (MethodNode method : mixin.methods) {
            AnnotationNode accessor = annotation(method, ACCESSOR_DESC);
            if (accessor == null || (method.access & Opcodes.ACC_ABSTRACT) == 0) continue;
            String field = accessedField(method, accessor);
            for (FieldMigration rule : MIGRATIONS) {
                if (rule.field().equals(field)
                        && targets.contains(rule.target())
                        && RetromodVersion.compareMcVersions(hostVersion, rule.removedSince()) >= 0
                        && hasAccessorShape(method.desc, rule)) {
                    migrated.put(method.name + method.desc, rule);
                    break;
                }
            }
        }
        return migrated;
    }

    private static boolean hasAccessorShape(String descriptor, FieldMigration rule) {
        Type[] args = Type.getArgumentTypes(descriptor);
        Type ret = Type.getReturnType(descriptor);
        boolean getter = rule.getter() != null && args.length == 0
                && rule.fieldDesc().equals(ret.getDescriptor());
        boolean setter = rule.setter() != null && args.length == 1
                && rule.fieldDesc().equals(args[0].getDescriptor()) && ret.getSort() == Type.VOID;
        return getter || setter;
    }

    /**
     * The field an accessor names: its explicit {@code value}, or the name Mixin infers from a
     * {@code getFoo}, {@code isFoo}, or {@code setFoo} method.
     */
    static String accessedField(MethodNode method, AnnotationNode accessor) {
        if (accessor.values != null) {
            for (int i = 0; i + 1 < accessor.values.size(); i += 2) {
                if ("value".equals(accessor.values.get(i))
                        && accessor.values.get(i + 1) instanceof String value
                        && !value.isEmpty()) {
                    return value;
                }
            }
        }
        String name = method.name.substring(method.name.lastIndexOf('$') + 1);
        for (String prefix : new String[]{"get", "set", "is"}) {
            if (name.length() > prefix.length() && name.startsWith(prefix)
                    && Character.isUpperCase(name.charAt(prefix.length()))) {
                String rest = name.substring(prefix.length());
                return rest.substring(0, 1).toLowerCase(Locale.ROOT) + rest.substring(1);
            }
        }
        return null;
    }

    private static List<String> mixinTargets(ClassNode mixin) {
        List<String> targets = new ArrayList<>();
        for (AnnotationNode annotation : annotations(mixin.visibleAnnotations, mixin.invisibleAnnotations)) {
            if (!MIXIN_DESC.equals(annotation.desc) || annotation.values == null) continue;
            for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                if (!(annotation.values.get(i + 1) instanceof List<?> values)) continue;
                for (Object value : values) {
                    if (value instanceof Type type) targets.add(type.getInternalName());
                    if (value instanceof String name) targets.add(name.replace('.', '/'));
                }
            }
        }
        return targets;
    }

    private static AnnotationNode annotation(MethodNode method, String desc) {
        for (AnnotationNode annotation : annotations(method.visibleAnnotations, method.invisibleAnnotations)) {
            if (desc.equals(annotation.desc)) return annotation;
        }
        return null;
    }

    private static List<AnnotationNode> annotations(
            List<AnnotationNode> visible, List<AnnotationNode> invisible) {
        List<AnnotationNode> all = new ArrayList<>();
        if (visible != null) all.addAll(visible);
        if (invisible != null) all.addAll(invisible);
        return all;
    }
}
