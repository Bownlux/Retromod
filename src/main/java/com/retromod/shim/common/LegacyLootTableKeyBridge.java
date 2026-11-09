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
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;

/** Gives mods built before 1.20.5 their loot table ids back where the host names loot tables by registry key. */
public final class LegacyLootTableKeyBridge {

    static final String HELPER = "com/retromod/generated/LegacyLootTableIds";
    /** The first version that keyed loot tables; mods built for it or later are left alone. */
    static final String FIRST_KEYED = "1.20.5";
    /** 1.21.2 made block and entity loot tables optional and dropped the EMPTY constant. */
    static final String FIRST_OPTIONAL = "1.21.2";

    static final String SET = "java/util/Set";
    static final String OPTIONAL = "java/util/Optional";
    static final String ENTRIES = "net/minecraft/world/level/storage/loot/entries/";

    /** The host's names for what the helper reads and what old calls name. */
    record Names(String id, String key, String keyId, String keyCreate, String idFromParts,
            String registries, String lootRegistry, String builtIn, String all, List<String> emptyFields,
            String blockBehaviour, String block, String blockLootTable,
            String entityType, String entityLootTable, String brushable, String brushableSet,
            String minecart, List<String> minecartOwners, String minecartSet,
            String container, List<String> oldContainerGetters, List<String> oldContainerSetters,
            String lootTable, String lootTableEmpty, List<String> nestedOwners, String nestedRef,
            String singletonBuilder, boolean bridgesResolver) {

        /** Mojang names, with the id class and the accessor names the host uses. */
        static Names mojang(String id, String keyId, String minecart, String containerGet, String containerSet) {
            String vehicle = "net/minecraft/world/entity/vehicle/";
            return new Names(id, "net/minecraft/resources/ResourceKey", keyId, "create", "fromNamespaceAndPath",
                    "net/minecraft/core/registries/Registries", "LOOT_TABLE",
                    "net/minecraft/world/level/storage/loot/BuiltInLootTables", "all",
                    // The bundled 26.x remap turns a Fabric 1.20.1 mod's field_844 into EMPTY; field_844 covers a table without that row.
                    List.of("EMPTY", "field_844"),
                    "net/minecraft/world/level/block/state/BlockBehaviour", "net/minecraft/world/level/block/Block",
                    "getLootTable", "net/minecraft/world/entity/EntityType", "getDefaultLootTable",
                    "net/minecraft/world/level/block/entity/BrushableBlockEntity", "setLootTable",
                    minecart, List.of(vehicle + "AbstractMinecartContainer", vehicle + "MinecartChest",
                            vehicle + "minecart/AbstractMinecartContainer", vehicle + "minecart/MinecartChest"),
                    "setLootTable", vehicle + "ContainerEntity",
                    List.of("getLootTable", "getContainerLootTable", containerGet),
                    List.of("setLootTable", "setContainerLootTable", containerSet),
                    "net/minecraft/world/level/storage/loot/LootTable", "EMPTY",
                    // 1.20.5 renamed LootTableReference to NestedLootTable; a Fabric mod names the new class after the remap.
                    List.of(ENTRIES + "LootTableReference", ENTRIES + "NestedLootTable"), "lootTableReference",
                    ENTRIES + "LootPoolSingletonContainer$Builder", true);
        }

        /** Fabric's intermediary names, which pre-26.1 Fabric hosts run; they never changed for these members. */
        static final Names INTERMEDIARY = new Names("net/minecraft/class_2960", "net/minecraft/class_5321",
                "method_29177", "method_29179", "method_60655", "net/minecraft/class_7924", "field_50079",
                "net/minecraft/class_39", "method_270", List.of("field_844"),
                "net/minecraft/class_4970", "net/minecraft/class_2248", "method_26162",
                "net/minecraft/class_1299", "method_16351", "net/minecraft/class_8174", "method_49216",
                "net/minecraft/class_1693", List.of("net/minecraft/class_1693", "net/minecraft/class_1694"), "method_7562",
                "net/minecraft/class_7265", List.of("method_42276"), List.of("method_42275"),
                "net/minecraft/class_52", "field_948", List.of("net/minecraft/class_83"), "method_428",
                "net/minecraft/class_85$class_86", false);

        String idDesc() {
            return "L" + id + ";";
        }

        String keyDesc() {
            return "L" + key + ";";
        }

        /** The host's own container accessors are the last name in each list. */
        String hostContainerGet() {
            return oldContainerGetters.get(oldContainerGetters.size() - 1);
        }

        String hostContainerSet() {
            return oldContainerSetters.get(oldContainerSetters.size() - 1);
        }

        /** The host's nested table class is the last owner. */
        String nested() {
            return nestedOwners.get(nestedOwners.size() - 1);
        }
    }

