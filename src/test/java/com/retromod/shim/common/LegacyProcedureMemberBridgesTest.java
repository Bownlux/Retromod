/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * The calls an MCreator food mod's procedures make, against the members later hosts removed: the
 * command source it plays a sound through, the jar it counts and clears out of the inventory, the
 * item it drops when the inventory is full, the tool it wears down, the damage it deals, the block
 * property values it reads, and the advancement it awards through the player's server. Found on
 * S33R More Food (Forge 1.19.2) on NeoForge 26.1.2 and 26.3, where each of those procedures failed
 * with {@code NoSuchMethodError} or {@code IllegalAccessError}.
 *
 * <p>The procedures are written in their old shapes, transformed, and run against stand-ins with
 * the 26.3 shape of the classes they call.
 */
class LegacyProcedureMemberBridgesTest {

    private static final Path MC_26_3 = Path.of("test-jars-mixin/minecraft-26.3-client.jar");

    private static final String PROCEDURES = "mod/Procedures";
    private static final String OWN_HURT_MOB = "mod/OwnHurtMob";
    private static final String OWN_DROP_PLAYER = "mod/OwnDropPlayer";
    private static final String OWN_THROW_MOB = "mod/OwnThrowMob";
    private static final String OWN_HURT_CLIENT_MOB = "mod/OwnHurtClientMob";
    private static final String OWN_PROPERTY = "mod/OwnProperty";

    private static final String STACK = LegacyCommandSourceBridge.STACK;
    private static final String SERVER_LEVEL = "net/minecraft/server/level/ServerLevel";
    private static final String SERVER_PLAYER = "net/minecraft/server/level/ServerPlayer";
    private static final String ENTITY = "net/minecraft/world/entity/Entity";
    private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
    private static final String PLAYER = Legacy263MemberBridge.PLAYER;
    private static final String INVENTORY = Legacy263MemberBridge.INVENTORY;
    private static final String ITEM_STACK = "net/minecraft/world/item/ItemStack";
    private static final String ITEM_ENTITY = "net/minecraft/world/entity/item/ItemEntity";
    private static final String DAMAGE_SOURCE = Legacy1212MemberBridge.DAMAGE_SOURCE;
    private static final String PROPERTY = Legacy1212MemberBridge.PROPERTY;
    private static final String INTEGER_PROPERTY = "net/minecraft/world/level/block/state/properties/IntegerProperty";
    private static final String MANAGER = LegacyAdvancementBridge.MANAGER;
    private static final String PLAYER_ADVANCEMENTS = LegacyAdvancementBridge.PLAYER_ADVANCEMENTS;
    private static final String ADVANCEMENT = LegacyAdvancementBridge.ADVANCEMENT;
    private static final String PROGRESS = LegacyAdvancementBridge.PROGRESS;
    private static final String IDENTIFIER = LegacyAdvancementBridge.IDENTIFIER;
    private static final String COMPONENT = LegacyCommandSourceBridge.COMPONENT;

    @TempDir
    static Path stubDir;

    /** The 26.3-shaped stand-ins, by binary name. */
    private static Map<String, byte[]> host;

    private RetromodTransformer transformer;

    @BeforeAll
    static void compileHost() throws Exception {
        host = compile(HostSources.SOURCES);
    }

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyCommandSourceBridge.registerRedirects(transformer, LegacyCommandSourceBridge.HostShape.ENTITY_NAMED);
        Legacy263MemberBridge.registerRedirects(transformer, new Legacy263MemberBridge.Members(true, true, true, false));
        Legacy1212MemberBridge.registerRedirects(transformer, new Legacy1212MemberBridge.Members(false, false, true, true));
        // Only the stack's hurt: the helper's other members name classes these stand-ins leave out.
        Legacy1205MemberBridge.registerRedirects(transformer, Legacy1205MemberBridge.MEMBERS.stream()
                        .filter(member -> member.desc().equals(Legacy1205MemberBridge.ITEM_HURT)).toList(),
                List.of(), false, Legacy1205MemberBridge.HostShape.AT_26_3);
        LegacyAdvancementBridge.registerRedirects(transformer, IDENTIFIER);
        LegacyPlayerServerBridge.registerRedirects(transformer);
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    // --- CommandSourceStack -------------------------------------------------------------------

    @Test
    @DisplayName("an old command source without an entity keeps its display name and permission level on 26.3")
    void commandSourceWithoutEntity() throws Throwable {
        Run run = new Run();
        Object level = run.newInstance(SERVER_LEVEL);
        Object stack = run.call("sound", level, null);

        assertEquals("constant", run.field(stack, "shape"), "the 26.3 constructor that takes a display name");
        assertEquals("Shown", run.field(run.field(stack, "displayName"), "text"));
        assertSame(level, run.field(stack, "level"));
        Object permissions = run.field(stack, "permissions");
        Method permissionLevel = run.loadClass(LegacyCommandSourceBridge.LEVEL_PERMISSIONS.replace('/', '.')).getMethod("level");
        assertEquals("OWNERS", permissionLevel.invoke(permissions).toString(),
                "level 4 is the owner level, as the host's own lookup maps it");
    }

    @Test
    @DisplayName("an old command source with an entity takes the entity's names on 26.3")
    void commandSourceWithEntity() throws Throwable {
        Run run = new Run();
        Object entity = run.newInstance(ENTITY);
        Object stack = run.call("sound", run.newInstance(SERVER_LEVEL), entity);

        assertEquals("entity", run.field(stack, "shape"));
        assertSame(entity, run.field(stack, "entity"));
    }

    @Test
    @DisplayName("withPermission(int) and withMaximumPermission(int) pass the level's permission set")
    void permissionLevelCalls() throws Throwable {
        Run run = new Run();
        Object stack = run.call("sound", run.newInstance(SERVER_LEVEL), null);
        Method permissionLevel = run.loadClass(LegacyCommandSourceBridge.LEVEL_PERMISSIONS.replace('/', '.'))
                .getMethod("level");
        Object raised = run.call("raise", stack);
        assertEquals("GAMEMASTERS", permissionLevel.invoke(run.field(raised, "permissions")).toString());
        Object capped = run.call("atLeast", stack);
        assertEquals("ADMINS", permissionLevel.invoke(run.field(capped, "permissions")).toString());
        assertEquals(List.of(LegacyCommandSourceBridge.HAS_PERMISSION_BRIDGE + ".hasPermission(Ljava/lang/Object;I)Z"),
                callsTo(transformProcedures(), "allowed", "hasPermission"),
                "hasPermission(int) goes to the bridge the 26.1 shims already use");
    }

    @Test
    @DisplayName("a 1.21.11 to 26.2 host keeps both names and only gets the permission set")
    void commandSourceOnANamedHost() {
        transformer.clearRedirectsForTesting();
        LegacyCommandSourceBridge.registerRedirects(transformer, LegacyCommandSourceBridge.HostShape.PERMISSION_SET_NAMED);
        assertEquals(List.of(LegacyCommandSourceBridge.HELPER + ".create" + LegacyCommandSourceBridge.factoryDesc(
                LegacyCommandSourceBridge.INT_FORM)), callsTo(transformProcedures(), "sound", "create"));

        List<String> built = calls(method(LegacyCommandSourceBridge.generateHelper(
                LegacyCommandSourceBridge.HostShape.PERMISSION_SET_NAMED), "create"));
        assertTrue(built.contains(STACK + ".<init>" + LegacyCommandSourceBridge.SET_FORM), built.toString());
        assertFalse(built.toString().contains(LegacyCommandSourceBridge.ENTITY_NAMED), built.toString());
    }

    @Test
    @DisplayName("each host gets only the constructor forms it lost")
    void commandSourceShapes() {
        ClassNode intHost = classWithConstructors(LegacyCommandSourceBridge.INT_FORM);
        ClassNode permissions = permissionsWithForLevel();
        assertEquals(LegacyCommandSourceBridge.HostShape.INT_LEVEL,
                LegacyCommandSourceBridge.shapeOf(intHost, permissions));
        assertEquals(LegacyCommandSourceBridge.HostShape.PERMISSION_SET_NAMED,
                LegacyCommandSourceBridge.shapeOf(classWithConstructors(LegacyCommandSourceBridge.SET_FORM), permissions));
        assertEquals(LegacyCommandSourceBridge.HostShape.ENTITY_NAMED, LegacyCommandSourceBridge.shapeOf(
                classWithConstructors(LegacyCommandSourceBridge.ENTITY_NAMED, LegacyCommandSourceBridge.CONSTANT_NAMED),
                permissions));
        assertEquals(LegacyCommandSourceBridge.HostShape.UNKNOWN, LegacyCommandSourceBridge.shapeOf(
                classWithConstructors(LegacyCommandSourceBridge.ENTITY_NAMED), permissions));
    }

