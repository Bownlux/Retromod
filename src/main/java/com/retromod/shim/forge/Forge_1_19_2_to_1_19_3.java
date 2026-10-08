/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

/** Forge 1.19.2 to 1.19.3: creative-tab rework, registry events, PoseStack to GuiGraphics. */
public class Forge_1_19_2_to_1_19_3 implements VersionShim {

    @Override public String getShimName() { return "Forge 1.19.2 to 1.19.3"; }
    @Override public String getSourceVersion() { return "1.19.2"; }
    @Override public String getTargetVersion() { return "1.19.3"; }
    @Override public String getModLoaderType() { return "forge"; }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // A 1.19.2 mod makes a creative tab by subclassing CreativeModeTab and calling its String
        // constructor, which 1.19.3 deleted in favour of a builder. The subclass loads and then
        // dies on its own super(...) call (#264). Rebase it onto a generated bridge.
        CreativeModeTabBridge.register(transformer);

        // 1.19.3 moved configured features, placed features and biomes into data pack
        // registries. Rebuild what the mod registers in code as a generated data pack.
        LegacyWorldgenBridge.register(transformer);
        LegacyRegistryKeyMoves.register(transformer);
        // 1.19.3 replaced com.mojang.math's vectors, quaternions and matrices with JOML.
        com.retromod.shim.common.LegacyMojangMathBridge.register(transformer);
        com.retromod.shim.common.LegacyOreRuleTests.register(transformer);
        // Banner patterns became registry entries and special recipes gained a book category.
        String host = com.retromod.core.RetromodVersion.TARGET_MC_VERSION;
        if (com.retromod.shim.common.LegacyBannerPatternBridge.appliesTo(host)) {
            com.retromod.shim.common.LegacyBannerPatternBridge.register(transformer);
        }
        if (com.retromod.shim.common.LegacyCraftingRecipeBridge.appliesTo(host)) {
            com.retromod.shim.common.LegacyCraftingRecipeBridge.register(transformer);
        }
        // 1.21.2 moved explosions to ServerExplosion, and explode no longer returns one.
        if (com.retromod.core.RetromodVersion.compareMcVersions(
                com.retromod.core.RetromodVersion.TARGET_MC_VERSION, "1.21.2") < 0) {
            com.retromod.shim.common.LegacyExplosionBridge.register(transformer);
        }
        if (!com.retromod.util.McReflect.isNeoForge()) {
            // Forge now locks each registry when its own event ends, so a static-initializer
            // register() that 1.19.2 accepted late throws "is being added too late".
            String lateRegistration = "com/retromod/shim/forge/embedded/LateForgeRegistration";
            com.retromod.core.SyntheticEmbedder.registerClassResource(
                    transformer, lateRegistration, Forge_1_19_2_to_1_19_3.class);
            for (String nameType : new String[]{"Ljava/lang/String;", "Lnet/minecraft/resources/ResourceLocation;"}) {
                transformer.registerMethodRedirect("net/minecraftforge/registries/IForgeRegistry",
                        "register", "(" + nameType + "Ljava/lang/Object;)V", lateRegistration,
                        "register", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V", true);
            }
        }

        // 1.19.3 replaced the SoundEvent constructors with static factories. MCreator registers
        // every sound with new SoundEvent(id), so the whole sound registry failed without this.
        String soundEvent = "net/minecraft/sounds/SoundEvent";
        for (String resourceId : new String[]{"Lnet/minecraft/resources/ResourceLocation;",
                "Lnet/minecraft/resources/Identifier;"}) {
            transformer.registerConstructorRedirect(soundEvent, "(" + resourceId + ")V",
                    soundEvent, "createVariableRangeEvent", "(" + resourceId + ")L" + soundEvent + ";");
            transformer.registerConstructorRedirect(soundEvent, "(" + resourceId + "F)V",
                    soundEvent, "createFixedRangeEvent", "(" + resourceId + "F)L" + soundEvent + ";");
        }

        // 1.19.3 removed the deprecated register overloads of the particle provider event.
        String particleEvent = "net/minecraftforge/client/event/RegisterParticleProvidersEvent";
        String spriteSetDesc = "(Lnet/minecraft/core/particles/ParticleType;"
                + "Lnet/minecraft/client/particle/ParticleEngine$SpriteParticleRegistration;)V";
        String providerDesc = "(Lnet/minecraft/core/particles/ParticleType;"
                + "Lnet/minecraft/client/particle/ParticleProvider;)V";
        transformer.registerMethodRedirect(particleEvent, "register", spriteSetDesc,
                particleEvent, "registerSpriteSet", spriteSetDesc);
        transformer.registerMethodRedirect(particleEvent, "register", providerDesc,
                particleEvent, "registerSpecial", providerDesc);

        // No class-redirect for Registry: it still exists as a type, so renaming it would break
        // every Registry<T> use. The moved statics (BLOCK, ITEM, ...) need FieldRedirects instead.
        transformer.registerMethodRedirect(
            "net/minecraft/world/item/CreativeModeTab", "builder",
            "(Lnet/minecraft/world/item/CreativeModeTab$Row;I)Lnet/minecraft/world/item/CreativeModeTab$Builder;",
            "com/retromod/shim/forge/embedded/CreativeTabShim", "builder",
            "(Ljava/lang/Object;I)Ljava/lang/Object;"
        );
        transformer.registerClassRedirect(
            "net/minecraftforge/event/RegistryEvent",
            "net/minecraftforge/registries/RegisterEvent"
        );
        transformer.registerMethodRedirect(
            "net/minecraftforge/client/event/RenderGuiOverlayEvent", "getMatrixStack",
            "()Lcom/mojang/blaze3d/vertex/PoseStack;",
            "com/retromod/shim/forge/embedded/RenderShim", "getGuiGraphics",
            "(Ljava/lang/Object;)Ljava/lang/Object;"
        );

        // Widget renamed to Renderable
        transformer.registerClassRedirect(
            "net/minecraft/client/gui/components/Widget",
            "net/minecraft/client/gui/components/Renderable"
        );

        // Loot providers renamed to the sub-provider pattern
        transformer.registerClassRedirect(
            "net/minecraft/data/loot/BlockLoot",
            "net/minecraft/data/loot/BlockLootSubProvider"
        );
        transformer.registerClassRedirect(
            "net/minecraft/data/loot/EntityLoot",
            "net/minecraft/data/loot/EntityLootSubProvider"
        );

        // ForgeRegistries field renames
        transformer.registerFieldRedirect(
            "net/minecraftforge/registries/ForgeRegistries", "CONTAINERS",
            "Lnet/minecraftforge/registries/IForgeRegistry;",
            "net/minecraftforge/registries/ForgeRegistries", "MENU_TYPES",
            "Lnet/minecraftforge/registries/IForgeRegistry;"
        );
        transformer.registerFieldRedirect(
            "net/minecraftforge/registries/ForgeRegistries", "BLOCK_ENTITIES",
            "Lnet/minecraftforge/registries/IForgeRegistry;",
            "net/minecraftforge/registries/ForgeRegistries", "BLOCK_ENTITY_TYPES",
            "Lnet/minecraftforge/registries/IForgeRegistry;"
        );
        transformer.registerFieldRedirect(
            "net/minecraftforge/registries/ForgeRegistries", "ENTITIES",
            "Lnet/minecraftforge/registries/IForgeRegistry;",
            "net/minecraftforge/registries/ForgeRegistries", "ENTITY_TYPES",
            "Lnet/minecraftforge/registries/IForgeRegistry;"
        );
    }

    @Override
    public String[] getShimClasses() {
        return new String[0];
    }
}