    /** The host's names, what its loot table getters return, and whether a nested table still takes a key (until 26.3). */
    record Shape(Names names, boolean optionalTables, boolean hostHasEmpty, boolean nestedTakesKey) {}

    private LegacyLootTableKeyBridge() {}

    /** Registers for Mojang-named mods where the host keys loot tables; offline, from 1.20.5. */
    public static void register(RetromodTransformer transformer) {
        Shape shape = mojangShape();
        if (shape != null) registerUnchecked(transformer, shape);
    }

    /** Registers for intermediary-named Fabric mods on a pre-26.1 host that keys loot tables. */
    public static void registerIntermediary(RetromodTransformer transformer) {
        Names names = Names.INTERMEDIARY;
        ClassNode builtIn = ClassResourceInspector.read(names.builtIn());
        // Only a running host shows its shape; field_885 is DESERT_PYRAMID.
        if (builtIn == null || !fieldHasDesc(builtIn, "field_885", names.keyDesc())) return;
        ClassNode block = ClassResourceInspector.read(names.blockBehaviour());
        boolean optional = block != null && hasMethod(block, names.blockLootTable(), "()L" + OPTIONAL + ";");
        registerUnchecked(transformer, new Shape(names, optional, hasField(builtIn, "field_844"), nestedTakesKey(names)));
    }

    /** The Mojang-named shape of the running host, or of the target version offline; null where ids are not keyed. */
    static Shape mojangShape() {
        Names probe = Names.mojang("", "", "", "", "");
        ClassNode builtIn = ClassResourceInspector.read(probe.builtIn());
        if (builtIn == null) return offlineShape(RetromodVersion.TARGET_MC_VERSION);
        if (!fieldHasDesc(builtIn, "DESERT_PYRAMID", probe.keyDesc())) return null;
        String id = ClassResourceInspector.exists("net/minecraft/resources/Identifier")
                ? "net/minecraft/resources/Identifier" : "net/minecraft/resources/ResourceLocation";
        ClassNode key = ClassResourceInspector.read(probe.key());
        String keyId = key != null && hasMethod(key, "identifier", "()L" + id + ";") ? "identifier" : "location";
        String vehicle = "net/minecraft/world/entity/vehicle/";
        String minecart = ClassResourceInspector.exists(vehicle + "minecart/AbstractMinecartContainer")
                ? vehicle + "minecart/AbstractMinecartContainer" : vehicle + "AbstractMinecartContainer";
        ClassNode block = ClassResourceInspector.read(probe.blockBehaviour());
        boolean optional = block != null && hasMethod(block, "getLootTable", "()L" + OPTIONAL + ";");
        // ContainerEntity renamed its accessors when Entity gained getLootTable(), with the optional tables.
        Names names = Names.mojang(id, keyId, minecart, optional ? "getContainerLootTable" : "getLootTable",
                optional ? "setContainerLootTable" : "setLootTable");
        return new Shape(names, optional, hasField(builtIn, "EMPTY"), nestedTakesKey(names));
    }

    /** Whether the host's nested table reference still takes a key; 26.3 takes a holder, which needs the registries. */
    private static boolean nestedTakesKey(Names names) {
        ClassNode nested = ClassResourceInspector.read(names.nested());
        return nested != null && hasMethod(nested, names.nestedRef(),
                "(" + names.keyDesc() + ")L" + names.singletonBuilder() + ";");
    }