    // --- Inventory and Player (26.3) ----------------------------------------------------------

    @Test
    @DisplayName("clearOrCountMatchingItems only counts when the old maximum was 0")
    void clearOrCountSetsTheCountFlag() throws Throwable {
        Run run = new Run();
        Object inventory = run.newInstance(INVENTORY);
        Predicate<Object> any = item -> true;

        assertEquals(7, run.call("count", inventory, any));
        assertEquals(Arrays.asList(any, true, 0, null), Arrays.asList((Object[]) run.field(inventory, "lastClear")));
        assertEquals(7, run.call("clear", inventory, any, 3));
        assertEquals(Arrays.asList(any, false, 3, null), Arrays.asList((Object[]) run.field(inventory, "lastClear")));
    }

    @Test
    @DisplayName("Player.drop(stack, retainOwnership) drops through the 26.3 form as a predicted drop")
    void dropIsPredicted() throws Throwable {
        Run run = new Run();
        Object player = run.newInstance(SERVER_PLAYER);
        Object stack = run.stack("apple", 3);

        run.call("dropBack", player, stack);
        Object[] drop = (Object[]) run.field(player, "lastDrop");
        assertSame(stack, drop[0]);
        assertEquals(false, drop[1]);
        assertEquals("PREDICTED", drop[2].toString());
    }

    @Test
    @DisplayName("a player's own override of the old drop still runs when a mod drops through Player")
    void ownDropOverrideRuns() throws Throwable {
        Run run = new Run();
        Object player = run.newInstance(OWN_DROP_PLAYER);
        run.call("dropBack", player, run.stack("apple", 1));

        assertEquals(true, run.field(player, "dropped"), "the override ran");
        assertEquals("PREDICTED", ((Object[]) run.field(player, "lastDrop"))[2].toString(),
                "its super call reached the host's drop");
    }

    @Test
    @DisplayName("LivingEntity.drop(stack, true, false) throws the item at random, as the host's own random drop does")
    void throwingDropThrowsAtRandom() throws Throwable {
        Run run = new Run();
        Object entity = run.newInstance(LIVING);
        Object level = run.onServer(entity);
        Object stack = run.stack("apple", 3);

        Object item = run.call("throwAway", entity, stack);
        assertNotNull(item, "the server spawned the item");
        assertSame(stack, run.field(item, "stack"));
        assertEquals(true, run.field(item, "thrownRandomly"));
        assertEquals(false, run.field(item, "retained"));
        assertEquals(List.of(item), run.field(level, "added"));
        assertNull(run.field(entity, "lastDrop"), "a random throw does not swing, as the old drop did not on the server");
    }

    @Test
    @DisplayName("Player.drop(stack, false, true) is the host's drop, which keeps the player's toss event and statistics")
    void keptDropIsTheHostsDrop() throws Throwable {
        Run run = new Run();
        Object player = run.newInstance(SERVER_PLAYER);
        Object level = run.onServer(player);
        Object stack = run.stack("apple", 1);

        run.call("dropKept", player, stack);
        assertArrayEquals(new Object[] {stack, true, run.predicted()}, (Object[]) run.field(player, "lastDrop"));
        assertEquals(true, run.field(player, "tossed"), "the drop went through the player's own drop");
        assertEquals(List.of(), run.field(level, "added"));
    }

    @Test
    @DisplayName("on the client a random drop is the host's drop, which spawns nothing")
    void clientThrowingDropSpawnsNothing() throws Throwable {
        Run run = new Run();
        Object entity = run.newInstance(LIVING);
        Object level = run.onServer(entity);
        run.setField(level, "clientSide", true);
        Object stack = run.stack("apple", 1);

        assertNull(run.call("throwAway", entity, stack));
        assertArrayEquals(new Object[] {stack, false, run.predicted()}, (Object[]) run.field(entity, "lastDrop"));
        assertEquals(List.of(), run.field(level, "added"));
    }

    @Test
    @DisplayName("on a host that captures drops, such as NeoForge, a random drop goes to the entity's captured drops")
    void throwingDropHonorsCapturedDrops() throws Throwable {
        transformer.clearRedirectsForTesting();
        Legacy263MemberBridge.registerRedirects(transformer, new Legacy263MemberBridge.Members(false, false, true, true));
        Run run = new Run();
        Object entity = run.newInstance(LIVING);
        Object level = run.onServer(entity);
        List<Object> captured = new ArrayList<>();
        run.setField(entity, "captured", captured);

        Object item = run.call("throwAway", entity, run.stack("apple", 1));
        assertEquals(List.of(item), captured);
        assertEquals(List.of(), run.field(level, "added"));

        run.setField(entity, "captured", null);
        Object spawned = run.call("throwAway", entity, run.stack("apple", 1));
        assertEquals(List.of(spawned), run.field(level, "added"), "without a capture the item is spawned");
    }

    @Test
    @DisplayName("an entity's own override of the three-argument drop still runs, and its super call reaches the host")
    void ownThrowingDropOverrideRuns() throws Throwable {
        Run run = new Run();
        Object mob = run.newInstance(OWN_THROW_MOB);
        Object level = run.onServer(mob);

        Object item = run.call("throwAway", mob, run.stack("apple", 1));
        assertEquals(true, run.field(mob, "threw"), "the override ran");
        assertEquals(true, run.field(item, "thrownRandomly"), "its super call threw the item at random");
        assertEquals(List.of(item), run.field(level, "added"));
    }

    @Test
    @DisplayName("each host gets the three-argument drop bridge only once it lost the method")
    void throwingDropShapes() {
        ClassNode prediction = new ClassNode();
        prediction.fields.add(new org.objectweb.asm.tree.FieldNode(ACC_PUBLIC | ACC_STATIC | ACC_FINAL,
                "PREDICTED", "L" + Legacy263MemberBridge.PREDICTION + ";", null, null));
        ClassNode player = new ClassNode();
        ClassNode before = classWithMethods(LIVING, "drop" + Legacy263MemberBridge.THROW_OLD,
                "createItemStackToDrop" + Legacy263MemberBridge.CREATE_TO_DROP);
        ClassNode after = classWithMethods(LIVING, "drop" + Legacy263MemberBridge.DROP_NEW,
                "createItemStackToDrop" + Legacy263MemberBridge.CREATE_TO_DROP);
        assertFalse(Legacy263MemberBridge.lostThrowingDrop(player, before, prediction), "26.2 keeps the old drop");
        assertTrue(Legacy263MemberBridge.lostThrowingDrop(player, after, prediction));
        assertTrue(Legacy263MemberBridge.capturesDrops(classWithMethods(ENTITY, "captureDrops()Ljava/util/Collection;")));
    }

    // --- 1.21.2: hurt and getPossibleValues ---------------------------------------------------

    @Test
    @DisplayName("LivingEntity.hurt(source, amount) is hurtOrSimulate, and an entity's own hurt still runs")
    void hurtIsHurtOrSimulate() throws Throwable {
        Run run = new Run();
        Object living = run.newInstance(LIVING);
        Object source = run.newInstance(DAMAGE_SOURCE);
        assertEquals(true, run.call("prick", living, source));
        assertArrayEquals(new Object[] {source, 2f}, (Object[]) run.field(living, "lastHurt"));

        Object mob = run.newInstance(OWN_HURT_MOB);
        assertEquals(true, run.call("prick", mob, source));
        assertEquals(true, run.field(mob, "ran"), "the mob's own hurt ran");
        assertArrayEquals(new Object[] {source, 2f}, (Object[]) run.field(mob, "lastHurt"),
                "its super call reached hurtOrSimulate");
    }

    @Test
    @DisplayName("a Fabric mob's own old hurt runs when a mod calls it through LivingEntity on 26.x")
    void fabricLivingEntityHurtRunsTheOverride() throws Throwable {
        // The 1.21.11 to 26.1 shims register this bridge after the 1.21.2 one, as a game does.
        IsOverloadBridgeSynthetic.register(transformer);
        Run run = new Run();
        Object source = run.newInstance(DAMAGE_SOURCE);
        Object living = run.newInstance(LIVING);
        assertEquals(true, run.call("prickClient", living, source));
        assertArrayEquals(new Object[] {source, 2f}, (Object[]) run.field(living, "lastHurt"));

        Object mob = run.newInstance(OWN_HURT_CLIENT_MOB);
        assertEquals(true, run.call("prickClient", mob, source));
        assertEquals(true, run.field(mob, "ran"), "the mob's own hurt ran");
        assertArrayEquals(new Object[] {source, 2f}, (Object[]) run.field(mob, "lastHurt"),
                "its super call reached hurtOrSimulate once");
    }

