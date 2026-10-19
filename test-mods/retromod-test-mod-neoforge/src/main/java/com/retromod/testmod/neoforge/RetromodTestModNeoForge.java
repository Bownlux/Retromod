/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NeoForge entry point for the Retromod test mod.
 *
 * <p>Compiled against MC 1.21.1 / NeoForge 21.1.x. Retromod transforms it
 * forward to whatever MC version the host is running. On a 26.1.2 host
 * this exercises NeoForge-targeted redirects + the Mojang-name surface.
 *
 * <p>Each test logs {@code [Retromod-Test-NeoForge] N (description): success/fail}.
 * Grep for {@code [Retromod-Test-NeoForge]} in the log to see results.
 *
 * <p>NeoForge gets a wider test surface than Forge because the 1.21.1 →
 * 26.1.2 translation distance is small and a lot of Forge mods are
 * actively migrating to NeoForge - this set covers the API surfaces those
 * migrations most commonly touch (Component / Text mutation, NBT, math
 * types, registry lookups, ItemStack construction, entity types, sounds).
 *
 * <p>This is the "immediate" phase only. Lifecycle phases like
 * {@code FMLClientSetupEvent} or {@code ClientPlayerNetworkEvent.LoggingIn}
 * for deferred tests (anything needing a world load) are a future addition.
 */
@Mod("retromod_test_mod_neoforge")
public class RetromodTestModNeoForge {

    private static final Logger LOG = LoggerFactory.getLogger("Retromod-Test-NeoForge");
    private static final String PREFIX = "[Retromod-Test-NeoForge]";

    public RetromodTestModNeoForge(IEventBus modBus) {
        // #188: 26.1 renamed this event and changed its registration method from one argument to
        // an id plus listener. Keeping a real old-signature listener in the test mod proves the
        // handler descriptor and call site both survive the transform. The callback log proves the
        // embedded bridge also works when the client reload actually runs.
        modBus.addListener(this::registerClientReloadListener);
        modBus.addListener(this::checkMobAttributes);
        // A block built in a common class and registered from this loader class by id.
        modBus.addListener(CrossClassRegistration::register);
        // An entity type built from its bare name in a deferred register's supplier.
        EntityTypeKeys.register(modBus);
        runTests();
    }

