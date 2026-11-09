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

import java.util.List;
import java.util.Set;

/** Lets a mob from a mod built before 1.20.5 keep the death loot table it names with the old getDefaultLootTable() id. */
public final class LegacyMobLootBridge {

    static final String HELPER = "com/retromod/generated/LegacyMobLootTables";
    public static final String DROPS = RebuiltApiClasses.ROOT + "minecraft/world/entity/LegacyEntityLootDrops";
    static final String FLAG_PREFIX = "legacy-mob-loot:";
    static final String OPTIONAL = "java/util/Optional";
    /** 1.20.5 keyed loot tables; mods built for it or later already override the keyed member. */
    static final String FIRST_KEYED = "1.20.5";
    /** 1.21.2 dropped getDefaultLootTable and made an entity's table an optional key. */
    static final String FIRST_OPTIONAL = "1.21.2";
    /** 1.21.5 read the last player to hurt an entity through a getter. */
    static final String FIRST_PLAYER_GETTER = "1.21.5";
    /** 1.21.9 added the drop that takes the table (checked in the 1.21.2 to 1.21.10 mappings). */
    static final String FIRST_TABLE_DROP = "1.21.9";
    static final String FIRST_IDENTIFIER = "1.21.11";

    /** How a host decides an entity's death table. */
    enum Mode {
        /** 1.20.5 to 1.21.1: Mob asks its overridable getDefaultLootTable() for a key. */
        KEY_DEFAULT,
        /** 1.21.2 to 1.21.8: no override point and no drop that takes a table, so Retromod drops it itself. */
        OWN_DROP,
        /** 1.21.9 on: the death drop can be handed a table. */
        TABLE_DROP
    }

    /**
     * The host's names for what the adapter overrides and calls; {@code mobOld} and {@code livingOld} are the old members' names
     * after the remap, and {@code mobOnlyOld} the one only a mob could have declared.
     */
    record Names(boolean intermediary, String entity, String living, String mob, String entityType, String id, String key,
            String level, String source, String player, String getType, String typeTable, String tableGetter,
            String livingKeyGetter, String keyDefault, String drop, String tableDrop, List<String> mobOld, List<String> livingOld,
            String mobOnlyOld, String playerField, String playerGetter) {

        static Names mojang(String id) {
            String e = "net/minecraft/world/entity/";
            // A Fabric 1.20.1 mob's method_5991 becomes getLootTable on 26.x, where that id names Entity.getLootTable().
            return new Names(false, e + "Entity", e + "LivingEntity", e + "Mob", e + "EntityType", id,
                    "net/minecraft/resources/ResourceKey", "net/minecraft/server/level/ServerLevel",
                    "net/minecraft/world/damagesource/DamageSource", e + "player/Player", "getType", "getDefaultLootTable",
                    "getLootTable", "getLootTable", "getDefaultLootTable", "dropFromLootTable", "dropFromLootTable",
                    List.of("getDefaultLootTable", "getLootTable"), List.of("getLootTable"), "getDefaultLootTable",
                    "lastHurtByPlayer", "getLastHurtByPlayer");
        }

        /** A Fabric 1.20.5 to 1.21.11 host's intermediary names; each id is the same in those versions' tables. */
        static final Names INTERMEDIARY = new Names(true, "net/minecraft/class_1297", "net/minecraft/class_1309",
                "net/minecraft/class_1308", "net/minecraft/class_1299", "net/minecraft/class_2960", "net/minecraft/class_5321",
                "net/minecraft/class_3218", "net/minecraft/class_1282", "net/minecraft/class_1657", "method_5864",
                "method_16351", "method_5991", "method_5989", "method_5991", "method_16077", "method_72395",
                List.of("method_5991"), List.of("method_5989"), "method_5991", null, null);

        String idDesc() {
            return "L" + id + ";";
        }

        String keyDesc() {
            return "L" + key + ";";
        }

        String dropDesc() {
            return "(L" + level + ";L" + source + ";Z)V";
        }

        String tableDropDesc() {
            return "(L" + level + ";L" + source + ";Z" + keyDesc() + ")V";
        }
    }

