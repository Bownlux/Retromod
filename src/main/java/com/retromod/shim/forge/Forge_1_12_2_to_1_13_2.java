/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

/** The Flattening: 1.13 rewrote the block/item ID, registry, and command systems. */
public class Forge_1_12_2_to_1_13_2 implements VersionShim {

    @Override public String getShimName() { return "Forge 1.12.2 to 1.13.2"; }
    @Override public String getSourceVersion() { return "1.12.2"; }
    @Override public String getTargetVersion() { return "1.13.2"; }
    @Override public String getModLoaderType() { return "forge"; }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // init.* classes moved into their type packages
        transformer.registerClassRedirect(
            "net/minecraft/init/Blocks",
            "net/minecraft/block/Blocks"
        );
        transformer.registerClassRedirect(
            "net/minecraft/init/Items",
            "net/minecraft/item/Items"
        );
        transformer.registerClassRedirect(
            "net/minecraft/init/SoundEvents",
            "net/minecraft/util/SoundEvents"
        );
        transformer.registerClassRedirect(
            "net/minecraft/init/Enchantments",
            "net/minecraft/enchantment/Enchantments"
        );
        transformer.registerClassRedirect(
            "net/minecraft/init/Biomes",
            "net/minecraft/world/biome/Biomes"
        );
        transformer.registerClassRedirect(
            "net/minecraft/init/PotionTypes",
            "net/minecraft/potion/Potions"
        );
        transformer.registerClassRedirect(
            "net/minecraft/init/MobEffects",
            "net/minecraft/potion/Effects"
        );

        transformer.registerClassRedirect(
            "net/minecraft/block/state/IBlockState",
            "net/minecraft/block/BlockState"
        );
        transformer.registerClassRedirect(
            "net/minecraft/block/state/BlockStateContainer",
            "net/minecraft/state/StateContainer"
        );
        transformer.registerClassRedirect(
            "net/minecraft/block/properties/PropertyInteger",
            "net/minecraft/state/IntegerProperty"
        );
        transformer.registerClassRedirect(
            "net/minecraft/block/properties/PropertyBool",
            "net/minecraft/state/BooleanProperty"
        );
        transformer.registerClassRedirect(
            "net/minecraft/block/properties/PropertyEnum",
            "net/minecraft/state/EnumProperty"
        );
        transformer.registerClassRedirect(
            "net/minecraft/block/properties/PropertyDirection",
            "net/minecraft/state/DirectionProperty"
        );

        transformer.registerClassRedirect(
            "net/minecraft/entity/player/EntityPlayer",
            "net/minecraft/entity/player/PlayerEntity"
        );
        transformer.registerClassRedirect(
            "net/minecraft/entity/player/EntityPlayerMP",
            "net/minecraft/entity/player/ServerPlayerEntity"
        );
        transformer.registerClassRedirect(
            "net/minecraft/entity/EntityLivingBase",
            "net/minecraft/entity/LivingEntity"
        );
        transformer.registerClassRedirect(
            "net/minecraft/entity/EntityCreature",
            "net/minecraft/entity/CreatureEntity"
        );
        transformer.registerClassRedirect(
            "net/minecraft/entity/monster/EntityMob",
            "net/minecraft/entity/monster/MonsterEntity"
        );
        transformer.registerClassRedirect(
            "net/minecraft/entity/passive/EntityAnimal",
            "net/minecraft/entity/passive/AnimalEntity"
        );
        transformer.registerClassRedirect(
            "net/minecraft/entity/item/EntityItem",
            "net/minecraft/entity/item/ItemEntity"
        );
        transformer.registerClassRedirect(
            "net/minecraft/entity/projectile/EntityArrow",
            "net/minecraft/entity/projectile/ArrowEntity"
        );
        transformer.registerClassRedirect(
            "net/minecraft/entity/EntityLiving",
            "net/minecraft/entity/MobEntity"
        );

