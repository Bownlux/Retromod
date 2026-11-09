/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forge entry point for the Retromod test mod.
 *
 * <p>Compiled against MC 1.20.1 / Forge 47.x. Retromod transforms it forward
 * to whatever MC version the host is running. On a 26.1.2 host this exercises
 * Forge-targeted redirects + the Mojang-name surface.
 *
 * <p>Each test logs {@code [Retromod-Test-Forge] N (description): success/fail}.
 * Grep for {@code [Retromod-Test-Forge]} in the log to see results.
 *
 * <p>Immediate phase only. Forge has equivalent lifecycle events through
 * {@code MinecraftForge.EVENT_BUS} for deferred phases - those would be a
 * follow-up.
 */
@Mod("retromod_test_mod_forge")
public class RetromodTestModForge {

    private static final Logger LOG = LoggerFactory.getLogger("Retromod-Test-Forge");
    private static final String PREFIX = "[Retromod-Test-Forge]";

    /** A message the server sends a joining player and the client answers. */
    record PingMessage(String text) {}

    /**
     * Round trip through a Forge SimpleChannel: the server sends "ping" when a player joins, the
     * client logs it and answers "pong", and the server logs the answer. On NeoForge this proves
     * Retromod delivers Forge messages through NeoForge payloads in both directions. Only a real
     * client and server exercise it, so the result is in the log rather than the summary.
     */
    private static final net.minecraftforge.network.simple.SimpleChannel PING =
            net.minecraftforge.network.NetworkRegistry.newSimpleChannel(
                    new ResourceLocation("retromod_test_mod_forge", "ping"),
                    () -> "1", "1"::equals, "1"::equals);