    /** One host: its mode, its names, and whether its drop reads the last player through the getter. */
    record Shape(Mode mode, Names names, boolean playerGetter) {
        /** The post-process flag that hands the adapter this shape. */
        String flag() {
            return FLAG_PREFIX + mode + ":" + (names.intermediary() ? "intermediary" : names.id()) + ":" + playerGetter;
        }

        /** The shape a flag carries, or null for another adapter's flag. */
        static Shape of(String flag) {
            if (!flag.startsWith(FLAG_PREFIX)) return null;
            String[] parts = flag.substring(FLAG_PREFIX.length()).split(":");
            Names names = parts[1].equals("intermediary") ? Names.INTERMEDIARY : Names.mojang(parts[1]);
            return new Shape(Mode.valueOf(parts[0]), names, Boolean.parseBoolean(parts[2]));
        }

        /** The shape among the transformer's post-process flags, or null where none is registered. */
        static Shape among(Set<String> flags) {
            for (String flag : flags) {
                Shape shape = of(flag);
                if (shape != null) return shape;
            }
            return null;
        }
    }

    private LegacyMobLootBridge() {}

    /** Mojang names: Forge and NeoForge from 1.20.5, and Fabric from 26.1. Call after {@link LegacyLootTableKeyBridge#register}. */
    public static void register(RetromodTransformer t) {
        Shape shape = mojangShape();
        if (shape != null) registerUnchecked(t, shape);
    }

    /** Intermediary names, for a running Fabric host from 1.20.5 to 1.21.11; 1.21.2 to 1.21.8 are left out. */
    public static void registerIntermediary(RetromodTransformer t) {
        Shape shape = intermediaryShape();
        if (shape != null) registerUnchecked(t, shape);
    }

    /** The running host's shape in Mojang names, or the target version's offline; null before 1.20.5. */
    static Shape mojangShape() {
        Names probe = Names.mojang("net/minecraft/resources/ResourceLocation");
        ClassNode mob = ClassResourceInspector.read(probe.mob());
        ClassNode living = ClassResourceInspector.read(probe.living());
        if (mob == null || living == null) return offlineShape(RetromodVersion.TARGET_MC_VERSION);
        String id = ClassResourceInspector.exists("net/minecraft/resources/Identifier")
                ? "net/minecraft/resources/Identifier" : "net/minecraft/resources/ResourceLocation";
        Names names = Names.mojang(id);
        return shapeOf(mob, living, names);
    }

    /** The running Fabric host's shape, or null where it cannot be read or has neither override point nor table drop. */
    static Shape intermediaryShape() {
        Names names = Names.INTERMEDIARY;
        ClassNode mob = ClassResourceInspector.read(names.mob());
        ClassNode living = ClassResourceInspector.read(names.living());
        if (mob == null || living == null) return null;
        Shape shape = shapeOf(mob, living, names);
        // The own drop is written in Mojang names only.
        return shape == null || shape.mode() == Mode.OWN_DROP ? null : shape;
    }

    static Shape shapeOf(ClassNode mob, ClassNode living, Names names) {
        if (has(mob, names.keyDefault(), "()" + names.keyDesc())) return new Shape(Mode.KEY_DEFAULT, names, false);
        if (has(living, names.tableDrop(), names.tableDropDesc())) return new Shape(Mode.TABLE_DROP, names, false);
        if (names.intermediary()) return null;
        // Without either, an entity's table is an optional key, which 1.21.2 brought along with this drop.
        if (has(living, names.playerGetter(), "()L" + names.player() + ";")) return new Shape(Mode.OWN_DROP, names, true);
        boolean field = living.fields.stream().anyMatch(f -> f.name.equals(names.playerField()) && f.desc.equals("L" + names.player() + ";"));
        return field ? new Shape(Mode.OWN_DROP, names, false) : null;
    }

