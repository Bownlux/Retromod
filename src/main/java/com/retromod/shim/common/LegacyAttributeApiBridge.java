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
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Bridges the attribute and mob effect API that 1.20.5 and 1.21 redesigned, for Mojang-named
 * mods built for 1.20.4 or older (Forge 1.20.1 mods on NeoForge, after the SRG remap).
 *
 * <p>Three changes break those mods, usually in a static initializer or an entity's attribute
 * builder, so the whole mod stops with {@code NoSuchMethodError} or {@code NoSuchFieldError}:
 * <ul>
 *   <li>1.20.5 made the {@code Attributes} and {@code MobEffects} constants, and every method that
 *       took an {@code Attribute} or {@code MobEffect}, use a registry {@code Holder}.</li>
 *   <li>1.21 keyed {@code AttributeModifier} by a resource id instead of a {@code UUID} and a
 *       name, and made it a record.</li>
 *   <li>{@code AttributeInstance} lookups and removals moved from the {@code UUID} to that id.</li>
 * </ul>
 *
 * <p>A read of an old constant becomes a getter that unwraps the holder. A call with the old
 * descriptor becomes a generated static adapter that wraps a bare value through its
 * {@code BuiltInRegistries} registry, which returns the registered holder, and derives the
 * modifier id from the {@code UUID} the same way at every call. A mod that adds a modifier under a
 * fixed {@code UUID} and later removes it by that {@code UUID} therefore removes the same modifier.
 *
 * <p>Every redirect is registered only after the host proves the old member is gone and the
 * replacement exists, so a host that still has the old API, or runs SRG names, gets none. On
 * 1.20.5 and 1.20.6, which have holders but still key modifiers by {@code UUID}, only the holder
 * changes are bridged. A
 * modifier's display name is dropped, because 1.21 modifiers have none. Overrides of removed
 * methods, such as {@code Item.getDefaultAttributeModifiers(EquipmentSlot)}, are not bridged.
 */