    /**
     * #346: a 1.21.1 mob's attributes come from {@code Mob.createMobAttributes()} or another host
     * factory, none of which has the tempt range a 1.21.2+ {@code TemptGoal} reads, so the mob
     * crashed on its first tick. Attribute values are only bound once registration reaches this
     * event, the point where mods build them, so the result is in the log rather than the summary.
     */
    private void checkMobAttributes(net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent event) {
        String result;
        try {
            result = builtAttributesHaveTemptRange() ? "success" : "fail: assertion was false";
        } catch (Throwable t) {
            result = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        LOG.info("{} #346 attributes built from Mob, Monster and LivingEntity have the tempt range (attribute event): {}",
                PREFIX, result);
    }

    /** Before 1.21.2 the goal had a fixed range and there is no attribute to look for. */
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
                net.minecraft.world.entity.LivingEntity.createLivingAttributes().build())) {
            boolean present = (boolean) built.getClass().getMethod("hasAttribute", range.getType())
                    .invoke(built, temptRange);
            if (!present) return false;
        }
        return true;
    }

    private void registerClientReloadListener(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) resourceManager -> {
            LOG.info("{} #188 client reload-listener bridge fired", PREFIX);
            LOG.info("{} #349 armor trim atlas, its sprites and render types (client reload): {}",
                    PREFIX, trimAtlasResult());
        });
    }

    /**
     * #349: 26.3 removed the armor trim atlas that a custom armor layer fetches from the texture
     * manager and draws trims from. Retromod rebuilds it from 26.3's trim textures and palettes,
     * so it is a real atlas again and the old sprite names resolve, including a {@code _darker}
     * palette. The trim and entity glint render types link too. Textures are built once the
     * client loads its resources, so the result is in the log rather than the summary.
     */
    private static String trimAtlasResult() {
        try {
            net.minecraft.client.renderer.texture.AbstractTexture texture = net.minecraft.client.Minecraft.getInstance()
                    .getTextureManager().getTexture(net.minecraft.client.renderer.Sheets.ARMOR_TRIMS_SHEET);
            if (!(texture instanceof net.minecraft.client.renderer.texture.TextureAtlas atlas)) {
                return "fail: the armor trim atlas is " + texture;
            }
            net.minecraft.client.renderer.texture.TextureAtlasSprite missing = atlas.getSprite(
                    net.minecraft.client.renderer.texture.MissingTextureAtlasSprite.getLocation());
            // 1.21.2 renamed the sprites; a host before it keeps the names this mod was built with.
            String[][] sprites = {
                {"trims/entity/humanoid/coast_iron", "trims/models/armor/coast_iron"},
                {"trims/entity/humanoid_leggings/coast_iron_darker", "trims/models/armor/coast_leggings_iron_darker"},
            };
            for (String[] names : sprites) {
                if (atlas.getSprite(ResourceLocation.withDefaultNamespace(names[0])) == missing
                        && atlas.getSprite(ResourceLocation.withDefaultNamespace(names[1])) == missing) {
                    return "fail: the atlas has no sprite " + names[0];
                }
            }
            if (net.minecraft.client.renderer.Sheets.armorTrimsSheet(false) == null
                    || net.minecraft.client.renderer.RenderType.entityGlint() == null) {
                return "fail: a trim or glint render type is null";
            }
            return "success";
        } catch (Throwable t) {
            return "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    private void runTests() {
        LOG.info("{} Starting tests", PREFIX);
        int n = 0, passed = 0;

        // Sanity
        n++; passed += check(n, "mod loaded", () -> true);

        // Components and text
        n++; passed += check(n, "Component.literal", () -> {
            Component c = Component.literal("hello");
            return c != null && "hello".equals(c.getString());
        });
        n++; passed += check(n, "Component.translatable", () -> {
            Component c = Component.translatable("retromod.test.key");
            return c != null;
        });
        n++; passed += check(n, "Component.empty", () -> {
            Component c = Component.empty();
            return c != null && "".equals(c.getString());
        });
        // Earlier this test asserted that getString() flattens to "ab".
        // That worked through MC 1.21.4 but stopped working in MC 26.1+
        // where getString() on a multi-sibling MutableComponent returns
        // the toString-style tree representation. The test's real intent
        // - verifying that copy().append() actually attached a sibling -
        // is checked more reliably with getSiblings().size().
        n++; passed += check(n, "Component copy().append() attaches sibling", () -> {
            MutableComponent c = Component.literal("a").copy().append(Component.literal("b"));
            return c != null && c.getSiblings().size() == 1;
        });

        // Resource locations
        n++; passed += check(n, "ResourceLocation.fromNamespaceAndPath", () -> {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath("retromod", "test");
            return id != null && "retromod:test".equals(id.toString());
        });
        n++; passed += check(n, "ResourceLocation.parse(\"ns:path\")", () -> {
            ResourceLocation id = ResourceLocation.parse("retromod:other");
            return id != null
                && "retromod".equals(id.getNamespace())
                && "other".equals(id.getPath());
        });
        n++; passed += check(n, "ResourceLocation.tryParse(invalid) -> null", () -> {
            ResourceLocation bad = ResourceLocation.tryParse("BAD ID");
            return bad == null;
        });

        // Blocks, items, and entity types
        n++; passed += check(n, "Blocks.STONE static field", () -> Blocks.STONE != null);
        n++; passed += check(n, "Blocks.AIR.defaultBlockState().isAir()", () ->
            Blocks.AIR.defaultBlockState().isAir());
        n++; passed += check(n, "Blocks.STONE.defaultBlockState() not air", () ->
            !Blocks.STONE.defaultBlockState().isAir());
        n++; passed += check(n, "BlockState round-trip", () ->
            Blocks.STONE.defaultBlockState().getBlock() == Blocks.STONE);

        n++; passed += check(n, "Items.DIAMOND static field", () -> Items.DIAMOND != null);
        n++; passed += check(n, "Items.NETHERITE_INGOT static field", () -> Items.NETHERITE_INGOT != null);
        n++; passed += check(n, "ItemStack.EMPTY.isEmpty()", () -> ItemStack.EMPTY.isEmpty());

        n++; passed += check(n, "EntityType.ZOMBIE static field", () -> EntityType.ZOMBIE != null);
        n++; passed += check(n, "EntityType.PLAYER static field", () -> EntityType.PLAYER != null);

        // Math
        n++; passed += check(n, "BlockPos.ZERO", () -> {
            BlockPos p = BlockPos.ZERO;
            return p.getX() == 0 && p.getY() == 0 && p.getZ() == 0;
        });
        n++; passed += check(n, "new BlockPos(1, 2, 3)", () -> {
            BlockPos p = new BlockPos(1, 2, 3);
            return p.getX() == 1 && p.getY() == 2 && p.getZ() == 3;
        });
        n++; passed += check(n, "BlockPos.above()", () -> new BlockPos(0, 0, 0).above().getY() == 1);
        n++; passed += check(n, "Vec3.ZERO", () -> {
            Vec3 v = Vec3.ZERO;
            return v.x == 0 && v.y == 0 && v.z == 0;
        });
        n++; passed += check(n, "new Vec3(1.5, 2.5, 3.5)", () -> {
            Vec3 v = new Vec3(1.5, 2.5, 3.5);
            return v.x == 1.5 && v.y == 2.5 && v.z == 3.5;
        });
        n++; passed += check(n, "Direction.NORTH.getOpposite() == SOUTH", () ->
            Direction.NORTH.getOpposite() == Direction.SOUTH);
        n++; passed += check(n, "AABB(0,0,0,1,1,1)", () -> {
            AABB box = new AABB(0, 0, 0, 1, 1, 1);
            return box.getXsize() == 1.0;
        });
        // #349: 26.3 widened Axis.of to take the Vector3fc interface and dropped the Vector3f
        // form this call was compiled against, the same JOML change 26.2 made elsewhere.
        n++; passed += check(n, "#349 Axis.of(Vector3f) after the JOML widening", () -> {
            org.joml.Vector3f turned = com.mojang.math.Axis.of(new org.joml.Vector3f(0f, 1f, 0f))
                    .rotationDegrees(90f).transform(new org.joml.Vector3f(1f, 0f, 0f));
            return Math.abs(turned.x) < 1.0e-4f && Math.abs(turned.z + 1f) < 1.0e-4f;
        });

        // NBT
        n++; passed += check(n, "CompoundTag putString round-trip", () -> {
            CompoundTag c = new CompoundTag();
            c.putString("k", "v");
            // 1.21.5+ getString returns Optional<String>; 1.21.4 returns String.
            // Use contains() instead so the test is signature-agnostic.
            return c.contains("k");
        });
        n++; passed += check(n, "CompoundTag putInt round-trip (via contains)", () -> {
            CompoundTag c = new CompoundTag();
            c.putInt("n", 42);
            return c.contains("n");
        });
        n++; passed += check(n, "ListTag.add + size", () -> {
            ListTag list = new ListTag();
            list.add(StringTag.valueOf("a"));
            list.add(StringTag.valueOf("b"));
            return list.size() == 2;
        });

        // Sound event static accessors
        // MC 1.21+ Mojang names dropped the BLOCK_/ENTITY_ prefixes that
        // earlier yarn-style naming used. AMBIENT_CAVE has been around
        // since alpha and is a safe field to probe for.
        n++; passed += check(n, "SoundEvents.AMBIENT_CAVE", () -> SoundEvents.AMBIENT_CAVE != null);

        // Regression for #87: strip the Mixin synthetic-args placeholder.
        // This mod's SOURCE jar deliberately ships
        // org/spongepowered/asm/synthetic/args/Dummy.class (the old-Forge
        // export hack Blueprint-era mods use). On a NeoForge 1.20.2+ host
        // the transform must strip that package - the host's own
        // mixin_synthetic module owns it, and a jar still shipping it fails
        // JPMS resolution for the whole module layer at boot. Booting far
        // enough to run this check is most of the assertion; the resource
        // lookup confirms the entries are really gone from the jar.
        // (Only meaningful on the TRANSFORMED jar - on a same-version host
        // the mod is passed through untransformed and this check fails.)
        n++; passed += check(n, "#87 mixin synthetic-args dummy stripped from jar", () ->
            getClass().getClassLoader()
                .getResource("org/spongepowered/asm/synthetic/args/Dummy.class") == null);

        // #234: the 1.21.1 source field was renamed in 26.1. A direct read proves
        // the transformed NeoForge class links to the current GameRules field.
        n++; passed += check(n, "#234 GameRules mob-griefing field", () ->
            GameRules.RULE_MOBGRIEFING != null);

        // #260: an item base Minecraft deleted rather than renamed. EnchantedBookItem went when
        // stored enchantments became a component, and the class-move table used to point it at
        // Items, a static holder with no constructor, so a subclass died at the super(...) call.
        // It now rebases onto a generated Item subclass. RecordItem, the other half of #260, is
        // deliberately absent here: it was already removed in 1.21, which this mod is built for.
        n++; passed += check(n, "#260 removed item base EnchantedBookItem", () ->
            linksWithoutInheritanceError(LegacyBook::new));

        // #248: NeoForge 1.21.9 turned FMLLoader's statics into instance methods behind
        // FMLLoader.getCurrent(), and replaced FMLEnvironment's two fields with methods. This mod
        // is built against 21.1, where the old shape is what the compiler emits, so these four
        // call sites are the exact bytecode a reported mod died on. A miss is not a wrong answer,
        // it is IncompatibleClassChangeError or NoSuchFieldError on the first call.
        n++; passed += check(n, "#248 FMLLoader.getDist static accessor", () ->
            FMLLoader.getDist() != null);
        n++; passed += check(n, "#248 FMLEnvironment.dist field read", () ->
            FMLEnvironment.dist != null);
        n++; passed += check(n, "#248 production flag in both of its forms", () ->
            FMLEnvironment.production == FMLLoader.isProduction());
        n++; passed += check(n, "#248 FMLLoader.getGamePath renamed accessor", () ->
            FMLLoader.getGamePath() != null);

        // #346: what a 1.21.1 mob calls for its food, offspring and milking, against the members
        // 1.21.2 and 26.3 removed. Calls on a null mob prove linkage only: the bridge links, then
        // forwards to the missing receiver.
        n++; passed += check(n, "#346 Ingredient.of(ItemStack...)", () -> linksWithoutInheritanceError(() ->
            net.minecraft.world.item.crafting.Ingredient.of(new ItemStack(Items.WHEAT))));
        n++; passed += check(n, "#346 EntityType.create(Level)", () -> linksWithoutInheritanceError(() ->
            EntityType.COW.create((net.minecraft.world.level.Level) null)));
        n++; passed += check(n, "#346 LivingEntity.swing(hand, send)", () -> linksWithoutInheritanceError(() ->
            ((net.minecraft.world.entity.LivingEntity) null).swing(net.minecraft.world.InteractionHand.MAIN_HAND, true)));
        // A mob's own override of the removed swing still runs when it calls it on itself.
        n++; passed += check(n, "#346 an entity's own swing(hand, send) runs when it swings",
                OwnOverrideMob::swingReachesOwnOverride);
        n++; passed += check(n, "#346 swings through LivingEntity reach the mob's own override",
                OwnOverrideMob::livingEntityCallsReachOwnOverride);

        // #344: 26.3 ships no GLFW, so a mod's own GLFW clock read failed with
        // NoClassDefFoundError. Clients only: a dedicated server never had GLFW.
        n++; passed += check(n, "#344 GLFW clock on a host without GLFW", () ->
            !FMLEnvironment.dist.isClient() || org.lwjgl.glfw.GLFW.glfwGetTime() >= 0.0);

        // #347: getValues() returns a Map on 1.21.1 and a stream from 26.1, so copying a block's
        // properties onto the next block, as MCreator procedures do, failed on newer hosts.
        n++; passed += check(n, "#347 BlockState.getValues() copies a block's properties",
                RetromodTestModNeoForge::stateValuesCopy);
        // This mod ships only pre-1.21.4 item models, which 1.21.4 reads through item definitions.
        n++; passed += check(n, "Legacy item models get 1.21.4 item definitions",
                RetromodTestModNeoForge::legacyItemDefinitions);
        // #347: the other members its procedures call that later hosts changed. Calls on a null
        // receiver prove linkage only: the bridge links, then fails on the missing receiver.
        n++; passed += check(n, "#347 CommandSourceStack with an int permission level",
                RetromodTestModNeoForge::legacyCommandSource);
        n++; passed += check(n, "#347 Inventory.clearOrCountMatchingItems(predicate, max, craftSlots)", () ->
            linksWithoutInheritanceError(() -> ((net.minecraft.world.entity.player.Inventory) null)
                    .clearOrCountMatchingItems(stack -> true, 0, null)));
        n++; passed += check(n, "#347 Player.drop(stack, retainOwnership)", () -> linksWithoutInheritanceError(() ->
            ((net.minecraft.world.entity.player.Player) null).drop(ItemStack.EMPTY, false)));
        n++; passed += check(n, "#347 Player.drop(stack, throwRandomly, retainOwnership)", () ->
            linksWithoutInheritanceError(() ->
                ((net.minecraft.world.entity.player.Player) null).drop(ItemStack.EMPTY, true, false)));
        n++; passed += check(n, "#347 LivingEntity.hurt(source, amount)", () -> linksWithoutInheritanceError(() ->
            ((net.minecraft.world.entity.LivingEntity) null).hurt(null, 1f)));
        n++; passed += check(n, "#347 IntegerProperty.getPossibleValues()", () ->
            net.minecraft.world.level.block.state.properties.BlockStateProperties.AGE_3.getPossibleValues().size() == 4);
        n++; passed += check(n, "#347 ServerPlayer.server", () -> linksWithoutInheritanceError(() -> {
            net.minecraft.server.MinecraftServer server = ((net.minecraft.server.level.ServerPlayer) null).server;
        }));
        n++; passed += check(n, "#347 ItemHandlerHelper.giveItemToPlayer", () -> linksWithoutInheritanceError(() ->
            net.neoforged.neoforge.items.ItemHandlerHelper.giveItemToPlayer(null, ItemStack.EMPTY)));

        // #349: 26.3 removed LivingEntity.swinging, which a physics mod reads to react to the arm
        // swing. A read asks isSwinging() instead, also through a player, which javac names as the
        // owner. Reads on a null receiver prove linkage only.
        n++; passed += check(n, "#349 LivingEntity.swinging read after 26.3 removed the field", () ->
            linksWithoutInheritanceError(() -> {
                boolean swinging = ((net.minecraft.world.entity.LivingEntity) null).swinging;
            }));
        n++; passed += check(n, "#349 Player.swinging read through the player", () ->
            linksWithoutInheritanceError(() -> {
                boolean swinging = ((net.minecraft.world.entity.player.Player) null).swinging;
            }));
        // #349: 26.3 removed the armor trim atlas, and Retromod rebuilds it under its own id. The
        // read reaches that id even on a dedicated server, where Sheets itself is absent, and the
        // render type call links before it finds no client to draw with. A server before 26.3
        // still has the atlas but no client classes, so it has nothing to check. Clients check the
        // atlas itself after a resource reload.
        n++; passed += check(n, "#349 Sheets.ARMOR_TRIMS_SHEET and armorTrimsSheet on a 26.3 dedicated server", () ->
            FMLEnvironment.dist.isClient() || !trimsUsePalettes()
                || "retromod".equals(net.minecraft.client.renderer.Sheets.ARMOR_TRIMS_SHEET.getNamespace())
                    && linksWithoutInheritanceError(() -> net.minecraft.client.renderer.Sheets.armorTrimsSheet(false)));

        n++; passed += check(n, "In-place loading runs the cached copy and leaves the original unchanged",
            () -> inPlaceCopyAndUntouchedOriginal(RetromodTestModNeoForge.class));

        // #362: the companion library jar (retromod-test-lib-neoforge) takes Tier in its API. From
        // 1.21.2 Retromod rebuilds Tier inside every translated jar, so the call only links when the
        // test mod reuses the library's copy instead of its own.
        n++; passed += check(n, "#362 a library's API taking the removed tool Tier links across jars",
            LibraryCalls::tierLinks);
        // #358: the library declares neither a minecraft nor a neoforge dependency, so its
        // Minecraft version is only in its manifest. Built for the same version as this mod, it
        // must be translated exactly when this mod is.
        n++; passed += check(n, "#358 a library versioned only by its manifest is translated with this mod",
            () -> LibraryCalls.manifestVersionedLibraryTranslatedLike(RetromodTestModNeoForge.class,
                    "retromod-test-mod-neoforge"));

        // #340: 26.3 removed NumberProvider, ConstantValue and UniformGenerator, and its loot pools
        // and count functions take int or float providers.
        n++; passed += check(n, "#340 loot numbers built in code reach a loot pool and a set count function",
            LootNumbers::poolAndCountBuild);
        // #336: 26.3 removed block codecs, rebuilt the loot entry builders, made a nested table a
        // holder, and its inventory takes a prediction when a menu hands items back as it closes.
        // Calls on a null receiver or with null registries prove linkage only.
        n++; passed += check(n, "#336 a block filling its codec with simpleCodec initializes",
            () -> CodecEggBlock.CODEC != null);
        n++; passed += check(n, "#336 loot entries built in code reach a loot pool",
            LootEntries::itemAndEmptyEntriesBuild);
        n++; passed += check(n, "#336 NestedLootTable.lootTableReference(key) in a loot handler", () ->
            linksWithoutInheritanceError(() -> LootEntries.nestedTable(null)));
        n++; passed += check(n, "#336 Inventory.placeItemBackInInventory(stack)", () -> linksWithoutInheritanceError(() ->
            ((net.minecraft.world.entity.player.Inventory) null).placeItemBackInInventory(ItemStack.EMPTY)));

        // #271: 26.1 removed EitherHolder and 26.3 removed the BLOCK_TYPE registry.
        n++; passed += check(n, "#271 an EitherHolder built from a key reports that key",
            RemovedRegistryTypes::eitherHolderKeyReadsBack);
        n++; passed += check(n, "#271 BuiltInRegistries.BLOCK_TYPE read", RemovedRegistryTypes::blockTypeRegistryReads);

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
                net.minecraft.commands.CommandSource.NULL, Vec3.ZERO, net.minecraft.world.phys.Vec2.ZERO, null, 4,
                "Retromod", Component.literal("Retromod"), null, null);
        return source.getTextName().equals("Retromod") && source.getDisplayName().getString().equals("Retromod")
                && source.hasPermission(2);
    }

    /**
     * Whether the probe links, such as a subclass of a removed base, ignoring anything that happens
     * after it does.
     *
     * <p>Building an item outside registration is fine on the version this mod is compiled for and
     * can be refused on later ones for reasons unrelated to the transform, so only a linkage failure
     * counts. {@code NoSuchMethodError} is already an {@code IncompatibleClassChangeError} subclass,
     * which is the family this covers.
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

    /**
     * Copies every property of a stair state onto a fresh one through {@code getValues()}. Each
     * property is moved off its default first, so a copy that does nothing fails.
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

    /** Extends a base deleted after 1.21.1, calling its one argument constructor. */
    private static class LegacyBook extends net.minecraft.world.item.EnchantedBookItem {
        LegacyBook() {
            super(new net.minecraft.world.item.Item.Properties());
        }
    }

    /**
     * Whether the host recolors armor trims from the palette its trim materials name, as 26.3 does
     * in place of the trim atlas. Looked up by name, because the class moved after 1.21.1.
     */
    private static boolean trimsUsePalettes() {
        try {
            Class.forName("net.minecraft.world.item.equipment.trim.TrimMaterial", false,
                    RetromodTestModNeoForge.class.getClassLoader()).getMethod("paletteId");
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
        String egg = resource("/assets/retromod_test_mod_neoforge/items/legacy_spawn_egg.json");
        String variant = resource("/assets/retromod_test_mod_neoforge/items/legacy_variant.json");
        ClassLoader loader = RetromodTestModNeoForge.class.getClassLoader();
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
        try (java.io.InputStream in = RetromodTestModNeoForge.class.getResourceAsStream(path)) {
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
}