    @Test
    @DisplayName("Property.getPossibleValues() on IntegerProperty returns the host's list")
    void possibleValuesOnASubclass() throws Throwable {
        Run run = new Run();
        assertEquals(List.of(0, 1, 2, 3), run.call("values", run.newInstance(INTEGER_PROPERTY)));
    }

    @Test
    @DisplayName("calls that link to a mod class's own old method are left alone")
    void ownDeclarationsKeepTheirCalls() {
        Map<String, byte[]> mod = modClasses();
        try (var scope = transformer.pushJarClassBytesProvider(classBytes(mod)::get)) {
            byte[] mob = transformer.transformClass(mod.get(OWN_HURT_MOB), OWN_HURT_MOB + ".class");
            assertEquals(List.of(OWN_HURT_MOB + ".hurt(L" + DAMAGE_SOURCE + ";F)Z"), callsTo(mob, "selfHurt", "hurt"));
            byte[] property = transformer.transformClass(mod.get(OWN_PROPERTY), OWN_PROPERTY + ".class");
            assertEquals(List.of(OWN_PROPERTY + ".getPossibleValues()Ljava/util/Collection;"),
                    callsTo(property, "selfValues", "getPossibleValues"));
            byte[] player = transformer.transformClass(mod.get(OWN_DROP_PLAYER), OWN_DROP_PLAYER + ".class");
            assertEquals(List.of(OWN_DROP_PLAYER + ".drop(L" + ITEM_STACK + ";Z)L" + ITEM_ENTITY + ";"),
                    callsTo(player, "selfDrop", "drop"));
            byte[] thrower = transformer.transformClass(mod.get(OWN_THROW_MOB), OWN_THROW_MOB + ".class");
            assertEquals(List.of(OWN_THROW_MOB + ".drop" + Legacy263MemberBridge.THROW_OLD),
                    callsTo(thrower, "selfThrow", "drop"));
        }
    }

    // --- 1.20.5: ItemStack.hurt ---------------------------------------------------------------

    @Test
    @DisplayName("ItemStack.hurt answers whether the stack broke and leaves the broken stack to the caller")
    void itemHurtLeavesTheBrokenStack() throws Throwable {
        Run run = new Run();
        Object player = run.newInstance(SERVER_PLAYER);
        run.setField(player, "level", run.newInstance(SERVER_LEVEL));

        Object worn = run.stack("cactus_slice", 1);
        run.setField(worn, "damage", 3);
        assertEquals(false, run.call("wear", worn, player));
        assertEquals(4, run.field(worn, "damage"));

        Object breaking = run.stack("cactus_slice", 1);
        run.setField(breaking, "damage", 9);
        assertEquals(true, run.call("wear", breaking, player));
        assertEquals(1, run.field(breaking, "count"), "the old method left the broken stack for the caller");
        assertEquals(10, run.field(breaking, "damage"));
    }

    @Test
    @DisplayName("ItemStack.hurt without a player damages by the full amount; a creative player's stack is spared")
    void itemHurtWithoutPlayerOrInCreative() throws Throwable {
        Run run = new Run();
        Object stack = run.stack("cactus_slice", 1);
        run.setField(stack, "damage", 9);
        assertEquals(true, run.call("wear", stack, null));
        assertEquals(10, run.field(stack, "damage"));
        assertEquals(1, run.field(stack, "count"));

        Object creative = run.newInstance(SERVER_PLAYER);
        run.setField(creative, "level", run.newInstance(SERVER_LEVEL));
        run.setField(creative, "creative", true);
        Object spared = run.stack("cactus_slice", 1);
        assertEquals(false, run.call("wear", spared, creative));
        assertEquals(0, run.field(spared, "damage"));
    }

    @Test
    @DisplayName("a 1.20.5 host reaches the random form, which already leaves the stack alone")
    void itemHurtOnA1205Host() {
        byte[] helper = Legacy1205MemberBridge.generateHelper(Legacy1205MemberBridge.MEMBERS,
                Legacy1205MemberBridge.HostShape.AT_1_20_5);
        List<String> calls = calls(method(helper, "hurt"));
        assertTrue(calls.contains(ITEM_STACK + ".hurtAndBreak" + Legacy1205MemberBridge.HURT_AND_BREAK_RANDOM), calls.toString());
        assertFalse(calls.toString().contains(".grow("), calls.toString());
    }

    // --- 1.20.2: advancements -----------------------------------------------------------------

    @Test
    @DisplayName("the old advancement lookup, progress and award reach the holder")
    void advancementCallsReachTheHolder() throws Throwable {
        Run run = new Run();
        Object manager = run.newInstance(MANAGER);
        Object tea = run.addAdvancement(manager, "s33r_more_food:tea_time");
        Object other = run.addAdvancement(manager, "s33r_more_food:other");
        Object advancements = run.newInstance(PLAYER_ADVANCEMENTS);

        assertEquals(true, run.call("tea", manager, advancements, run.id("s33r_more_food:tea_time")));
        assertEquals(List.of("s33r_more_food:tea_time/drank"), run.field(advancements, "awarded"));

        // The last lookup was another advancement, so this one is found through the manager.
        run.call("lookup", manager, run.id("s33r_more_food:other"));
        assertEquals(true, run.call("award", advancements, run.call("valueOf", tea), "again"));
        assertEquals(List.of("s33r_more_food:tea_time/drank", "s33r_more_food:tea_time/again"),
                run.field(advancements, "awarded"));
        assertNotNull(other);
    }

    @Test
    @DisplayName("ServerPlayer.server, private since 1.21.6, is read through the player's level")
    void playerServerIsReadThroughTheLevel() throws Throwable {
        Run run = new Run();
        Object player = run.newInstance(SERVER_PLAYER);
        Object level = run.newInstance(SERVER_LEVEL);
        run.setField(player, "level", level);
        assertSame(run.field(level, "server"), run.call("serverOf", player));
    }

    @Test
    @DisplayName("an advancement the server does not have gets empty progress and is never awarded")
    void unknownAdvancementIsNotAwarded() throws Throwable {
        Run run = new Run();
        Object manager = run.newInstance(MANAGER);
        Object advancements = run.newInstance(PLAYER_ADVANCEMENTS);

        assertEquals(false, run.call("tea", manager, advancements, run.id("s33r_more_food:missing")));
        assertEquals(List.of(), run.field(advancements, "awarded"));
    }

    // --- the real 26.3 host -------------------------------------------------------------------

    @Test
    @DisplayName("the real 26.3 jar has the shapes these bridges are registered for")
    void real263Shapes() throws Throwable {
        Assumptions.assumeTrue(Files.isRegularFile(MC_26_3), "26.3 client jar present");
        try (ZipFile jar = new ZipFile(MC_26_3.toFile())) {
            assertEquals(LegacyCommandSourceBridge.HostShape.ENTITY_NAMED, LegacyCommandSourceBridge.shapeOf(
                    read(jar, STACK), read(jar, LegacyCommandSourceBridge.LEVEL_PERMISSIONS)));
            assertTrue(Legacy263MemberBridge.clearTakesCountFlag(read(jar, INVENTORY)));
            assertTrue(Legacy263MemberBridge.dropTakesPrediction(read(jar, PLAYER), read(jar, LIVING),
                    read(jar, Legacy263MemberBridge.PREDICTION)));
            assertTrue(Legacy263MemberBridge.lostThrowingDrop(read(jar, PLAYER), read(jar, LIVING),
                    read(jar, Legacy263MemberBridge.PREDICTION)));
            assertFalse(Legacy263MemberBridge.capturesDrops(read(jar, ENTITY)), "vanilla does not capture drops");
            assertTrue(Legacy1212MemberBridge.replacedIn(read(jar, ENTITY), "hurt", Legacy1212MemberBridge.HURT_OLD,
                    "hurtOrSimulate", Legacy1212MemberBridge.HURT_OLD));
            assertTrue(Legacy1212MemberBridge.replacedIn(read(jar, PROPERTY), "getPossibleValues",
                    Legacy1212MemberBridge.VALUES_OLD, "getPossibleValues", Legacy1212MemberBridge.VALUES_NEW));
            assertEquals(IDENTIFIER, LegacyAdvancementBridge.idClassOf(read(jar, MANAGER)));
            assertTrue(LegacyPlayerServerBridge.fieldHidden(read(jar, SERVER_PLAYER)));
            ClassNode stack = read(jar, ITEM_STACK);
            assertTrue(stack.methods.stream().anyMatch(m -> m.name.equals("hurtAndBreak")
                    && m.desc.equals(Legacy1205MemberBridge.HURT_AND_BREAK_LEVEL)));
            assertTrue(stack.methods.stream().noneMatch(m -> m.name.equals("hurt")));
        }
    }

