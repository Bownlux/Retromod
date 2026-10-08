/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

/** Forge 1.19.4 to 1.20: Material removed, MaterialColor renamed to NoteBlockInstrument, sign API changed. */
public class Forge_1_19_4_to_1_20 implements VersionShim {

    @Override public String getShimName() { return "Forge 1.19.4 to 1.20"; }
    @Override public String getSourceVersion() { return "1.19.4"; }
    @Override public String getTargetVersion() { return "1.20"; }
    @Override public String getModLoaderType() { return "forge"; }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // 1.20 replaced GuiComponent's PoseStack drawing with GuiGraphics, and gave
        // HumanoidArmorLayer a ModelManager. Gated to the hosts whose buffer API it links.
        LegacyGuiComponentBridge.register(transformer);
        transformer.registerMethodRename(
            "net/minecraft/world/level/block/state/BlockBehaviour$Properties",
            "noDrops", "noLootTable"
        );
        // 1.20 removed Material. Properties.of(Material, ...) becomes Properties.of(), and the
        // material and color arguments are popped rather than left on the stack, which the verifier
        // rejected as "Bad operand type when invoking <init>". Material constants read as null,
        // since the class no longer exists to load them from; the block keeps its other settings.
        String properties = "net/minecraft/world/level/block/state/BlockBehaviour$Properties";
        String material = "Lnet/minecraft/world/level/material/Material;";
        for (String extra : new String[]{"", "Lnet/minecraft/world/level/material/MaterialColor;",
                "Lnet/minecraft/world/level/material/MapColor;", "Lnet/minecraft/world/item/DyeColor;",
                "Ljava/util/function/Function;"}) {
            transformer.registerArgDropMethodRedirect(properties, "of",
                    "(" + material + extra + ")L" + properties + ";",
                    properties, "of", "()L" + properties + ";");
        }
        transformer.registerStaticFieldNuller("net/minecraft/world/level/material/Material");
        transformer.registerClassRedirect(
            "net/minecraft/world/level/material/MaterialColor",
            "net/minecraft/world/level/material/MapColor"
        );
        // 1.20 split the loot builder: what a block's getDrops receives is LootParams.Builder.
        // Without this, a getDrops override named a deleted class and was never called.
        transformer.registerClassRedirect(
            "net/minecraft/world/level/storage/loot/LootContext$Builder",
            "net/minecraft/world/level/storage/loot/LootParams$Builder"
        );
        // Forge 1.20 replaced LivingSetAttackTargetEvent with LivingChangeTargetEvent, which
        // calls the target getNewTarget. A listener for the old event failed to register, and
        // Forge then rejected every automatic subscriber in the mod.
        String oldTargetEvent = "net/minecraftforge/event/entity/living/LivingSetAttackTargetEvent";
        String targetEvent = "net/minecraftforge/event/entity/living/LivingChangeTargetEvent";
        transformer.registerClassRedirect(oldTargetEvent, targetEvent);
        transformer.registerClassRedirect(oldTargetEvent + "$LivingTargetType",
                targetEvent + "$LivingTargetType");
        for (String owner : new String[]{oldTargetEvent, targetEvent}) {
            transformer.registerMethodRedirect(owner, "getTarget",
                    "()Lnet/minecraft/world/entity/LivingEntity;",
                    targetEvent, "getNewTarget", "()Lnet/minecraft/world/entity/LivingEntity;");
        }
        // 1.20 made Entity.level private behind level(). Mods read it through Entity, Player and
        // their own entity classes, and an SRG id names that one field whatever the owner, so
        // the read is matched by id. A Mojang-named host keeps the plain name and is not covered.
        transformer.registerUniqueFieldGetter("f_19853_", "Lnet/minecraft/world/level/Level;",
                "net/minecraft/world/entity/Entity", "level", "()Lnet/minecraft/world/level/Level;");
        LegacyEntityApi120Adapter.register(transformer);
        // 1.20 renamed ProjectileUtil.getHitResult(Entity, Predicate) when it added the view
        // vector variant; the five-argument getHitResult kept its name.
        String projectileUtil = "net/minecraft/world/entity/projectile/ProjectileUtil";
        String hitDesc = "(Lnet/minecraft/world/entity/Entity;Ljava/util/function/Predicate;)"
                + "Lnet/minecraft/world/phys/HitResult;";
        transformer.registerMethodRedirect(projectileUtil, "getHitResult", hitDesc,
                projectileUtil, "getHitResultOnMoveVector", hitDesc);
        transformer.registerMethodRedirect(
            "net/minecraft/world/level/block/entity/SignBlockEntity", "getMessage",
            "(I)Lnet/minecraft/network/chat/Component;",
            "com/retromod/shim/forge/embedded/SignShim", "getMessage",
            "(Ljava/lang/Object;I)Ljava/lang/Object;"
        );
    }

    @Override
    public String[] getShimClasses() {
        return new String[0];
    }
}
