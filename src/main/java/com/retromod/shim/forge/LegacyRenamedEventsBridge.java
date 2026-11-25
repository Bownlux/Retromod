/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.forge.embedded.LegacyPickupEvents;
import com.retromod.shim.forge.embedded.LegacySpawnEvents;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import java.util.function.Predicate;

import static org.objectweb.asm.Opcodes.*;

/** Moves Forge 1.20.1 item pickup and spawn finalize listeners onto the NeoForge events that replaced them. */
final class LegacyRenamedEventsBridge {

    static final String FORGE_PICKUP = "net/minecraftforge/event/entity/player/EntityItemPickupEvent";
    static final String NEO_PICKUP = "net/neoforged/neoforge/event/entity/player/ItemEntityPickupEvent$Pre";
    static final String PICKUP_HELPER = "com/retromod/shim/forge/embedded/LegacyPickupEvents";
    static final String FORGE_PICKED_UP = "net/minecraftforge/event/entity/player/PlayerEvent$ItemPickupEvent";
    static final String NEO_PICKED_UP = "net/neoforged/neoforge/event/entity/player/ItemEntityPickupEvent$Post";
    static final String FIRE_HELPER = "com/retromod/generated/LegacyPickupHooks";
    static final String FIRE_DESC = "(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/item/ItemEntity;"
            + "Lnet/minecraft/world/item/ItemStack;)V";
    static final String FORGE_FINALIZE = "net/minecraftforge/event/entity/living/MobSpawnEvent$FinalizeSpawn";
    static final String NEO_FINALIZE = "net/neoforged/neoforge/event/entity/living/FinalizeSpawnEvent";
    static final String SPAWN_HELPER = "com/retromod/shim/forge/embedded/LegacySpawnEvents";
    static final String FORGE_RESULT = "Lnet/minecraftforge/eventbus/api/Event$Result;";

    private LegacyRenamedEventsBridge() {}

    static void register(RetromodTransformer t, Predicate<String> hostHasClass) {
        registerPickup(t, hostHasClass);
        registerPickedUp(t, hostHasClass);
        registerFinalizeSpawn(t, hostHasClass);
    }

    /**
     * NeoForge fires {@code ItemEntityPickupEvent.Pre} at the same point, with a TriState in place of
     * Forge's cancel and result, so the listener class keeps registering and its cancel still decides.
     */
    static boolean registerPickup(RetromodTransformer t, Predicate<String> hostHasClass) {
        if (hostHasClass.test(FORGE_PICKUP) || !hostHasClass.test(NEO_PICKUP)) return false;
        SyntheticEmbedder.registerClassResource(t, PICKUP_HELPER, LegacyPickupEvents.class);
        t.registerClassRedirect(FORGE_PICKUP, NEO_PICKUP);
        t.registerMethodRedirect(FORGE_PICKUP, "getEntity", "()Lnet/minecraft/world/entity/player/Player;",
                NEO_PICKUP, "getPlayer", "()Lnet/minecraft/world/entity/player/Player;");
        t.registerMethodRedirect(FORGE_PICKUP, "getItem", "()Lnet/minecraft/world/entity/item/ItemEntity;",
                NEO_PICKUP, "getItemEntity", "()Lnet/minecraft/world/entity/item/ItemEntity;");
        t.registerMethodRedirect(FORGE_PICKUP, "setCanceled", "(Z)V", PICKUP_HELPER, "setCanceled", "(Ljava/lang/Object;Z)V", true);
        t.registerMethodRedirect(FORGE_PICKUP, "isCanceled", "()Z", PICKUP_HELPER, "isCanceled", "(Ljava/lang/Object;)Z", true);
        t.registerMethodRedirect(FORGE_PICKUP, "setResult", "(" + FORGE_RESULT + ")V",
                PICKUP_HELPER, "setResult", "(Ljava/lang/Object;Ljava/lang/Object;)V", true);
        // Supplementaries reads the result back; the helper answers with the mod's own result type.
        t.registerMethodRedirect(FORGE_PICKUP, "getResult", "()" + FORGE_RESULT,
                PICKUP_HELPER, "getResult", "(Ljava/lang/Object;)L" + LegacyEventResultAdapter.RESULT + ";", true);
        return true;
    }