    @Test
    @DisplayName("every generated helper passes the stack verifier")
    void helpersVerify() throws Throwable {
        for (LegacyCommandSourceBridge.HostShape shape : List.of(LegacyCommandSourceBridge.HostShape.PERMISSION_SET_NAMED,
                LegacyCommandSourceBridge.HostShape.ENTITY_NAMED)) {
            verify(LegacyCommandSourceBridge.generateHelper(shape));
            assertEquals(List.of(STACK + ".withPermission" + LegacyCommandSourceBridge.WITH_LEVEL_NEW),
                    callsTo(LegacyCommandSourceBridge.generateHelper(shape), "withPermission", "withPermission"));
        }
        verify(Legacy263MemberBridge.generateHelper(new Legacy263MemberBridge.Members(true, true, true, false)));
        verify(Legacy263MemberBridge.generateHelper(new Legacy263MemberBridge.Members(true, true, true, true)));
        verify(IsOverloadBridgeSynthetic.generate());
        verify(Legacy1212MemberBridge.generateHelper(new Legacy1212MemberBridge.Members(true, true, true, true)));
        verify(Legacy1205MemberBridge.generateHelper(Legacy1205MemberBridge.MEMBERS, Legacy1205MemberBridge.HostShape.AT_1_20_5));
        verify(Legacy1205MemberBridge.generateBreakFlag());
        verify(LegacyAdvancementBridge.generateHelper(LegacyAdvancementBridge.RESOURCE_LOCATION));
        verify(LegacyPlayerServerBridge.generateHelper());
    }

    // --- running the transformed procedures ---------------------------------------------------

    /** The transformed mod classes, the helpers and the stand-ins in one loader. */
    private final class Run extends ClassLoader {
        private final Map<String, byte[]> classes = new HashMap<>(host);
        private final Class<?> procedures;

