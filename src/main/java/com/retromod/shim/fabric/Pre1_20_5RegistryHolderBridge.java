/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.fabric.embedded.LegacyAttributeKeys;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Bridges the {@code Attribute} and {@code MobEffect} overloads that 1.20.5 replaced with
 * {@code Holder} parameters, for intermediary-namespace Fabric mods on pre-26.1 hosts.
 *
 * <p>A 1.20.1 mod builds entity attributes with {@code AttributeSupplier.Builder.add(Attribute)},
 * reads them with {@code LivingEntity.getAttributeValue(Attribute)}, and creates effects with
 * {@code new MobEffectInstance(MobEffect, ...)}. The vanilla constants in {@code Attributes} and
 * {@code MobEffects} became holders too. Each old call dies with {@code NoSuchMethodError} or
 * {@code NoSuchFieldError}, often inside a Mixin handler or registry {@code <clinit>} that stops the
 * whole mod.
 *
 * <p>A read of an old constant becomes a getter that unwraps the holder, so the mod keeps seeing
 * the bare type it was compiled against. Retyping the field instead breaks verification wherever a
 * local or a branch merge still declares the bare type. Each old call becomes a static adapter that
 * accepts either a bare value or a holder, and wraps a bare value through its
 * {@code BuiltInRegistries} registry. The registry wrap returns the registered holder, so maps
 * keyed by holder agree on identity.
 *
 * <p>A mod can add its own attribute to the entity defaults before it registers that attribute,
 * which 1.20.1 tolerated. The defaults then hold a direct holder, so every attribute lookup first
 * lets {@link LegacyAttributeKeys} move that entry to the registered holder.
 *
 * <p>Return values are not converted back: {@code MobEffectInstance.getEffect()} and similar
 * getters still fail. Attribute modifiers keyed by {@code UUID} are not bridged, since 1.21 keys
 * them by identifier.
 */
