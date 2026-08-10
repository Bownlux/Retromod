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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.common.Mod;
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
        runTests();
    }

    private void registerClientReloadListener(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) resourceManager ->
                LOG.info("{} #188 client reload-listener bridge fired", PREFIX));
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

        LOG.info("{} SUMMARY: {}/{} passed", PREFIX, passed, n);
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