        Run() throws Exception {
            super(LegacyProcedureMemberBridgesTest.class.getClassLoader());
            Map<String, byte[]> mod = modClasses();
            try (var scope = transformer.pushJarClassBytesProvider(classBytes(mod)::get)) {
                for (Map.Entry<String, byte[]> modClass : mod.entrySet()) {
                    classes.put(modClass.getKey().replace('/', '.'),
                            transformer.transformClass(modClass.getValue(), modClass.getKey() + ".class"));
                }
            }
            transformer.getSyntheticClasses().forEach((name, bytes) -> classes.put(name.replace('/', '.'), bytes));
            // The stand-ins leave out the block, item and holder classes the bridge's other
            // helpers name, which the verifier would load, so only its damage helpers are kept.
            classes.computeIfPresent(IsOverloadBridgeSynthetic.INTERNAL.replace('/', '.'),
                    (name, bytes) -> keepMethods(bytes, method -> method.contains("HurtClient")));
            procedures = loadClass(PROCEDURES.replace('/', '.'));
        }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) return loaded;
            byte[] bytes = classes.get(name);
            if (bytes == null) return super.loadClass(name, resolve);
            return defineClass(name, bytes, 0, bytes.length);
        }

        Object call(String name, Object... args) throws Throwable {
            for (Method method : procedures.getMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == args.length) {
                    try {
                        return method.invoke(null, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                }
            }
            throw new AssertionError("no procedure " + name);
        }

        Object newInstance(String internalName) throws Exception {
            return loadClass(internalName.replace('/', '.')).getConstructor().newInstance();
        }

        Object stack(String item, int count) throws Exception {
            return loadClass(ITEM_STACK.replace('/', '.')).getConstructor(String.class, int.class)
                    .newInstance(item, count);
        }

        Object id(String id) throws Exception {
            return loadClass(IDENTIFIER.replace('/', '.')).getConstructor(String.class).newInstance(id);
        }

        Object addAdvancement(Object manager, String id) throws Exception {
            return manager.getClass().getMethod("add", String.class).invoke(manager, id);
        }

        Object field(Object owner, String name) throws Exception {
            return owner.getClass().getField(name).get(owner);
        }

        /** Puts the entity in a fresh server level and returns the level. */
        Object onServer(Object entity) throws Exception {
            Object level = newInstance(SERVER_LEVEL);
            setField(entity, "level", level);
            return level;
        }

        Object predicted() throws Exception {
            return loadClass(Legacy263MemberBridge.PREDICTION.replace('/', '.')).getField("PREDICTED").get(null);
        }

        void setField(Object owner, String name, Object value) throws Exception {
            owner.getClass().getField(name).set(owner, value);
        }
    }

    private byte[] transformProcedures() {
        Map<String, byte[]> mod = modClasses();
        try (var scope = transformer.pushJarClassBytesProvider(classBytes(mod)::get)) {
            return transformer.transformClass(mod.get(PROCEDURES), PROCEDURES + ".class");
        }
    }

    /** The mod classes, and the stand-ins they extend, so the hierarchy can be proven. */
    private static Map<String, byte[]> classBytes(Map<String, byte[]> mod) {
        Map<String, byte[]> all = new HashMap<>(mod);
        host.forEach((name, bytes) -> all.put(name.replace('.', '/'), bytes));
        return all;
    }

    // --- the mod under test, in its old shapes -------------------------------------------------

    private static Map<String, byte[]> modClasses() {
        Map<String, byte[]> mod = new HashMap<>();
        mod.put(PROCEDURES, procedures());
        mod.put(OWN_HURT_MOB, ownHurtMob());
        mod.put(OWN_DROP_PLAYER, ownDropPlayer());
        mod.put(OWN_THROW_MOB, ownThrowMob());
        mod.put(OWN_HURT_CLIENT_MOB, ownHurtClientMob());
        mod.put(OWN_PROPERTY, ownProperty());
        return mod;
    }

    private static byte[] procedures() {
        ClassWriter cw = writer();
        cw.visit(V17, ACC_PUBLIC, PROCEDURES, null, "java/lang/Object", null);

        // new CommandSourceStack(null, null, null, level, 4, "Shown", Component.literal("Shown"), null, entity)
        MethodVisitor sound = begin(cw, "sound", "(L" + SERVER_LEVEL + ";L" + ENTITY + ";)L" + STACK + ";");
        sound.visitTypeInsn(NEW, STACK);
        sound.visitInsn(DUP);
        sound.visitInsn(ACONST_NULL);
        sound.visitInsn(ACONST_NULL);
        sound.visitInsn(ACONST_NULL);
        sound.visitVarInsn(ALOAD, 0);
        sound.visitInsn(ICONST_4);
        sound.visitLdcInsn("Shown");
        sound.visitLdcInsn("Shown");
        sound.visitMethodInsn(INVOKESTATIC, COMPONENT, "literal", "(Ljava/lang/String;)L" + COMPONENT + ";", true);
        sound.visitInsn(ACONST_NULL);
        sound.visitVarInsn(ALOAD, 1);
        sound.visitMethodInsn(INVOKESPECIAL, STACK, "<init>", LegacyCommandSourceBridge.INT_FORM, false);
        sound.visitInsn(ARETURN);
        end(sound);

        String withLevel = "(L" + STACK + ";)L" + STACK + ";";
        MethodVisitor raise = begin(cw, "raise", withLevel);
        raise.visitVarInsn(ALOAD, 0);
        raise.visitInsn(ICONST_2);
        raise.visitMethodInsn(INVOKEVIRTUAL, STACK, "withPermission", LegacyCommandSourceBridge.WITH_LEVEL_OLD, false);
        raise.visitInsn(ARETURN);
        end(raise);
        MethodVisitor atLeast = begin(cw, "atLeast", withLevel);
        atLeast.visitVarInsn(ALOAD, 0);
        atLeast.visitInsn(ICONST_3);
        atLeast.visitMethodInsn(INVOKEVIRTUAL, STACK, "withMaximumPermission", LegacyCommandSourceBridge.WITH_LEVEL_OLD, false);
        atLeast.visitInsn(ARETURN);
        end(atLeast);
        MethodVisitor allowed = begin(cw, "allowed", "(L" + STACK + ";)Z");
        allowed.visitVarInsn(ALOAD, 0);
        allowed.visitInsn(ICONST_2);
        allowed.visitMethodInsn(INVOKEVIRTUAL, STACK, "hasPermission", "(I)Z", false);
        allowed.visitInsn(IRETURN);
        end(allowed);

        MethodVisitor count = begin(cw, "count", "(L" + INVENTORY + ";Ljava/util/function/Predicate;)I");
        count.visitVarInsn(ALOAD, 0);
        count.visitVarInsn(ALOAD, 1);
        count.visitInsn(ICONST_0);
        count.visitInsn(ACONST_NULL);
        count.visitMethodInsn(INVOKEVIRTUAL, INVENTORY, "clearOrCountMatchingItems", Legacy263MemberBridge.CLEAR_OLD, false);
        count.visitInsn(IRETURN);
        end(count);

        MethodVisitor clear = begin(cw, "clear", "(L" + INVENTORY + ";Ljava/util/function/Predicate;I)I");
        clear.visitVarInsn(ALOAD, 0);
        clear.visitVarInsn(ALOAD, 1);
        clear.visitVarInsn(ILOAD, 2);
        clear.visitInsn(ACONST_NULL);
        clear.visitMethodInsn(INVOKEVIRTUAL, INVENTORY, "clearOrCountMatchingItems", Legacy263MemberBridge.CLEAR_OLD, false);
        clear.visitInsn(IRETURN);
        end(clear);

        // Called on a ServerPlayer, so only the proven hierarchy reaches Player.drop.
        MethodVisitor drop = begin(cw, "dropBack", "(L" + SERVER_PLAYER + ";L" + ITEM_STACK + ";)V");
        drop.visitVarInsn(ALOAD, 0);
        drop.visitVarInsn(ALOAD, 1);
        drop.visitInsn(ICONST_0);
        drop.visitMethodInsn(INVOKEVIRTUAL, SERVER_PLAYER, "drop", Legacy263MemberBridge.DROP_OLD, false);
        drop.visitInsn(POP);
        drop.visitInsn(RETURN);
        end(drop);

        MethodVisitor prick = begin(cw, "prick", "(L" + LIVING + ";L" + DAMAGE_SOURCE + ";)Z");
        prick.visitVarInsn(ALOAD, 0);
        prick.visitVarInsn(ALOAD, 1);
        prick.visitLdcInsn(2f);
        prick.visitMethodInsn(INVOKEVIRTUAL, LIVING, "hurt", Legacy1212MemberBridge.HURT_OLD, false);
        prick.visitInsn(IRETURN);
        end(prick);

        // The old combined hurt as a 1.21.1 Fabric mod's call reads after the 26.x name table.
        MethodVisitor prickClient = begin(cw, "prickClient", "(L" + LIVING + ";L" + DAMAGE_SOURCE + ";)Z");
        prickClient.visitVarInsn(ALOAD, 0);
        prickClient.visitVarInsn(ALOAD, 1);
        prickClient.visitLdcInsn(2f);
        prickClient.visitMethodInsn(INVOKEVIRTUAL, LIVING, "hurtClient", Legacy1212MemberBridge.HURT_OLD, false);
        prickClient.visitInsn(IRETURN);
        end(prickClient);

        // drop(stack, throwRandomly, retainOwnership), named after LivingEntity as 1.21.11 to 26.2
        // declare it, and after Player as 1.21.1 and older do.
        MethodVisitor throwAway = begin(cw, "throwAway", "(L" + LIVING + ";L" + ITEM_STACK + ";)L" + ITEM_ENTITY + ";");
        throwAway.visitVarInsn(ALOAD, 0);
        throwAway.visitVarInsn(ALOAD, 1);
        throwAway.visitInsn(ICONST_1);
        throwAway.visitInsn(ICONST_0);
        throwAway.visitMethodInsn(INVOKEVIRTUAL, LIVING, "drop", Legacy263MemberBridge.THROW_OLD, false);
        throwAway.visitInsn(ARETURN);
        end(throwAway);
        MethodVisitor dropKept = begin(cw, "dropKept", "(L" + SERVER_PLAYER + ";L" + ITEM_STACK + ";)L" + ITEM_ENTITY + ";");
        dropKept.visitVarInsn(ALOAD, 0);
        dropKept.visitVarInsn(ALOAD, 1);
        dropKept.visitInsn(ICONST_0);
        dropKept.visitInsn(ICONST_1);
        dropKept.visitMethodInsn(INVOKEVIRTUAL, PLAYER, "drop", Legacy263MemberBridge.THROW_OLD, false);
        dropKept.visitInsn(ARETURN);
        end(dropKept);

        MethodVisitor values = begin(cw, "values", "(L" + INTEGER_PROPERTY + ";)Ljava/util/Collection;");
        values.visitVarInsn(ALOAD, 0);
        values.visitMethodInsn(INVOKEVIRTUAL, INTEGER_PROPERTY, "getPossibleValues", Legacy1212MemberBridge.VALUES_OLD, false);
        values.visitInsn(ARETURN);
        end(values);

        MethodVisitor wear = begin(cw, "wear", "(L" + ITEM_STACK + ";L" + SERVER_PLAYER + ";)Z");
        wear.visitVarInsn(ALOAD, 0);
        wear.visitInsn(ICONST_1);
        wear.visitInsn(ACONST_NULL);
        wear.visitVarInsn(ALOAD, 1);
        wear.visitMethodInsn(INVOKEVIRTUAL, ITEM_STACK, "hurt", Legacy1205MemberBridge.ITEM_HURT, false);
        wear.visitInsn(IRETURN);
        end(wear);

        emitTea(cw);
        String server = LegacyPlayerServerBridge.SERVER;
        MethodVisitor serverOf = begin(cw, "serverOf", "(L" + SERVER_PLAYER + ";)L" + server + ";");
        serverOf.visitVarInsn(ALOAD, 0);
        serverOf.visitFieldInsn(GETFIELD, SERVER_PLAYER, "server", "L" + server + ";");
        serverOf.visitInsn(ARETURN);
        end(serverOf);
        String lookupDesc = "(L" + MANAGER + ";L" + IDENTIFIER + ";)L" + ADVANCEMENT + ";";
        MethodVisitor lookup = begin(cw, "lookup", lookupDesc);
        lookup.visitVarInsn(ALOAD, 0);
        lookup.visitVarInsn(ALOAD, 1);
        lookup.visitMethodInsn(INVOKEVIRTUAL, MANAGER, "getAdvancement", "(L" + IDENTIFIER + ";)L" + ADVANCEMENT + ";", false);
        lookup.visitInsn(ARETURN);
        end(lookup);
        MethodVisitor award = begin(cw, "award", "(L" + PLAYER_ADVANCEMENTS + ";L" + ADVANCEMENT + ";Ljava/lang/String;)Z");
        award.visitVarInsn(ALOAD, 0);
        award.visitVarInsn(ALOAD, 1);
        award.visitVarInsn(ALOAD, 2);
        award.visitMethodInsn(INVOKEVIRTUAL, PLAYER_ADVANCEMENTS, "award", LegacyAdvancementBridge.CRITERION_OLD, false);
        award.visitInsn(IRETURN);
        end(award);
        // The advancement data a holder pairs with its id, as a mod that kept it would have it.
        String holder = LegacyAdvancementBridge.HOLDER;
        MethodVisitor valueOf = begin(cw, "valueOf", "(L" + holder + ";)L" + ADVANCEMENT + ";");
        valueOf.visitVarInsn(ALOAD, 0);
        valueOf.visitMethodInsn(INVOKEVIRTUAL, holder, "value", "()L" + ADVANCEMENT + ";", false);
        valueOf.visitInsn(ARETURN);
        end(valueOf);

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * MCreator's advancement trigger: look the advancement up, read the player's progress, and
     * award a criterion unless it is done. The advancement stays in a local of its old type
     * across the branch, so the frame there names it.
     */
    private static void emitTea(ClassWriter cw) {
        MethodVisitor tea = begin(cw, "tea", "(L" + MANAGER + ";L" + PLAYER_ADVANCEMENTS + ";L" + IDENTIFIER + ";)Z");
        Label award = new Label();
        tea.visitVarInsn(ALOAD, 0);
        tea.visitVarInsn(ALOAD, 2);
        tea.visitMethodInsn(INVOKEVIRTUAL, MANAGER, "getAdvancement", "(L" + IDENTIFIER + ";)L" + ADVANCEMENT + ";", false);
        tea.visitVarInsn(ASTORE, 3);
        tea.visitVarInsn(ALOAD, 1);
        tea.visitVarInsn(ALOAD, 3);
        tea.visitMethodInsn(INVOKEVIRTUAL, PLAYER_ADVANCEMENTS, "getOrStartProgress",
                LegacyAdvancementBridge.GET_OR_START_OLD, false);
        tea.visitMethodInsn(INVOKEVIRTUAL, PROGRESS, "isDone", "()Z", false);
        tea.visitJumpInsn(IFEQ, award);
        tea.visitInsn(ICONST_0);
        tea.visitInsn(IRETURN);
        tea.visitLabel(award);
        tea.visitVarInsn(ALOAD, 1);
        tea.visitVarInsn(ALOAD, 3);
        tea.visitLdcInsn("drank");
        tea.visitMethodInsn(INVOKEVIRTUAL, PLAYER_ADVANCEMENTS, "award", LegacyAdvancementBridge.CRITERION_OLD, false);
        tea.visitInsn(IRETURN);
        end(tea);
    }

    /** A mob that overrides the old hurt, calls super, and also calls it on itself. */
    private static byte[] ownHurtMob() {
        ClassWriter cw = writer();
        cw.visit(V17, ACC_PUBLIC, OWN_HURT_MOB, null, LIVING, null);
        cw.visitField(ACC_PUBLIC, "ran", "Z", null, null).visitEnd();
        HostStubs.defaultConstructor(cw, LIVING);
        MethodVisitor hurt = cw.visitMethod(ACC_PUBLIC, "hurt", Legacy1212MemberBridge.HURT_OLD, null, null);
        hurt.visitCode();
        hurt.visitVarInsn(ALOAD, 0);
        hurt.visitInsn(ICONST_1);
        hurt.visitFieldInsn(PUTFIELD, OWN_HURT_MOB, "ran", "Z");
        hurt.visitVarInsn(ALOAD, 0);
        hurt.visitVarInsn(ALOAD, 1);
        hurt.visitVarInsn(FLOAD, 2);
        hurt.visitMethodInsn(INVOKESPECIAL, LIVING, "hurt", Legacy1212MemberBridge.HURT_OLD, false);
        hurt.visitInsn(IRETURN);
        end(hurt);
        MethodVisitor self = cw.visitMethod(ACC_PUBLIC, "selfHurt", "(L" + DAMAGE_SOURCE + ";)Z", null, null);
        self.visitCode();
        self.visitVarInsn(ALOAD, 0);
        self.visitVarInsn(ALOAD, 1);
        self.visitInsn(FCONST_1);
        self.visitMethodInsn(INVOKEVIRTUAL, OWN_HURT_MOB, "hurt", Legacy1212MemberBridge.HURT_OLD, false);
        self.visitInsn(IRETURN);
        end(self);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A player that overrides the old drop, calls super, and also calls it on itself. */
    private static byte[] ownDropPlayer() {
        ClassWriter cw = writer();
        cw.visit(V17, ACC_PUBLIC, OWN_DROP_PLAYER, null, SERVER_PLAYER, null);
        cw.visitField(ACC_PUBLIC, "dropped", "Z", null, null).visitEnd();
        HostStubs.defaultConstructor(cw, SERVER_PLAYER);
        MethodVisitor drop = cw.visitMethod(ACC_PUBLIC, "drop", Legacy263MemberBridge.DROP_OLD, null, null);
        drop.visitCode();
        drop.visitVarInsn(ALOAD, 0);
        drop.visitInsn(ICONST_1);
        drop.visitFieldInsn(PUTFIELD, OWN_DROP_PLAYER, "dropped", "Z");
        drop.visitVarInsn(ALOAD, 0);
        drop.visitVarInsn(ALOAD, 1);
        drop.visitVarInsn(ILOAD, 2);
        drop.visitMethodInsn(INVOKESPECIAL, SERVER_PLAYER, "drop", Legacy263MemberBridge.DROP_OLD, false);
        drop.visitInsn(ARETURN);
        end(drop);
        MethodVisitor self = cw.visitMethod(ACC_PUBLIC, "selfDrop", "(L" + ITEM_STACK + ";)V", null, null);
        self.visitCode();
        self.visitVarInsn(ALOAD, 0);
        self.visitVarInsn(ALOAD, 1);
        self.visitInsn(ICONST_1);
        self.visitMethodInsn(INVOKEVIRTUAL, OWN_DROP_PLAYER, "drop", Legacy263MemberBridge.DROP_OLD, false);
        self.visitInsn(POP);
        self.visitInsn(RETURN);
        end(self);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A mob that overrides the old three-argument drop, calls super, and also calls it on itself. */
    private static byte[] ownThrowMob() {
        ClassWriter cw = writer();
        cw.visit(V17, ACC_PUBLIC, OWN_THROW_MOB, null, LIVING, null);
        cw.visitField(ACC_PUBLIC, "threw", "Z", null, null).visitEnd();
        HostStubs.defaultConstructor(cw, LIVING);
        MethodVisitor drop = cw.visitMethod(ACC_PUBLIC, "drop", Legacy263MemberBridge.THROW_OLD, null, null);
        drop.visitCode();
        drop.visitVarInsn(ALOAD, 0);
        drop.visitInsn(ICONST_1);
        drop.visitFieldInsn(PUTFIELD, OWN_THROW_MOB, "threw", "Z");
        drop.visitVarInsn(ALOAD, 0);
        drop.visitVarInsn(ALOAD, 1);
        drop.visitVarInsn(ILOAD, 2);
        drop.visitVarInsn(ILOAD, 3);
        drop.visitMethodInsn(INVOKESPECIAL, LIVING, "drop", Legacy263MemberBridge.THROW_OLD, false);
        drop.visitInsn(ARETURN);
        end(drop);
        MethodVisitor self = cw.visitMethod(ACC_PUBLIC, "selfThrow", "(L" + ITEM_STACK + ";)V", null, null);
        self.visitCode();
        self.visitVarInsn(ALOAD, 0);
        self.visitVarInsn(ALOAD, 1);
        self.visitInsn(ICONST_1);
        self.visitInsn(ICONST_0);
        self.visitMethodInsn(INVOKEVIRTUAL, OWN_THROW_MOB, "drop", Legacy263MemberBridge.THROW_OLD, false);
        self.visitInsn(POP);
        self.visitInsn(RETURN);
        end(self);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * A 1.21.1 Fabric mob's override of the old hurt after the 26.x name table, which names it
     * {@code hurtClient}, ending in its super call.
     */
    private static byte[] ownHurtClientMob() {
        ClassWriter cw = writer();
        cw.visit(V17, ACC_PUBLIC, OWN_HURT_CLIENT_MOB, null, LIVING, null);
        cw.visitField(ACC_PUBLIC, "ran", "Z", null, null).visitEnd();
        HostStubs.defaultConstructor(cw, LIVING);
        MethodVisitor hurt = cw.visitMethod(ACC_PUBLIC, "hurtClient", Legacy1212MemberBridge.HURT_OLD, null, null);
        hurt.visitCode();
        hurt.visitVarInsn(ALOAD, 0);
        hurt.visitInsn(ICONST_1);
        hurt.visitFieldInsn(PUTFIELD, OWN_HURT_CLIENT_MOB, "ran", "Z");
        hurt.visitVarInsn(ALOAD, 0);
        hurt.visitVarInsn(ALOAD, 1);
        hurt.visitVarInsn(FLOAD, 2);
        hurt.visitMethodInsn(INVOKESPECIAL, LIVING, "hurtClient", Legacy1212MemberBridge.HURT_OLD, false);
        hurt.visitInsn(IRETURN);
        end(hurt);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A custom property that declares the old getPossibleValues and reads it on itself. */
    private static byte[] ownProperty() {
        ClassWriter cw = writer();
        cw.visit(V17, ACC_PUBLIC, OWN_PROPERTY, null, PROPERTY, null);
        HostStubs.defaultConstructor(cw, PROPERTY);
        MethodVisitor values = cw.visitMethod(ACC_PUBLIC, "getPossibleValues", Legacy1212MemberBridge.VALUES_OLD, null, null);
        values.visitCode();
        values.visitMethodInsn(INVOKESTATIC, "java/util/List", "of", "()Ljava/util/List;", true);
        values.visitInsn(ARETURN);
        end(values);
        MethodVisitor self = cw.visitMethod(ACC_PUBLIC, "selfValues", "()Ljava/util/Collection;", null, null);
        self.visitCode();
        self.visitVarInsn(ALOAD, 0);
        self.visitMethodInsn(INVOKEVIRTUAL, OWN_PROPERTY, "getPossibleValues", Legacy1212MemberBridge.VALUES_OLD, false);
        self.visitInsn(ARETURN);
        end(self);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassWriter writer() {
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
    }

    private static MethodVisitor begin(ClassWriter cw, String name, String desc) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        return mv;
    }

    private static void end(MethodVisitor mv) {
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    // --- inspection ---------------------------------------------------------------------------

    private static ClassNode classWithConstructors(String... descs) {
        ClassNode node = new ClassNode();
        node.name = STACK;
        for (String desc : descs) node.methods.add(new MethodNode(ACC_PUBLIC, "<init>", desc, null, null));
        return node;
    }

    /** A class that declares each {@code name + descriptor}, with no code. */
    private static ClassNode classWithMethods(String name, String... methods) {
        ClassNode node = new ClassNode();
        node.name = name;
        for (String method : methods) {
            int split = method.indexOf('(');
            node.methods.add(new MethodNode(ACC_PUBLIC, method.substring(0, split), method.substring(split), null, null));
        }
        return node;
    }

    private static ClassNode permissionsWithForLevel() {
        ClassNode node = new ClassNode();
        node.name = LegacyCommandSourceBridge.LEVEL_PERMISSIONS;
        node.methods.add(new MethodNode(ACC_PUBLIC | ACC_STATIC, "forLevel", LegacyCommandSourceBridge.FOR_LEVEL, null, null));
        return node;
    }

    private static ClassNode read(ZipFile jar, String internalName) throws Exception {
        try (InputStream in = jar.getInputStream(jar.getEntry(internalName + ".class"))) {
            ClassNode node = new ClassNode();
            new ClassReader(in.readAllBytes()).accept(node, ClassReader.SKIP_CODE);
            return node;
        }
    }

    private static void verify(byte[] bytes) throws Exception {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        for (MethodNode method : node.methods) {
            new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicVerifier())
                    .analyze(node.name, method);
        }
    }

    /** The class with only its constructors and the methods whose name passes {@code keep}. */
    private static byte[] keepMethods(byte[] bytes, Predicate<String> keep) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        node.methods.removeIf(method -> !method.name.startsWith("<") && !keep.test(method.name));
        ClassWriter cw = new ClassWriter(0);
        node.accept(cw);
        return cw.toByteArray();
    }

    private static MethodNode method(byte[] bytes, String name) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    private static List<String> callsTo(byte[] bytes, String methodName, String calledName) {
        List<String> calls = new ArrayList<>();
        for (String call : calls(method(bytes, methodName))) {
            if (call.substring(call.lastIndexOf('.') + 1).startsWith(calledName + "(")) calls.add(call);
        }
        return calls;
    }

    private static List<String> calls(MethodNode method) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
        }
        return calls;
    }

    private static Map<String, byte[]> compile(Map<String, String> sources) throws Exception {
        Path src = stubDir.resolve("src");
        Path out = stubDir.resolve("out");
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), "--release", "17", "-nowarn"));
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = src.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            args.add(file.toString());
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "the tests need a JDK to compile the 26.3 stand-ins");
        assertEquals(0, compiler.run(null, null, null, args.toArray(new String[0])), "the 26.3 stand-ins must compile");
        Map<String, byte[]> classes = new HashMap<>();
        try (var files = Files.walk(out)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".class"))::iterator) {
                String name = out.relativize(file).toString().replace(java.io.File.separatorChar, '.');
                classes.put(name.substring(0, name.length() - ".class".length()), Files.readAllBytes(file));
            }
        }
        return classes;
    }

    /** Stand-ins with the 26.3 shape of each class the procedures and the helpers call. */
    private static final class HostSources {
        static final Map<String, String> SOURCES = new HashMap<>();

        private static void add(String path, String source) {
            SOURCES.put(path, source);
        }

        static {
            add("net/minecraft/commands/CommandSource.java", """
                    package net.minecraft.commands;
                    public interface CommandSource {}""");
            add("net/minecraft/commands/CommandSourceStack.java", """
                    package net.minecraft.commands;
                    import net.minecraft.network.chat.Component;
                    import net.minecraft.server.MinecraftServer;
                    import net.minecraft.server.level.ServerLevel;
                    import net.minecraft.server.permissions.PermissionSet;
                    import net.minecraft.world.entity.Entity;
                    import net.minecraft.world.phys.Vec2;
                    import net.minecraft.world.phys.Vec3;
                    public class CommandSourceStack {
                        public final String shape;
                        public final ServerLevel level;
                        public final PermissionSet permissions;
                        public final Component displayName;
                        public final Entity entity;
                        public CommandSourceStack(CommandSource source, Vec3 position, Vec2 rotation, ServerLevel level,
                                PermissionSet permissions, MinecraftServer server, Entity entity) {
                            this.shape = "entity"; this.level = level; this.permissions = permissions;
                            this.displayName = null; this.entity = entity;
                        }
                        public CommandSourceStack(CommandSource source, Vec3 position, Vec2 rotation, ServerLevel level,
                                PermissionSet permissions, Component displayName, MinecraftServer server) {
                            this.shape = "constant"; this.level = level; this.permissions = permissions;
                            this.displayName = displayName; this.entity = null;
                        }
                        public CommandSourceStack withPermission(PermissionSet permissions) {
                            return new CommandSourceStack(null, null, null, level, permissions, displayName, null);
                        }
                        public CommandSourceStack withMaximumPermission(PermissionSet permissions) {
                            return withPermission(permissions);
                        }
                    }""");
            add("net/minecraft/network/chat/Component.java", """
                    package net.minecraft.network.chat;
                    public interface Component {
                        String getString();
                        static Component literal(String text) { return new Literal(text); }
                        final class Literal implements Component {
                            public final String text;
                            Literal(String text) { this.text = text; }
                            public String getString() { return text; }
                        }
                    }""");
            add("net/minecraft/server/permissions/PermissionLevel.java", """
                    package net.minecraft.server.permissions;
                    public enum PermissionLevel {
                        ALL, MODERATORS, GAMEMASTERS, ADMINS, OWNERS;
                        public static PermissionLevel byId(int id) { return values()[Math.max(0, Math.min(id, 4))]; }
                    }""");
            add("net/minecraft/server/permissions/PermissionSet.java", """
                    package net.minecraft.server.permissions;
                    public interface PermissionSet {}""");
            add("net/minecraft/server/permissions/LevelBasedPermissionSet.java", """
                    package net.minecraft.server.permissions;
                    public interface LevelBasedPermissionSet extends PermissionSet {
                        PermissionLevel level();
                        static LevelBasedPermissionSet forLevel(PermissionLevel level) { return () -> level; }
                    }""");
            add("net/minecraft/world/phys/Vec2.java", """
                    package net.minecraft.world.phys;
                    public class Vec2 {}""");
            add("net/minecraft/world/phys/Vec3.java", """
                    package net.minecraft.world.phys;
                    public class Vec3 {}""");
            add("net/minecraft/server/MinecraftServer.java", """
                    package net.minecraft.server;
                    public class MinecraftServer {}""");
            add("net/minecraft/util/RandomSource.java", """
                    package net.minecraft.util;
                    public interface RandomSource { float nextFloat(); }""");
            add("net/minecraft/util/Prediction.java", """
                    package net.minecraft.util;
                    public enum Prediction { PREDICTED, SERVER_ONLY }""");
            add("net/minecraft/world/level/Level.java", """
                    package net.minecraft.world.level;
                    public class Level {
                        public boolean clientSide;
                        public final java.util.List<Object> added = new java.util.ArrayList<>();
                        public boolean isClientSide() { return clientSide; }
                        public boolean addFreshEntity(net.minecraft.world.entity.Entity entity) { return added.add(entity); }
                    }""");
            add("net/minecraft/server/level/ServerLevel.java", """
                    package net.minecraft.server.level;
                    public class ServerLevel extends net.minecraft.world.level.Level {
                        public final net.minecraft.server.MinecraftServer server = new net.minecraft.server.MinecraftServer();
                        public net.minecraft.server.MinecraftServer getServer() { return server; }
                    }""");
            add("net/minecraft/world/damagesource/DamageSource.java", """
                    package net.minecraft.world.damagesource;
                    public class DamageSource {}""");
            add("net/minecraft/world/entity/Entity.java", """
                    package net.minecraft.world.entity;
                    import net.minecraft.world.damagesource.DamageSource;
                    import net.minecraft.world.level.Level;
                    public class Entity {
                        public Level level;
                        public Object[] lastHurt;
                        public java.util.Collection<net.minecraft.world.entity.item.ItemEntity> captured;
                        public Level level() { return level; }
                        public java.util.Collection<net.minecraft.world.entity.item.ItemEntity> captureDrops() { return captured; }
                        public final boolean hurtOrSimulate(DamageSource source, float amount) {
                            lastHurt = new Object[] {source, amount};
                            return true;
                        }
                    }""");
            add("net/minecraft/world/entity/LivingEntity.java", """
                    package net.minecraft.world.entity;
                    import net.minecraft.util.Prediction;
                    import net.minecraft.world.entity.item.ItemEntity;
                    import net.minecraft.world.item.ItemStack;
                    public class LivingEntity extends Entity {
                        public Object[] lastDrop;
                        public ItemEntity drop(ItemStack stack, boolean retainOwnership, Prediction prediction) {
                            lastDrop = new Object[] {stack, retainOwnership, prediction};
                            return null;
                        }
                        public ItemEntity createItemStackToDrop(ItemStack stack, boolean throwRandomly, boolean retainOwnership) {
                            if (stack.isEmpty()) return null;
                            ItemEntity item = new ItemEntity();
                            item.stack = stack; item.thrownRandomly = throwRandomly; item.retained = retainOwnership;
                            return item;
                        }
                    }""");
            add("net/minecraft/world/entity/item/ItemEntity.java", """
                    package net.minecraft.world.entity.item;
                    public class ItemEntity extends net.minecraft.world.entity.Entity {
                        public net.minecraft.world.item.ItemStack stack;
                        public boolean thrownRandomly;
                        public boolean retained;
                    }""");
            add("net/minecraft/world/entity/player/Player.java", """
                    package net.minecraft.world.entity.player;
                    import net.minecraft.util.Prediction;
                    import net.minecraft.world.entity.item.ItemEntity;
                    import net.minecraft.world.item.ItemStack;
                    public class Player extends net.minecraft.world.entity.LivingEntity {
                        public boolean creative;
                        public boolean tossed;
                        public ItemEntity drop(ItemStack stack, boolean retainOwnership, Prediction prediction) {
                            tossed = true;
                            return super.drop(stack, retainOwnership, prediction);
                        }
                    }""");
            add("net/minecraft/server/level/ServerPlayer.java", """
                    package net.minecraft.server.level;
                    public class ServerPlayer extends net.minecraft.world.entity.player.Player {
                        private final net.minecraft.server.MinecraftServer server = null;
                    }""");
            add("net/minecraft/world/Container.java", """
                    package net.minecraft.world;
                    public interface Container {}""");
            add("net/minecraft/world/entity/player/Inventory.java", """
                    package net.minecraft.world.entity.player;
                    import java.util.function.Predicate;
                    import net.minecraft.world.Container;
                    import net.minecraft.world.item.ItemStack;
                    public class Inventory {
                        public Object[] lastClear;
                        public int clearOrCountMatchingItems(Predicate<ItemStack> predicate, boolean countOnly, int max,
                                Container craftSlots) {
                            lastClear = new Object[] {predicate, countOnly, max, craftSlots};
                            return 7;
                        }
                    }""");
            add("net/minecraft/world/item/ItemStack.java", """
                    package net.minecraft.world.item;
                    import java.util.function.Consumer;
                    import net.minecraft.server.level.ServerLevel;
                    import net.minecraft.server.level.ServerPlayer;
                    public class ItemStack {
                        public final String item;
                        public int count;
                        public int damage;
                        public int max = 10;
                        public ItemStack(String item, int count) { this.item = item; this.count = count; }
                        public boolean isEmpty() { return count <= 0 || item.equals("air"); }
                        public ItemStack copy() { ItemStack copy = new ItemStack(item, count); copy.damage = damage; return copy; }
                        public int getCount() { return isEmpty() ? 0 : count; }
                        public void grow(int amount) { count = getCount() + amount; }
                        public boolean isDamageableItem() { return max > 0; }
                        public int getDamageValue() { return damage; }
                        public void setDamageValue(int damage) { this.damage = damage; }
                        public int getMaxDamage() { return max; }
                        public void hurtAndBreak(int amount, ServerLevel level, ServerPlayer player,
                                Consumer<ItemStack> onBreak) {
                            if (level == null) throw new NullPointerException("level");
                            if (player != null && player.creative) return;
                            damage += amount;
                            if (damage >= max) { ItemStack copy = copy(); count -= 1; onBreak.accept(copy); }
                        }
                    }""");
            add("net/minecraft/world/level/block/state/properties/Property.java", """
                    package net.minecraft.world.level.block.state.properties;
                    public abstract class Property<T> {
                        public java.util.List<T> getPossibleValues() { return java.util.List.of(); }
                    }""");
            add("net/minecraft/world/level/block/state/properties/IntegerProperty.java", """
                    package net.minecraft.world.level.block.state.properties;
                    public class IntegerProperty extends Property<Integer> {
                        public java.util.List<Integer> getPossibleValues() { return java.util.List.of(0, 1, 2, 3); }
                    }""");
            add("net/minecraft/resources/Identifier.java", """
                    package net.minecraft.resources;
                    public final class Identifier {
                        public final String id;
                        public Identifier(String id) { this.id = id; }
                        public boolean equals(Object other) { return other instanceof Identifier i && i.id.equals(id); }
                        public int hashCode() { return id.hashCode(); }
                    }""");
            add("net/minecraft/advancements/Advancement.java", """
                    package net.minecraft.advancements;
                    public class Advancement {}""");
            add("net/minecraft/advancements/AdvancementHolder.java", """
                    package net.minecraft.advancements;
                    public record AdvancementHolder(net.minecraft.resources.Identifier id, Advancement value) {}""");
            add("net/minecraft/advancements/AdvancementProgress.java", """
                    package net.minecraft.advancements;
                    public class AdvancementProgress { public boolean isDone() { return false; } }""");
            add("net/minecraft/server/ServerAdvancementManager.java", """
                    package net.minecraft.server;
                    import java.util.Collection;
                    import java.util.LinkedHashMap;
                    import java.util.Map;
                    import net.minecraft.advancements.Advancement;
                    import net.minecraft.advancements.AdvancementHolder;
                    import net.minecraft.resources.Identifier;
                    public class ServerAdvancementManager {
                        private final Map<Identifier, AdvancementHolder> byId = new LinkedHashMap<>();
                        public AdvancementHolder add(String id) {
                            AdvancementHolder holder = new AdvancementHolder(new Identifier(id), new Advancement());
                            byId.put(holder.id(), holder);
                            return holder;
                        }
                        public AdvancementHolder get(Identifier id) { return byId.get(id); }
                        public Collection<AdvancementHolder> getAllAdvancements() { return byId.values(); }
                    }""");
            add("net/minecraft/server/PlayerAdvancements.java", """
                    package net.minecraft.server;
                    import java.util.ArrayList;
                    import java.util.HashMap;
                    import java.util.List;
                    import java.util.Map;
                    import java.util.Objects;
                    import net.minecraft.advancements.AdvancementHolder;
                    import net.minecraft.advancements.AdvancementProgress;
                    public class PlayerAdvancements {
                        public final List<String> awarded = new ArrayList<>();
                        private final Map<AdvancementHolder, AdvancementProgress> progress = new HashMap<>();
                        public AdvancementProgress getOrStartProgress(AdvancementHolder holder) {
                            return progress.computeIfAbsent(Objects.requireNonNull(holder), h -> new AdvancementProgress());
                        }
                        public boolean award(AdvancementHolder holder, String criterion) {
                            awarded.add(Objects.requireNonNull(holder).id().id + "/" + criterion);
                            return true;
                        }
                        public boolean revoke(AdvancementHolder holder, String criterion) { return false; }
                    }""");
        }
    }
}
