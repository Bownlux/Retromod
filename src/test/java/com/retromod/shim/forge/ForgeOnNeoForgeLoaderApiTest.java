/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.forge.embedded.LegacyPickupEvents;
import com.retromod.shim.forge.embedded.LegacySpawnEvents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static com.retromod.shim.forge.LoaderApiHostJars.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Forge 1.20.1 loader APIs that NeoForge 26.3 kept under other names or shapes. */
class ForgeOnNeoForgeLoaderApiTest {

    private static final String TAG_KEY = LegacyNeoForgeTagsBridge.TAG_KEY;

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Tag constants read NeoForge's c: constant, vanilla's armor tag, or keep their forge: id")
    void tagConstantsFollowTheTable() {
        LegacyNeoForgeTagsBridge.register(transformer, name -> null);
        String items = LegacyNeoForgeTagsBridge.FORGE_TAGS + "Items";
        byte[] out = transformer.transformClass(mod("mod/Tags", "()V", mv -> {
            for (String field : List.of("INGOTS_IRON", "SHEARS", "ARMORS_HELMETS", "GLASS_RED")) {
                mv.visitFieldInsn(GETSTATIC, items, field, TAG_KEY);
                mv.visitInsn(POP);
            }
            mv.visitInsn(RETURN);
        }), "mod/Tags");
        assertEquals(List.of(
                "net/neoforged/neoforge/common/Tags$Items.INGOTS_IRON:" + TAG_KEY,
                "net/neoforged/neoforge/common/Tags$Items.TOOLS_SHEAR:" + TAG_KEY,
                "net/minecraft/tags/ItemTags.HEAD_ARMOR:" + TAG_KEY,
                LegacyNeoForgeTagsBridge.HELPER + ".Items_GLASS_RED()" + TAG_KEY), refs(out));
    }

    @Test
    @DisplayName("Against Forge 1.20.1 and NeoForge 26.3: every old constant is listed with its path, every target exists")
    void tagTableMatchesTheRealJars() {
        Assumptions.assumeTrue(present(FORGE_1201, NEOFORGE_263, NEOFORGE_263_CLIENT), "the Forge 1.20.1 and NeoForge 26.3 jars are not in the local Prism library");
        Function<String, ClassNode> forge = host(FORGE_1201);
        Function<String, ClassNode> neo = host(NEOFORGE_263, NEOFORGE_263_CLIENT);
        Map<String, Integer> perGroup = new HashMap<>();
        for (LegacyNeoForgeTagsBridge.Row row : LegacyNeoForgeTagsBridge.ROWS) {
            String old = LegacyNeoForgeTagsBridge.FORGE_TAGS + row.group();
            assertEquals(row.forgePath(), forgePath(forge.apply(old), row.field()), "1.20.1 path of " + row);
            if (!row.kind().equals("forge")) {
                String owner = row.kind().equals("vanilla") ? LegacyNeoForgeTagsBridge.ITEM_TAGS : LegacyNeoForgeTagsBridge.NEO_TAGS + row.group();
                assertTrue(neo.apply(owner).fields.stream().anyMatch(f -> f.name.equals(row.target())), "26.3 has the target of " + row);
            }
            perGroup.merge(row.group(), 1, Integer::sum);
        }
        // Every public constant of every 1.20.1 group has a row, so no old read is left unbridged.
        for (String group : List.of("Items", "Blocks", "Biomes", "Fluids", "EntityTypes")) {
            long constants = forge.apply(LegacyNeoForgeTagsBridge.FORGE_TAGS + group).fields.stream()
                    .filter(f -> (f.access & ACC_STATIC) != 0 && f.desc.equals(TAG_KEY)).count();
            assertEquals(constants, (long) perGroup.getOrDefault(group, 0), "rows for Tags$" + group);
        }
        LegacyNeoForgeTagsBridge.register(transformer, neo);
        assertTrue(transformer.getSyntheticClasses().containsKey(LegacyNeoForgeTagsBridge.HELPER));
        assertEquals(LegacyNeoForgeTagsBridge.ROWS.size(), transformer.getFieldRedirects().size(), "one redirect per constant");
    }