        transformer.registerClassRedirect(
            "net/minecraft/nbt/NBTTagCompound",
            "net/minecraft/nbt/CompoundNBT"
        );
        transformer.registerClassRedirect(
            "net/minecraft/nbt/NBTTagList",
            "net/minecraft/nbt/ListNBT"
        );
        transformer.registerClassRedirect(
            "net/minecraft/nbt/NBTTagString",
            "net/minecraft/nbt/StringNBT"
        );
        transformer.registerClassRedirect(
            "net/minecraft/nbt/NBTTagInt",
            "net/minecraft/nbt/IntNBT"
        );
        transformer.registerClassRedirect(
            "net/minecraft/nbt/NBTTagByte",
            "net/minecraft/nbt/ByteNBT"
        );
        transformer.registerClassRedirect(
            "net/minecraft/nbt/NBTBase",
            "net/minecraft/nbt/INBT"
        );

        transformer.registerClassRedirect(
            "net/minecraft/client/gui/GuiScreen",
            "net/minecraft/client/gui/screen/Screen"
        );
        transformer.registerClassRedirect(
            "net/minecraft/client/gui/GuiButton",
            "net/minecraft/client/gui/widget/button/Button"
        );
        transformer.registerClassRedirect(
            "net/minecraft/client/gui/GuiTextField",
            "net/minecraft/client/gui/widget/TextFieldWidget"
        );
        transformer.registerClassRedirect(
            "net/minecraft/client/gui/inventory/GuiContainer",
            "net/minecraft/client/gui/screen/inventory/ContainerScreen"
        );
        transformer.registerClassRedirect(
            "net/minecraft/client/gui/inventory/GuiChest",
            "net/minecraft/client/gui/screen/inventory/ChestScreen"
        );

        transformer.registerClassRedirect(
            "net/minecraft/inventory/Container",
            "net/minecraft/inventory/container/Container"
        );
        transformer.registerClassRedirect(
            "net/minecraft/inventory/Slot",
            "net/minecraft/inventory/container/Slot"
        );
        transformer.registerClassRedirect(
            "net/minecraft/creativetab/CreativeTabs",
            "net/minecraft/item/ItemGroup"
        );

        transformer.registerClassRedirect(
            "net/minecraft/world/WorldServer",
            "net/minecraft/world/server/ServerWorld"
        );
        transformer.registerClassRedirect(
            "net/minecraft/world/chunk/Chunk",
            "net/minecraft/world/chunk/Chunk"
        );

        transformer.registerClassRedirect(
            "net/minecraft/network/play/server/SPacketChat",
            "net/minecraft/network/play/server/SChatPacket"
        );

        // GameRegistry.register split into typed register calls
        transformer.registerMethodRedirect(
            "net/minecraftforge/fml/common/registry/GameRegistry", "register",
            "(Lnet/minecraft/item/Item;)V",
            "com/retromod/shim/forge/embedded/FlatteningShim", "registerItem",
            "(Ljava/lang/Object;)V"
        );
        transformer.registerMethodRedirect(
            "net/minecraftforge/fml/common/registry/GameRegistry", "register",
            "(Lnet/minecraft/block/Block;)V",
            "com/retromod/shim/forge/embedded/FlatteningShim", "registerBlock",
            "(Ljava/lang/Object;)V"
        );

        transformer.registerClassRedirect(
            "net/minecraftforge/fml/common/Mod",
            "net/minecraftforge/fml/common/Mod"
        );
        transformer.registerClassRedirect(
            "net/minecraftforge/fml/common/Mod$EventHandler",
            "net/minecraftforge/fml/common/Mod$EventBusSubscriber"
        );
        transformer.registerClassRedirect(
            "net/minecraftforge/fml/common/event/FMLInitializationEvent",
            "net/minecraftforge/fml/event/lifecycle/FMLCommonSetupEvent"
        );
        transformer.registerClassRedirect(
            "net/minecraftforge/fml/common/event/FMLPreInitializationEvent",
            "net/minecraftforge/fml/event/lifecycle/FMLCommonSetupEvent"
        );
        transformer.registerClassRedirect(
            "net/minecraftforge/fml/common/event/FMLPostInitializationEvent",
            "net/minecraftforge/fml/event/lifecycle/FMLLoadCompleteEvent"
        );
        transformer.registerClassRedirect(
            "net/minecraftforge/fml/common/event/FMLServerStartingEvent",
            "net/minecraftforge/fml/event/server/FMLServerStartingEvent"
        );