    /** The shape a version shows, for offline transforms; null before 1.20.5. */
    static Shape offlineShape(String version) {
        if (RetromodVersion.compareMcVersions(version, FIRST_KEYED) < 0) return null;
        // 1.21.11 renamed ResourceLocation to Identifier and the key's location() to identifier().
        boolean identifier = RetromodVersion.compareMcVersions(version, "1.21.11") >= 0;
        boolean optional = RetromodVersion.compareMcVersions(version, FIRST_OPTIONAL) >= 0;
        String vehicle = "net/minecraft/world/entity/vehicle/";
        Names names = Names.mojang(identifier ? "net/minecraft/resources/Identifier" : "net/minecraft/resources/ResourceLocation",
                identifier ? "identifier" : "location",
                vehicle + (identifier ? "minecart/" : "") + "AbstractMinecartContainer",
                optional ? "getContainerLootTable" : "getLootTable", optional ? "setContainerLootTable" : "setLootTable");
        return new Shape(names, optional, !optional, RetromodVersion.compareMcVersions(version, "26.3") < 0);
    }

    /** Unconditional registration for a known shape, as transform-shape tests use it. */
    static void registerUnchecked(RetromodTransformer t, Shape shape) {
        Names n = shape.names();
        String id = n.idDesc();
        String key = n.keyDesc();
        t.registerSyntheticClass(HELPER, generate(shape));

        // A constant read the old way reads the key and hands back its id. Only a mod built before 1.20.5
        // reads these fields as ids, because they have been keys since, so the descriptor limits it.
        t.registerStaticFieldTypeDrift(n.builtIn(), id, key, HELPER, "lootTableId", "(" + key + ")" + id);
        if (!shape.hostHasEmpty()) {
            for (String empty : n.emptyFields()) {
                t.registerExactFieldRedirect(n.builtIn(), empty, id, HELPER, "emptyLootTableId", "()" + id);
            }
        }

        // all() kept its descriptor and now holds keys, so only older mods may take the id set.
        redirect(t, n.builtIn(), n.all(), "()L" + SET + ";", "allLootTableIds", "()L" + SET + ";", false);
        // Block and entity type getters return a key, or an optional key from 1.21.2.
        for (String owner : List.of(n.blockBehaviour(), n.block())) {
            redirect(t, owner, n.blockLootTable(), "()" + id, "blockLootTable", "(L" + n.blockBehaviour() + ";)" + id, true);
        }
        redirect(t, n.entityType(), n.entityLootTable(), "()" + id,
                "entityLootTable", "(L" + n.entityType() + ";)" + id, true);
        // The table setters of suspicious sand, chest minecarts and container vehicles take a key.
        redirect(t, n.brushable(), n.brushableSet(), "(" + id + "J)V",
                "setBrushableLootTable", "(L" + n.brushable() + ";" + id + "J)V", true);
        for (String owner : n.minecartOwners()) {
            redirect(t, owner, n.minecartSet(), "(" + id + "J)V",
                    "setMinecartLootTable", "(L" + n.minecart() + ";" + id + "J)V", true);
        }
        for (String getter : n.oldContainerGetters()) {
            redirect(t, n.container(), getter, "()" + id, "containerLootTable", "(L" + n.container() + ";)" + id, false);
        }
        for (String setter : n.oldContainerSetters()) {
            redirect(t, n.container(), setter, "(" + id + ")V",
                    "setContainerLootTable", "(L" + n.container() + ";" + id + ")V", false);
        }
        if (shape.nestedTakesKey()) {
            String builder = "L" + n.singletonBuilder() + ";";
            for (String owner : n.nestedOwners()) {
                redirect(t, owner, n.nestedRef(), "(" + id + ")" + builder, "lootTableReference", "(" + id + ")" + builder, false);
            }
        }
        if (n.bridgesResolver()) registerResolver(t, n);
    }

    /**
     * {@code LootContext.getResolver()} returned a {@code LootDataResolver} until 1.20.5 and a
     * {@code HolderGetter.Provider} since, so the class follows and its id lookup goes to the helper.
     */
    private static void registerResolver(RetromodTransformer t, Names n) {
        String resolver = "net/minecraft/world/level/storage/loot/LootDataResolver";
        String provider = "net/minecraft/core/HolderGetter$Provider";
        t.registerClassRedirect(resolver, provider);
        t.limitClassRedirectToModsBefore(resolver, FIRST_KEYED);
        String table = "L" + n.lootTable() + ";";
        for (String owner : List.of(resolver, provider)) {
            t.registerMethodRedirect(owner, "getLootTable", "(" + n.idDesc() + ")" + table,
                    HELPER, "resolveLootTable", "(L" + provider + ";" + n.idDesc() + ")" + table);
            t.limitRedirectToModsBefore(owner, "getLootTable", "(" + n.idDesc() + ")" + table, FIRST_KEYED);
        }
    }