public final class LegacyAttributeApiBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    static final String HELPER = "com/retromod/generated/LegacyAttributeApi";
    static final String ID_NAMESPACE = "retromod";

    static final String HOLDER = "net/minecraft/core/Holder";
    static final String REGISTRY = "net/minecraft/core/Registry";
    static final String BUILT_IN_REGISTRIES = "net/minecraft/core/registries/BuiltInRegistries";
    static final String ATTRIBUTE = "net/minecraft/world/entity/ai/attributes/Attribute";
    static final String MOB_EFFECT = "net/minecraft/world/effect/MobEffect";
    static final String ATTRIBUTES = "net/minecraft/world/entity/ai/attributes/Attributes";
    static final String MOB_EFFECTS = "net/minecraft/world/effect/MobEffects";
    static final String MODIFIER = "net/minecraft/world/entity/ai/attributes/AttributeModifier";
    static final String OPERATION = MODIFIER + "$Operation";
    static final String INSTANCE = "net/minecraft/world/entity/ai/attributes/AttributeInstance";
    static final String ATTRIBUTE_MAP = "net/minecraft/world/entity/ai/attributes/AttributeMap";
    static final String SUPPLIER = "net/minecraft/world/entity/ai/attributes/AttributeSupplier";
    static final String SUPPLIER_BUILDER = SUPPLIER + "$Builder";
    static final String LIVING_ENTITY = "net/minecraft/world/entity/LivingEntity";
    static final String EFFECT_INSTANCE = "net/minecraft/world/effect/MobEffectInstance";

    static final String RESOURCE_LOCATION = "net/minecraft/resources/ResourceLocation";
    static final String OFFLINE_FIRST_VERSION = "1.21";
    static final String OFFLINE_END_VERSION = "1.21.2";

    /** The static holder constants a 1.21.1 host declares, in declaration order. */
    static final List<String> OFFLINE_ATTRIBUTE_CONSTANTS = List.of("ARMOR", "ARMOR_TOUGHNESS",
            "ATTACK_DAMAGE", "ATTACK_KNOCKBACK", "ATTACK_SPEED", "BLOCK_BREAK_SPEED",
            "BLOCK_INTERACTION_RANGE", "BURNING_TIME", "EXPLOSION_KNOCKBACK_RESISTANCE",
            "ENTITY_INTERACTION_RANGE", "FALL_DAMAGE_MULTIPLIER", "FLYING_SPEED", "FOLLOW_RANGE",
            "GRAVITY", "JUMP_STRENGTH", "KNOCKBACK_RESISTANCE", "LUCK", "MAX_ABSORPTION", "MAX_HEALTH",
            "MINING_EFFICIENCY", "MOVEMENT_EFFICIENCY", "MOVEMENT_SPEED", "OXYGEN_BONUS",
            "SAFE_FALL_DISTANCE", "SCALE", "SNEAKING_SPEED", "SPAWN_REINFORCEMENTS_CHANCE",
            "STEP_HEIGHT", "SUBMERGED_MINING_SPEED", "SWEEPING_DAMAGE_RATIO", "WATER_MOVEMENT_EFFICIENCY");
    static final List<String> OFFLINE_EFFECT_CONSTANTS = List.of("MOVEMENT_SPEED", "MOVEMENT_SLOWDOWN",
            "DIG_SPEED", "DIG_SLOWDOWN", "DAMAGE_BOOST", "HEAL", "HARM", "JUMP", "CONFUSION",
            "REGENERATION", "DAMAGE_RESISTANCE", "FIRE_RESISTANCE", "WATER_BREATHING", "INVISIBILITY",
            "BLINDNESS", "NIGHT_VISION", "HUNGER", "WEAKNESS", "POISON", "WITHER", "HEALTH_BOOST",
            "ABSORPTION", "SATURATION", "GLOWING", "LEVITATION", "LUCK", "UNLUCK", "SLOW_FALLING",
            "CONDUIT_POWER", "DOLPHINS_GRACE", "BAD_OMEN", "HERO_OF_THE_VILLAGE", "DARKNESS",
            "TRIAL_OMEN", "RAID_OMEN", "WIND_CHARGED", "WEAVING", "OOZING", "INFESTED");

    private static final String UUID = "java/util/UUID";
    private static final String L_UUID = "Ljava/util/UUID;";
    private static final String L_ATTRIBUTE = "L" + ATTRIBUTE + ";";
    private static final String L_EFFECT = "L" + MOB_EFFECT + ";";
    private static final String L_HOLDER = "L" + HOLDER + ";";
    private static final String L_MODIFIER = "L" + MODIFIER + ";";
    private static final String L_OPERATION = "L" + OPERATION + ";";
    private static final String L_INSTANCE = "L" + INSTANCE + ";";
    private static final String L_EFFECT_INSTANCE = "L" + EFFECT_INSTANCE + ";";
    private static final String L_BUILDER = "L" + SUPPLIER_BUILDER + ";";

    /**
     * One removed member and its replacement. Parameters and the return value are converted by
     * type: a bare attribute or effect becomes its holder, a {@code UUID} or a modifier becomes the
     * modifier id, an {@code int} widens to {@code float}, and a returned holder or id converts
     * back to what the old member returned.
     */
    record Rewrite(String owner, String oldName, String oldDesc, String newName, String newDesc,
                   boolean constructor) {
        String adapterName() {
            return constructor ? "new$" + simpleName(owner) + "$" + Integer.toHexString(oldDesc.hashCode())
                    : simpleName(owner) + "$" + oldName + "$" + Integer.toHexString(oldDesc.hashCode());
        }

        String adapterDesc() {
            if (constructor) return oldDesc.substring(0, oldDesc.lastIndexOf(')') + 1) + "L" + owner + ";";
            return "(L" + owner + ";" + oldDesc.substring(1);
        }
    }

    static Rewrite call(String owner, String name, String oldDesc, String newName, String newDesc) {
        return new Rewrite(owner, name, oldDesc, newName, newDesc, false);
    }

    static Rewrite construct(String owner, String oldDesc, String newDesc) {
        return new Rewrite(owner, "<init>", oldDesc, "<init>", newDesc, true);
    }

    /** The members a 1.20.1 mod calls, in 1.21.1 shape. {@code ID} stands for the host id type. */
    static List<Rewrite> candidates(String id) {
        String lId = "L" + id + ";";
        return List.of(
            call(LIVING_ENTITY, "getAttribute", "(" + L_ATTRIBUTE + ")" + L_INSTANCE,
                    "getAttribute", "(" + L_HOLDER + ")" + L_INSTANCE),
            call(LIVING_ENTITY, "getAttributeValue", "(" + L_ATTRIBUTE + ")D",
                    "getAttributeValue", "(" + L_HOLDER + ")D"),
            call(LIVING_ENTITY, "getAttributeBaseValue", "(" + L_ATTRIBUTE + ")D",
                    "getAttributeBaseValue", "(" + L_HOLDER + ")D"),
            call(LIVING_ENTITY, "hasEffect", "(" + L_EFFECT + ")Z", "hasEffect", "(" + L_HOLDER + ")Z"),
            call(LIVING_ENTITY, "getEffect", "(" + L_EFFECT + ")" + L_EFFECT_INSTANCE,
                    "getEffect", "(" + L_HOLDER + ")" + L_EFFECT_INSTANCE),
            call(LIVING_ENTITY, "removeEffect", "(" + L_EFFECT + ")Z",
                    "removeEffect", "(" + L_HOLDER + ")Z"),
            call(LIVING_ENTITY, "removeEffectNoUpdate", "(" + L_EFFECT + ")" + L_EFFECT_INSTANCE,
                    "removeEffectNoUpdate", "(" + L_HOLDER + ")" + L_EFFECT_INSTANCE),
            call(ATTRIBUTE_MAP, "getInstance", "(" + L_ATTRIBUTE + ")" + L_INSTANCE,
                    "getInstance", "(" + L_HOLDER + ")" + L_INSTANCE),
            call(ATTRIBUTE_MAP, "hasAttribute", "(" + L_ATTRIBUTE + ")Z",
                    "hasAttribute", "(" + L_HOLDER + ")Z"),
            call(ATTRIBUTE_MAP, "hasModifier", "(" + L_ATTRIBUTE + L_UUID + ")Z",
                    "hasModifier", "(" + L_HOLDER + lId + ")Z"),
            call(ATTRIBUTE_MAP, "getValue", "(" + L_ATTRIBUTE + ")D", "getValue", "(" + L_HOLDER + ")D"),
            call(ATTRIBUTE_MAP, "getBaseValue", "(" + L_ATTRIBUTE + ")D",
                    "getBaseValue", "(" + L_HOLDER + ")D"),
            call(ATTRIBUTE_MAP, "getModifierValue", "(" + L_ATTRIBUTE + L_UUID + ")D",
                    "getModifierValue", "(" + L_HOLDER + lId + ")D"),
            call(SUPPLIER, "getValue", "(" + L_ATTRIBUTE + ")D", "getValue", "(" + L_HOLDER + ")D"),
            call(SUPPLIER, "getBaseValue", "(" + L_ATTRIBUTE + ")D",
                    "getBaseValue", "(" + L_HOLDER + ")D"),
            call(SUPPLIER, "getModifierValue", "(" + L_ATTRIBUTE + L_UUID + ")D",
                    "getModifierValue", "(" + L_HOLDER + lId + ")D"),
            call(SUPPLIER, "hasAttribute", "(" + L_ATTRIBUTE + ")Z",
                    "hasAttribute", "(" + L_HOLDER + ")Z"),
            call(SUPPLIER, "hasModifier", "(" + L_ATTRIBUTE + L_UUID + ")Z",
                    "hasModifier", "(" + L_HOLDER + lId + ")Z"),
            call(SUPPLIER_BUILDER, "add", "(" + L_ATTRIBUTE + ")" + L_BUILDER,
                    "add", "(" + L_HOLDER + ")" + L_BUILDER),
            call(SUPPLIER_BUILDER, "add", "(" + L_ATTRIBUTE + "D)" + L_BUILDER,
                    "add", "(" + L_HOLDER + "D)" + L_BUILDER),
            call(INSTANCE, "getModifier", "(" + L_UUID + ")" + L_MODIFIER,
                    "getModifier", "(" + lId + ")" + L_MODIFIER),
            call(INSTANCE, "removeModifier", "(" + L_UUID + ")V", "removeModifier", "(" + lId + ")Z"),
            call(INSTANCE, "removePermanentModifier", "(" + L_UUID + ")V",
                    "removeModifier", "(" + lId + ")Z"),
            call(INSTANCE, "hasModifier", "(" + L_MODIFIER + ")Z", "hasModifier", "(" + lId + ")Z"),
            call(INSTANCE, "getAttribute", "()" + L_ATTRIBUTE, "getAttribute", "()" + L_HOLDER),
            call(MODIFIER, "getId", "()" + L_UUID, "id", "()" + lId),
            call(MODIFIER, "getName", "()Ljava/lang/String;", "id", "()" + lId),
            call(MODIFIER, "getAmount", "()D", "amount", "()D"),
            call(MODIFIER, "getOperation", "()" + L_OPERATION, "operation", "()" + L_OPERATION),
            call(EFFECT_INSTANCE, "getEffect", "()" + L_EFFECT, "getEffect", "()" + L_HOLDER),
            construct(EFFECT_INSTANCE, "(" + L_EFFECT + ")V", "(" + L_HOLDER + ")V"),
            construct(EFFECT_INSTANCE, "(" + L_EFFECT + "I)V", "(" + L_HOLDER + "I)V"),
            construct(EFFECT_INSTANCE, "(" + L_EFFECT + "II)V", "(" + L_HOLDER + "II)V"),
            construct(EFFECT_INSTANCE, "(" + L_EFFECT + "IIZZ)V", "(" + L_HOLDER + "IIZZ)V"),
            construct(EFFECT_INSTANCE, "(" + L_EFFECT + "IIZZZ)V", "(" + L_HOLDER + "IIZZZ)V"),
            construct(EFFECT_INSTANCE, "(" + L_EFFECT + "IIZZZ" + L_EFFECT_INSTANCE + ")V",
                    "(" + L_HOLDER + "IIZZZ" + L_EFFECT_INSTANCE + ")V"),
            // (UUID, String name, double, Operation) -> (id, double, Operation): the name is dropped.
            construct(MODIFIER, "(" + L_UUID + "Ljava/lang/String;D" + L_OPERATION + ")V",
                    "(" + lId + "D" + L_OPERATION + ")V"),
            // (String name, double, Operation) used a random UUID, so it gets a random id.
            construct(MODIFIER, "(Ljava/lang/String;D" + L_OPERATION + ")V",
                    "(" + lId + "D" + L_OPERATION + ")V")
        );
    }

    private LegacyAttributeApiBridge() {}

    /** Registers adapters for the members this host replaced, after checking each one. */
    public static void register(RetromodTransformer transformer) {
        if (hostIsUnreadable()) {
            registerOffline(transformer, RetromodVersion.TARGET_MC_VERSION);
            return;
        }
        String id = hostModifierIdType();
        List<String> attributeConstants = holderConstants(ATTRIBUTES);
        if (!ClassResourceInspector.exists(BUILT_IN_REGISTRIES) || (id == null && attributeConstants.isEmpty())) {
            LOGGER.debug("Legacy attribute API bridge not needed: the host keeps the old attribute API");
            return;
        }
        // 1.20.5 and 1.20.6 moved to holders but still key modifiers by UUID. With the UUID as
        // the id type, every modifier rewrite fails the host check below and only holders change.
        if (id == null) id = UUID;
        List<Rewrite> rewrites = new ArrayList<>();
        for (Rewrite rewrite : candidates(id)) {
            if (replacedOnHost(rewrite)) rewrites.add(rewrite);
        }
        List<String> effectConstants = holderConstants(MOB_EFFECTS);
        registerRedirects(transformer, id, rewrites, attributeConstants, effectConstants);
        LOGGER.info("Bridged {} removed attribute and mob effect members and {} holder constants",
                rewrites.size(), attributeConstants.size() + effectConstants.size());
    }

    /**
     * Offline, where Minecraft is not readable, only a 1.21 or 1.21.1 target gets the bridge: that
     * is the shape checked member by member against a real host. 1.20.5 and 1.20.6 changed only
     * some of these members, and later versions renamed constants, so neither is guessed.
     */
    static boolean registerOffline(RetromodTransformer transformer, String targetVersion) {
        if (RetromodVersion.compareMcVersions(targetVersion, OFFLINE_FIRST_VERSION) < 0
                || RetromodVersion.compareMcVersions(targetVersion, OFFLINE_END_VERSION) >= 0) {
            LOGGER.debug("Legacy attribute API bridge skipped offline: no verified shape for {}", targetVersion);
            return false;
        }
        registerRedirects(transformer, RESOURCE_LOCATION, candidates(RESOURCE_LOCATION),
                OFFLINE_ATTRIBUTE_CONSTANTS, OFFLINE_EFFECT_CONSTANTS);
        LOGGER.info("Bridged the 1.21 attribute and mob effect API from the target version");
        return true;
    }

    private static boolean hostIsUnreadable() {
        return ClassResourceInspector.read(MODIFIER) == null && ClassResourceInspector.read(ATTRIBUTES) == null;
    }

    /** Unconditional registration for transform-shape tests. */
    static void registerRedirects(RetromodTransformer transformer, String id, List<Rewrite> rewrites,
            List<String> attributeConstants, List<String> effectConstants) {
        transformer.registerSyntheticClass(HELPER,
                generateHelper(id, rewrites, attributeConstants, effectConstants));
        for (String constant : attributeConstants) {
            transformer.registerExactFieldRedirect(ATTRIBUTES, constant, L_ATTRIBUTE,
                    HELPER, constantGetter(ATTRIBUTES, constant), "()" + L_ATTRIBUTE);
        }
        for (String constant : effectConstants) {
            transformer.registerExactFieldRedirect(MOB_EFFECTS, constant, L_EFFECT,
                    HELPER, constantGetter(MOB_EFFECTS, constant), "()" + L_EFFECT);
        }
        for (Rewrite rewrite : rewrites) {
            if (rewrite.constructor()) {
                transformer.registerConstructorRedirect(rewrite.owner(), rewrite.oldDesc(),
                        HELPER, rewrite.adapterName(), rewrite.adapterDesc());
            } else {
                // A mod entity usually calls its own inherited getAttributeValue, so the redirect
                // follows the proven hierarchy rather than only the declaring owner.
                transformer.registerInheritedMethodRedirect(rewrite.owner(), rewrite.oldName(),
                        rewrite.oldDesc(), HELPER, rewrite.adapterName(), rewrite.adapterDesc());
            }
        }
    }

    /** The id type of the host's record-shaped modifier, or null while it still takes a UUID. */
    private static String hostModifierIdType() {
        ClassNode modifier = ClassResourceInspector.read(MODIFIER);
        if (modifier == null) return null;
        for (MethodNode method : modifier.methods) {
            if (!method.name.equals("<init>")) continue;
            Type[] params = Type.getArgumentTypes(method.desc);
            if (params.length == 3 && params[1] == Type.DOUBLE_TYPE
                    && params[2].getDescriptor().equals(L_OPERATION)
                    && params[0].getSort() == Type.OBJECT && !params[0].getInternalName().equals(UUID)) {
                return params[0].getInternalName();
            }
        }
        return null;
    }

    private static boolean replacedOnHost(Rewrite rewrite) {
        ClassNode owner = ClassResourceInspector.read(rewrite.owner());
        return owner != null && !hasMethod(owner, rewrite.oldName(), rewrite.oldDesc())
                && hasMethod(owner, rewrite.newName(), rewrite.newDesc());
    }

    /** Static {@code Holder} fields, which held the bare value before 1.20.5. */
    private static List<String> holderConstants(String ownerName) {
        List<String> names = new ArrayList<>();
        ClassNode owner = ClassResourceInspector.read(ownerName);
        if (owner == null) return names;
        for (FieldNode field : owner.fields) {
            if ((field.access & Opcodes.ACC_STATIC) != 0 && field.desc.equals(L_HOLDER)) {
                names.add(field.name);
            }
        }
        return names;
    }

    private static boolean hasMethod(ClassNode owner, String name, String desc) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        return false;
    }

    static String constantGetter(String owner, String constant) {
        return simpleName(owner) + "$" + constant;
    }

    private static String simpleName(String internalName) {
        return internalName.substring(internalName.lastIndexOf('/') + 1);
    }

    static byte[] generateHelper(String id, List<Rewrite> rewrites, List<String> attributeConstants,
            List<String> effectConstants) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                // Minecraft is not on Retromod's classpath when this class is generated.
                return "java/lang/Object";
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                HELPER, null, "java/lang/Object", null);
        emitHolder(cw, "attributeHolder", "ATTRIBUTE");
        emitHolder(cw, "mobEffectHolder", "MOB_EFFECT");
        if (!id.equals(UUID)) {
            emitIdOfUuid(cw, id);
            emitUuidOfId(cw, id);
        }
        for (String constant : attributeConstants) emitConstantGetter(cw, ATTRIBUTES, constant, ATTRIBUTE);
        for (String constant : effectConstants) emitConstantGetter(cw, MOB_EFFECTS, constant, MOB_EFFECT);
        for (Rewrite rewrite : rewrites) emitAdapter(cw, id, rewrite);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A holder passes through; a bare value is wrapped by its built-in registry. */
    private static void emitHolder(ClassWriter cw, String name, String registryField) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name,
                "(Ljava/lang/Object;)" + L_HOLDER, null, null);
        mv.visitCode();
        Label wrap = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, HOLDER);
        mv.visitJumpInsn(Opcodes.IFEQ, wrap);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.CHECKCAST, HOLDER);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(wrap);
        mv.visitFieldInsn(Opcodes.GETSTATIC, BUILT_IN_REGISTRIES, registryField, "L" + REGISTRY + ";");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, REGISTRY, "wrapAsHolder",
                "(Ljava/lang/Object;)" + L_HOLDER, true);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code retromod:<uuid>}, the same id for the same UUID at every call. */
    private static void emitIdOfUuid(ClassWriter cw, String id) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "idOf",
                "(" + L_UUID + ")L" + id + ";", null, null);
        mv.visitCode();
        mv.visitLdcInsn(ID_NAMESPACE);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, UUID, "toString", "()Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, id, "fromNamespaceAndPath",
                "(Ljava/lang/String;Ljava/lang/String;)L" + id + ";", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * The UUID an id stands for: the original one for an id this bridge derived, otherwise a
     * name-based UUID, so an id from a newer mod still gives a stable answer.
     */
    private static void emitUuidOfId(ClassWriter cw, String id) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "uuidOf",
                "(L" + id + ";)" + L_UUID, null, null);
        mv.visitCode();
        Label start = new Label();
        Label end = new Label();
        Label fallback = new Label();
        mv.visitTryCatchBlock(start, end, fallback, "java/lang/IllegalArgumentException");
        mv.visitLdcInsn(ID_NAMESPACE);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, id, "getNamespace", "()Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false);
        Label nameBased = new Label();
        mv.visitJumpInsn(Opcodes.IFEQ, nameBased);
        mv.visitLabel(start);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, id, "getPath", "()Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, UUID, "fromString", "(Ljava/lang/String;)" + L_UUID, false);
        mv.visitLabel(end);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(fallback);
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(nameBased);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "toString", "()Ljava/lang/String;", false);
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/nio/charset/StandardCharsets", "UTF_8",
                "Ljava/nio/charset/Charset;");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "getBytes",
                "(Ljava/nio/charset/Charset;)[B", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, UUID, "nameUUIDFromBytes", "([B)" + L_UUID, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitConstantGetter(ClassWriter cw, String owner, String constant, String type) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                constantGetter(owner, constant), "()L" + type + ";", null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, owner, constant, L_HOLDER);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, HOLDER, "value", "()Ljava/lang/Object;", true);
        mv.visitTypeInsn(Opcodes.CHECKCAST, type);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitAdapter(ClassWriter cw, String id, Rewrite rewrite) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                rewrite.adapterName(), rewrite.adapterDesc(), null, null);
        mv.visitCode();
        int slot = 0;
        if (rewrite.constructor()) {
            mv.visitTypeInsn(Opcodes.NEW, rewrite.owner());
            mv.visitInsn(Opcodes.DUP);
        } else {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            slot = 1;
        }
        Type[] oldParams = Type.getArgumentTypes(rewrite.oldDesc());
        Type[] newParams = Type.getArgumentTypes(rewrite.newDesc());
        if (rewrite.constructor() && rewrite.owner().equals(MODIFIER)) {
            slot = loadModifierConstructorArgs(mv, id, oldParams);
        } else {
            for (int i = 0; i < oldParams.length; i++) {
                mv.visitVarInsn(oldParams[i].getOpcode(Opcodes.ILOAD), slot);
                convertArgument(mv, id, oldParams[i], newParams[i]);
                slot += oldParams[i].getSize();
            }
        }
        if (rewrite.constructor()) {
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, rewrite.owner(), "<init>", rewrite.newDesc(), false);
            mv.visitInsn(Opcodes.ARETURN);
        } else {
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, rewrite.owner(), rewrite.newName(),
                    rewrite.newDesc(), false);
            convertReturn(mv, id, Type.getReturnType(rewrite.oldDesc()), Type.getReturnType(rewrite.newDesc()));
        }
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** Loads (id, amount, operation) from either legacy modifier constructor. */
    private static int loadModifierConstructorArgs(MethodVisitor mv, String id, Type[] oldParams) {
        int slot = 0;
        if (oldParams[0].getDescriptor().equals(L_UUID)) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            slot = 2; // UUID, then the name the record no longer has
        } else {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, UUID, "randomUUID", "()" + L_UUID, false);
            slot = 1;
        }
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "idOf", "(" + L_UUID + ")L" + id + ";", false);
        mv.visitVarInsn(Opcodes.DLOAD, slot);
        mv.visitVarInsn(Opcodes.ALOAD, slot + 2);
        return slot + 3;
    }

    private static void convertArgument(MethodVisitor mv, String id, Type from, Type to) {
        if (from.equals(to)) return;
        String fromDesc = from.getDescriptor();
        String toDesc = to.getDescriptor();
        if (toDesc.equals(L_HOLDER)) {
            String holder = fromDesc.equals(L_EFFECT) ? "mobEffectHolder" : "attributeHolder";
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, holder, "(Ljava/lang/Object;)" + L_HOLDER, false);
        } else if (fromDesc.equals(L_UUID)) {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "idOf", "(" + L_UUID + ")L" + id + ";", false);
        } else if (fromDesc.equals(L_MODIFIER)) {
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MODIFIER, "id", "()L" + id + ";", false);
        } else if (from.getSort() == Type.INT && to.getSort() == Type.FLOAT) {
            mv.visitInsn(Opcodes.I2F);
        } else {
            throw new IllegalArgumentException("No conversion from " + fromDesc + " to " + toDesc);
        }
    }

    private static void convertReturn(MethodVisitor mv, String id, Type old, Type current) {
        String oldDesc = old.getDescriptor();
        if (old.equals(current)) {
            // same type
        } else if (old.getSort() == Type.VOID) {
            mv.visitInsn(current.getSize() == 2 ? Opcodes.POP2 : Opcodes.POP);
        } else if (current.getDescriptor().equals(L_HOLDER)) {
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, HOLDER, "value", "()Ljava/lang/Object;", true);
            mv.visitTypeInsn(Opcodes.CHECKCAST, old.getInternalName());
        } else if (oldDesc.equals(L_UUID)) {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "uuidOf", "(L" + id + ";)" + L_UUID, false);
        } else if (oldDesc.equals("Ljava/lang/String;")) {
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "toString", "()Ljava/lang/String;", false);
        } else {
            throw new IllegalArgumentException("No conversion from " + current + " to " + old);
        }
        mv.visitInsn(old.getOpcode(Opcodes.IRETURN));
    }
}
