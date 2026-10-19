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
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bridges entity and recipe members 1.21.2 removed or made stricter, for mods built for 1.21.1 or older. */
public final class Legacy1212MemberBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    static final String HELPER = "com/retromod/generated/Legacy1212Members";

    static final String ENTITY = "net/minecraft/world/entity/Entity";
    static final String ENTITY_TYPE = "net/minecraft/world/entity/EntityType";
    static final String SPAWN_REASON = "net/minecraft/world/entity/EntitySpawnReason";
    static final String LEVEL = "net/minecraft/world/level/Level";
    static final String INGREDIENT = "net/minecraft/world/item/crafting/Ingredient";
    static final String ITEM_STACK = "net/minecraft/world/item/ItemStack";
    static final String ITEM_LIKE = "net/minecraft/world/level/ItemLike";

    static final String CREATE_OLD = "(L" + LEVEL + ";)L" + ENTITY + ";";
    static final String CREATE_NEW = "(L" + LEVEL + ";L" + SPAWN_REASON + ";)L" + ENTITY + ";";
    static final String CREATE_HELPER = "(L" + ENTITY_TYPE + ";L" + LEVEL + ";)L" + ENTITY + ";";
    static final String OF_STACKS = "([L" + ITEM_STACK + ";)L" + INGREDIENT + ";";
    static final String OF_ITEMS = "([L" + ITEM_LIKE + ";)L" + INGREDIENT + ";";

    static final String PROPERTY = "net/minecraft/world/level/block/state/properties/Property";
    static final String VALUES_OLD = "()Ljava/util/Collection;";
    static final String VALUES_NEW = "()Ljava/util/List;";

    static final String DAMAGE_SOURCE = "net/minecraft/world/damagesource/DamageSource";
    static final String HURT_OLD = "(L" + DAMAGE_SOURCE + ";F)Z";
    // The old hurt as a Fabric mod built for 1.21.1 or older names it after the 26.x name table, which
    // follows its intermediary id to the new one-argument client half. IsOverloadBridgeSynthetic
    // answers a call that names LivingEntity; this answers Entity, a player, or the mod's own class.
    static final String HURT_CLIENT_NAME = "hurtClient";

    /** The names one {@code hurt} helper is written in: Mojang names, or a Fabric host's intermediary. */
    record HurtNames(String helper, String entity, String damageSource, String hostMethod) {
        String oldDesc() {
            return "(L" + damageSource + ";F)Z";
        }

        String helperDesc() {
            return "(L" + entity + ";L" + damageSource + ";F)Z";
        }
    }

    static final HurtNames MOJANG_HURT = new HurtNames(HELPER, ENTITY, DAMAGE_SOURCE, "hurtOrSimulate");

    // A Fabric host from 1.21.2 to 1.21.11 still runs intermediary names: method_5643(DamageSource,
    // float) became method_64420 (hurtOrSimulate), and method_5643 now names the one-argument client
    // half. Checked against the 1.21.1, 1.21.2 and 1.21.11 intermediary tables.
    static final HurtNames INTERMEDIARY_HURT = new HurtNames("com/retromod/generated/Legacy1212IntermediaryMembers",
            "net/minecraft/class_1297", "net/minecraft/class_1282", "method_64420");
    static final String INTERMEDIARY_HURT_NAME = "method_5643";

    /** Which members the host lost. */
    record Members(boolean create, boolean ingredient, boolean possibleValues, boolean hurt) {
        boolean any() {
            return create || ingredient || possibleValues || hurt;
        }
    }

    private Legacy1212MemberBridge() {}

    /** Registers a bridge for each member this host lost. */
    public static void register(RetromodTransformer transformer) {
        // TemptGoal reads minecraft:tempt_range, which most old mobs' attributes lack.
        LegacyTemptRangeBridge.register(transformer);
        // MCreator mods make all of these calls: offspring, attributes, tempting food, block
        // property copies, and entity damage.
        Members members = new Members(
                replacedOnHost(ENTITY_TYPE, "create", CREATE_OLD, "create", CREATE_NEW),
                replacedOnHost(INGREDIENT, "of", OF_STACKS, "of", OF_ITEMS),
                replacedOnHost(PROPERTY, "getPossibleValues", VALUES_OLD, "getPossibleValues", VALUES_NEW),
                replacedOnHost(ENTITY, "hurt", HURT_OLD, "hurtOrSimulate", HURT_OLD));
        if (!members.any()) {
            LOGGER.debug("1.21.2 member bridge not needed: the host keeps the old members");
            return;
        }
        registerRedirects(transformer, members);
        LOGGER.info("Bridged 1.21.2 members (EntityType.create: {}, Ingredient.of stacks: {}, "
                + "Property.getPossibleValues: {}, Entity.hurt: {})", members.create(), members.ingredient(),
                members.possibleValues(), members.hurt());
    }

    /** Unconditional registration for transform-shape tests. */
    static void registerRedirects(RetromodTransformer transformer, boolean create, boolean ingredient) {
        registerRedirects(transformer, new Members(create, ingredient, false, false));
    }

    /** Unconditional registration for transform-shape tests. */
    static void registerRedirects(RetromodTransformer transformer, Members members) {
        if (!members.any()) return;
        transformer.registerSyntheticClass(HELPER, generateHelper(members));
        // EntityType.create(Level) gained a spawn reason.
        if (members.create()) {
            // Mods also call it on their own EntityType subclass, so the hierarchy is followed.
            transformer.registerInheritedMethodRedirect(ENTITY_TYPE, "create", CREATE_OLD,
                    HELPER, "create", CREATE_HELPER);
        }
        // Ingredient.of(ItemStack...) was removed.
        if (members.ingredient()) {
            transformer.registerMethodRedirect(INGREDIENT, "of", OF_STACKS, HELPER, "of", OF_STACKS);
        }
        // Property.getPossibleValues() returns a List, which is still the Collection the old call
        // returned, so only the descriptor changes.
        if (members.possibleValues()) {
            // Mods call it on IntegerProperty, BooleanProperty, EnumProperty and their own subclasses.
            transformer.registerInheritedMethodRedirect(PROPERTY, "getPossibleValues", VALUES_OLD,
                    PROPERTY, "getPossibleValues", VALUES_NEW);
        }
        // Entity.hurt(source, amount) was split into a server and a client half. hurtOrSimulate is the
        // old call: it damages on the server, predicts on the client, and answers whether damage was
        // taken. The host only calls the new halves, so an old hurt override misses the game's damage.
        if (members.hurt()) {
            for (String name : new String[] {"hurt", HURT_CLIENT_NAME}) {
                registerHurtRedirect(transformer, MOJANG_HURT, name);
            }
        }
    }

    /** Registers the {@code hurt} bridge in intermediary names, for Fabric hosts 1.21.2 to 1.21.11. */
    public static void registerIntermediaryHurt(RetromodTransformer transformer) {
        HurtNames names = INTERMEDIARY_HURT;
        if (!replacedOnHost(names.entity(), INTERMEDIARY_HURT_NAME, names.oldDesc(),
                names.hostMethod(), names.oldDesc())) {
            LOGGER.debug("Intermediary hurt bridge not needed: the host keeps the old method");
            return;
        }
        // It keeps the Mojang bridge's behavior: an entity's own override of the old method runs
        // first, and that override's super call reaches the host.
        transformer.registerSyntheticClass(names.helper(), generateHurtHelper(names, INTERMEDIARY_HURT_NAME));
        registerHurtRedirect(transformer, names, INTERMEDIARY_HURT_NAME);
    }

    /** Sends old {@code hurt} calls, and an override's {@code super} call, to the helper. */
    private static void registerHurtRedirect(RetromodTransformer transformer, HurtNames names, String name) {
        // Mods call it on their own entity class, and its super call names the direct superclass,
        // so the hierarchy is followed for both.
        transformer.registerInheritedMethodRedirect(names.entity(), name, names.oldDesc(),
                names.helper(), name, names.helperDesc());
        OwnOverrideCall.register(transformer, names.helper(), name, names.helperDesc());
    }

    /** Whether the host lost {@code oldName + oldDesc} and has {@code newName + newDesc}. */
    static boolean replacedOnHost(String owner, String oldName, String oldDesc, String newName, String newDesc) {
        ClassNode host = ClassResourceInspector.read(owner);
        // An unreadable host means an offline transform, so the host version decides.
        if (host == null) {
            return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "1.21.2") >= 0;
        }
        return replacedIn(host, oldName, oldDesc, newName, newDesc);
    }

    static boolean replacedIn(ClassNode host, String oldName, String oldDesc, String newName, String newDesc) {
        return !hasMethod(host, oldName, oldDesc) && hasMethod(host, newName, newDesc);
    }

    private static boolean hasMethod(ClassNode owner, String name, String desc) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        return false;
    }

    static byte[] generateHelper(boolean create, boolean ingredient) {
        return generateHelper(new Members(create, ingredient, false, false));
    }

    /** Writes the helper class with a static method for each bridged member. */
    static byte[] generateHelper(Members members) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                // Minecraft is not on Retromod's classpath when this class is generated.
                return "java/lang/Object";
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                HELPER, null, "java/lang/Object", null);
        if (members.create()) emitCreate(cw);
        if (members.ingredient()) emitIngredientOfStacks(cw);
        if (members.hurt()) {
            emitHurt(cw, MOJANG_HURT, "hurt");
            emitHurt(cw, MOJANG_HURT, HURT_CLIENT_NAME);
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Writes a helper class that holds only the {@code hurt} bridge, in the given names. */
    static byte[] generateHurtHelper(HurtNames names, String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                names.helper(), null, "java/lang/Object", null);
        emitHurt(cw, names, name);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Writes {@code type.create(level, EntitySpawnReason.TRIGGERED)}. */
    private static void emitCreate(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "create",
                CREATE_HELPER, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        // The host only records the reason and does not act on it while building the entity.
        // TRIGGERED is the reason for an entity created by code.
        mv.visitFieldInsn(Opcodes.GETSTATIC, SPAWN_REASON, "TRIGGERED", "L" + SPAWN_REASON + ";");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ENTITY_TYPE, "create", CREATE_NEW, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** Writes {@code name(entity, source, amount)} and its {@code super$} twin. */
    private static void emitHurt(ClassWriter cw, HurtNames names, String name) {
        String own = "own" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        String desc = names.helperDesc();
        // The entity's own override of the old method runs when its class declares one, even
        // through an Entity reference. Otherwise the call is entity.hurtOrSimulate(source, amount).
        OwnOverrideCall.declareLookup(cw, own);
        MethodVisitor call = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, desc, null, null);
        call.visitCode();
        OwnOverrideCall.emitOwnCall(call, names.helper(), own, name, desc, OwnOverrideCall.ownArguments(desc));
        emitHurtOrSimulate(call, names);

        // The super$ twin answers the override's own super call with the host's method only.
        MethodVisitor superCall = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                OwnOverrideCall.superName(name), desc, null, null);
        superCall.visitCode();
        emitHurtOrSimulate(superCall, names);
    }

    /** Writes {@code return entity.hurtOrSimulate(source, amount)} in the given names. */
    private static void emitHurtOrSimulate(MethodVisitor mv, HurtNames names) {
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.FLOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, names.entity(), names.hostMethod(), names.oldDesc(), false);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** Copies each stack's item into an {@code ItemLike[]} and builds the ingredient from that. */
    private static void emitIngredientOfStacks(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_VARARGS,
                "of", OF_STACKS, null, null);
        mv.visitCode();
        // The old ingredient matched a stack by its item, so items[i] = stacks[i].getItem().
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ARRAYLENGTH);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, ITEM_LIKE);
        mv.visitVarInsn(Opcodes.ASTORE, 1);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 2);
        Label loop = new Label();
        Label done = new Label();
        mv.visitLabel(loop);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ARRAYLENGTH);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, done);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitInsn(Opcodes.AALOAD);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "getItem", "()Lnet/minecraft/world/item/Item;", false);
        mv.visitInsn(Opcodes.AASTORE);
        mv.visitIincInsn(2, 1);
        mv.visitJumpInsn(Opcodes.GOTO, loop);
        // Minecraft no longer has an empty ingredient, so an empty array or an air stack still
        // fails here, with the host's own message.
        mv.visitLabel(done);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, INGREDIENT, "of", OF_ITEMS, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
