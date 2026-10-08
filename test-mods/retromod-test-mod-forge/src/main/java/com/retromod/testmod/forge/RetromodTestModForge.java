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

        LOG.info("{} SUMMARY: {}/{} passed", PREFIX, passed, n);
    }

    /**
     * Whether a subclass of a removed base links, ignoring anything that happens after it does.
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
            LOG.warn("{} removed base did not link: {}: {}", PREFIX,
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