    /** The shape a version shows, for offline transforms; null before 1.20.5. */
    static Shape offlineShape(String version) {
        if (RetromodVersion.compareMcVersions(version, FIRST_KEYED) < 0) return null;
        Names names = Names.mojang(RetromodVersion.compareMcVersions(version, FIRST_IDENTIFIER) >= 0
                ? "net/minecraft/resources/Identifier" : "net/minecraft/resources/ResourceLocation");
        if (RetromodVersion.compareMcVersions(version, FIRST_OPTIONAL) < 0) return new Shape(Mode.KEY_DEFAULT, names, false);
        if (RetromodVersion.compareMcVersions(version, FIRST_TABLE_DROP) >= 0) return new Shape(Mode.TABLE_DROP, names, false);
        return new Shape(Mode.OWN_DROP, names, RetromodVersion.compareMcVersions(version, FIRST_PLAYER_GETTER) >= 0);
    }

    /** Registers a known shape, as the transform tests use it; needs the loot table id helper to be registered first. */
    static void registerUnchecked(RetromodTransformer t, Shape shape) {
        if (!t.getSyntheticClasses().containsKey(LegacyLootTableKeyBridge.HELPER)) return;
        t.registerSyntheticClass(HELPER, generate(shape.names()));
        if (shape.mode() == Mode.OWN_DROP) RebuiltApiClasses.registerTree(t, DROPS);
        t.enablePostProcess(shape.flag());
    }

    /** The helper the adapted classes call, in the host's names; it reads ids and keys through the loot table id helper. */
    static byte[] generate(Names n) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                // Minecraft is not on Retromod's classpath when this class is generated.
                return "java/lang/Object";
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, HELPER, null, "java/lang/Object", null);
        String ids = LegacyLootTableKeyBridge.HELPER;

        // static KEY defaultLootTableKey(ID id): a null id named no table, which 1.20.1 read as the empty one.
        MethodVisitor key = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "defaultLootTableKey",
                "(" + n.idDesc() + ")" + n.keyDesc(), null, null);
        key.visitCode();
        Label named = new Label();
        key.visitVarInsn(Opcodes.ALOAD, 0);
        key.visitJumpInsn(Opcodes.IFNONNULL, named);
        key.visitMethodInsn(Opcodes.INVOKESTATIC, ids, "emptyLootTableId", "()" + n.idDesc(), false);
        key.visitVarInsn(Opcodes.ASTORE, 0);
        key.visitLabel(named);
        key.visitVarInsn(Opcodes.ALOAD, 0);
        key.visitMethodInsn(Opcodes.INVOKESTATIC, ids, "lootTableKey", "(" + n.idDesc() + ")" + n.keyDesc(), false);
        key.visitInsn(Opcodes.ARETURN);
        key.visitMaxs(0, 0);
        key.visitEnd();

        // static Optional ownLootTable(ID id): an entity's table as 1.21.2 asks for it, empty for none.
        MethodVisitor own = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "ownLootTable",
                "(" + n.idDesc() + ")L" + OPTIONAL + ";", null, null);
        own.visitCode();
        own.visitVarInsn(Opcodes.ALOAD, 0);
        own.visitMethodInsn(Opcodes.INVOKESTATIC, ids, "lootTableKey", "(" + n.idDesc() + ")" + n.keyDesc(), false);
        own.visitMethodInsn(Opcodes.INVOKESTATIC, OPTIONAL, "ofNullable", "(Ljava/lang/Object;)L" + OPTIONAL + ";", false);
        own.visitInsn(Opcodes.ARETURN);
        own.visitMaxs(0, 0);
        own.visitEnd();

        // static ID mobDefaultLootTable(Object entity): super.getDefaultLootTable(), the entity type's table as 1.20.1 answered it.
        MethodVisitor base = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "mobDefaultLootTable",
                "(Ljava/lang/Object;)" + n.idDesc(), null, null);
        base.visitCode();
        base.visitVarInsn(Opcodes.ALOAD, 0);
        base.visitTypeInsn(Opcodes.CHECKCAST, n.entity());
        base.visitMethodInsn(Opcodes.INVOKEVIRTUAL, n.entity(), n.getType(), "()L" + n.entityType() + ";", false);
        base.visitMethodInsn(Opcodes.INVOKESTATIC, ids, "entityLootTable", "(L" + n.entityType() + ";)" + n.idDesc(), false);
        base.visitInsn(Opcodes.ARETURN);
        base.visitMaxs(0, 0);
        base.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static boolean has(ClassNode owner, String name, String desc) {
        return owner.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc));
    }
}