    public RetromodTestModForge() {
        runTests();
        PING.registerMessage(0, PingMessage.class,
                (message, buffer) -> buffer.writeUtf(message.text()),
                buffer -> new PingMessage(buffer.readUtf()),
                RetromodTestModForge::handlePing);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(this::sendPingOnLogin);
        net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus()
                .addListener(this::checkMobAttributes);
        net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus()
                .addListener(this::checkAttributeModification);
        // A block built in a common class and registered from this loader class by id.
        net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus()
                .addListener(CrossClassRegistration::register);
        // #302: an addon's knife and registry reads against the installed Farmer's Delight.
        FarmersDelightAddon.register(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
        // A listener on the ModConfigEvent base, which Forge 26.2 made an interface, reads its config.
        ConfigEventCase.register(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
        // An entity type built from its bare name in a deferred register's supplier.
        EntityTypeKeys.register(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
        // #372: a 1.20.1 recipe serializer and advancement trigger, checked once the server's data loads.
        LegacyRecipesAndTriggers.register(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
        ListenerTriggerCase.register();
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(this::checkLoadedData);
        // Old listener objects with explicit priorities and a lone listener, checked when the server starts.
        ListenerPriorities.register();
        // Listeners on EntityInteract and SaplingGrowTreeEvent, which Forge 26.2 replaced.
        RenamedEvents.register();
        // NetworkHooks, ForgeMod reach, tags and the griefing hook, checked once a server runs.
        ForgeSmallRemovals.register(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
        // FakePlayerFactory and instanceof FakePlayer across the library jar, checked once a server runs.
        FakePlayerCase.register();
        // Villager and wandering trader trades from the removed trade events, checked once a server runs.
        LegacyTradeCase.register();
        // Forge's folder pack, which Forge 26.2 removed, built again where mods add their packs. Forge only.
        if (!LegacyEventBaseCase.onNeoForge()) {
            LegacyPathPacks.register(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
            // A spark-style hook on the base TickEvent, unregistered at run time. Forge only.
            TickHookCase.register();
            // Listeners on RenderLevelStageEvent, which Forge 26.2 replaced with frame passes. Client only.
            if (net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) ClientRenderCases.register();
            // Block and item color handlers, which Forge 26.2 and Minecraft 26.1 replaced with tint sources. Client only.
            if (net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) {
                ColorHandlerCases.register(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
            }
            // An item drawn by an old BlockEntityWithoutLevelRenderer, which Minecraft 1.21.4 removed. The item is registered on
            // both sides; its renderer and checks are client only.
            ItemRendererItems.register(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
            if (net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) ItemRendererCases.register();
            // The same item builds a second renderer while items register, before 26.x has its model manager. Client only.
            if (net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) EarlyModelsCase.register();
        }
        // A mod's own sprite atlas on TextureAtlasHolder, as JEI's GUI sprites are, which 26.x removed. Client only;
        // the class is vanilla, so this runs on NeoForge too.
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) {
            LegacyAtlasCase.register(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
            // A KeyMapping subclass whose super(...) names a String category, as Carry On's keybinds do.
            KeyMappingSubclassCase.register(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
            // An arm pose built with a transformer in client setup, as Farmer's Delight's skillet flip is.
            ArmPoseCase.register(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
            // The frame's tick delta through the old getter, as Jade's overlay reads it.
            FrameDeltaCase.register(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
            // A mixin on a method 26.1 renamed, as many client mods hook resizeDisplay().
            RenamedMixinTargetCase.register();
            // Item properties registered in client setup's queued work, as Farmer's Delight and Sophisticated Core register theirs.
            ItemPropertyCase.register(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
            // The server brand and connection id that JEI reads while joining a world.
            ClientConnectionCase.register();
            // Layers on the player renderers through getSkinMap() and the AddLayers lookups, as Sophisticated Backpacks and Curios add theirs.
            PlayerLayerCase.register(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
            // An old block entity renderer and entity renderer driven through 26.x's state and submit, as Farmer's Delight's are.
            OldRendererDrawCase.register();
            // An old entity renderer drawing a cube with the 1.20.1 vertex calls from renderFinal, as GeckoLib 4's does.
            OldCubeRendererCase.register(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
            // Old model calls through ItemRenderer and BlockRenderDispatcher, as Sophisticated Backpacks, Farmer's Delight and Carry On make them.
            BakedItemModelCase.register(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
        }
        // ForgeRegistries.ENCHANTMENTS lookups, checked once a server has loaded the world's enchantments.
        EnchantmentRegistryReads.register();
        // Reload listeners of the 1.20.1 shape, which 1.21.2 and 1.21.9 changed: the server's data reload on both
        // sides, and the client's resource reload, registered the way GeckoLib registers its cache.
        OldReloadListenerCases.register();
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) {
            OldReloadListenerCases.registerClient(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
        }
        // Component.Serializer's JSON text statics and the two Gson adapters, checked once a server runs.
        TextJsonCase.register();
        // The level height getters 1.21.2 renamed, called and overridden, checked once a server runs.
        LevelHeightCase.register();
        // Registry.getTags() pairs, an advancement's id and a mod's own LivingEvent, checked once a server runs, and
        // the render and screen event members Forge 26.2 and 26.3 dropped, checked as the client posts them.
        EventMemberCases.register();
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) EventMemberCases.registerClient();
        // Listeners on a parent event, Forge's version classes, setConfig and FMLConfig, which stopped Forge mods
        // while NeoForge constructed them.
        NeoForgeStartupCases.register();
        // Handler, entity and capability data saved with the old NBT methods, and an item's old capability hook,
        // checked once a server runs. Forge only: NeoForge rebuilds Forge's capabilities on its own system.
        if (!LegacyEventBaseCase.onNeoForge()) {
            LegacyCapabilityDataCases.register(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
        }
        // ModLoader.get() posts and warnings, checked once a server runs. Forge only, as NeoForge has its own ModLoader.
        if (!LegacyEventBaseCase.onNeoForge()) ModLoaderCases.register();
        // Lambda and @SubscribeEvent listeners that cancel a Forge event, which Forge 26.2 cancels by return value.
        // Forge only: NeoForge keeps its own cancel calls.
        if (!LegacyEventBaseCase.onNeoForge()) {
            LegacyCancelCases.register();
            // Listeners on ScreenEvent.Init, which Forge 26.2 posts only as Init.Pre and Init.Post. Client only.
            if (net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) BuslessParentCases.register();
            // A sapling listener's denial read back after the post, which Forge 26.2 renamed. Forge only.
            LegacyResultCases.register();
            // An old HUD overlay and render-event listeners, which Forge 26.2 removed. Client only; NeoForge maps
            // these events onto its own layers.
            if (net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) {
                LegacyHudOverlays.register(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
            }
            // Chunk render type sets, block layers and int[] quads, which Forge 26.2 removed, checked at client
            // setup. Client only; NeoForge keeps its own.
            if (net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) {
                ModelFoundationCases.register(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
            }
            // A wrapped block model and an extra model through Forge 1.20.1's baked model events, which Forge 26.2
            // rebuilt. Client only; NeoForge keeps its own.
            if (net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) {
                BakedModelCases.register(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
            }
            // A geometry loader whose model nests a vanilla model, which Forge 26.2's loaders no longer read the same way.
            // Client only; NeoForge keeps its own.
            if (net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) {
                GeometryLoaderCases.register(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
            }
        }
        // The static brewing registry, which Forge 26.2 replaced with an event, read back once a server runs.
        // Forge only: NeoForge has no static lookups, so Retromod bridges only the adds there.
        if (!LegacyEventBaseCase.onNeoForge()) {
            LegacyBrewingRegistryCase.register(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
        }
        // A command context of the mod's own, which 1.20.5 made a registry provider, used while commands register.
        CommandContextCase.register();
        // A capability registered through RegisterCapabilitiesEvent, attached to an entity and a stack, and read back.
        // Forge only: on NeoForge, Retromod rebuilds Forge's capabilities on NeoForge's own system.
        if (!LegacyEventBaseCase.onNeoForge()) {
            LegacyCapabilityCases.register(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
        }
        // Biome modifier serializers registered as 1.20.1 codecs, which Forge 26.2 and NeoForge read as map codecs,
        // and a biome with 1.20.1's carvers per step. A failure is this case's result, so the other cases still run.
        try {
            LegacyBiomeModifierCase.register(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
        } catch (Throwable t) {
            LOG.info("{} 1.20.1 biome modifier codecs registered while the mod constructs: fail: {}: {}", PREFIX,
                    t.getClass().getSimpleName(), t.getMessage());
        }
        // A loot function type and a condition type built around 1.20.1 serializers, which 26.1 turned into map codec
        // registries, used by a data loot table the world reads. A failure is this case's result.
        try {
            LegacyLootTypeCase.register(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
        } catch (Throwable t) {
            LOG.info("{} 1.20.1 loot function and condition types registered while the mod constructs: fail: {}: {}", PREFIX,
                    t.getClass().getSimpleName(), t.getMessage());
        }
        // A structure processor type from a deferred register, which 26.2 turned into a map codec registry, named by a
        // data processor list. A failure is this case's result.
        try {
            LegacyProcessorTypeCase.register(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
        } catch (Throwable t) {
            LOG.info("{} 1.20.1 structure processor type registered while the mod constructs: fail: {}: {}", PREFIX,
                    t.getClass().getSimpleName(), t.getMessage());
        }
        // Placement modifier and block state provider types and subclasses, whose bases 26.3 turned into interfaces.
        try {
            PlacementTypesCase.register(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
        } catch (Throwable t) {
            LOG.info("{} 1.20.1 placement modifier and block state provider types registered while the mod constructs: "
                    + "fail: {}: {}", PREFIX, t.getClass().getSimpleName(), t.getMessage());
        }
        // Old ingredient serializers registered through CraftingHelper, read by a data recipe once a server runs.
        // Forge only: Retromod registers them in Forge 26.2's ingredient serializer registry.
        if (!LegacyEventBaseCase.onNeoForge()) {
            try {
                LegacyIngredientCase.register(
                        net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
            } catch (Throwable t) {
                // A serializer class that does not link fails this case only.
                LOG.info("{} old ingredient serializers registered while the mod constructs: fail: {}: {}", PREFIX,
                        t.getClass().getSimpleName(), t.getMessage());
            }
        }
    }

    /**
     * #372: Fossils and Archaeology's recipes and advancement criteria are read from data with the
     * 1.20.1 APIs. They load with the server's data, so the results are in the log rather than the summary.
     */
    private void checkLoadedData(net.minecraftforge.event.server.ServerStartedEvent event) {
        String recipes;
        try {
            recipes = LegacyRecipesAndTriggers.recipesLoad(event.getServer());
        } catch (Throwable t) {
            recipes = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        LOG.info("{} #372 a 1.20.1 recipe serializer loads, matches, and syncs its recipe (server started): {}",
                PREFIX, recipes);
        LOG.info("{} #372 a 1.20.1 advancement trigger reads its player and item predicates (server started): {}",
                PREFIX, LegacyRecipesAndTriggers.triggerRead());
        String vanillaRecipes;
        try {
            vanillaRecipes = LegacyRecipesAndTriggers.vanillaRecipesListed(event.getServer());
        } catch (Throwable t) {
            vanillaRecipes = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        LOG.info("{} getAllRecipesFor lists the vanilla smelting recipes (server started): {}", PREFIX, vanillaRecipes);
        String explosion;
        try {
            explosion = LegacyExplosionCase.run(event.getServer().overworld());
        } catch (Throwable t) {
            explosion = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        LOG.info("{} #366 an Explosion subclass explodes, then finishes (server started): {}", PREFIX, explosion);
        String rules;
        try {
            rules = LegacyExplosionCase.gameRuleRead(event.getServer().overworld());
        } catch (Throwable t) {
            rules = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        LOG.info("{} #366 level.getGameRules().getBoolean(rule) (server started): {}", PREFIX, rules);
        String surfaceRules;
        try {
            surfaceRules = SurfaceRuleCases.wrapsTheWorldRule(event.getServer());
        } catch (Throwable t) {
            surfaceRules = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        LOG.info("{} the overworld's surface rule through the old accessor, wrapped in a rule source of the mod's own (server started): {}",
                PREFIX, surfaceRules);
        // Item stacks need item components, which are bound only once a server loads its data.
        String ingredients;
        try {
            ingredients = ConstructionCases.compoundIngredientsMatch() ? "success" : "fail: assertion was false";
        } catch (Throwable t) {
            ingredients = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        LOG.info("{} A CompoundIngredient subclass and CompoundIngredient.of match their children's items (server started): {}",
                PREFIX, ingredients);
        String food;
        try {
            food = WorldLoadMemberCases.foodAndRemainder() ? "success" : "fail: assertion was false";
        } catch (Throwable t) {
            food = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        LOG.info("{} ItemStack.isEdible and the crafting remainder (server started): {}", PREFIX, food);
        String earlyStacks;
        try {
            earlyStacks = EarlyStackCases.matchOnServer();
        } catch (Throwable t) {
            earlyStacks = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        LOG.info("{} Item stacks built while constructing match, stack with and save as new ones (server started): {}",
                PREFIX, earlyStacks);
        // A block entity saves through the server's registries, and Lootr reads persistent data from its tile ticker.
        String blockEntityData;
        try {
            blockEntityData = BlockEntityDataCases.savesAndLoads();
        } catch (Throwable t) {
            blockEntityData = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        LOG.info("{} A 1.20.1 block entity save and load, with getPersistentData under ForgeData (server started): {}",
                PREFIX, blockEntityData);
        String vanillaData;
        try {
            vanillaData = BlockEntityDataCases.vanillaChestKeepsItsData() ? "success" : "fail: assertion was false";
        } catch (Throwable t) {
            vanillaData = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        LOG.info("{} getPersistentData on a vanilla chest, one compound per block entity (server started): {}",
                PREFIX, vanillaData);
        // Arrows, stack comparison and world data saves that 1.20.5 to 1.21 reshaped.
        String arrows;
        try {
            arrows = LegacyGameplayCases.arrowsBuildAndSave(event.getServer().overworld());
        } catch (Throwable t) {
            arrows = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        LOG.info("{} An arrow built with the 1.20.1 constructors and knockback saves its pickup stack (server started): {}",
                PREFIX, arrows);
        // A mob's own getDefaultLootTable(), which hosts from 1.20.5 no longer ask, as Alex's Mobs names its tables.
        String ownLoot;
        try {
            ownLoot = OwnLootMobCase.dropsItsOwnTable(event.getServer().overworld());
        } catch (Throwable t) {
            ownLoot = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        LOG.info("{} A mob that overrides getDefaultLootTable() drops its own table when it dies (server started): {}", PREFIX, ownLoot);
        String sameTags;
        try {
            sameTags = LegacyGameplayCases.sameItemSameTags() ? "success" : "fail: assertion was false";
        } catch (Throwable t) {
            sameTags = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        LOG.info("{} ItemStack.isSameItemSameTags and setHoverName after 1.20.5 (server started): {}", PREFIX, sameTags);
        String tags;
        try {
            tags = LegacyGameplayCases.tagsKeepWrites() ? "success" : "fail: assertion was false";
        } catch (Throwable t) {
            tags = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        LOG.info("{} getOrCreateTag and getOrCreateTagElement keep a new stack's writes after 1.20.5 (server started): {}",
                PREFIX, tags);
        String dataSave;
        try {
            dataSave = LegacyGameplayCases.worldDataSaves() ? "success" : "fail: assertion was false";
        } catch (Throwable t) {
            dataSave = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        LOG.info("{} SavedData.save(tag) through SavedData after 1.20.5 (server started): {}", PREFIX, dataSave);
        // A mod-bus class whose key-mapping event left the mod bus on Forge 26.2; a dedicated server never loads it.
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) MovedModBusSubscriber.report();
    }

    /**
     * #346: a 1.20.1 mob's attributes come from {@code Mob.createMobAttributes()} or another host
     * factory, none of which has the tempt range a 1.21.2+ {@code TemptGoal} reads, so Cake Cow
     * crashed on its first tick. Attribute values are only bound once registration reaches this
     * event, the point where mods build them, so the result is in the log rather than the summary.
     */
    private void checkMobAttributes(net.minecraftforge.event.entity.EntityAttributeCreationEvent event) {
        String result;
        try {
            result = builtAttributesHaveTemptRange() ? "success" : "fail: assertion was false";
        } catch (Throwable t) {
            result = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        LOG.info("{} #346 attributes built from Mob, Monster and LivingEntity have the tempt range (attribute event): {}",
                PREFIX, result);
    }

    /**
     * #283: Scorched Guns adds its attributes to the player in this event with a bare attribute,
     * which NeoForge 1.21 retyped to a holder. The event fires during registration, so the result
     * is in the log rather than the summary.
     */
    private void checkAttributeModification(net.minecraftforge.event.entity.EntityAttributeModificationEvent event) {
        String result;
        try {
            event.add(net.minecraft.world.entity.EntityType.PLAYER,
                    net.minecraft.world.entity.ai.attributes.Attributes.LUCK);
            result = event.has(net.minecraft.world.entity.EntityType.PLAYER,
                    net.minecraft.world.entity.ai.attributes.Attributes.LUCK) ? "success" : "fail: luck was not added";
        } catch (Throwable t) {
            result = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        LOG.info("{} #283 EntityAttributeModificationEvent.add with a bare attribute (attribute event): {}",
                PREFIX, result);
    }

    private void sendPingOnLogin(
            net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            PING.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                    new PingMessage("ping"));
        }
    }

    private static void handlePing(PingMessage message,
            java.util.function.Supplier<net.minecraftforge.network.NetworkEvent.Context> supplier) {
        net.minecraftforge.network.NetworkEvent.Context context = supplier.get();
        net.minecraft.server.level.ServerPlayer sender = context.getSender();
        context.enqueueWork(() -> {
            if (sender == null) {
                LOG.info("{} packet round trip: client received {}", PREFIX, message.text());
                PING.sendToServer(new PingMessage("pong"));
            } else {
                LOG.info("{} packet round trip: server received {} from {}", PREFIX,
                        message.text(), sender.getGameProfile().getName());
            }
        });
        context.setPacketHandled(true);
    }

    private void runTests() {
        LOG.info("{} Starting tests", PREFIX);
        int n = 0, passed = 0;

        n++; passed += check(n, "mod loaded", () -> true);
        n++; passed += check(n, "Component.literal", () -> {
            Component c = Component.literal("hello");
            return c != null && "hello".equals(c.getString());
        });
        n++; passed += check(n, "ResourceLocation 2-arg ctor (factory)", () -> {
            ResourceLocation id = new ResourceLocation("retromod", "test");
            return id != null && "retromod:test".equals(id.toString());
        });
        n++; passed += check(n, "Blocks.STONE static field", () -> Blocks.STONE != null);
        n++; passed += check(n, "Items.DIAMOND static field", () -> Items.DIAMOND != null);
        n++; passed += check(n, "Block.defaultBlockState()", () ->
            Blocks.STONE.defaultBlockState() != null);
        n++; passed += check(n, "BlockState round-trip", () ->
            Blocks.STONE.defaultBlockState().getBlock() == Blocks.STONE);
        // #192 uses the same target-SRG member after starting from the older
        // func_176223_P spelling. The owner-qualified conversion itself is covered by
        // TargetSrgMapperTest; this is the Forge load-time target-member smoke check.
        n++; passed += check(n, "#192 Forge 1.20.1 target SRG block state", () ->
            Blocks.DIRT.defaultBlockState().getBlock() == Blocks.DIRT);

        // The 1.20.1 production jar stores this call as m_21211_. Forge 1.20.6+
        // exposes getUseItem instead, so resolving this call catches the #204 namespace gap.
        n++; passed += check(n, "#204 Forge official-name LivingEntity.getUseItem", () -> {
            try {
                callLegacyGetUseItem(null);
                return false;
            } catch (NullPointerException expected) {
                return true;
            }
        });

        // #349: 26.3 widened Axis.of to take the Vector3fc interface and dropped the Vector3f
        // form this call was compiled against, the same JOML change 26.2 made elsewhere.
        n++; passed += check(n, "#349 Axis.of(Vector3f) after the JOML widening", () -> {
            org.joml.Vector3f turned = com.mojang.math.Axis.of(new org.joml.Vector3f(0f, 1f, 0f))
                    .rotationDegrees(90f).transform(new org.joml.Vector3f(1f, 0f, 0f));
            return Math.abs(turned.x) < 1.0e-4f && Math.abs(turned.z + 1f) < 1.0e-4f;
        });

        // ─── #85/#115: FMLJavaModLoadingContext Forge->NeoForge bridge ───
        // The idiom nearly every Forge @Mod constructor opens with, to grab its event bus for
        // DeferredRegister. On a Forge host this is the native FML class and the check passes
        // trivially. On a NeoForge host NeoForge DELETED
        // net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext, so Retromod must redirect
        // this reference to the B4 bridge synthetic and EMBED it per-mod
        // (com/retromod/embedded/<mod>/FMLJavaModLoadingContext). If that redirect+embed
        // regresses, this line throws NoClassDefFoundError for the Forge class - exactly the
        // #85/#115 construct crash (Macaw's / Management Wanted on NeoForge 26.2). The guarding
        // try/catch in check() turns a regression into a visible fail line instead of taking the
        // whole mod down, but the reference a native @Mod would crash on is the same one.
        // only meaningful when this Forge mod runs on a NeoForge host (deploy this jar to a
        // NeoForge instance's retromod-input/); on a Forge host it is native. The host-independent
        // redirect assertion lives in the JUnit ForgeEventMigrationTest.
        n++; passed += check(n, "#85/#115 FMLJavaModLoadingContext.get().getModEventBus() bridge", () ->
            net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus() != null);

        // #207: these Forge FML types moved as a package, and 26.x replaced the dist field with
        // an accessor. Class literals keep the checks side-effect free while still forcing linkage.
        n++; passed += check(n, "#207 Forge FML package migration", () ->
            net.minecraftforge.fml.loading.FMLEnvironment.dist != null
                && net.minecraftforge.fml.util.thread.SidedThreadGroups.SERVER != null
                && net.minecraftforge.forgespi.language.IModInfo.class.getName() != null);
        n++; passed += check(n, "#207 Forge spawn egg replacement is loadable", () ->
            net.minecraftforge.common.ForgeSpawnEggItem.class.getName() != null);

        // #234: the 1.20.1 source field was renamed in 26.1. A direct read proves
        // the transformed Forge class links to the current GameRules field.
        n++; passed += check(n, "#234 GameRules mob-griefing field", () ->
            GameRules.RULE_MOBGRIEFING != null);
        // 1.21.5 made getString, getInt and getBoolean return Optional, so these calls compiled
        // against 1.20.1 reach the host only through Retromod's NBT getter bridge.
        n++; passed += check(n, "CompoundTag getString, getInt and getBoolean round-trips", () -> {
            net.minecraft.nbt.CompoundTag c = new net.minecraft.nbt.CompoundTag();
            c.putString("k", "v");
            c.putInt("n", 42);
            c.putBoolean("b", true);
            return "v".equals(c.getString("k")) && c.getInt("n") == 42 && c.getBoolean("b")
                && "".equals(c.getString("missing")) && c.getInt("missing") == 0;
        });
        // ResourceKey.location() and the ChunkPos(long) constructor are gone on 26.x. Forge's
        // 26.1 shim once kept its own list and skipped these, while the CLI repaired them.
        n++; passed += check(n, "ResourceKey.location() and new ChunkPos(long) on 26.x", () ->
            net.minecraft.world.level.Level.OVERWORLD.location() != null
                && new net.minecraft.world.level.ChunkPos(0L) != null);
        // 1.21.4 turned the foliage color getters into constants. Only the color is compared,
        // because newer versions return the same color with a full alpha byte.
        n++; passed += check(n, "1.21.4 foliage color getters", () ->
            (net.minecraft.world.level.FoliageColor.getEvergreenColor() & 0xFFFFFF) == 0x619961
                && (net.minecraft.world.level.FoliageColor.getBirchColor() & 0xFFFFFF) == 0x80A755
                && (net.minecraft.world.level.FoliageColor.getDefaultColor() & 0xFFFFFF) == 0x48B518
                && (net.minecraft.world.level.FoliageColor.getMangroveColor() & 0xFFFFFF) == 0x92C648);
        // Members world generation, loot chests, player join, crash reports and meals reach on 26.x.
        n++; passed += check(n, "ChunkPos.asLong packs region keys on 26.1 and newer", WorldLoadMemberCases::chunkKeys);
        n++; passed += check(n, "WeightedEntry and WeightedRandom pick by weight after 1.21.5", WorldLoadMemberCases::weightedPick);
        n++; passed += check(n, "EntityDataSerializers.COMPOUND_TAG after 1.21.9", WorldLoadMemberCases::compoundTagSerializer);
        n++; passed += check(n, "SystemReport.setDetail(name, supplier) on 26.2", WorldLoadMemberCases::systemReportDetail);
        n++; passed += check(n, "A chest's lootTable id and setLootTable(id, seed) after 1.20.5", WorldLoadMemberCases::lootTableId);
        // The stack checks need item stacks, so they are logged once a server starts.
        n++; passed += check(n, "GameEvent radius while constructing", WorldLoadMemberCases::gameEventRadius);
        n++; passed += check(n, "VillagerData.getProfession() after 1.21.5", WorldLoadMemberCases::profession);
        // Biomes O' Plenty builds its surface rules while it constructs; 26.3 replaced them with material rules.
        n++; passed += check(n, "SurfaceRules factories, constants and isBiome with no server", SurfaceRuleCases::buildsWithoutServer);
        // Tectonic and Lithostitched compute and walk old density functions; 26.3 compiles them into samplers.
        n++; passed += check(n, "an old density function computes, and mapAll swaps a marker under a reference", DensityFunctionCases::computesAndSwaps);

        // #260: item bases Minecraft deleted rather than renamed. RecordItem went when jukebox
        // playability became a component, EnchantedBookItem when stored enchantments did. Neither
        // has a class successor, and the class-move table used to name the nearest surviving class:
        // a final record for one, a constructor-less holder for the other. Both now rebase onto a
        // generated Item subclass. Only linkage is asserted, since the dropped constructor
        // arguments are gone by design.
        n++; passed += check(n, "#260 removed item base RecordItem", () ->
            linksWithoutInheritanceError(LegacyDisc::new));
        n++; passed += check(n, "#260 removed item base EnchantedBookItem", () ->
            linksWithoutInheritanceError(LegacyBook::new));

        // #267/#302: NeoForge moved registerConfig to ModContainer and replaced Forge's generic
        // IConfigSpec, so this exact MDK line failed in the constructor.
        n++; passed += check(n, "#267 ModLoadingContext.registerConfig", () -> {
            net.minecraftforge.common.ForgeConfigSpec.Builder builder =
                    new net.minecraftforge.common.ForgeConfigSpec.Builder();
            builder.define("retromod_test_value", true);
            net.minecraftforge.fml.ModLoadingContext.get().registerConfig(
                    net.minecraftforge.fml.config.ModConfig.Type.COMMON, builder.build());
            return true;
        });
        // #267: menu types built from a FriendlyByteBuf factory, which NeoForge reads as a
        // RegistryFriendlyByteBuf. Building the type proves the adapter links.
        n++; passed += check(n, "#267 IForgeMenuType.create", () ->
            net.minecraftforge.common.extensions.IForgeMenuType.create((id, inventory, buf) -> null) != null);
        // #306: ForgeRegistries.Keys also held the vanilla keys, which NeoForge only exposes
        // through Registries.
        n++; passed += check(n, "#306 ForgeRegistries.Keys.ITEMS", () ->
            net.minecraftforge.registries.ForgeRegistries.Keys.ITEMS != null);

        // #267: a SimpleChannel with a directional message. NeoForge deleted the whole API, so
        // this links only through Retromod's channel stand-in, which registers it as a payload.
        n++; passed += check(n, "#267 SimpleChannel registerMessage", () -> {
            net.minecraftforge.network.simple.SimpleChannel channel =
                    net.minecraftforge.network.NetworkRegistry.newSimpleChannel(
                            new ResourceLocation("retromod_test_mod_forge", "main"),
                            () -> "1", "1"::equals, "1"::equals);
            channel.registerMessage(0, String.class,
                    (message, buffer) -> buffer.writeUtf(message),
                    buffer -> buffer.readUtf(),
                    (message, context) -> context.get().setPacketHandled(true),
                    java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER));
            return true;
        });

        // #306/#308: NeoForge deleted Forge's capability API; Retromod supplies it under the same
        // names. One token type must name one capability, and orEmpty answers only for it.
        n++; passed += check(n, "#306/#308 Forge capability token and LazyOptional", () -> {
            net.minecraftforge.common.capabilities.Capability<String> cap =
                    net.minecraftforge.common.capabilities.CapabilityManager.get(
                            new net.minecraftforge.common.capabilities.CapabilityToken<String>() {});
            net.minecraftforge.common.capabilities.Capability<String> again =
                    net.minecraftforge.common.capabilities.CapabilityManager.get(
                            new net.minecraftforge.common.capabilities.CapabilityToken<String>() {});
            net.minecraftforge.common.util.LazyOptional<String> value =
                    net.minecraftforge.common.util.LazyOptional.of(() -> "stored");
            return cap == again && "stored".equals(cap.orEmpty(cap, value).orElse(null));
        });
        // #306: ToolActions were renamed ItemAbilities on NeoForge with the same constants.
        n++; passed += check(n, "#306 ToolActions.AXE_STRIP", () ->
            net.minecraftforge.common.ToolActions.AXE_STRIP != null);
        // #311: a handler that reports whether it handled the packet, built with the message builder.
        n++; passed += check(n, "#311 SimpleChannel consumerNetworkThread(ToBooleanBiFunction)", () -> {
            net.minecraftforge.network.simple.SimpleChannel channel =
                    net.minecraftforge.network.NetworkRegistry.newSimpleChannel(
                            new ResourceLocation("retromod_test_mod_forge", "handled"),
                            () -> "1", "1"::equals, "1"::equals);
            channel.messageBuilder(String.class, 0)
                    .encoder((message, buffer) -> buffer.writeUtf(message))
                    .decoder(buffer -> buffer.readUtf())
                    .consumerNetworkThread((message, context) -> true)
                    .add();
            return true;
        });
        // #338: 1.20.5 split Block.use, so an old override and its super.use need a bridge.
        n++; passed += check(n, "#338 pre-1.20.5 Block.use override", RetromodTestModForge::oldUseOverrideRuns);
        // #344: 26.3 ships no GLFW, so a mod's own GLFW clock read failed with
        // NoClassDefFoundError. Clients only: a dedicated server never had GLFW.
        n++; passed += check(n, "#344 GLFW clock on a host without GLFW", () ->
            !net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()
                || org.lwjgl.glfw.GLFW.glfwGetTime() >= 0.0);

        // #346: what a 1.20.1 mob calls for its goals, food, offspring and milking, against the
        // members later hosts removed. Cake Cow crashed as soon as it was spawned. Calls on a null
        // mob prove linkage only: the bridge links, then forwards to the missing receiver.
        n++; passed += check(n, "#346 TemptGoal with an Ingredient", () -> linksWithoutInheritanceError(() ->
            new net.minecraft.world.entity.ai.goal.TemptGoal(null, 1.0,
                    net.minecraft.world.item.crafting.Ingredient.of(Items.WHEAT), false)));
        n++; passed += check(n, "#346 Ingredient.of(ItemStack...)", () -> linksWithoutInheritanceError(() ->
            net.minecraft.world.item.crafting.Ingredient.of(new ItemStack(Items.WHEAT))));
        n++; passed += check(n, "#346 EntityType.create(Level)", () -> linksWithoutInheritanceError(() ->
            net.minecraft.world.entity.EntityType.COW.create((net.minecraft.world.level.Level) null)));
        n++; passed += check(n, "#346 Mob.finalizeSpawn with a CompoundTag", () -> linksWithoutInheritanceError(() ->
            ((net.minecraft.world.entity.Mob) null).finalizeSpawn(null, null,
                    net.minecraft.world.entity.MobSpawnType.BREEDING, null, null)));
        n++; passed += check(n, "#346 LivingEntity.swing(hand, send)", () -> linksWithoutInheritanceError(() ->
            ((LivingEntity) null).swing(net.minecraft.world.InteractionHand.MAIN_HAND, true)));
        // A mob's own override of those removed methods still runs when it calls them on itself.
        n++; passed += check(n, "#346 a mob's own five-argument finalizeSpawn runs for its offspring",
                OwnOverrideMob::breedReachesOwnOverride);
        n++; passed += check(n, "#346 an entity's own swing(hand, send) runs when it swings",
                OwnOverrideMob::swingReachesOwnOverride);
        n++; passed += check(n, "#346 calls through Mob and LivingEntity reach the mob's own overrides",
                OwnOverrideMob::hostTypedCallsReachOwnOverrides);
        // #347: getValues() returned an ImmutableMap here, a Map from 1.20.5 and a stream from 26.1,
        // so MCreator's copy of a block's properties onto the next block failed on newer hosts.
        n++; passed += check(n, "#347 BlockState.getValues() copies a block's properties",
                RetromodTestModForge::stateValuesCopy);
        // This mod ships only pre-1.21.4 item models, which 1.21.4 reads through item definitions.
        n++; passed += check(n, "Legacy item models get 1.21.4 item definitions",
                RetromodTestModForge::legacyItemDefinitions);
        // #347: the other members its procedures call that later hosts changed. Calls on a null
        // receiver prove linkage only: the bridge links, then fails on the missing receiver.
        n++; passed += check(n, "#347 CommandSourceStack with an int permission level",
                RetromodTestModForge::legacyCommandSource);
        n++; passed += check(n, "#347 Inventory.clearOrCountMatchingItems(predicate, max, craftSlots)", () ->
            linksWithoutInheritanceError(() -> ((net.minecraft.world.entity.player.Inventory) null)
                    .clearOrCountMatchingItems(stack -> true, 0, null)));
        n++; passed += check(n, "#347 Player.drop(stack, retainOwnership)", () -> linksWithoutInheritanceError(() ->
            ((net.minecraft.world.entity.player.Player) null).drop(ItemStack.EMPTY, false)));
        n++; passed += check(n, "#347 Player.drop(stack, throwRandomly, retainOwnership)", () ->
            linksWithoutInheritanceError(() ->
                ((net.minecraft.world.entity.player.Player) null).drop(ItemStack.EMPTY, true, false)));
        n++; passed += check(n, "#347 LivingEntity.hurt(source, amount)", () -> linksWithoutInheritanceError(() ->
            ((LivingEntity) null).hurt(null, 1f)));
        n++; passed += check(n, "#347 IntegerProperty.getPossibleValues()", () ->
            net.minecraft.world.level.block.state.properties.BlockStateProperties.AGE_3.getPossibleValues().size() == 4);
        // An empty stack takes no damage, so this one also checks the answer.
        n++; passed += check(n, "#347 ItemStack.hurt(amount, random, player)", () ->
            !ItemStack.EMPTY.hurt(1, net.minecraft.util.RandomSource.create(), null));
        n++; passed += check(n, "#347 ServerAdvancementManager.getAdvancement and PlayerAdvancements.award", () ->
            linksWithoutInheritanceError(() -> ((net.minecraft.server.ServerAdvancementManager) null)
                    .getAdvancement(new ResourceLocation("retromod", "tea")))
                && linksWithoutInheritanceError(() -> ((net.minecraft.server.PlayerAdvancements) null)
                    .award((net.minecraft.advancements.Advancement) null, "drank")));
        n++; passed += check(n, "#347 ServerPlayer.server", () -> linksWithoutInheritanceError(() -> {
            net.minecraft.server.MinecraftServer server = ((net.minecraft.server.level.ServerPlayer) null).server;
        }));
        n++; passed += check(n, "#347 ItemHandlerHelper.giveItemToPlayer", () -> linksWithoutInheritanceError(() ->
            net.minecraftforge.items.ItemHandlerHelper.giveItemToPlayer(null, ItemStack.EMPTY)));

        // #349: 26.3 removed LivingEntity.swinging, which a physics mod reads to react to the arm
        // swing. A read asks isSwinging() instead, also through a player, which javac names as the
        // owner. Reads on a null receiver prove linkage only.
        n++; passed += check(n, "#349 LivingEntity.swinging read after 26.3 removed the field", () ->
            linksWithoutInheritanceError(() -> {
                boolean swinging = ((LivingEntity) null).swinging;
            }));
        n++; passed += check(n, "#349 Player.swinging read through the player", () ->
            linksWithoutInheritanceError(() -> {
                boolean swinging = ((net.minecraft.world.entity.player.Player) null).swinging;
            }));
        // #349: 26.3 removed the armor trim atlas, and Retromod rebuilds it under its own id. The
        // read reaches that id even on a dedicated server, where Sheets itself is absent. A server
        // before 26.3 still has the atlas but no client classes, so it has nothing to check.
        n++; passed += check(n, "#349 Sheets.ARMOR_TRIMS_SHEET on a 26.3 dedicated server", () ->
            net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient() || !trimsUsePalettes()
                || "retromod".equals(net.minecraft.client.renderer.Sheets.ARMOR_TRIMS_SHEET.getNamespace()));

        n++; passed += check(n, "In-place loading runs the cached copy and leaves the original unchanged",
            () -> inPlaceCopyAndUntouchedOriginal(RetromodTestModForge.class));

        // #362: the companion library jar (retromod-test-lib-forge) takes these types in its API.
        // Retromod rebuilds them inside every translated jar on newer hosts, so the calls only
        // link when the test mod reuses the library's copies instead of its own.
        n++; passed += check(n, "#362 a library's API taking Forge's LazyOptional links across jars",
            LibraryCalls::lazyOptionalLinks);
        n++; passed += check(n, "#362 a library's API taking the removed tool Tier links across jars",
            LibraryCalls::tierLinks);
        // #358: the library declares no minecraft dependency, so its Minecraft version is only in
        // its manifest, as Project Rankine ships it. Built for the same version as this mod, it
        // must be translated exactly when this mod is.
        n++; passed += check(n, "#358 a library versioned only by its manifest is translated with this mod",
            () -> LibraryCalls.manifestVersionedLibraryTranslatedLike(RetromodTestModForge.class,
                    "retromod-test-mod-forge"));
        // A move for a library's users never applies to the jar that defines the class, as GeckoLib 4's own
        // jar was moved onto com.geckolib and its classes could not load.
        n++; passed += check(n, "A class this mod ships under a library name Retromod moves stays its own",
            OwnLibraryCopy::staysTheModsOwn);

        // #340: 26.3 removed NumberProvider, ConstantValue and UniformGenerator, and its loot pools
        // and count functions take int or float providers.
        n++; passed += check(n, "#340 loot numbers built in code reach a loot pool and a set count function",
            LootNumbers::poolAndCountBuild);
        // #336: 26.3 rebuilt the loot entry builders, and its inventory takes a prediction when a
        // menu hands items back as it closes. The inventory call on a null receiver proves linkage only.
        n++; passed += check(n, "#336 loot entries built in code reach a loot pool",
            LootEntries::itemAndEmptyEntriesBuild);
        n++; passed += check(n, "#336 Inventory.placeItemBackInInventory(stack)", () -> linksWithoutInheritanceError(() ->
            ((net.minecraft.world.entity.player.Inventory) null).placeItemBackInInventory(ItemStack.EMPTY)));

        // #283: this mod declares minecraft [1.20,), like Scorched Guns, whose 1.20 lower bound took
        // the 1.19.4 SRG names, so level() and onGround() became getLevel() and isOnGround(). This
        // jar ships official names, so the case is a smoke check; SrgVersionDriftTest covers the ids.
        n++; passed += check(n, "#283 Entity.level() and onGround() in a mod built from 1.20", () ->
            linksWithoutInheritanceError(() -> ((net.minecraft.world.entity.Entity) null).level())
                && linksWithoutInheritanceError(() -> ((net.minecraft.world.entity.Entity) null).onGround()));
        // #283: the rest of Scorched Guns' registration shapes, each of which stopped its
        // registration on NeoForge 1.21.1. Blocks built outside registration only prove linkage.
        n++; passed += check(n, "#283 a MobEffect declaring its modifier with a UUID string",
            LegacyRegistrationShapes::effectDeclaresItsModifier);
        n++; passed += check(n, "#283 DropExperienceBlock, AmethystClusterBlock, StairBlock and cauldron constructors",
            () -> linksWithoutInheritanceError(LegacyRegistrationShapes::buildOldBlocks));
        n++; passed += check(n, "#283 CauldronInteraction.EMPTY as a map", () ->
            linksWithoutInheritanceError(LegacyRegistrationShapes::readEmptyCauldronTable));
        n++; passed += check(n, "#283 Rarity.create adds a rarity, or the vanilla fallback where Rarity is a plain enum",
            LegacyRegistrationShapes::rarityWasAdded);
        n++; passed += check(n, "#283 a vanilla ArmorMaterials constant read as an ArmorMaterial",
            LegacyRegistrationShapes::vanillaArmorIsAMaterial);
        n++; passed += check(n, "#283 a block's menu provider asked through another block", () ->
            linksWithoutInheritanceError(LegacyRegistrationShapes::menuThroughAnotherBlock));
        n++; passed += check(n, "#283 a DeferredRegister put on the mod bus twice", () -> {
            net.minecraftforge.registries.DeferredRegister<net.minecraft.world.item.Item> twice =
                    net.minecraftforge.registries.DeferredRegister.create(
                            net.minecraftforge.registries.ForgeRegistries.ITEMS, "retromod_test_mod_forge");
            net.minecraftforge.eventbus.api.IEventBus bus =
                    net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus();
            twice.register(bus);
            twice.register(bus);
            return true;
        });

        // #271: 26.1 removed EitherHolder.
        n++; passed += check(n, "#271 an EitherHolder built from a key reports that key",
            RemovedEitherHolder::keyReadsBack);

        // 26.1 renamed the atlas and texture Material to SpriteId and gave its name to an unrelated
        // record. A client class, so a dedicated server has nothing to check.
        n++; passed += check(n, "Material(atlas, texture) reads both back on a client", () ->
            !net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()
                || SpriteIdMaterial.atlasAndTextureReadBack());

        // Forge 26.2 made ModList static, and Fuzs mods null-check ModList.get() while they construct.
        n++; passed += check(n, "ModList.get() is not null while mods construct", () -> {
            net.minecraftforge.fml.ModList mods = net.minecraftforge.fml.ModList.get();
            return mods != null && mods.isLoaded("retromod_test_mod_forge");
        });

        // Forge 26.2 removed DistExecutor, which old mods call while their proxy class initializes.
        n++; passed += check(n, "DistExecutor.runForDist in a static initializer picks this side's proxy",
            DistProxyCase::pickedThisSide);
        n++; passed += check(n, "DistExecutor run, call and safe forms run only on the side they name",
            DistProxyCase::runsOnlyOnTheNamedSide);
        // Smaller Forge 26.2 removals; the menu, tag data, reach value and griefing checks are logged later.
        n++; passed += check(n, "ToolActions.SHIELD_BLOCK and DEFAULT_SHIELD_ACTIONS", ForgeSmallRemovals::shieldAction);
        n++; passed += check(n, "ForgeRegistries.Keys.ENCHANTMENTS", ForgeSmallRemovals::enchantmentKey);
        n++; passed += check(n, "Tags.Blocks.STONE and Tags.Items.STAINED_GLASS", ForgeSmallRemovals::tagConstants);
        n++; passed += check(n, "ForgeMod.ENTITY_REACH is one registry object", ForgeSmallRemovals::reachIsOneObject);
        // Forge 26.2 removed ForgeRegistries.ENCHANTMENTS, which old mods read in static initializers.
        // The entry is checked again in the log once registry events are over.
        n++; passed += check(n, "DeferredRegister.create(ForgeRegistries.ENCHANTMENTS) with an entry in a static initializer", () ->
            EnchantmentRegistryReads.staticRegisterNamesEnchantments(
                net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus()));
        // Forge 26.2 removed its folder pack; NeoForge gets no stand-in for it, so these are Forge cases.
        n++; passed += check(n, "new PathPackResources(id, builtin, folder) reads pack.mcmeta", () ->
            LegacyEventBaseCase.onNeoForge() || LegacyPathPacks.builtPackReads());
        n++; passed += check(n, "A PathPackResources subclass reads through its resolve override", () ->
            LegacyEventBaseCase.onNeoForge() || LegacyPathPacks.subclassReadsThroughResolve());

        // Forge 26.2 removed the Event base a mod's own events extend, and Event.Result. NeoForge keeps
        // its own base, so these are Forge cases.
        n++; passed += check(n, "A mod's own events on the old Event base cancel and keep a result", () ->
            LegacyEventBaseCase.onNeoForge() || LegacyEventBaseCase.cancelAndResult());
        n++; passed += check(n, "instanceof Event and Event.class see a mod's own event, and cancel through it", () ->
            LegacyEventBaseCase.onNeoForge() || LegacyEventBaseCase.eventTypedCode());
        n++; passed += check(n, "A switch over Event.Result takes each constant's branch", () ->
            LegacyEventBaseCase.onNeoForge() || LegacyEventBaseCase.resultSwitch());
        n++; passed += check(n, "A GenericEvent built with its type reports it", () ->
            LegacyEventBaseCase.onNeoForge() || LegacyEventBaseCase.genericType());
        n++; passed += check(n, "A result set on a library's event reads back in the library", () ->
            LegacyEventBaseCase.onNeoForge() || LegacyEventBaseCase.crossModResult());
        // Forge 26.2's own Result enum on the events that kept a result, and a result kept for the ones that lost it.
        n++; passed += check(n, "BonemealEvent.setResult(ALLOW) reads back and hasResult() is true", () ->
            LegacyEventBaseCase.onNeoForge() || LegacyResultCases.bonemealAllow());
        n++; passed += check(n, "CropGrowEvent.Pre keeps a DENY result", () ->
            LegacyEventBaseCase.onNeoForge() || LegacyResultCases.cropGrowDeny());
        n++; passed += check(n, "Two equal EntityMountEvent records keep their own results", () ->
            LegacyEventBaseCase.onNeoForge() || LegacyResultCases.mountResultsStaySeparate());
        // Posting those events keeps Forge 1.20.1's dispatch; the game-bus cases are logged once a server starts.
        n++; passed += check(n, "A game-bus post of an own event during construction runs nothing and returns false", () ->
            LegacyEventBaseCase.onNeoForge() || LegacyEventDispatchCases.gameBusClosedDuringConstruction());
        n++; passed += check(n, "A library's mod-bus event reaches a listener on this mod's bus", () ->
            LegacyEventBaseCase.onNeoForge() || LegacyEventDispatchCases.libraryEventOnModBus());
        // Forge 26.2 made ModLoader static, and a warning handed to it stops the game bus on a client.
        n++; passed += check(n, "ModLoader.get().addWarning while constructing (the server-started lines must still print)", () ->
            LegacyEventBaseCase.onNeoForge() || ModLoaderCases.warnDuringConstruction());
        n++; passed += check(n, "ModLoader.get().getWarnings() does not hold that warning", () ->
            LegacyEventBaseCase.onNeoForge() || ModLoaderCases.warningStayedOutOfForge());
        // Balm, Farmer's Delight and JEI: DataFixerUpper 6's getOrThrow, a field typed as Tiers, and an override
        // of a method newer hosts made final. Each failed as soon as its class loaded.
        n++; passed += check(n, "DataResult.getOrThrow(false, consumer) returns the value and reports an error once",
            OldShapeCases::dataResultGetOrThrow);
        n++; passed += check(n, "A static field typed as Tiers holds IRON, and Tiers.values() and valueOf name it IRON", OldShapeCases::tierField);
        n++; passed += check(n, "A recipe that overrides ShapedRecipe.showNotification loads", OldShapeCases::finalOverrideLoads);

        // Balm and Farmer's Delight construct with these: DataFixerUpper 9 moved Codec.unit to
        // MapCodec.unitCodec, and Forge 26.2 cannot add a recipe book type.
        n++; passed += check(n, "Codec.unit(value) and Codec.unit(supplier) parse to their values", ConstructionCases::unitCodecs);
        n++; passed += check(n, "RecipeBookType.create in a static initializer gives a book type", ConstructionCases::recipeBookType);
        // Farmer's Delight, Balm through Waystones and JEI stopped constructing on these: Forge 26.2 moved the old
        // ingredient types, 1.21.11 renamed the buffer's id members, and the event channel and getByName went away.
        // Matching needs item stacks, so it is logged once a server starts.
        n++; passed += check(n, "A CompoundIngredient subclass and CompoundIngredient.of build while constructing",
            ConstructionCases::compoundIngredientsBuild);
        n++; passed += check(n, "FriendlyByteBuf.writeResourceLocation and readResourceLocation, by call and by reference",
            ConstructionCases::bufferIdentifierRoundTrip);
        n++; passed += check(n, "NetworkRegistry.newEventChannel and addListener while constructing", ConstructionCases::eventChannel);
        n++; passed += check(n, "ChatFormatting.getByName finds a color by name", ConstructionCases::chatFormattingByName);
        // 1.20.5 moved KeyDispatchDataCodec to map codecs, and 26.3 removed it; TerraBlender registers
        // its surface rule codec with these calls while Minecraft bootstraps.
        n++; passed += check(n, "KeyDispatchDataCodec.of(Codec) and its constructor build, and codec() parses",
            KeyDispatchCodecCases::oldFactoriesParse);
        n++; passed += check(n, "KeyDispatchDataCodec.codec() handed to a registration is the dispatch's own codec",
            KeyDispatchCodecCases::registrationGetsTheDispatchCodec);
        n++; passed += check(n, "KeyDispatchDataCodec.of(Codec.unit(x)).codec() parses {}, which has no value key",
            KeyDispatchCodecCases::unitCodecParsesWithoutAValueKey);
        // Farmer's Delight, Lootr and Sophisticated Backpacks stopped in RegisterEvent on Forge 26.2 on these.
        n++; passed += check(n, "A fence gate built from its open and close sounds links its constructor",
            () -> linksWithoutInheritanceError(RegisterEventCases::buildSoundGate));
        n++; passed += check(n, "A flower built from a mob effect and seconds links its constructor",
            () -> linksWithoutInheritanceError(RegisterEventCases::buildWildCrop));
        n++; passed += check(n, "Mth.color(1, 0.5, 0) packs to 0xFF7F00", RegisterEventCases::packedColor);
        n++; passed += check(n, "RandomPatchConfiguration builds and keeps its tries and spreads",
            RegisterEventCases::randomPatchConfiguration);
        n++; passed += check(n, "A block entity implementing the 1.20.1 RecipeHolder records and clears its recipe",
            RegisterEventCases::recipeHolder);
        n++; passed += check(n, "FoodProperties.Builder.effect with a supplier builds the food",
            RegisterEventCases::suppliedFoodEffect);
        n++; passed += check(n, "An entity client factory naming PlayMessages.SpawnEntity links",
            () -> linksWithoutInheritanceError(RegisterEventCases::clientFactory));
        n++; passed += check(n, "An item reads Item.BASE_ATTACK_DAMAGE_UUID through its own class",
            RegisterEventCases::baseAttackUuid);
        n++; passed += check(n, "A branch between two of a library's result kinds returns the library's base type",
            () -> RegisterEventCases.libraryJoin());
        // Forge 26.2's mob effect registry is a class that is not public, so Retromod reads a flower's effect through
        // the public Registry interface. The block itself may still be refused outside registration.
        n++; passed += check(n, "A flower built from a mob effect gets its effect through Forge's registry",
            RegisterEventCases::wildCropReadsItsEffect);
        // Patchouli reads Minecraft.screen from a crash report hook while mods construct. 26.2 moved the screen
        // behind Minecraft.gui, which is still null then, so the read gives null as the old field did. Clients only.
        n++; passed += check(n, "Minecraft.screen reads null while the game constructs", () ->
            !net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()
                || net.minecraft.client.Minecraft.getInstance().screen == null);
        // Carry On reads a registry's tag names and its lookup while configs load; 1.21.2 made the registry its own
        // lookup, and 26.x refuses to list tags before a world binds them.
        n++; passed += check(n, "Registry.getTagNames() lists while mods construct, before any tag is bound", () ->
            net.minecraft.core.registries.BuiltInRegistries.ITEM.getTagNames().count() >= 0);
        n++; passed += check(n, "Registry.asLookup() answers the registry's lookup", () ->
            net.minecraft.core.registries.BuiltInRegistries.ITEM.asLookup() != null);
        // Carry On and FancyMenu read item strings with ItemParser.parseForItem, which 1.20.5 removed with its result.
        n++; passed += check(n, "ItemParser.parseForItem reads an item id and the NBT after it", () -> {
            net.minecraft.commands.arguments.item.ItemParser.ItemResult result =
                net.minecraft.commands.arguments.item.ItemParser.parseForItem(
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.asLookup(),
                    new com.mojang.brigadier.StringReader("minecraft:diamond{Damage:3}"));
            return result.item().value() == Items.DIAMOND && String.valueOf(result.nbt()).contains("Damage:3");
        });
        // 26.x binds item components only when a world's data loads; the stacks are matched once a server starts.
        n++; passed += check(n, "new ItemStack in a static field and from a holder, and getDefaultInstance, while constructing",
            EarlyStackCases::buildWhileConstructing);
        // Sophisticated Backpacks builds its entity loot mapping from BuiltInLootTables, which 1.20.5 keyed.
        n++; passed += check(n, "BuiltInLootTables constants and all() read as ids, equal each time and usable as map keys",
            LootTableIds::constantsAreIds);
        n++; passed += check(n, "BuiltInLootTables.EMPTY, Block.getLootTable() and EntityType.getDefaultLootTable() read as ids",
            LootTableIds::emptyAndGetters);

        LOG.info("{} SUMMARY: {}/{} passed", PREFIX, passed, n);
    }

    /**
     * In-place loading: when Retromod translated this mod, the loader runs the copy in
     * Retromod's cache and the jar the player installed keeps its exact bytes. The bootstrap
     * reports both in the status file it names in {@code retromod.inplace.status}. In rewrite
     * mode, or when this mod needed no translation, it must not run from the cache.
     */
    private static boolean inPlaceCopyAndUntouchedOriginal(Class<?> self) throws Exception {
        String location = java.net.URLDecoder.decode(String.valueOf(
                self.getProtectionDomain().getCodeSource().getLocation()),
                java.nio.charset.StandardCharsets.UTF_8);
        boolean fromCache = location.contains("retromod-cache/");
        String statusPath = System.getProperty("retromod.inplace.status");
        if (statusPath == null || !"in-place".equals(System.getProperty("retromod.inplace.mode"))) {
            return !fromCache;
        }
        com.google.gson.JsonObject status = com.google.gson.JsonParser.parseString(
                java.nio.file.Files.readString(java.nio.file.Path.of(statusPath)))
                .getAsJsonObject();
        for (com.google.gson.JsonElement element : status.getAsJsonArray("served")) {
            com.google.gson.JsonObject row = element.getAsJsonObject();
            java.nio.file.Path source = java.nio.file.Path.of(row.get("source").getAsString());
            String copy = java.nio.file.Path.of(row.get("file").getAsString())
                    .getFileName().toString();
            if (location.contains("/" + copy)) {
                byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(java.nio.file.Files.readAllBytes(source));
                return fromCache && java.util.HexFormat.of().formatHex(digest)
                        .equals(row.get("sha256").getAsString());
            }
            if (location.contains("/" + source.getFileName())) {
                return false; // the untranslated original loaded although a copy was served
            }
        }
        return !fromCache;
    }

    /**
     * The command source an MCreator procedure builds to run a command, with an int permission
     * level and both names. Without an entity, the names it reports are the ones it was given.
     */
    private static boolean legacyCommandSource() {
        net.minecraft.commands.CommandSourceStack source = new net.minecraft.commands.CommandSourceStack(
                net.minecraft.commands.CommandSource.NULL, net.minecraft.world.phys.Vec3.ZERO,
                net.minecraft.world.phys.Vec2.ZERO, null, 4, "Retromod", Component.literal("Retromod"), null, null);
        return source.getTextName().equals("Retromod") && source.getDisplayName().getString().equals("Retromod")
                && source.hasPermission(2);
    }

    /**
     * Whether the probe links, such as a subclass of a removed base, ignoring anything that happens
     * after it does.
     *
     * <p>Building an item outside registration is fine on the version this mod is compiled for and
     * can be refused on later ones for reasons unrelated to the transform, so only a linkage failure
     * counts. {@code NoSuchMethodError} and {@code NoSuchFieldError} are already
     * {@code IncompatibleClassChangeError} subclasses, which is the family this covers.
     */
    private boolean linksWithoutInheritanceError(Runnable probe) {
        try {
            probe.run();
            return true;
        } catch (IncompatibleClassChangeError | NoClassDefFoundError | VerifyError e) {
            LOG.warn("{} probe did not link: {}: {}", PREFIX,
                    e.getClass().getSimpleName(), e.getMessage());
            return false;
        } catch (Throwable linkedButRefused) {
            return true;
        }
    }

    /** Extends a base deleted after 1.20.1, calling its four argument constructor. */
    private static class LegacyDisc extends net.minecraft.world.item.RecordItem {
        LegacyDisc() {
            super(1, (net.minecraft.sounds.SoundEvent) null,
                    new net.minecraft.world.item.Item.Properties(), 1);
        }
    }

    /** Same shape, different removed base and a one argument constructor. */
    private static class LegacyBook extends net.minecraft.world.item.EnchantedBookItem {
        LegacyBook() {
            super(new net.minecraft.world.item.Item.Properties());
        }
    }

    /**
     * Calls the five argument {@code useWithoutItem} Retromod adds for {@link UseBlock}'s old
     * override, the method the game calls on a right click. A host before 1.20.5 still calls
     * {@code use} itself, so it passes there. The block is allocated without its constructor,
     * because newer hosts refuse to build a block with no registry id.
     */
    private static boolean oldUseOverrideRuns() throws Throwable {
        java.lang.reflect.Method modern = fiveArgumentUse(UseBlock.class);
        if (modern == null) {
            for (Class<?> type = net.minecraft.world.level.block.Block.class; type != null; type = type.getSuperclass()) {
                if (fiveArgumentUse(type) != null) return false;
            }
            return true;
        }
        java.lang.reflect.Field theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        UseBlock block = (UseBlock) unsafe.getClass().getMethod("allocateInstance", Class.class)
                .invoke(unsafe, UseBlock.class);
        modern.setAccessible(true);
        try {
            modern.invoke(block, null, null, null, null, null);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
        return block.ran;
    }

    private static java.lang.reflect.Method fiveArgumentUse(Class<?> type) {
        for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
            if (method.getParameterCount() == 5
                    && method.getReturnType() == net.minecraft.world.InteractionResult.class) {
                return method;
            }
        }
        return null;
    }

    private static class UseBlock extends net.minecraft.world.level.block.Block {
        boolean ran;

        UseBlock(Properties properties) {
            super(properties);
        }

        @Override
        @SuppressWarnings("deprecation")
        public net.minecraft.world.InteractionResult use(net.minecraft.world.level.block.state.BlockState state,
                net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos,
                net.minecraft.world.entity.player.Player player, net.minecraft.world.InteractionHand hand,
                net.minecraft.world.phys.BlockHitResult hit) {
            net.minecraft.world.InteractionResult parent = super.use(state, level, pos, player, hand, hit);
            ran = hand == net.minecraft.world.InteractionHand.MAIN_HAND;
            return parent;
        }
    }

    /**
     * Copies every property of a stair state onto a fresh one through {@code getValues()}, the way
     * an MCreator procedure swaps one block for another. Each property is moved off its default
     * first, so a copy that does nothing fails.
     */
    private static boolean stateValuesCopy() {
        net.minecraft.world.level.block.state.BlockState source = Blocks.OAK_STAIRS.defaultBlockState();
        for (net.minecraft.world.level.block.state.properties.Property<?> property : source.getProperties()) {
            source = source.cycle(property);
        }
        net.minecraft.world.level.block.state.BlockState copy = Blocks.OAK_STAIRS.defaultBlockState();
        for (java.util.Map.Entry<net.minecraft.world.level.block.state.properties.Property<?>, Comparable<?>> entry
                : source.getValues().entrySet()) {
            copy = withValue(copy, entry.getKey(), entry.getValue());
        }
        return copy != Blocks.OAK_STAIRS.defaultBlockState() && copy == source;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static net.minecraft.world.level.block.state.BlockState withValue(
            net.minecraft.world.level.block.state.BlockState state,
            net.minecraft.world.level.block.state.properties.Property property, Comparable value) {
        return state.setValue(property, value);
    }

    /**
     * Whether attributes built from each host factory carry the tempt range a host's
     * {@code TemptGoal} reads. Before 1.21.2 the goal had a fixed range and there is no attribute
     * to look for.
     */
    private static boolean builtAttributesHaveTemptRange() throws Throwable {
        java.lang.reflect.Field range;
        try {
            range = net.minecraft.world.entity.ai.attributes.Attributes.class.getField("TEMPT_RANGE");
        } catch (NoSuchFieldException olderHost) {
            return true;
        }
        Object temptRange = range.get(null);
        for (net.minecraft.world.entity.ai.attributes.AttributeSupplier built : java.util.List.of(
                net.minecraft.world.entity.Mob.createMobAttributes().build(),
                net.minecraft.world.entity.monster.Monster.createMonsterAttributes().build(),
                LivingEntity.createLivingAttributes().build())) {
            boolean present = (boolean) built.getClass().getMethod("hasAttribute", range.getType())
                    .invoke(built, temptRange);
            if (!present) return false;
        }
        return true;
    }

    /**
     * Whether the host recolors armor trims from the palette its trim materials name, as 26.3 does
     * in place of the trim atlas. Looked up by name, because the class moved after 1.20.1.
     */
    private static boolean trimsUsePalettes() {
        try {
            Class.forName("net.minecraft.world.item.equipment.trim.TrimMaterial", false,
                    RetromodTestModForge.class.getClassLoader()).getMethod("paletteId");
            return true;
        } catch (ReflectiveOperationException olderHost) {
            return false;
        }
    }

    /**
     * Old item models get the client item definitions 1.21.4 reads in their place. This mod ships
     * only {@code models/item/} files: a spawn egg on vanilla's egg template and an item whose
     * custom model data override picks a variant. A host before 1.21.4 needs no definition. A
     * dedicated server ships no vanilla assets, so there only the shape of a written one is checked.
     */
    private static boolean legacyItemDefinitions() throws java.io.IOException {
        String egg = resource("/assets/retromod_test_mod_forge/items/legacy_spawn_egg.json");
        String variant = resource("/assets/retromod_test_mod_forge/items/legacy_variant.json");
        ClassLoader loader = RetromodTestModForge.class.getClassLoader();
        // A dedicated server jar ships the English lang file but no models, so only a model
        // proves the client resources are there to compare against.
        boolean vanillaAssets = loader.getResource("assets/minecraft/models/item/stick.json") != null;
        boolean readsDefinitions = loader.getResource("assets/minecraft/items/stick.json") != null;
        String problem = null;
        if ((egg == null) != (variant == null)) {
            problem = "only one definition was written: egg " + (egg != null) + ", variant " + (variant != null);
        } else if (egg == null) {
            if (vanillaAssets && readsDefinitions) problem = "this host reads items/ definitions, and none was written";
        } else if (vanillaAssets && !readsDefinitions) {
            problem = "definitions were written for a host that still reads model overrides";
        } else if (!egg.contains("\"tints\"")) {
            problem = "the egg definition carries no colors: " + egg;
        } else if (!variant.contains("minecraft:custom_model_data") || !variant.contains("item/legacy_variant_one")) {
            problem = "the custom model data override was not translated: " + variant;
        }
        if (problem != null) LOG.warn("{} legacy item models: {}", PREFIX, problem);
        return problem == null;
    }

    private static String resource(String path) throws java.io.IOException {
        try (java.io.InputStream in = RetromodTestModForge.class.getResourceAsStream(path)) {
            return in == null ? null : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    @FunctionalInterface
    private interface BoolCheck { boolean run() throws Throwable; }


    private int check(int n, String description, BoolCheck body) {
        try {
            if (body.run()) {
                LOG.info("{} {} ({}): success", PREFIX, n, description);
                return 1;
            } else {
                LOG.warn("{} {} ({}): fail: assertion was false", PREFIX, n, description);
                return 0;
            }
        } catch (Throwable t) {
            LOG.warn("{} {} ({}): fail: {}: {}", PREFIX, n, description,
                    t.getClass().getSimpleName(), t.getMessage());
            return 0;
        }
    }

    private static ItemStack callLegacyGetUseItem(LivingEntity entity) {
        return entity.getUseItem();
    }
}