    @Test
    @DisplayName("A host without a target constant keeps the forge: id, and a 1.21.1 host builds it with ResourceLocation")
    void missingTargetFallsBackToTheForgeId() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        ClassNode tags = new ClassNode();
        tags.name = LegacyNeoForgeTagsBridge.NEO_TAGS + "Items";
        Function<String, ClassNode> host = name -> name.equals(tags.name) ? tags : null;
        LegacyNeoForgeTagsBridge.register(transformer, host);
        byte[] helper = transformer.getSyntheticClasses().get(LegacyNeoForgeTagsBridge.HELPER);
        assertTrue(refs(helper).contains("net/minecraft/resources/ResourceLocation.fromNamespaceAndPath"
                + "(Ljava/lang/String;Ljava/lang/String;)Lnet/minecraft/resources/ResourceLocation;"));
        assertTrue(ldcStrings(helper).contains("tools/tridents"), "TOOLS_TRIDENTS keeps forge:tools/tridents there");
    }

    @Test
    @DisplayName("Offline, TOOLS_TRIDENTS reads TOOLS_TRIDENT only on targets from 26.1, which first have it")
    void tridentRowIsGatedOffline() {
        for (String version : List.of("1.21.1", "1.21.11", "26.1")) {
            transformer.clearRedirectsForTesting();
            RetromodVersion.TARGET_MC_VERSION = version;
            LegacyNeoForgeTagsBridge.register(transformer, name -> null);
            String owner = transformer.getFieldRedirects().entrySet().stream()
                    .filter(e -> e.getKey().name().equals("TOOLS_TRIDENTS")).findFirst().orElseThrow().getValue().owner();
            assertEquals(version.equals("26.1") ? LegacyNeoForgeTagsBridge.NEO_TAGS + "Items" : LegacyNeoForgeTagsBridge.HELPER,
                    owner, "TOOLS_TRIDENTS on " + version);
        }
    }

    @Test
    @DisplayName("On a NeoForge 21.1 shaped host, CommonHooks.getBurnTime becomes the stack's own read")
    void forgeHooksBurnTimeOn211() {
        ClassNode extension = new ClassNode();
        extension.methods.add(new MethodNode(ACC_PUBLIC, "getBurnTime", "(Lnet/minecraft/world/item/crafting/RecipeType;)I", null, null));
        ClassNode hooks = new ClassNode();
        Map<String, ClassNode> host = Map.of("net/neoforged/neoforge/common/extensions/IItemStackExtension", extension,
                "net/neoforged/neoforge/common/CommonHooks", hooks);
        com.retromod.shim.neoforge.LegacyBurnTimeBridge.registerForgeHooksRead(transformer, host::get);
        String desc = "(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/crafting/RecipeType;)I";
        byte[] out = transformer.transformClass(mod("mod/Furnace211", "(Lnet/minecraft/world/item/ItemStack;)I", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitInsn(ACONST_NULL);
            mv.visitMethodInsn(INVOKESTATIC, "net/neoforged/neoforge/common/CommonHooks", "getBurnTime", desc, false);
            mv.visitInsn(IRETURN);
        }), "mod/Furnace211");
        assertEquals(List.of("com/retromod/generated/LegacyStackBurnTimes.getBurnTime" + desc), refs(out));
        assertEquals(List.of("net/minecraft/world/item/ItemStack.getBurnTime(Lnet/minecraft/world/item/crafting/RecipeType;)I"),
                refs(transformer.getSyntheticClasses().get("com/retromod/generated/LegacyStackBurnTimes")));
    }

    @Test
    @DisplayName("The permission API, ModInfo and ModFile move by name onto classes the host has")
    void permissionAndModFileMoves() {
        Set<String> neo = Set.of("net/neoforged/neoforge/server/permission/PermissionAPI",
                "net/neoforged/neoforge/server/permission/nodes/PermissionNode");
        ForgeNeoForgeApiBridge.registerMoves(transformer, neo::contains);
        Set<String> fml = Set.of("net/neoforged/fml/loading/moddiscovery/ModInfo", "net/neoforged/fml/loading/moddiscovery/ModFile");
        ForgeNeoForgeApiBridge.registerAbsoluteMoves(transformer, fml::contains);
        Map<String, String> redirects = transformer.getClassRedirects();
        assertEquals("net/neoforged/neoforge/server/permission/PermissionAPI", redirects.get("net/minecraftforge/server/permission/PermissionAPI"));
        assertEquals("net/neoforged/neoforge/server/permission/nodes/PermissionNode",
                redirects.get("net/minecraftforge/server/permission/nodes/PermissionNode"));
        assertNull(redirects.get("net/minecraftforge/server/permission/nodes/PermissionTypes"), "not on this host");
        assertEquals("net/neoforged/fml/loading/moddiscovery/ModFile", redirects.get("net/minecraftforge/fml/loading/moddiscovery/ModFile"));
        assertEquals("net/neoforged/fml/loading/moddiscovery/ModInfo", redirects.get("net/minecraftforge/fml/loading/moddiscovery/ModInfo"));
    }

    @Test
    @DisplayName("Against NeoForge 26.3: every permission class and FML move target exists")
    void movesExistOnNeoForge263() {
        Assumptions.assumeTrue(present(NEOFORGE_263, FML_12), "the NeoForge 26.3 jars are not in the local Prism library");
        Function<String, ClassNode> neo = host(NEOFORGE_263, FML_12);
        for (String[] move : ForgeNeoForgeApiBridge.MOVES) {
            if (move[0].startsWith("server/permission/")) assertNotNull(neo.apply("net/neoforged/neoforge/" + move[1]), move[1]);
        }
        for (String[] move : ForgeNeoForgeApiBridge.ABSOLUTE_MOVES) assertNotNull(neo.apply(move[1]), move[1]);
    }

    @Test
    @DisplayName("A pickup listener takes ItemEntityPickupEvent.Pre, and its getters, cancel and result follow")
    void pickupListenerMovesToThePreEvent() {
        String forge = LegacyRenamedEventsBridge.FORGE_PICKUP;
        assertTrue(LegacyRenamedEventsBridge.registerPickup(transformer, Set.of(LegacyRenamedEventsBridge.NEO_PICKUP)::contains));
        byte[] out = transformer.transformClass(mod("mod/Pickup", "(L" + forge + ";)V", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, forge, "getItem", "()Lnet/minecraft/world/entity/item/ItemEntity;", false);
            mv.visitInsn(POP);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitInsn(ICONST_1);
            mv.visitMethodInsn(INVOKEVIRTUAL, forge, "setCanceled", "(Z)V", false);
            mv.visitInsn(RETURN);
        }), "mod/Pickup");
        assertEquals("(L" + LegacyRenamedEventsBridge.NEO_PICKUP + ";)V", runDesc(out), "the bus resolves the listener's parameter");
        assertEquals(List.of(LegacyRenamedEventsBridge.NEO_PICKUP + ".getItemEntity()Lnet/minecraft/world/entity/item/ItemEntity;",
                LegacyRenamedEventsBridge.PICKUP_HELPER + ".setCanceled(Ljava/lang/Object;Z)V"), refs(out));
    }

    @Test
    @DisplayName("An after-pickup listener takes ItemEntityPickupEvent.Post, and a mod's own fire call swaps to NeoForge's order")
    void afterPickupMovesToThePostEvent() {
        String forge = LegacyRenamedEventsBridge.FORGE_PICKED_UP;
        String post = LegacyRenamedEventsBridge.NEO_PICKED_UP;
        assertTrue(LegacyRenamedEventsBridge.registerPickedUp(transformer, Set.of(post)::contains));
        byte[] out = transformer.transformClass(mod("mod/PickedUp", "(L" + forge + ";)V", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, forge, "getStack", "()Lnet/minecraft/world/item/ItemStack;", false);
            mv.visitInsn(POP);
            mv.visitInsn(ACONST_NULL);
            mv.visitInsn(ACONST_NULL);
            mv.visitInsn(ACONST_NULL);
            mv.visitMethodInsn(INVOKESTATIC, "net/minecraftforge/event/ForgeEventFactory", "firePlayerItemPickupEvent",
                    LegacyRenamedEventsBridge.FIRE_DESC, false);
            mv.visitInsn(RETURN);
        }), "mod/PickedUp");
        assertEquals("(L" + post + ";)V", runDesc(out));
        assertEquals(List.of(post + ".getOriginalStack()Lnet/minecraft/world/item/ItemStack;",
                LegacyRenamedEventsBridge.FIRE_HELPER + ".firePlayerItemPickupEvent" + LegacyRenamedEventsBridge.FIRE_DESC), refs(out));
        assertTrue(refs(transformer.getSyntheticClasses().get(LegacyRenamedEventsBridge.FIRE_HELPER)).get(0)
                .startsWith("net/neoforged/neoforge/event/EventHooks.fireItemPickupPost(Lnet/minecraft/world/entity/item/ItemEntity;"));
    }

    /** NeoForge's TriState names. */
    public enum TriState { TRUE, DEFAULT, FALSE }

    /** Shaped like ItemEntityPickupEvent.Pre. */
    public static final class FakePickup {
        TriState state = TriState.DEFAULT;
        public void setCanPickup(TriState state) { this.state = state; }
        public TriState canPickup() { return state; }
    }

    @Test
    @DisplayName("A cancel refuses the pickup, ALLOW leaves the delay and owner checks in place, and a cancel outranks it")
    void pickupHelperDecides() {
        FakePickup event = new FakePickup();
        LegacyPickupEvents.setResult(event, LegacyDespawnEventBridgeTest.Result.ALLOW);
        assertEquals(TriState.DEFAULT, event.state, "NeoForge's TRUE would skip checks Forge's ALLOW kept");
        LegacyPickupEvents.setCanceled(event, true);
        assertEquals(TriState.FALSE, event.state);
        assertTrue(LegacyPickupEvents.isCanceled(event));
        LegacyPickupEvents.setResult(event, LegacyDespawnEventBridgeTest.Result.ALLOW);
        assertEquals(TriState.FALSE, event.state, "Forge checked the cancel first");
        LegacyPickupEvents.setCanceled(event, false);
        assertEquals(TriState.DEFAULT, event.state);
        assertEquals(com.retromod.shim.forge.embedded.LegacyEventResult.DEFAULT, LegacyPickupEvents.getResult(event));
        event.state = TriState.TRUE;
        assertEquals(com.retromod.shim.forge.embedded.LegacyEventResult.ALLOW, LegacyPickupEvents.getResult(event),
                "a pickup another mod forced reads as ALLOW");
    }

    @Test
    @DisplayName("getResult on the pickup event and the spawn tag calls on the finalize event go to their helpers")
    void pickupResultAndSpawnTagCalls() {
        LegacyRenamedEventsBridge.register(transformer, Set.of(LegacyRenamedEventsBridge.NEO_PICKUP, LegacyRenamedEventsBridge.NEO_FINALIZE)::contains);
        String result = "Lnet/minecraftforge/eventbus/api/Event$Result;";
        String tag = "Lnet/minecraft/nbt/CompoundTag;";
        byte[] out = transformer.transformClass(mod("mod/Calls", "(L" + LegacyRenamedEventsBridge.FORGE_PICKUP + ";L"
                + LegacyRenamedEventsBridge.FORGE_FINALIZE + ";)V", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, LegacyRenamedEventsBridge.FORGE_PICKUP, "getResult", "()" + result, false);
            mv.visitInsn(POP);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, LegacyRenamedEventsBridge.FORGE_FINALIZE, "getSpawnTag", "()" + tag, false);
            mv.visitMethodInsn(INVOKEVIRTUAL, LegacyRenamedEventsBridge.FORGE_FINALIZE, "setSpawnTag", "(" + tag + ")V", false);
            mv.visitInsn(RETURN);
        }), "mod/Calls");
        List<String> refs = refs(out);
        assertTrue(refs.get(0).startsWith(LegacyRenamedEventsBridge.PICKUP_HELPER + ".getResult(Ljava/lang/Object;)"), refs.get(0));
        assertEquals(LegacyRenamedEventsBridge.SPAWN_HELPER + ".getSpawnTag(Ljava/lang/Object;)Ljava/lang/Object;", refs.get(1));
        assertEquals(LegacyRenamedEventsBridge.SPAWN_HELPER + ".setSpawnTag(Ljava/lang/Object;Ljava/lang/Object;)V", refs.get(2));
        assertNull(LegacySpawnEvents.getSpawnTag(new Object()));
    }

    /** Shaped like FinalizeSpawnEvent with a natural spawn, and with a block entity spawner. */
    public static final class FakeFinalize {
        final Object spawner;
        FakeFinalize(Object spawner) { this.spawner = spawner; }
        public Object getSpawner() { return spawner; }
    }

    /** Shaped like DataFixerUpper's Either. */
    public static final class FakeEither {
        final Object left;
        FakeEither(Object left) { this.left = left; }
        public Optional<Object> left() { return Optional.ofNullable(left); }
        public Optional<Object> right() { return Optional.empty(); }
    }

    /** A spawner source whose getSpawner is not the old BaseSpawner, as a trial spawner's is not. */
    public static final class FakeTrialSpawner {
        public String getSpawner() { return "trial"; }
    }

    @Test
    @DisplayName("A finalize listener takes FinalizeSpawnEvent, and getSpawner answers null where no old spawner exists")
    void finalizeSpawnMovesAndReadsTheSpawner() {
        String forge = LegacyRenamedEventsBridge.FORGE_FINALIZE;
        assertTrue(LegacyRenamedEventsBridge.registerFinalizeSpawn(transformer, Set.of(LegacyRenamedEventsBridge.NEO_FINALIZE)::contains));
        byte[] out = transformer.transformClass(mod("mod/Spawn", "(L" + forge + ";)V", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, forge, "getSpawner", "()Lnet/minecraft/world/level/BaseSpawner;", false);
            mv.visitInsn(POP);
            mv.visitInsn(RETURN);
        }), "mod/Spawn");
        assertEquals("(L" + LegacyRenamedEventsBridge.NEO_FINALIZE + ";)V", runDesc(out));
        assertEquals(LegacyRenamedEventsBridge.SPAWN_HELPER + ".getSpawner(Ljava/lang/Object;)Ljava/lang/Object;", refs(out).get(0));
        assertNull(LegacySpawnEvents.getSpawner(new FakeFinalize(null)), "a natural spawn has no spawner");
        assertNull(LegacySpawnEvents.getSpawner(new FakeFinalize(new FakeEither(new FakeTrialSpawner()))));
    }

    @Test
    @DisplayName("ForgeMod's fluid types read NeoForgeMod's holders as DeferredHolders, and milk keeps its type")
    void forgeModFluids() {
        LegacyForgeModFluids.register(transformer, name -> null);
        String holder = "L" + LegacyForgeModFluids.DEFERRED_HOLDER + ";";
        byte[] out = transformer.transformClass(mod("mod/Fluids", "()V", mv -> {
            mv.visitFieldInsn(GETSTATIC, LegacyForgeModFluids.FORGE_MOD, "WATER_TYPE", LegacyForgeModFluids.REGISTRY_OBJECT);
            mv.visitInsn(POP);
            mv.visitFieldInsn(GETSTATIC, LegacyForgeModFluids.FORGE_MOD, "MILK", LegacyForgeModFluids.REGISTRY_OBJECT);
            mv.visitInsn(POP);
            mv.visitMethodInsn(INVOKESTATIC, LegacyForgeModFluids.FORGE_MOD, "enableMilkFluid", "()V", false);
            mv.visitInsn(RETURN);
        }), "mod/Fluids");
        List<String> refs = refs(out);
        assertEquals(LegacyForgeModFluids.HELPER + ".WATER_TYPE()" + holder, refs.get(0));
        assertTrue(refs.get(1).startsWith(LegacyForgeModFluids.NEO_MOD + ".MILK:"), refs.get(1));
        assertEquals(LegacyForgeModFluids.NEO_MOD + ".enableMilkFluid()V", refs.get(2));
        assertTrue(refs(transformer.getSyntheticClasses().get(LegacyForgeModFluids.HELPER))
                .contains(LegacyForgeModFluids.NEO_MOD + ".WATER_TYPE:Lnet/minecraft/core/Holder;"));
    }

    @Test
    @DisplayName("Against NeoForge 26.3: the fluid fields, the spawn packet constructor and the same-shape client hooks exist")
    void neoForgeShapes() {
        Assumptions.assumeTrue(present(NEOFORGE_263, NEOFORGE_263_CLIENT), "the NeoForge 26.3 jars are not in the local Prism library");
        Function<String, ClassNode> neo = host(NEOFORGE_263, NEOFORGE_263_CLIENT);
        ClassNode neoMod = neo.apply(LegacyForgeModFluids.NEO_MOD);
        for (String field : LegacyForgeModFluids.FLUID_TYPES) {
            assertTrue(neoMod.fields.stream().anyMatch(f -> f.name.equals(field) && f.desc.equals(LegacyForgeModFluids.HOLDER)), field);
        }
        assertTrue(neo.apply(LegacyNetworkHooksBridge.ADD_ENTITY_PACKET).methods.stream()
                .anyMatch(m -> m.name.equals("<init>") && m.desc.equals(LegacyNetworkHooksBridge.ADD_ENTITY_DESC)));
        LegacyForgeHooksBridge.registerClientHooks(transformer, neo);
        long hooks = transformer.getMethodRedirects().keySet().stream()
                .filter(key -> key.owner().equals(LegacyForgeHooksBridge.FORGE_HOOKS_CLIENT)).map(key -> key.name()).distinct().count();
        assertEquals(LegacyForgeHooksBridge.SAME_SHAPE_CLIENT_HOOKS.size(), hooks, "each same-shape client hook is on 26.3");
    }

    @Test
    @DisplayName("NetworkHooks.getEntitySpawningPacket returns vanilla's add-entity packet")
    void spawnPacketOnNeoForge() {
        LegacyNetworkHooksBridge.registerNeoForge(transformer);
        byte[] out = transformer.transformClass(mod("mod/Entity", "(Lnet/minecraft/world/entity/Entity;)Ljava/lang/Object;", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESTATIC, LegacyNetworkHooksBridge.HOOKS, "getEntitySpawningPacket", LegacyNetworkHooksBridge.SPAWN_DESC, false);
            mv.visitInsn(ARETURN);
        }), "mod/Entity");
        assertEquals(LegacyNetworkHooksBridge.NEO_HELPER + ".getEntitySpawningPacket" + LegacyNetworkHooksBridge.SPAWN_DESC, refs(out).get(0));
        assertTrue(refs(transformer.getSyntheticClasses().get(LegacyNetworkHooksBridge.NEO_HELPER))
                .contains(LegacyNetworkHooksBridge.ADD_ENTITY_PACKET + ".<init>" + LegacyNetworkHooksBridge.ADD_ENTITY_DESC));
    }

    @Test
    @DisplayName("CommonHooks.getBurnTime, and Forge's ForgeHooks one renamed to it, reads the rebuilt burn time on 26.3")
    void commonHooksBurnTime() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        com.retromod.shim.neoforge.LegacyBurnTimeBridge.register(transformer, name -> null);
        String desc = "(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/crafting/RecipeType;)I";
        byte[] out = transformer.transformClass(mod("mod/Furnace", "(Lnet/minecraft/world/item/ItemStack;)I", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitInsn(ACONST_NULL);
            mv.visitMethodInsn(INVOKESTATIC, "net/neoforged/neoforge/common/CommonHooks", "getBurnTime", desc, false);
            mv.visitInsn(IRETURN);
        }), "mod/Furnace");
        assertEquals(List.of(com.retromod.shim.neoforge.LegacyBurnTimeBridge.COMPONENT_HELPER + ".getBurnTime" + desc), refs(out));
    }

    private static String forgePath(ClassNode tags, String field) {
        MethodNode clinit = tags.methods.stream().filter(m -> m.name.equals("<clinit>")).findFirst().orElseThrow();
        String last = null;
        for (var insn : clinit.instructions) {
            if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String text) last = text;
            if (insn instanceof FieldInsnNode get && get.getOpcode() == GETSTATIC && get.owner.endsWith("DyeColor")) {
                last = "dyes/" + get.name.toLowerCase(java.util.Locale.ROOT);
            }
            if (insn instanceof FieldInsnNode put && put.getOpcode() == PUTSTATIC) {
                if (put.name.equals(field)) return last;
                last = null;
            }
        }
        return null;
    }

    private static List<String> ldcStrings(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new org.objectweb.asm.ClassReader(classBytes).accept(node, 0);
        List<String> strings = new java.util.ArrayList<>();
        node.methods.forEach(m -> m.instructions.forEach(insn -> {
            if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String text) strings.add(text);
        }));
        return strings;
    }
}
