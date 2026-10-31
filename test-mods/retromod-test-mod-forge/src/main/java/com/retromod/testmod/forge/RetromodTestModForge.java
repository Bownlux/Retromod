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
        net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus()
                .addListener(FarmersDelightAddon::register);
        // An entity type built from its bare name in a deferred register's supplier.
        EntityTypeKeys.register(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
        // #372: a 1.20.1 recipe serializer and advancement trigger, checked once the server's data loads.
        LegacyRecipesAndTriggers.register(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(this::checkLoadedData);
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
        n++; passed += check(n, "#283 Rarity.create is added as a rarity", LegacyRegistrationShapes::rarityWasAdded);
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