public final class Pre1_20_5RegistryHolderBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    static final String HELPER = "com/retromod/generated/LegacyRegistryHolders";
    static final String HOLDER = "net/minecraft/class_6880";
    static final String REGISTRY = "net/minecraft/class_2378";
    static final String BUILT_IN_REGISTRIES = "net/minecraft/class_7923";
    static final String WRAP_AS_HOLDER = "method_47983";
    static final String ATTRIBUTE_KEYS = "com/retromod/shim/fabric/embedded/LegacyAttributeKeys";

    /** A registry-backed value type whose API moved from the bare value to its holder. */
    enum Kind {
        ATTRIBUTE("net/minecraft/class_1320", "field_41190", "attributeHolder"),
        MOB_EFFECT("net/minecraft/class_1291", "field_41174", "mobEffectHolder");

        final String valueType;
        final String registryField;
        final String holderMethod;

        Kind(String valueType, String registryField, String holderMethod) {
            this.valueType = valueType;
            this.registryField = registryField;
            this.holderMethod = holderMethod;
        }
    }

    static final String ATTRIBUTES = "net/minecraft/class_5134";
    static final String MOB_EFFECTS = "net/minecraft/class_1294";
    static final String MOB_EFFECT_INSTANCE = "net/minecraft/class_1293";
    static final String FLOWER_BLOCK = "net/minecraft/class_2356";

    private static final String LIVING_ENTITY = "net/minecraft/class_1309";
    private static final String ATTRIBUTE_MAP = "net/minecraft/class_5131";
    private static final String ATTRIBUTE_SUPPLIER = "net/minecraft/class_5132";
    private static final String SUPPLIER_BUILDER = "net/minecraft/class_5132$class_5133";
    private static final String L_ATTRIBUTE_INSTANCE = "Lnet/minecraft/class_1324;";
    private static final String L_EFFECT_INSTANCE = "L" + MOB_EFFECT_INSTANCE + ";";
    private static final String L_PROPERTIES = "Lnet/minecraft/class_4970$class_2251;";

    /** The thirteen attribute constants a pre-1.20.5 mod can name. */
    static final String[] ATTRIBUTE_CONSTANTS = {
        "field_23716", "field_23717", "field_23718", "field_23719", "field_23720",
        "field_23721", "field_23722", "field_23723", "field_23724", "field_23725",
        "field_23726", "field_23727", "field_23728",
    };

    /** The 33 effect constants in 1.20.1 {@code MobEffects}. */
    static final String[] MOB_EFFECT_CONSTANTS = {
        "field_16595", "field_18980", "field_38092", "field_5898", "field_5899", "field_5900",
        "field_5901", "field_5902", "field_5903", "field_5904", "field_5905", "field_5906",
        "field_5907", "field_5908", "field_5909", "field_5910", "field_5911", "field_5912",
        "field_5913", "field_5914", "field_5915", "field_5916", "field_5917", "field_5918",
        "field_5919", "field_5920", "field_5921", "field_5922", "field_5923", "field_5924",
        "field_5925", "field_5926", "field_5927",
    };

    /**
     * One removed instance overload whose first parameter was the bare value.
     *
     * @param extraParams descriptors of the parameters after the value
     */
    record Overload(Kind kind, String owner, String oldName, String newName, String extraParams,
                    String returnDesc) {
        String oldDesc() { return "(L" + kind.valueType + ";" + extraParams + ")" + returnDesc; }
        String newDesc() { return "(L" + HOLDER + ";" + extraParams + ")" + returnDesc; }
        String adapterName() { return simpleName(owner) + "$" + oldName; }
        String adapterDesc() {
            return "(L" + owner + ";Ljava/lang/Object;" + extraParams + ")" + returnDesc;
        }
    }

    /**
     * One removed constructor whose first parameter was the bare value. The remaining parameters
     * are passed through, with {@code int} widened where the new constructor takes a {@code float}.
     */
    record Constructor(Kind kind, String owner, String oldParams, String newParams) {
        String oldDesc() { return "(L" + kind.valueType + ";" + oldParams + ")V"; }
        String newDesc() { return "(L" + HOLDER + ";" + newParams + ")V"; }
        String factoryName() { return "new$" + simpleName(owner); }
        String factoryDesc() { return "(Ljava/lang/Object;" + oldParams + ")L" + owner + ";"; }
    }

    static final List<Overload> OVERLOADS = List.of(
        new Overload(Kind.ATTRIBUTE, LIVING_ENTITY, "method_5996", "method_5996", "", L_ATTRIBUTE_INSTANCE),
        new Overload(Kind.ATTRIBUTE, LIVING_ENTITY, "method_26825", "method_45325", "", "D"),
        new Overload(Kind.ATTRIBUTE, LIVING_ENTITY, "method_26826", "method_45326", "", "D"),
        new Overload(Kind.ATTRIBUTE, ATTRIBUTE_MAP, "method_26842", "method_45329", "", L_ATTRIBUTE_INSTANCE),
        new Overload(Kind.ATTRIBUTE, ATTRIBUTE_MAP, "method_27306", "method_45331", "", "Z"),
        new Overload(Kind.ATTRIBUTE, ATTRIBUTE_MAP, "method_26852", "method_26852", "", "D"),
        new Overload(Kind.ATTRIBUTE, ATTRIBUTE_MAP, "method_26856", "method_26856", "", "D"),
        new Overload(Kind.ATTRIBUTE, ATTRIBUTE_SUPPLIER, "method_26862", "method_26862", "", "D"),
        new Overload(Kind.ATTRIBUTE, ATTRIBUTE_SUPPLIER, "method_26864", "method_26864", "", "D"),
        new Overload(Kind.ATTRIBUTE, SUPPLIER_BUILDER, "method_26867", "method_26867", "",
                "L" + SUPPLIER_BUILDER + ";"),
        new Overload(Kind.ATTRIBUTE, SUPPLIER_BUILDER, "method_26868", "method_26868", "D",
                "L" + SUPPLIER_BUILDER + ";"),
        new Overload(Kind.MOB_EFFECT, LIVING_ENTITY, "method_6059", "method_6059", "", "Z"),
        new Overload(Kind.MOB_EFFECT, LIVING_ENTITY, "method_6112", "method_6112", "", L_EFFECT_INSTANCE),
        new Overload(Kind.MOB_EFFECT, LIVING_ENTITY, "method_6016", "method_6016", "", "Z"),
        new Overload(Kind.MOB_EFFECT, LIVING_ENTITY, "method_6111", "method_6111", "", L_EFFECT_INSTANCE)
    );

    static final List<Constructor> CONSTRUCTORS = List.of(
        new Constructor(Kind.MOB_EFFECT, MOB_EFFECT_INSTANCE, "", ""),
        new Constructor(Kind.MOB_EFFECT, MOB_EFFECT_INSTANCE, "I", "I"),
        new Constructor(Kind.MOB_EFFECT, MOB_EFFECT_INSTANCE, "II", "II"),
        new Constructor(Kind.MOB_EFFECT, MOB_EFFECT_INSTANCE, "IIZZ", "IIZZ"),
        new Constructor(Kind.MOB_EFFECT, MOB_EFFECT_INSTANCE, "IIZZZ", "IIZZZ"),
        new Constructor(Kind.MOB_EFFECT, MOB_EFFECT_INSTANCE, "IIZZZ" + L_EFFECT_INSTANCE,
                "IIZZZ" + L_EFFECT_INSTANCE),
        // FlowerBlock(MobEffect, int seconds, Properties) -> (Holder, float seconds, Properties)
        new Constructor(Kind.MOB_EFFECT, FLOWER_BLOCK, "I" + L_PROPERTIES, "F" + L_PROPERTIES)
    );

    private Pre1_20_5RegistryHolderBridge() {}

    /** Registers adapters only for the members the host replaced, after checking each target. */
    public static void register(RetromodTransformer transformer) {
        if (!ClassResourceInspector.exists(HOLDER) || !ClassResourceInspector.exists(BUILT_IN_REGISTRIES)) {
            LOGGER.debug("Registry holder support is not needed because the holder classes are unavailable");
            return;
        }
        List<Overload> overloads = new ArrayList<>();
        for (Overload overload : OVERLOADS) {
            if (replacedOnHost(overload.owner(), overload.oldName(), overload.oldDesc(),
                    overload.newName(), overload.newDesc())) {
                overloads.add(overload);
            }
        }
        List<Constructor> constructors = new ArrayList<>();
        for (Constructor constructor : CONSTRUCTORS) {
            if (replacedOnHost(constructor.owner(), "<init>", constructor.oldDesc(),
                    "<init>", constructor.newDesc())) {
                constructors.add(constructor);
            }
        }
        boolean attributeConstants = constantsAreHolders(ATTRIBUTES, ATTRIBUTE_CONSTANTS[0]);
        boolean effectConstants = constantsAreHolders(MOB_EFFECTS, MOB_EFFECT_CONSTANTS[0]);
        if (overloads.isEmpty() && constructors.isEmpty() && !attributeConstants && !effectConstants) {
            LOGGER.debug("The host still supports the bare Attribute and MobEffect overloads");
            return;
        }
        registerRedirects(transformer, overloads, constructors, attributeConstants, effectConstants);
        LOGGER.debug("Added {} holder adapters for removed Attribute and MobEffect members",
                overloads.size() + constructors.size());
    }

    /** Unconditional registration for offline transforms and transform-shape tests. */
    public static void registerRedirects(RetromodTransformer transformer, List<Overload> overloads,
            List<Constructor> constructors, boolean attributeConstants, boolean effectConstants) {
        SyntheticEmbedder.registerClassResource(transformer, ATTRIBUTE_KEYS, LegacyAttributeKeys.class);
        transformer.registerSyntheticClass(HELPER, generateHelper(overloads, constructors));
        if (attributeConstants) retypeConstants(transformer, Kind.ATTRIBUTE, ATTRIBUTES, ATTRIBUTE_CONSTANTS);
        if (effectConstants) retypeConstants(transformer, Kind.MOB_EFFECT, MOB_EFFECTS, MOB_EFFECT_CONSTANTS);
        for (Overload overload : overloads) {
            // Calls are often emitted against a subclass, such as a mod entity calling its own
            // inherited getAttributeValue, so the redirect follows the proven hierarchy.
            transformer.registerInheritedMethodRedirect(
                    overload.owner(), overload.oldName(), overload.oldDesc(),
                    HELPER, overload.adapterName(), overload.adapterDesc());
        }
        for (Constructor constructor : constructors) {
            transformer.registerConstructorRedirect(constructor.owner(), constructor.oldDesc(),
                    HELPER, constructor.factoryName(), constructor.factoryDesc());
        }
    }

    private static void retypeConstants(RetromodTransformer transformer, Kind kind, String owner,
            String[] constants) {
        for (String constant : constants) {
            transformer.registerFieldRedirect(owner, constant, "L" + kind.valueType + ";",
                    HELPER, constantGetter(owner, constant), "()L" + kind.valueType + ";");
        }
    }

    static String constantGetter(String owner, String constant) {
        return simpleName(owner) + "$" + constant;
    }

    private static boolean replacedOnHost(String ownerName, String oldName, String oldDesc,
            String newName, String newDesc) {
        ClassNode owner = ClassResourceInspector.read(ownerName);
        return owner != null && !hasMethod(owner, oldName, oldDesc) && hasMethod(owner, newName, newDesc);
    }

    private static boolean constantsAreHolders(String ownerName, String sampleField) {
        ClassNode owner = ClassResourceInspector.read(ownerName);
        if (owner == null) return false;
        return owner.fields.stream().anyMatch(field -> field.name.equals(sampleField)
                && field.desc.equals("L" + HOLDER + ";"));
    }

    private static boolean hasMethod(ClassNode owner, String name, String desc) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        return false;
    }

    private static String simpleName(String internalName) {
        return internalName.substring(internalName.lastIndexOf('/') + 1);
    }

    static byte[] generateHelper(List<Overload> overloads, List<Constructor> constructors) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                // Minecraft is not on Retromod's classpath when this class is generated.
                return "java/lang/Object";
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                HELPER, null, "java/lang/Object", null);

        for (Kind kind : Kind.values()) {
            emitHolderMethod(cw, kind);
        }
        for (String constant : ATTRIBUTE_CONSTANTS) {
            emitConstantGetter(cw, Kind.ATTRIBUTE, ATTRIBUTES, constant);
        }
        for (String constant : MOB_EFFECT_CONSTANTS) {
            emitConstantGetter(cw, Kind.MOB_EFFECT, MOB_EFFECTS, constant);
        }
        for (Overload overload : overloads) {
            emitOverloadAdapter(cw, overload);
        }
        for (Constructor constructor : constructors) {
            emitConstructorFactory(cw, constructor);
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code holder(Object)}: a holder passes through, a bare value is wrapped by its registry. */
    private static void emitHolderMethod(ClassWriter cw, Kind kind) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, kind.holderMethod,
                "(Ljava/lang/Object;)L" + HOLDER + ";", null, null);
        mv.visitCode();
        Label wrap = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, HOLDER);
        mv.visitJumpInsn(Opcodes.IFEQ, wrap);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.CHECKCAST, HOLDER);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(wrap);
        mv.visitFieldInsn(Opcodes.GETSTATIC, BUILT_IN_REGISTRIES, kind.registryField,
                "L" + REGISTRY + ";");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, REGISTRY, WRAP_AS_HOLDER,
                "(Ljava/lang/Object;)L" + HOLDER + ";", true);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code Owner.CONSTANT.value()}, the bare value a pre-1.20.5 field read produced. */
    private static void emitConstantGetter(ClassWriter cw, Kind kind, String owner, String constant) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                constantGetter(owner, constant), "()L" + kind.valueType + ";", null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, owner, constant, "L" + HOLDER + ";");
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, HOLDER, "comp_349", "()Ljava/lang/Object;", true);
        mv.visitTypeInsn(Opcodes.CHECKCAST, kind.valueType);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitOverloadAdapter(ClassWriter cw, Overload overload) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                overload.adapterName(), overload.adapterDesc(), null, null);
        mv.visitCode();
        String repair = attributeKeyRepair(overload);
        if (repair != null) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, overload.kind().holderMethod,
                    "(Ljava/lang/Object;)L" + HOLDER + ";", false);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, ATTRIBUTE_KEYS, repair,
                    "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
        }
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, overload.kind().holderMethod,
                "(Ljava/lang/Object;)L" + HOLDER + ";", false);
        loadParameters(mv, overload.extraParams(), overload.extraParams(), 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, overload.owner(), overload.newName(),
                overload.newDesc(), false);
        mv.visitInsn(Type.getReturnType(overload.newDesc()).getOpcode(Opcodes.IRETURN));
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * The {@link LegacyAttributeKeys} method that repairs the defaults an attribute lookup reads,
     * or null for an overload that only builds defaults.
     */
    static String attributeKeyRepair(Overload overload) {
        if (overload.kind() != Kind.ATTRIBUTE) return null;
        return switch (overload.owner()) {
            case LIVING_ENTITY -> "repairEntity";
            case ATTRIBUTE_MAP -> "repairMap";
            case ATTRIBUTE_SUPPLIER -> "repairSupplier";
            default -> null;
        };
    }

    private static void emitConstructorFactory(ClassWriter cw, Constructor constructor) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                constructor.factoryName(), constructor.factoryDesc(), null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, constructor.owner());
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, constructor.kind().holderMethod,
                "(Ljava/lang/Object;)L" + HOLDER + ";", false);
        loadParameters(mv, constructor.oldParams(), constructor.newParams(), 1);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, constructor.owner(), "<init>",
                constructor.newDesc(), false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** Loads the old parameters, widening an {@code int} where the new signature takes a float. */
    private static void loadParameters(MethodVisitor mv, String oldParams, String newParams, int slot) {
        Type[] oldTypes = Type.getArgumentTypes("(" + oldParams + ")V");
        Type[] newTypes = Type.getArgumentTypes("(" + newParams + ")V");
        for (int i = 0; i < oldTypes.length; i++) {
            mv.visitVarInsn(oldTypes[i].getOpcode(Opcodes.ILOAD), slot);
            if (oldTypes[i].getSort() == Type.INT && newTypes[i].getSort() == Type.FLOAT) {
                mv.visitInsn(Opcodes.I2F);
            }
            slot += oldTypes[i].getSize();
        }
    }
}
