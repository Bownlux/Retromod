/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import com.mojang.blaze3d.vertex.PoseStack;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import org.slf4j.LoggerFactory;

/** Old model calls through ItemRenderer and BlockRenderDispatcher, as Sophisticated Backpacks, Farmer's Delight and Carry On make them. Client only. */
final class BakedItemModelCase {

    private static final String RELOAD = "getBlockRenderer() and a block model's quads answer in a client reload listener";
    private static final String DRAWN = "getModel, render with a model, renderModelLists and renderSingleBlock reach the frame from an old"
            + " block entity renderer (first client tick in a world)";

    private static boolean reported;

    private BakedItemModelCase() {}

    static void register(IEventBus modBus) {
        try {
            modBus.addListener(BakedItemModelCase::onReloadListeners);
            MinecraftForge.EVENT_BUS.addListener(BakedItemModelCase::onClientTick);
        } catch (Throwable t) {
            log("Old model call checks register (construction)", "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    // Sophisticated Backpacks builds its backpack shapes from the block models in a reload listener.
    private static void onReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) manager -> {
            try {
                BlockRenderDispatcher blocks = Minecraft.getInstance().getBlockRenderer();
                BlockState stone = Blocks.STONE.defaultBlockState();
                BakedModel model = blocks.getBlockModel(stone);
                boolean same = blocks == Minecraft.getInstance().getBlockRenderer();
                int quads = model.getQuads(stone, null, RandomSource.create()).size();
                blocks.getBlockModelShaper().getParticleIcon(stone);
                log(RELOAD, same && model.getClass() != null ? "success" : "fail: same " + same + ", " + quads + " quads");
            } catch (Throwable t) {
                log(RELOAD, "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        });
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || reported || Minecraft.getInstance().level == null) return;
        reported = true;
        log(DRAWN, drawShelf());
    }

    // The steps 26.x takes for a block entity each frame: a state, the extract, then the submit into the frame's collector.
    private static String drawShelf() {
        try {
            OldShelf shelf = new OldShelf();
            SignBlockEntity sign = new SignBlockEntity(BlockPos.ZERO, Blocks.OAK_SIGN.defaultBlockState());
            Object state = method("createRenderState", 0).invoke(shelf);
            method("extractRenderState", 5).invoke(shelf, sign, state, 0.5f, null, null);
            Class<?> collectorType = Class.forName("net.minecraft.client.renderer.SubmitNodeCollector");
            List<String> calls = new ArrayList<>();
            Object collector = Proxy.newProxyInstance(collectorType.getClassLoader(), new Class<?>[]{collectorType}, (proxy, called, args) -> {
                calls.add(called.getName());
                return called.getReturnType() == boolean.class ? false : null;
            });
            method("submit", 4).invoke(shelf, state, new PoseStack(), collector, null);
            if (shelf.failure != null) return "fail: " + shelf.failure;
            if (shelf.drawn != 1) return "fail: the old render ran " + shelf.drawn + " times";
            if (shelf.appleIsBlock || !shelf.stoneIsBlock) return "fail: isGui3d apple " + shelf.appleIsBlock + ", stone " + shelf.stoneIsBlock;
            return calls.contains("submitItem") && calls.contains("submitCustomGeometry") ? "success" : "fail: the collector was handed " + calls;
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            return "fail: " + cause.getClass().getSimpleName() + ": " + cause.getMessage();
        }
    }

    private static Method method(String name, int parameters) {
        for (Method method : OldShelf.class.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameters) return method;
        }
        return null;
    }

    private static void log(String what, String result) {
        LoggerFactory.getLogger("Retromod-Test-Forge").info("[Retromod-Test-Forge] {}: {}", what, result);
    }

    /** Draws an apple with its model, a stone block's model as an item, the apple's quads and a stone block, as 1.20.1 renderers did. */
    static final class OldShelf implements BlockEntityRenderer<SignBlockEntity> {
        int drawn;
        boolean appleIsBlock;
        boolean stoneIsBlock;
        String failure;

        @Override
        public void render(SignBlockEntity sign, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light, int overlay) {
            drawn++;
            try {
                ItemRenderer items = Minecraft.getInstance().getItemRenderer();
                BlockRenderDispatcher blocks = Minecraft.getInstance().getBlockRenderer();
                ItemStack apple = new ItemStack(Items.APPLE);
                ItemStack stoneItem = new ItemStack(Blocks.STONE);
                BlockState stone = Blocks.STONE.defaultBlockState();
                // Farmer's Delight's cutting board: lay the item flat unless its model is 3D.
                poseStack.pushPose();
                appleIsBlock = items.getModel(apple, sign.getLevel(), null, 0).applyTransform(ItemDisplayContext.FIXED, poseStack, false).isGui3d();
                stoneIsBlock = items.getModel(stoneItem, sign.getLevel(), null, 0).isGui3d();
                poseStack.popPose();
                BakedModel model = items.getModel(apple, sign.getLevel(), null, 0);
                items.render(apple, ItemDisplayContext.FIXED, false, poseStack, buffers, light, overlay, model);
                // Sophisticated Backpacks' item renderer: the model's quads into a buffer it asked for.
                items.renderModelLists(model, apple, light, overlay, poseStack,
                        ItemRenderer.getFoilBuffer(buffers, Sheets.translucentItemSheet(), true, false));
                // Carry On: a block's model drawn as an item, then the block itself.
                items.render(stoneItem, ItemDisplayContext.NONE, false, poseStack, buffers, light, overlay, blocks.getBlockModel(stone));
                blocks.renderSingleBlock(stone, poseStack, buffers, light, overlay);
            } catch (Throwable t) {
                failure = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
        }
    }
}