        // SidedProxy gone in 1.13+
        transformer.registerClassRedirect(
            "net/minecraftforge/fml/common/SidedProxy",
            "com/retromod/shim/forge/embedded/SidedProxyShim"
        );

        // Client sound interface: the 1.17 repackaging moved client/audio/ISound to
        // client/resources/sounds/SoundInstance (same name on 1.20.1 and 26.1). Ungated: the target
        // exists on every supported host (1.18+). The Betweenlands hit this as its only gap (#113).
        transformer.registerClassRedirect(
            "net/minecraft/client/audio/ISound",
            "net/minecraft/client/resources/sounds/SoundInstance"
        );

        // Removed pre-1.13 Forge API types (#131/#132/#134/#135): a 1.12.2 mod's class
        // implements/references a Forge type deleted in later Forge (IWorldGenerator [1.13],
        // IGuiHandler [1.13], IMessage/IMessageHandler [1.13], IForgeRegistryEntry [1.17]), so it
        // can't even LOAD (NoClassDefFoundError) on a modern Forge/NeoForge host. Empty stub
        // interfaces let the classes load; the feature (worldgen/gui/networking) is inert until its
        // registration idiom (GameRegistry.registerWorldGenerator / NetworkRegistry.registerGuiHandler
        // / SimpleNetworkWrapper) is bridged. Fires on Forge, NeoForge, and the offline CLI.
        registerRemovedForgeApiStubs(transformer);

        // 1.12.2 -> 26.1 class-move baseline (data-driven). 308 of the 344 issues on a real
        // 1.12.2 mod (Metallurgy 4, #103) are class moves; this table is the dominant fix. Called
        // last so its final 26.1 names win over the intermediate 1.13 names above. Gated to a 26.1+
        // host inside the loader, since the targets are 26.1 names.
        loadDirectClassMoves(transformer);

