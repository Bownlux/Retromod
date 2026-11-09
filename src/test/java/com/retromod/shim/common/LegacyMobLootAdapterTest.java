/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.common.LegacyMobLootBridge.Mode;
import com.retromod.shim.common.LegacyMobLootBridge.Shape;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A mob written for 1.20.1 that names its own death table with getDefaultLootTable() drops that table on hosts from 1.20.5. */
class LegacyMobLootAdapterTest {

    private static final String RL = "net/minecraft/resources/ResourceLocation";
    private static final String IDENTIFIER = "net/minecraft/resources/Identifier";

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("26.x: the mob's own table, the base table through super, a custom table, a subclass's, none, and a non-mob's")
    void tableDropHost(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir, "26.3");
        assertEquals(List.of("crabmod:entities/crab"), die(run, "mod/Crab", false, false));
        assertEquals(List.of("minecraft:entities/zombie"), die(run, "mod/Crab", true, false), "super.getDefaultLootTable() is the type's");
        assertEquals(List.of("spawner:custom"), die(run, "mod/Crab", false, true), "a table set on the mob still wins and is cleared");
        assertEquals(List.of("crabmod:entities/kid"), die(run, "mod/Kid", false, false));
        assertEquals(List.of("minecraft:empty"), die(run, "mod/Nothing", false, false), "null named the empty table in 1.20.1");
        assertEquals(List.of("crabmod:entities/ghost"), die(run, "mod/Ghost", false, false), "a non-mob's own getLootTable()");
        assertEquals(List.of("minecraft:entities/zombie"), die(run, "mod/Plain", false, false), "a mob without an override");
    }

    @Test
    @DisplayName("26.x: a type whose default table is a new Optional on each call, as a mixin may build it, still drops the mob's own")
    void rebuiltDefaultTableOptional(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir, "26.3", true);
        assertEquals(List.of("crabmod:entities/crab"), die(run, "mod/Crab", false, false), "equal tables, though not the same Optional");
        assertEquals(List.of("spawner:custom"), die(run, "mod/Crab", false, true), "a table set on the mob still wins");
    }

    @Test
    @DisplayName("1.21.1: the mob's getDefaultLootTable() answers a key, so every reader of the table sees the old choice")
    void keyedDefaultHost(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir, "1.21.1");
        assertEquals(List.of("crabmod:entities/crab"), die(run, "mod/Crab", false, false));
        assertEquals(List.of("minecraft:entities/zombie"), die(run, "mod/Crab", true, false));
        assertEquals(List.of("spawner:custom"), die(run, "mod/Crab", false, true));
        assertEquals(List.of("crabmod:entities/kid"), die(run, "mod/Kid", false, false));
        assertEquals(List.of("minecraft:empty"), die(run, "mod/Nothing", false, false));
        assertEquals(List.of("crabmod:entities/ghost"), die(run, "mod/Ghost", false, false));
    }

    @Test
    @DisplayName("1.21.2 to 1.21.8: Retromod drops the old table itself, with the context those hosts give an entity's table")
    void ownDropHosts(@TempDir Path dir) throws Throwable {
        for (String host : List.of("1.21.4", "1.21.5")) {
            BridgeHarness.Run run = run(dir.resolve(host), host);
            assertEquals(List.of("table crabmod:entities/crab seed 7 params [THIS_ENTITY, ORIGIN, DAMAGE_SOURCE, ATTACKING_ENTITY,"
                    + " DIRECT_ATTACKING_ENTITY, LAST_DAMAGE_PLAYER] luck 1.5", "spawned bone"), die(run, "mod/Crab", false, false), host);
            assertEquals(List.of("table spawner:custom (the host's own drop)"), die(run, "mod/Crab", false, true), host);
            assertEquals(List.of("table crabmod:entities/ghost (the host's own drop)"), die(run, "mod/Ghost", false, false), host);
        }
    }

    @Test
    @DisplayName("A mod built for 1.20.5 or later, a class with the host's own member, and an unrelated class are left alone")
    void declines(@TempDir Path dir) throws Throwable {
        Map<String, byte[]> old = BridgeHarness.only(BridgeHarness.compile(dir.resolve("old"), World.old()), "mod/");
        Set<String> flags = Set.of(LegacyMobLootBridge.offlineShape("26.3").flag());
        byte[] crab = old.get("mod/Crab");
        assertSame(crab, LegacyMobLootAdapter.apply(crab, flags, "1.21.1"), "a 1.21.1 mod already overrides the keyed member");
        assertSame(crab, LegacyMobLootAdapter.apply(crab, Set.of(), null), "no shape registered, as before 1.20.5");
        byte[] own = withOwnDrop();
        assertSame(own, LegacyMobLootAdapter.apply(own, LegacyMobLootBridge.offlineShape("26.3"), (child, ancestor) -> true),
                "a class that already overrides the host's drop keeps it");
        byte[] unrelated = old.get("mod/Ghost");
        assertSame(unrelated, LegacyMobLootAdapter.apply(unrelated, LegacyMobLootBridge.offlineShape("26.3"), (child, ancestor) -> false),
                "a class the hierarchy does not prove to be an entity");
    }

    @Test
    @DisplayName("Fabric 1.20.5 to 1.21.1 and 1.21.9 to 1.21.11, in intermediary: the keyed method_5991, and the drop through method_72395")
    void intermediaryNames() {
        String old = "()Lnet/minecraft/class_2960;";
        byte[] mob = mob("net/minecraft/class_1588", "method_5991", old);
        ClassNode keyed = adapted(mob, new Shape(Mode.KEY_DEFAULT, LegacyMobLootBridge.Names.INTERMEDIARY, false), "net/minecraft/class_1308");
        org.objectweb.asm.tree.MethodNode key = keyed.methods.stream()
                .filter(m -> m.name.equals("method_5991") && m.desc.equals("()Lnet/minecraft/class_5321;")).findFirst().orElseThrow();
        assertTrue(java.util.Arrays.stream(key.instructions.toArray()).anyMatch(i -> i instanceof org.objectweb.asm.tree.MethodInsnNode c
                && c.owner.equals(LegacyMobLootBridge.HELPER) && c.desc.equals("(Lnet/minecraft/class_2960;)Lnet/minecraft/class_5321;")),
                "the host's keyed getDefaultLootTable reads the old id");

        ClassNode drop = adapted(mob, new Shape(Mode.TABLE_DROP, LegacyMobLootBridge.Names.INTERMEDIARY, false), "net/minecraft/class_1308");
        List<String> calls = BridgeHarness.calls(write(drop), "method_16077");
        assertTrue(calls.contains("mod/Fish.method_5991()Ljava/util/Optional;"), calls.toString());
        assertTrue(calls.contains("net/minecraft/class_1299.method_16351()Ljava/util/Optional;"), calls.toString());
        assertTrue(calls.contains("mod/Fish.method_72395(Lnet/minecraft/class_3218;Lnet/minecraft/class_1282;ZLnet/minecraft/class_5321;)V"),
                calls.toString());
        assertTrue(calls.contains("net/minecraft/class_1588.method_16077(Lnet/minecraft/class_3218;Lnet/minecraft/class_1282;Z)V"),
                "the super call for a table set on the mob: " + calls);
    }

    @Test
    @DisplayName("Fabric on 26.x: the remapped getLootTable()Identifier is adapted only where the hierarchy proves a mob")
    void fabricMojangFormNeedsProof() {
        byte[] mob = mob("net/minecraft/world/entity/monster/Monster", "getLootTable", "()L" + IDENTIFIER + ";");
        Shape shape = LegacyMobLootBridge.offlineShape("26.3");
        assertSame(mob, LegacyMobLootAdapter.apply(mob, shape, (child, ancestor) -> false), "any entity could declare getLootTable");
        ClassNode drop = adapted(mob, shape, "net/minecraft/world/entity/Mob");
        assertTrue(BridgeHarness.calls(write(drop), "dropFromLootTable").contains("mod/Fish.getLootTable()L" + IDENTIFIER + ";"));
    }

    @Test
    @DisplayName("The offline shape follows the versions the mappings show")
    void offlineShapes() {
        assertNull(LegacyMobLootBridge.offlineShape("1.20.4"));
        assertEquals(Mode.KEY_DEFAULT, LegacyMobLootBridge.offlineShape("1.20.5").mode());
        assertEquals(Mode.KEY_DEFAULT, LegacyMobLootBridge.offlineShape("1.21.1").mode());
        Shape field = LegacyMobLootBridge.offlineShape("1.21.4");
        assertEquals(Mode.OWN_DROP, field.mode());
        assertFalse(field.playerGetter(), "1.21.2 to 1.21.4 keep the last player in a field");
        assertTrue(LegacyMobLootBridge.offlineShape("1.21.8").playerGetter());
        assertEquals(Mode.TABLE_DROP, LegacyMobLootBridge.offlineShape("1.21.9").mode());
        assertEquals(IDENTIFIER, LegacyMobLootBridge.offlineShape("26.3").names().id());
        assertEquals(RL, LegacyMobLootBridge.offlineShape("1.21.10").names().id());
        Shape back = Shape.of(LegacyMobLootBridge.offlineShape("1.21.5").flag());
        assertEquals(Mode.OWN_DROP, back.mode());
        assertTrue(back.playerGetter());
    }

    /** {@code die()} on a fresh mob of the class: the tables its death drop asked for. */
    @SuppressWarnings("unchecked")
    private static List<String> die(BridgeHarness.Run run, String mob, boolean baby, boolean custom) throws Throwable {
        Object entity = run.loadClass(mob.replace('/', '.')).getConstructor().newInstance();
        return (List<String>) run.call("net/minecraft/world/entity/LivingEntity", "dieAndRecord", entity, baby, custom);
    }

    private static BridgeHarness.Run run(Path dir, String host) throws Exception {
        return run(dir, host, false);
    }

    private static BridgeHarness.Run run(Path dir, String host, boolean rebuiltTable) throws Exception {
        Map<String, byte[]> old = BridgeHarness.only(BridgeHarness.compile(dir.resolve("old"), World.old()), "mod/");
        Map<String, byte[]> hostClasses = BridgeHarness.compile(dir.resolve("host"), World.host(host, rebuiltTable));
        Shape shape = LegacyMobLootBridge.offlineShape(host);
        String id = shape.names().id();
        Map<String, byte[]> mod = BridgeHarness.transform(host, t -> {
            // As the chain does: 26.x renames the class, and 1.21 made the constructor a factory.
            if (!id.equals(RL)) t.registerClassRedirect(RL, id);
            t.registerConstructorRedirect(RL, "(Ljava/lang/String;Ljava/lang/String;)V", id, "fromNamespaceAndPath",
                    "(Ljava/lang/String;Ljava/lang/String;)L" + id + ";");
            // The loot table id helper's three members the mob loot helper reads, as LegacyLootTableKeyBridge provides them.
            t.registerSyntheticClass(LegacyLootTableKeyBridge.HELPER, hostClasses.get(LegacyLootTableKeyBridge.HELPER));
            LegacyMobLootBridge.registerUnchecked(t, shape);
        }, old, hostClasses);
        for (byte[] bytes : mod.values()) {
            assertDoesNotThrow(() -> new ClassReader(bytes).accept(new CheckClassAdapter(new ClassWriter(0), true), 0));
        }
        return new BridgeHarness.Run(hostClasses, mod);
    }

    // mod/Fish extends superName and declares the old table member under the given name.
    private static byte[] mob(String superName, String name, String desc) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "mod/Fish", null, superName, null);
        MethodVisitor old = cw.visitMethod(Opcodes.ACC_PROTECTED, name, desc, null, null);
        old.visitCode();
        old.visitInsn(Opcodes.ACONST_NULL);
        old.visitInsn(Opcodes.ARETURN);
        old.visitMaxs(0, 0);
        old.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    // The class adapted with only {@code mob} proven as its ancestor, checked by ASM's verifier.
    private static ClassNode adapted(byte[] bytes, Shape shape, String mob) {
        byte[] out = LegacyMobLootAdapter.apply(bytes, shape, (child, ancestor) -> ancestor.equals(mob));
        assertFalse(out == bytes, "the class was adapted");
        assertDoesNotThrow(() -> new ClassReader(out).accept(new CheckClassAdapter(new ClassWriter(0), true), 0));
        ClassNode node = new ClassNode();
        new ClassReader(out).accept(node, 0);
        return node;
    }

    private static byte[] write(ClassNode node) {
        ClassWriter cw = new ClassWriter(0);
        node.accept(cw);
        return cw.toByteArray();
    }

    // A mob that already overrides the host's three-argument drop.
    private static byte[] withOwnDrop() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "mod/Own", null, "net/minecraft/world/entity/monster/Monster", null);
        MethodVisitor old = cw.visitMethod(Opcodes.ACC_PROTECTED, "getDefaultLootTable", "()L" + IDENTIFIER + ";", null, null);
        old.visitCode();
        old.visitInsn(Opcodes.ACONST_NULL);
        old.visitInsn(Opcodes.ARETURN);
        old.visitMaxs(0, 0);
        old.visitEnd();
        MethodVisitor drop = cw.visitMethod(Opcodes.ACC_PROTECTED, "dropFromLootTable",
                "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;Z)V", null, null);
        drop.visitCode();
        drop.visitInsn(Opcodes.RETURN);
        drop.visitMaxs(0, 0);
        drop.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The stand-ins: 1.20.1 for compiling the old mod, and each host shape it runs on. */
    static final class World {

        // 1.20.1: a mob's table comes from getDefaultLootTable() unless one was set on it.
        static Map<String, String> old() {
            Map<String, String> s = new HashMap<>();
            s.put("net/minecraft/resources/ResourceLocation.java", """
                    package net.minecraft.resources;
                    public class ResourceLocation { public ResourceLocation(String namespace, String path) {} }""");
            s.put("net/minecraft/world/entity/EntityType.java", """
                    package net.minecraft.world.entity;
                    public class EntityType<T> { public net.minecraft.resources.ResourceLocation getDefaultLootTable() { return null; } }""");
            s.put("net/minecraft/world/entity/Entity.java", """
                    package net.minecraft.world.entity;
                    public class Entity { public EntityType<?> getType() { return null; } }""");
            s.put("net/minecraft/world/entity/LivingEntity.java", """
                    package net.minecraft.world.entity;
                    public class LivingEntity extends Entity {
                        public net.minecraft.resources.ResourceLocation getLootTable() { return getType().getDefaultLootTable(); }
                    }""");
            s.put("net/minecraft/world/entity/Mob.java", """
                    package net.minecraft.world.entity;
                    public class Mob extends LivingEntity {
                        protected net.minecraft.resources.ResourceLocation getDefaultLootTable() { return getType().getDefaultLootTable(); }
                    }""");
            s.put("net/minecraft/world/entity/monster/Monster.java", """
                    package net.minecraft.world.entity.monster;
                    public class Monster extends net.minecraft.world.entity.Mob {}""");
            // As Alex's Mobs: a baby drops the type's table through super, an adult its own.
            s.put("mod/Crab.java", """
                    package mod;
                    import net.minecraft.resources.ResourceLocation;
                    public class Crab extends net.minecraft.world.entity.monster.Monster {
                        public boolean baby;
                        protected ResourceLocation getDefaultLootTable() {
                            return baby ? super.getDefaultLootTable() : new ResourceLocation("crabmod", "entities/crab");
                        }
                    }""");
            s.put("mod/Kid.java", """
                    package mod;
                    public class Kid extends Crab {
                        protected net.minecraft.resources.ResourceLocation getDefaultLootTable() {
                            return new net.minecraft.resources.ResourceLocation("crabmod", "entities/kid");
                        }
                    }""");
            s.put("mod/Nothing.java", """
                    package mod;
                    public class Nothing extends net.minecraft.world.entity.monster.Monster {
                        protected net.minecraft.resources.ResourceLocation getDefaultLootTable() { return null; }
                    }""");
            s.put("mod/Ghost.java", """
                    package mod;
                    public class Ghost extends net.minecraft.world.entity.LivingEntity {
                        public net.minecraft.resources.ResourceLocation getLootTable() {
                            return new net.minecraft.resources.ResourceLocation("crabmod", "entities/ghost");
                        }
                    }""");
            s.put("mod/Plain.java", """
                    package mod;
                    public class Plain extends net.minecraft.world.entity.monster.Monster {}""");
            return s;
        }

        // With rebuiltTable, the type answers a new Optional of the same key on each call, as a mixin may.
        static Map<String, String> host(String version, boolean rebuiltTable) {
            Shape shape = LegacyMobLootBridge.offlineShape(version);
            boolean keyed = shape.mode() == Mode.KEY_DEFAULT;
            String id = shape.names().id().replace('/', '.');
            String idSimple = id.substring(id.lastIndexOf('.') + 1);
            Map<String, String> s = new HashMap<>();
            s.put(shape.names().id() + ".java", """
                    package net.minecraft.resources;
                    public final class ID {
                        private final String text;
                        private ID(String text) { this.text = text; }
                        public static ID fromNamespaceAndPath(String namespace, String path) { return new ID(namespace + ":" + path); }
                        public String toString() { return text; }
                    }""".replace("ID", idSimple));
            s.put("net/minecraft/resources/ResourceKey.java", """
                    package net.minecraft.resources;
                    public final class ResourceKey<T> {
                        private final ID id;
                        private ResourceKey(ID id) { this.id = id; }
                        public static <T> ResourceKey<T> of(ID id) { return new ResourceKey<>(id); }
                        public ID identifier() { return id; }
                        public String toString() { return id.toString(); }
                    }""".replace("ID", idSimple));
            // The loot table id helper's members the mob loot helper calls, as LegacyLootTableKeyBridge generates them.
            s.put("com/retromod/generated/LegacyLootTableIds.java", """
                    package com.retromod.generated;
                    import net.minecraft.resources.*;
                    import net.minecraft.world.entity.EntityType;
                    public final class LegacyLootTableIds {
                        public static ResourceKey lootTableKey(ID id) { return id == null ? null : ResourceKey.of(id); }
                        public static ID emptyLootTableId() { return ID.fromNamespaceAndPath("minecraft", "empty"); }
                        public static ID entityLootTable(EntityType type) { return TYPE_TABLE; }
                    }""".replace("ID", idSimple).replace("TYPE_TABLE", keyed ? "type.getDefaultLootTable().identifier()"
                    : "type.getDefaultLootTable().isEmpty() ? emptyLootTableId() : ((ResourceKey) type.getDefaultLootTable().get()).identifier()"));
            s.put("net/minecraft/server/level/ServerLevel.java", "package net.minecraft.server.level; public class ServerLevel {}");
            s.put("net/minecraft/world/damagesource/DamageSource.java", "package net.minecraft.world.damagesource; public class DamageSource {}");
            String table = keyed ? "ResourceKey<?>" : "java.util.Optional<ResourceKey<?>>";
            s.put("net/minecraft/world/entity/EntityType.java", """
                    package net.minecraft.world.entity;
                    import net.minecraft.resources.*;
                    public class EntityType<T> {
                        public static final EntityType<?> ZOMBIE = new EntityType<>();
                        private final TABLE table = WRAP(ResourceKey.of(ID.fromNamespaceAndPath("minecraft", "entities/zombie")));
                        public TABLE getDefaultLootTable() { return ANSWER; }
                    }""".replace("ANSWER", rebuiltTable ? "java.util.Optional.of(table.get())" : "table")
                    .replace("TABLE", table).replace("WRAP", keyed ? "" : "java.util.Optional.of").replace("ID", idSimple));
            s.put("net/minecraft/world/entity/Entity.java", """
                    package net.minecraft.world.entity;
                    public class Entity {
                        public EntityType<?> getType() { return EntityType.ZOMBIE; }
                        GETTER
                    }""".replace("GETTER", keyed ? "" : "public java.util.Optional<net.minecraft.resources.ResourceKey<?>> getLootTable()"
                    + " { return getType().getDefaultLootTable(); }"));
            s.put("net/minecraft/world/entity/LivingEntity.java", living(shape, idSimple));
            s.put("net/minecraft/world/entity/Mob.java", keyed ? """
                    package net.minecraft.world.entity;
                    import net.minecraft.resources.ResourceKey;
                    public class Mob extends LivingEntity {
                        private ResourceKey<?> lootTable;
                        public final ResourceKey<?> getLootTable() { return lootTable == null ? getDefaultLootTable() : lootTable; }
                        protected ResourceKey<?> getDefaultLootTable() { return super.getLootTable(); }
                        public void setCustomTable(ResourceKey<?> key) { lootTable = key; }
                    }""" : """
                    package net.minecraft.world.entity;
                    import java.util.Optional;
                    import net.minecraft.resources.ResourceKey;
                    public class Mob extends LivingEntity {
                        private Optional<ResourceKey<?>> lootTable = Optional.empty();
                        public final Optional<ResourceKey<?>> getLootTable() { return lootTable.isPresent() ? lootTable : super.getLootTable(); }
                        protected void dropFromLootTable(net.minecraft.server.level.ServerLevel level,
                                net.minecraft.world.damagesource.DamageSource source, boolean hit) {
                            super.dropFromLootTable(level, source, hit);
                            if (lootTable.isPresent()) RECORD.add("cleared");
                            lootTable = Optional.empty();
                        }
                        public void setCustomTable(ResourceKey<?> key) { lootTable = Optional.of(key); }
                    }""");
            s.put("net/minecraft/world/entity/monster/Monster.java", """
                    package net.minecraft.world.entity.monster;
                    public class Monster extends net.minecraft.world.entity.Mob {}""");
            if (shape.mode() == Mode.OWN_DROP) ownDropHost(s);
            return s;
        }

        // The death drop of each shape, recording the table it uses, and a static die() the test calls.
        private static String living(Shape shape, String idSimple) {
            String die = """
                        public static java.util.List<String> RECORD = new java.util.ArrayList<>();
                        public static java.util.List<String> dieAndRecord(LivingEntity entity, boolean baby, boolean custom) throws Exception {
                            RECORD = new java.util.ArrayList<>();
                            if (baby) entity.getClass().getField("baby").setBoolean(entity, true);
                            if (custom) ((Mob) entity).setCustomTable(net.minecraft.resources.ResourceKey.of(
                                    net.minecraft.resources.ID.fromNamespaceAndPath("spawner", "custom")));
                            DIE
                            java.util.List<String> recorded = RECORD;
                            if (custom) {
                                if (!recorded.remove("cleared")) throw new AssertionError("the custom table was not cleared");
                            }
                            return recorded;
                        }
                    """.replace("ID", idSimple);
            if (shape.mode() == Mode.KEY_DEFAULT) {
                return """
                        package net.minecraft.world.entity;
                        public class LivingEntity extends Entity {
                            public net.minecraft.resources.ResourceKey<?> getLootTable() { return getType().getDefaultLootTable(); }
                            protected void dropFromLootTable(Object source, boolean hit) { RECORD.add(getLootTable().toString()); }
                        DIE_METHOD
                        }""".replace("DIE_METHOD", die.replace("DIE", "entity.dropFromLootTable(null, true);")
                        .replace("if (custom) {", "if (false) {"));
            }
            String drop = shape.mode() == Mode.TABLE_DROP ? """
                            protected void dropFromLootTable(net.minecraft.server.level.ServerLevel level,
                                    net.minecraft.world.damagesource.DamageSource source, boolean hit) {
                                java.util.Optional<net.minecraft.resources.ResourceKey<?>> table = getLootTable();
                                if (table.isEmpty()) return;
                                dropFromLootTable(level, source, hit, (net.minecraft.resources.ResourceKey) table.get());
                            }
                            public void dropFromLootTable(net.minecraft.server.level.ServerLevel level,
                                    net.minecraft.world.damagesource.DamageSource source, boolean hit, net.minecraft.resources.ResourceKey<?> table) {
                                RECORD.add(table.toString());
                            }
                    """ : """
                            protected net.minecraft.world.entity.player.Player PLAYER_FIELD;
                            PLAYER_GETTER
                            protected void dropFromLootTable(net.minecraft.server.level.ServerLevel level,
                                    net.minecraft.world.damagesource.DamageSource source, boolean hit) {
                                RECORD.add("table " + getLootTable().get() + " (the host's own drop)");
                            }
                            public long getLootTableSeed() { return 7L; }
                            public final net.minecraft.world.phys.Vec3 position() { return new net.minecraft.world.phys.Vec3(); }
                            public net.minecraft.world.entity.item.ItemEntity spawnAtLocation(net.minecraft.server.level.ServerLevel level,
                                    net.minecraft.world.item.ItemStack stack) {
                                RECORD.add("spawned " + stack);
                                return null;
                            }
                    """.replace("PLAYER_FIELD", shape.playerGetter() ? "lastHurtByPlayerReference" : "lastHurtByPlayer")
                    .replace("PLAYER_GETTER", shape.playerGetter()
                            ? "public net.minecraft.world.entity.player.Player getLastHurtByPlayer() { return lastHurtByPlayerReference; }" : "");
            String dieCall = shape.mode() == Mode.TABLE_DROP ? "entity.dropFromLootTable(null, null, true);"
                    : "entity.PLAYER_FIELD = new net.minecraft.world.entity.player.Player();"
                    .replace("PLAYER_FIELD", shape.playerGetter() ? "lastHurtByPlayerReference" : "lastHurtByPlayer")
                    + " entity.dropFromLootTable(new net.minecraft.server.level.ServerLevel(), new net.minecraft.world.damagesource.DamageSource(), true);";
            return """
                    package net.minecraft.world.entity;
                    public class LivingEntity extends Entity {
                    DROP
                    DIE_METHOD
                    }""".replace("DROP", drop).replace("DIE_METHOD", die.replace("DIE", dieCall));
        }

        // The loot classes Retromod's own drop builds its context with, each recording what it was given.
        private static void ownDropHost(Map<String, String> s) {
            s.put("net/minecraft/world/entity/player/Player.java", """
                    package net.minecraft.world.entity.player;
                    public class Player extends net.minecraft.world.entity.LivingEntity { public float getLuck() { return 1.5f; } }""");
            s.put("net/minecraft/world/phys/Vec3.java", "package net.minecraft.world.phys; public class Vec3 {}");
            s.put("net/minecraft/world/item/ItemStack.java", """
                    package net.minecraft.world.item;
                    public class ItemStack { public String toString() { return "bone"; } }""");
            s.put("net/minecraft/world/entity/item/ItemEntity.java", "package net.minecraft.world.entity.item; public class ItemEntity {}");
            s.put("net/minecraft/server/level/ServerLevel.java", """
                    package net.minecraft.server.level;
                    public class ServerLevel {
                        public net.minecraft.server.MinecraftServer getServer() { return new net.minecraft.server.MinecraftServer(); }
                    }""");
            s.put("net/minecraft/server/MinecraftServer.java", """
                    package net.minecraft.server;
                    public class MinecraftServer {
                        public ReloadableServerRegistries.Holder reloadableRegistries() { return new ReloadableServerRegistries.Holder(); }
                    }""");
            s.put("net/minecraft/server/ReloadableServerRegistries.java", """
                    package net.minecraft.server;
                    public class ReloadableServerRegistries {
                        public static class Holder {
                            public net.minecraft.world.level.storage.loot.LootTable getLootTable(net.minecraft.resources.ResourceKey<?> key) {
                                return new net.minecraft.world.level.storage.loot.LootTable(key.toString());
                            }
                        }
                    }""");
            s.put("net/minecraft/world/damagesource/DamageSource.java", """
                    package net.minecraft.world.damagesource;
                    public class DamageSource {
                        public net.minecraft.world.entity.Entity getEntity() { return new net.minecraft.world.entity.Entity(); }
                        public net.minecraft.world.entity.Entity getDirectEntity() { return new net.minecraft.world.entity.Entity(); }
                    }""");
            s.put("net/minecraft/util/context/ContextKey.java", """
                    package net.minecraft.util.context;
                    public class ContextKey<T> {
                        final String name;
                        public ContextKey(String name) { this.name = name; }
                        public String toString() { return name; }
                    }""");
            s.put("net/minecraft/util/context/ContextKeySet.java", "package net.minecraft.util.context; public class ContextKeySet {}");
            s.put("net/minecraft/world/level/storage/loot/parameters/LootContextParams.java", """
                    package net.minecraft.world.level.storage.loot.parameters;
                    import net.minecraft.util.context.ContextKey;
                    public class LootContextParams {
                        public static final ContextKey<net.minecraft.world.entity.Entity> THIS_ENTITY = new ContextKey<>("THIS_ENTITY");
                        public static final ContextKey<net.minecraft.world.phys.Vec3> ORIGIN = new ContextKey<>("ORIGIN");
                        public static final ContextKey<net.minecraft.world.damagesource.DamageSource> DAMAGE_SOURCE = new ContextKey<>("DAMAGE_SOURCE");
                        public static final ContextKey<net.minecraft.world.entity.Entity> ATTACKING_ENTITY = new ContextKey<>("ATTACKING_ENTITY");
                        public static final ContextKey<net.minecraft.world.entity.Entity> DIRECT_ATTACKING_ENTITY = new ContextKey<>("DIRECT_ATTACKING_ENTITY");
                        public static final ContextKey<net.minecraft.world.entity.player.Player> LAST_DAMAGE_PLAYER = new ContextKey<>("LAST_DAMAGE_PLAYER");
                    }""");
            s.put("net/minecraft/world/level/storage/loot/parameters/LootContextParamSets.java", """
                    package net.minecraft.world.level.storage.loot.parameters;
                    public class LootContextParamSets { public static final net.minecraft.util.context.ContextKeySet ENTITY = new net.minecraft.util.context.ContextKeySet(); }""");
            s.put("net/minecraft/world/level/storage/loot/LootParams.java", """
                    package net.minecraft.world.level.storage.loot;
                    import net.minecraft.util.context.ContextKey;
                    public class LootParams {
                        final java.util.List<String> keys;
                        final float luck;
                        LootParams(java.util.List<String> keys, float luck) { this.keys = keys; this.luck = luck; }
                        public static class Builder {
                            final java.util.List<String> keys = new java.util.ArrayList<>();
                            float luck;
                            public Builder(net.minecraft.server.level.ServerLevel level) {}
                            public <T> Builder withParameter(ContextKey<T> key, T value) { keys.add(key.toString()); return this; }
                            public <T> Builder withOptionalParameter(ContextKey<T> key, T value) { if (value != null) keys.add(key.toString()); return this; }
                            public Builder withLuck(float luck) { this.luck = luck; return this; }
                            public LootParams create(net.minecraft.util.context.ContextKeySet set) { return new LootParams(keys, luck); }
                        }
                    }""");
            s.put("net/minecraft/world/level/storage/loot/LootTable.java", """
                    package net.minecraft.world.level.storage.loot;
                    public class LootTable {
                        final String key;
                        public LootTable(String key) { this.key = key; }
                        public void getRandomItems(LootParams params, long seed, java.util.function.Consumer<net.minecraft.world.item.ItemStack> out) {
                            net.minecraft.world.entity.LivingEntity.RECORD.add("table " + key + " seed " + seed + " params " + params.keys + " luck " + params.luck);
                            out.accept(new net.minecraft.world.item.ItemStack());
                        }
                    }""");
        }
    }
}