    /**
     * Forge's after-pickup {@code PlayerEvent.ItemPickupEvent} is NeoForge's {@code ItemEntityPickupEvent.Post}, fired at
     * the same point; Forge's stack was the copy taken before pickup, which Post keeps as its original stack.
     */
    static boolean registerPickedUp(RetromodTransformer t, Predicate<String> hostHasClass) {
        if (hostHasClass.test(FORGE_PICKED_UP) || !hostHasClass.test(NEO_PICKED_UP)) return false;
        t.registerClassRedirect(FORGE_PICKED_UP, NEO_PICKED_UP);
        t.registerMethodRedirect(FORGE_PICKED_UP, "getEntity", "()Lnet/minecraft/world/entity/player/Player;",
                NEO_PICKED_UP, "getPlayer", "()Lnet/minecraft/world/entity/player/Player;");
        t.registerMethodRedirect(FORGE_PICKED_UP, "getOriginalEntity", "()Lnet/minecraft/world/entity/item/ItemEntity;",
                NEO_PICKED_UP, "getItemEntity", "()Lnet/minecraft/world/entity/item/ItemEntity;");
        t.registerMethodRedirect(FORGE_PICKED_UP, "getStack", "()Lnet/minecraft/world/item/ItemStack;",
                NEO_PICKED_UP, "getOriginalStack", "()Lnet/minecraft/world/item/ItemStack;");
        // A mod that fires the event itself (Forge's ForgeEventFactory call) reaches NeoForge's, which takes the item first.
        t.registerSyntheticClass(FIRE_HELPER, generateFireHelper());
        for (String owner : new String[] {"net/minecraftforge/event/ForgeEventFactory", "net/neoforged/neoforge/event/EventHooks"}) {
            t.registerMethodRedirect(owner, "firePlayerItemPickupEvent", FIRE_DESC, FIRE_HELPER, "firePlayerItemPickupEvent", FIRE_DESC);
        }
        return true;
    }

    static byte[] generateFireHelper() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, FIRE_HELPER, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "firePlayerItemPickupEvent", FIRE_DESC, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKESTATIC, "net/neoforged/neoforge/event/EventHooks", "fireItemPickupPost",
                "(Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/world/entity/player/Player;"
                        + "Lnet/minecraft/world/item/ItemStack;)V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * NeoForge's {@code FinalizeSpawnEvent} is the same event under its own name; only the spawner
     * became an {@code Either} of block entity and entity, read back to the old spawner logic.
     */
    static boolean registerFinalizeSpawn(RetromodTransformer t, Predicate<String> hostHasClass) {
        if (hostHasClass.test(FORGE_FINALIZE) || !hostHasClass.test(NEO_FINALIZE)) return false;
        SyntheticEmbedder.registerClassResource(t, SPAWN_HELPER, LegacySpawnEvents.class);
        t.registerClassRedirect(FORGE_FINALIZE, NEO_FINALIZE);
        t.registerMethodRedirect(FORGE_FINALIZE, "getSpawner", "()Lnet/minecraft/world/level/BaseSpawner;",
                SPAWN_HELPER, "getSpawner", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        // NeoForge dropped the spawn tag from the event and from finalizeSpawn, so a read finds none and a write is lost.
        t.registerMethodRedirect(FORGE_FINALIZE, "getSpawnTag", "()Lnet/minecraft/nbt/CompoundTag;",
                SPAWN_HELPER, "getSpawnTag", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        t.registerMethodRedirect(FORGE_FINALIZE, "setSpawnTag", "(Lnet/minecraft/nbt/CompoundTag;)V",
                SPAWN_HELPER, "setSpawnTag", "(Ljava/lang/Object;Ljava/lang/Object;)V", true);
        return true;
    }
}