    /**
     * A redirect to the helper, limited to mods built before 1.20.5; {@code inherited} follows a mod's own
     * subclasses. An instance call's receiver becomes the helper's first argument.
     */
    private static void redirect(RetromodTransformer t, String owner, String name, String desc,
            String helperName, String helperDesc, boolean inherited) {
        if (inherited) {
            t.registerInheritedMethodRedirect(owner, name, desc, HELPER, helperName, helperDesc);
        } else {
            t.registerMethodRedirect(owner, name, desc, HELPER, helperName, helperDesc);
        }
        t.limitRedirectToModsBefore(owner, name, desc, FIRST_KEYED);
    }

    private static boolean hasField(ClassNode owner, String name) {
        for (FieldNode field : owner.fields) {
            if (field.name.equals(name)) return true;
        }
        return false;
    }

    private static boolean fieldHasDesc(ClassNode owner, String name, String desc) {
        for (FieldNode field : owner.fields) {
            if (field.name.equals(name)) return field.desc.equals(desc);
        }
        return false;
    }

    private static boolean hasMethod(ClassNode owner, String name, String desc) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        return false;
    }

    /** Generates the helper in the host's names; it is embedded into each mod that calls it. */
    static byte[] generate(Shape shape) {
        Names n = shape.names();
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                // Minecraft is not on Retromod's classpath when this class is generated.
                return "java/lang/Object";
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, HELPER, null,
                "java/lang/Object", null);
        // The id set and the empty id are built once, so repeated reads compare equal and identical.
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE, "allIds",
                "L" + SET + ";", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE, "emptyId",
                n.idDesc(), null, null).visitEnd();
        emitLootTableId(cw, n);
        emitLootTableKey(cw, n);
        emitEmptyLootTableId(cw, shape);
        emitAllLootTableIds(cw, n);
        emitTableIdOf(cw, shape);
        emitGetter(cw, n, "blockLootTable", n.blockBehaviour(), n.blockLootTable(), shape);
        emitGetter(cw, n, "entityLootTable", n.entityType(), n.entityLootTable(), shape);
        emitSetter(cw, n, "setBrushableLootTable", n.brushable(), n.brushableSet(), true, false);
        emitSetter(cw, n, "setMinecartLootTable", n.minecart(), n.minecartSet(), true, false);
        emitContainerGetter(cw, n);
        emitSetter(cw, n, "setContainerLootTable", n.container(), n.hostContainerSet(), false, true);
        if (shape.nestedTakesKey()) emitLootTableReference(cw, n);
        if (n.bridgesResolver()) emitResolveLootTable(cw, n);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code static ID lootTableId(KEY key)}: the key's id, or null for none. */
    private static void emitLootTableId(ClassWriter cw, Names n) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "lootTableId",
                "(" + n.keyDesc() + ")" + n.idDesc(), null, null);
        mv.visitCode();
        Label present = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitJumpInsn(Opcodes.IFNONNULL, present);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(present);
        // Keys are interned, so each constant answers the same id object every time.
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, n.key(), n.keyId(), "()" + n.idDesc(), false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code static KEY lootTableKey(ID id)}: the loot table key for an id, or null for none. */
    private static void emitLootTableKey(ClassWriter cw, Names n) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "lootTableKey",
                "(" + n.idDesc() + ")" + n.keyDesc(), null, null);
        mv.visitCode();
        Label present = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitJumpInsn(Opcodes.IFNONNULL, present);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(present);
        mv.visitFieldInsn(Opcodes.GETSTATIC, n.registries(), n.lootRegistry(), n.keyDesc());
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, n.key(), n.keyCreate(),
                "(" + n.keyDesc() + n.idDesc() + ")" + n.keyDesc(), false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code static ID emptyLootTableId()}: {@code minecraft:empty}, which 1.20.1 named a table that drops nothing. */
    private static void emitEmptyLootTableId(ClassWriter cw, Shape shape) {
        Names n = shape.names();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "emptyLootTableId",
                "()" + n.idDesc(), null, null);
        mv.visitCode();
        if (shape.hostHasEmpty()) {
            // The host still has the constant, so its id is the one every caller compares with.
            mv.visitFieldInsn(Opcodes.GETSTATIC, n.builtIn(), n.emptyFields().get(0), n.keyDesc());
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "lootTableId", "(" + n.keyDesc() + ")" + n.idDesc(), false);
            mv.visitInsn(Opcodes.ARETURN);
        } else {
            Label built = new Label();
            mv.visitFieldInsn(Opcodes.GETSTATIC, HELPER, "emptyId", n.idDesc());
            mv.visitInsn(Opcodes.DUP);
            mv.visitJumpInsn(Opcodes.IFNONNULL, built);
            mv.visitInsn(Opcodes.POP);
            // Read back through an interned key, so every mod's copy of this helper answers one object.
            mv.visitLdcInsn("minecraft");
            mv.visitLdcInsn("empty");
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, n.id(), n.idFromParts(),
                    "(Ljava/lang/String;Ljava/lang/String;)" + n.idDesc(), false);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "lootTableKey", "(" + n.idDesc() + ")" + n.keyDesc(), false);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "lootTableId", "(" + n.keyDesc() + ")" + n.idDesc(), false);
            mv.visitInsn(Opcodes.DUP);
            mv.visitFieldInsn(Opcodes.PUTSTATIC, HELPER, "emptyId", n.idDesc());
            mv.visitLabel(built);
            mv.visitInsn(Opcodes.ARETURN);
        }
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code static Set allLootTableIds()}: the ids of {@code BuiltInLootTables.all()}, in its order, unmodifiable. */
    private static void emitAllLootTableIds(ClassWriter cw, Names n) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "allLootTableIds",
                "()L" + SET + ";", null, null);
        mv.visitCode();
        Label build = new Label();
        mv.visitFieldInsn(Opcodes.GETSTATIC, HELPER, "allIds", "L" + SET + ";");
        mv.visitInsn(Opcodes.DUP);
        mv.visitJumpInsn(Opcodes.IFNULL, build);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(build);
        mv.visitInsn(Opcodes.POP);
        // Locals: 0 the ids, 1 the keys' iterator.
        mv.visitTypeInsn(Opcodes.NEW, "java/util/LinkedHashSet");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/LinkedHashSet", "<init>", "()V", false);
        mv.visitVarInsn(Opcodes.ASTORE, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, n.builtIn(), n.all(), "()L" + SET + ";", false);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, SET, "iterator", "()Ljava/util/Iterator;", true);
        mv.visitVarInsn(Opcodes.ASTORE, 1);
        Label loop = new Label();
        Label done = new Label();
        mv.visitLabel(loop);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
        mv.visitJumpInsn(Opcodes.IFEQ, done);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
        mv.visitTypeInsn(Opcodes.CHECKCAST, n.key());
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "lootTableId", "(" + n.keyDesc() + ")" + n.idDesc(), false);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, SET, "add", "(Ljava/lang/Object;)Z", true);
        mv.visitInsn(Opcodes.POP);
        mv.visitJumpInsn(Opcodes.GOTO, loop);
        mv.visitLabel(done);
        // 1.20.1 handed out an unmodifiable view as well.
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Collections", "unmodifiableSet",
                "(L" + SET + ";)L" + SET + ";", false);
        mv.visitInsn(Opcodes.DUP);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, HELPER, "allIds", "L" + SET + ";");
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code static ID tableIdOf(Object table)}: the id of a key, or of an optional key where an empty one means EMPTY. */
    private static void emitTableIdOf(ClassWriter cw, Shape shape) {
        Names n = shape.names();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "tableIdOf",
                "(Ljava/lang/Object;)" + n.idDesc(), null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        if (shape.optionalTables()) {
            // A block or entity without a table has an empty optional where 1.20.1 had EMPTY.
            Label present = new Label();
            mv.visitTypeInsn(Opcodes.CHECKCAST, OPTIONAL);
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, OPTIONAL, "isPresent", "()Z", false);
            mv.visitJumpInsn(Opcodes.IFNE, present);
            mv.visitInsn(Opcodes.POP);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "emptyLootTableId", "()" + n.idDesc(), false);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitLabel(present);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, OPTIONAL, "get", "()Ljava/lang/Object;", false);
        }
        mv.visitTypeInsn(Opcodes.CHECKCAST, n.key());
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "lootTableId", "(" + n.keyDesc() + ")" + n.idDesc(), false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code static ID name(OWNER owner)}: the owner's table getter, read as an id. */
    private static void emitGetter(ClassWriter cw, Names n, String name, String owner, String getter, Shape shape) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name,
                "(L" + owner + ";)" + n.idDesc(), null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        String returned = shape.optionalTables() ? "L" + OPTIONAL + ";" : n.keyDesc();
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, getter, "()" + returned, false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "tableIdOf", "(Ljava/lang/Object;)" + n.idDesc(), false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code static ID containerLootTable(ContainerEntity entity)}: the vehicle's table as an id. */
    private static void emitContainerGetter(ClassWriter cw, Names n) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "containerLootTable",
                "(L" + n.container() + ";)" + n.idDesc(), null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, n.container(), n.hostContainerGet(), "()" + n.keyDesc(), true);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "lootTableId", "(" + n.keyDesc() + ")" + n.idDesc(), false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code static void name(OWNER owner, ID id[, long seed])}: the owner's setter with the id's key; null clears it. */
    private static void emitSetter(ClassWriter cw, Names n, String name, String owner, String setter,
            boolean seeded, boolean itf) {
        String seed = seeded ? "J" : "";
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name,
                "(L" + owner + ";" + n.idDesc() + seed + ")V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "lootTableKey", "(" + n.idDesc() + ")" + n.keyDesc(), false);
        if (seeded) mv.visitVarInsn(Opcodes.LLOAD, 2);
        mv.visitMethodInsn(itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL, owner, setter,
                "(" + n.keyDesc() + seed + ")V", itf);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code static Builder lootTableReference(ID id)}: a nested table entry for the id's key. */
    private static void emitLootTableReference(ClassWriter cw, Names n) {
        String builder = "L" + n.singletonBuilder() + ";";
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "lootTableReference",
                "(" + n.idDesc() + ")" + builder, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "lootTableKey", "(" + n.idDesc() + ")" + n.keyDesc(), false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, n.nested(), n.nestedRef(), "(" + n.keyDesc() + ")" + builder, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * {@code static LootTable resolveLootTable(HolderGetter.Provider resolver, ID id)}: the table the
     * resolver holds under the id, or the empty table, as {@code LootDataResolver.getLootTable} answered.
     */
    private static void emitResolveLootTable(ClassWriter cw, Names n) {
        String provider = "net/minecraft/core/HolderGetter$Provider";
        String getter = "net/minecraft/core/HolderGetter";
        String table = "L" + n.lootTable() + ";";
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "resolveLootTable",
                "(L" + provider + ";" + n.idDesc() + ")" + table, null, null);
        mv.visitCode();
        Label missing = new Label();
        // resolver.lookup(Registries.LOOT_TABLE), then get(key) on that lookup.
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETSTATIC, n.registries(), n.lootRegistry(), n.keyDesc());
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, provider, "lookup", "(" + n.keyDesc() + ")L" + OPTIONAL + ";", true);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, OPTIONAL, "isPresent", "()Z", false);
        mv.visitJumpInsn(Opcodes.IFEQ, missing);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, OPTIONAL, "get", "()Ljava/lang/Object;", false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, getter);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "lootTableKey", "(" + n.idDesc() + ")" + n.keyDesc(), false);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, getter, "get", "(" + n.keyDesc() + ")L" + OPTIONAL + ";", true);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, OPTIONAL, "isPresent", "()Z", false);
        mv.visitJumpInsn(Opcodes.IFEQ, missing);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, OPTIONAL, "get", "()Ljava/lang/Object;", false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "net/minecraft/core/Holder");
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "net/minecraft/core/Holder", "value", "()Ljava/lang/Object;", true);
        mv.visitTypeInsn(Opcodes.CHECKCAST, n.lootTable());
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(missing);
        mv.visitInsn(Opcodes.POP);
        mv.visitFieldInsn(Opcodes.GETSTATIC, n.lootTable(), n.lootTableEmpty(), table);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