        // The pre-1.13 FML lifecycle: @Mod(modid=...) rewrite + @Mod.EventHandler wiring +
        // FML*InitializationEvent stand-ins. Without it the mod is scanned (mcmod.info toml
        // generation) but modern FML finds no mod id and never calls its setup.
        Forge1122LifecycleSynthetics.register(transformer);
    }

    /**
     * Empty stub interfaces for pre-1.13 Forge API types that modern Forge/NeoForge deleted, so a
     * 1.12.2 mod's class that {@code implements}/references them can LOAD instead of
     * {@code NoClassDefFoundError} (#131 IWorldGenerator, #132 IGuiHandler, #134 IForgeRegistryEntry,
     * #135 IMessage). An empty interface is sufficient: the JVM does not require the interface to
     * declare the methods the mod's class defines, so {@code implements X} and a field/param of type
     * {@code X} both resolve. The paired registration idiom (worldgen/gui/networking registration) is
     * a separate follow-up; those calls target other removed classes and stay inert until bridged.
     */
    private static void registerRemovedForgeApiStubs(RetromodTransformer transformer) {
        stubInterface(transformer,
                "net/minecraftforge/fml/common/IWorldGenerator",
                "com/retromod/generated/forge1122/IWorldGenerator");                 // Forge 1.13
        stubInterface(transformer,
                "net/minecraftforge/fml/common/network/IGuiHandler",
                "com/retromod/generated/forge1122/IGuiHandler");                     // Forge 1.13
        stubInterface(transformer,
                "net/minecraftforge/fml/common/network/simpleimpl/IMessage",
                "com/retromod/generated/forge1122/IMessage");                        // Forge 1.13
        stubInterface(transformer,
                "net/minecraftforge/fml/common/network/simpleimpl/IMessageHandler",
                "com/retromod/generated/forge1122/IMessageHandler");                 // Forge 1.13
        stubInterface(transformer,
                "net/minecraftforge/registries/IForgeRegistryEntry",
                "com/retromod/generated/forge1122/IForgeRegistryEntry");             // Forge 1.17

        // RegistryEvent (+ inner Register/MissingMappings) is the pre-1.13 registration EVENT, used as
        // a @SubscribeEvent handler PARAMETER. Unlike the interfaces above, an empty stub is not enough:
        // Forge's EventBus.registerListener rejects (throws at registration) a @SubscribeEvent param that
        // is not assignable to the eventbus base Event. So the stub must be a CLASS extending the host's
        // eventbus Event. The event never fires on a modern host, so the mod's registration-via-event
        // stays inert (soft-fail), but registration completes without throwing (#134, the domino after
        // the IForgeRegistryEntry stub).
        // Resolve the host's eventbus base Event as a loaded Class so we can read isInterface(): the
        // base is a CLASS on EventBus 6 (Forge 1.20.1, api/Event) and NeoForge (bus/api/Event), but an
        // INTERFACE on EventBus 7 (Forge 26.2, internal/Event). A stub that `extends` an interface fails
        // to load (IncompatibleClassChangeError), so we `implements` an interface base and `extends` a
        // class base. Offline (no host on the classpath) it can't be resolved: skip the RegistryEvent
        // stubs rather than bake a wrong/absent superclass -- the mod then hits its original
        // NoClassDefFoundError, no worse than without this stub. (#134; verified against EventBus 6/7 + Neo.)
        Class<?> eventBase = resolveEventBusBaseClass();
        if (eventBase != null) {
            String base = eventBase.getName().replace('.', '/');
            boolean isInterface = eventBase.isInterface();
            stubEventClass(transformer, "net/minecraftforge/event/RegistryEvent",
                    "com/retromod/generated/forge1122/RegistryEvent", base, isInterface);
            stubEventClass(transformer, "net/minecraftforge/event/RegistryEvent$Register",
                    "com/retromod/generated/forge1122/RegistryEvent$Register", base, isInterface);
            stubEventClass(transformer, "net/minecraftforge/event/RegistryEvent$MissingMappings",
                    "com/retromod/generated/forge1122/RegistryEvent$MissingMappings", base, isInterface);
            // ModelRegistryEvent: the pre-1.17 client model-registration event (removed when models
            // went data-driven). Same @SubscribeEvent-parameter situation as RegistryEvent, so the same
            // Event-subtype class stub. Surfaced in-game as the domino after the RegistryEvent stub (#134).
            stubEventClass(transformer, "net/minecraftforge/client/event/ModelRegistryEvent",
                    "com/retromod/generated/forge1122/ModelRegistryEvent", base, isInterface);
        }
    }

    /**
     * The host eventbus base Event as a loaded {@code Class} (so callers can read {@link Class#isInterface()}),
     * or {@code null} if none is resolvable (the offline CLI, where the host classpath is absent).
     * NeoForge {@code net/neoforged/bus/api/Event}, then EventBus 7 {@code .../eventbus/internal/Event},
     * then EventBus 6 {@code .../eventbus/api/Event}.
     */
    private static Class<?> resolveEventBusBaseClass() {
        String[] candidates = {
                "net.neoforged.bus.api.Event",
                "net.minecraftforge.eventbus.internal.Event",
                "net.minecraftforge.eventbus.api.Event",
        };
        for (String c : candidates) {
            try {
                // initialize=false: only need the class shape, never run its <clinit>.
                return Class.forName(c, false, Forge_1_12_2_to_1_13_2.class.getClassLoader());
            } catch (Throwable ignore) {
                // try next
            }
        }
        return null;
    }

    /** Register a synthetic Event-subtype CLASS and redirect a removed event onto it. */
    private static void stubEventClass(RetromodTransformer transformer, String removed, String stub,
                                       String base, boolean baseIsInterface) {
        transformer.registerSyntheticClass(stub, eventClassBytes(stub, base, baseIsInterface));
        transformer.registerClassRedirect(removed, stub);
    }

    /**
     * A loadable Event-subtype stub with a no-arg ctor. For a class base it {@code extends base}; for an
     * interface base (EventBus 7's internal/Event) it {@code extends Object implements base}, since you
     * cannot extend an interface.
     */
    static byte[] eventClassBytes(String internalName, String base, boolean baseIsInterface) {
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
        String superName = baseIsInterface ? "java/lang/Object" : base;
        String[] ifaces = baseIsInterface ? new String[]{base} : null;
        cw.visit(org.objectweb.asm.Opcodes.V17,
                org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_SUPER,
                internalName, null, superName, ifaces);
        org.objectweb.asm.MethodVisitor mv =
                cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
        mv.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Register an empty synthetic interface and redirect a removed class name onto it. */
    private static void stubInterface(RetromodTransformer transformer, String removed, String stub) {
        transformer.registerSyntheticClass(stub, emptyInterface(stub));
        transformer.registerClassRedirect(removed, stub);
    }

    /** A public empty interface: enough for {@code implements X} or a field/param of type {@code X} to load. */
    private static byte[] emptyInterface(String internalName) {
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
        cw.visit(org.objectweb.asm.Opcodes.V17,
                org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_ABSTRACT
                        | org.objectweb.asm.Opcodes.ACC_INTERFACE,
                internalName, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static final String CLASS_MOVES_RESOURCE = "/retromod/forge-1.12.2-class-moves.tsv";
    private static final String CLASS_MOVES_1201_RESOURCE =
            "/retromod/forge-1.12.2-class-moves-1201.tsv";

    /**
     * Load the bundled 1.12.2 (MCP) class-move table matching the host: on a 26.1+ host the
     * 26.1-target table (every target validated against the 26.1 jar at harvest time), on a
     * 1.20.x host the 1.20.x-target variant (validated against Mojang's official 1.20.1
     * mappings; it also carries the families 26.1 removed but 1.20.x still has, e.g.
     * {@code ItemSword -> SwordItem}, {@code EnumAction -> UseAnim}). 1.21.x hosts get
     * neither for now: no table has been validated against those jars, and a wrong redirect
     * is worse than none. Additive and soft-failing either way: if the resource is missing
     * or a line is malformed, the hardcoded chain redirects still apply.
     */
    void loadDirectClassMoves(RetromodTransformer transformer) {
        String host = com.retromod.core.RetromodVersion.TARGET_MC_VERSION;
        String resource;
        if (com.retromod.core.RetromodVersion.isUnobfuscatedTarget(host)) {
            resource = CLASS_MOVES_RESOURCE;
        } else if (!com.retromod.core.RetromodVersion.mcVersionExceeds(host, "1.20.6")) {
            // 1.20 - 1.20.6 host (Retromod hosts start at 1.20): the reported 1.12.2 hosts
            // (#103/#108/#117 all ran 1.20.1)
            resource = CLASS_MOVES_1201_RESOURCE;
        } else {
            return; // 1.21.x host: no validated table yet
        }
        try (java.io.InputStream is = getClass().getResourceAsStream(resource)) {
            if (is == null) return;
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isBlank() || line.charAt(0) == '#') continue;
                    int t = line.indexOf('\t');
                    if (t <= 0) continue;
                    transformer.registerClassRedirect(line.substring(0, t).trim(), line.substring(t + 1).trim());
                }
            }
        } catch (Exception e) {
            // additive; the hardcoded chain redirects still apply
        }

        // Block/Item Properties-constructor bridge. MC 1.20 already removed Material and made
        // Block/Item Properties-constructed (verified against Mojang's official 1.20.1 mappings:
        // Material is absent, BlockBehaviour$Properties.of() and Item$Properties() are present,
        // same shapes as 26.1), so the same bridge serves both table hosts. Item: super() ->
        // super(new Item.Properties()). Block: super(Material) -> super(BlockBehaviour.Properties.of())
        // (the Material GETSTATIC is nulled below and popped by the replace bridge). Default
        // properties only (material-specific settings are lost), but the block/item constructs.
        transformer.registerSuperConstructorRedirect(
            "net/minecraft/world/item/Item", "()V",
            "(Lnet/minecraft/world/item/Item$Properties;)V");
        transformer.registerSuperConstructorReplace(
            "net/minecraft/world/level/block/Block",
            "(Lnet/minecraft/block/material/Material;)V",
            "(Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;)V");
        // Material is gone on every table host; null its static-constant reads (e.g. Material.IRON)
        // so the super(Material.X) read doesn't fault before the replace bridge pops it.
        transformer.registerStaticFieldNuller("net/minecraft/block/material/Material");
    }

    @Override
    public String[] getShimClasses() {
        return new String[] {
            "com.retromod.shim.forge.embedded.FlatteningShim",
            "com.retromod.shim.forge.embedded.SidedProxyShim"
        };
    }
}
