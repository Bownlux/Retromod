/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.client.event;

import com.retromod.legacyapi.neoforge.client.extensions.LegacyArmorExtensions;
import com.retromod.legacyapi.neoforge.client.extensions.LegacyFluidTypeReads;
import com.retromod.shim.common.embedded.LegacyLog;
import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterFluidModelsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix3x2fStack;

/** Posts this copy's removed client events at the times the old loader did, and gives old mod fluids and armor their models and textures. */
final class LegacyClientEventPosts {

    private static final String HOST_CONTAINER_EVENT = "net.neoforged.neoforge.client.event.ContainerScreenEvent";
    private static boolean listening;
    private static Field leftPos;
    private static Field topPos;

    private LegacyClientEventPosts() {}

    /** Listens on the constructing mod's bus and the game bus, once per copy. */
    static void listen() {
        // Construction is the only time FML names an active mod.
        ModContainer container = ModLoadingContext.get().getActiveContainer();
        IEventBus bus = container == null ? null : container.getEventBus();
        if (bus == null) return;
        synchronized (LegacyClientEventPosts.class) {
            if (listening) return;
            listening = true;
        }
        // The old event came with the first shader load, on the main thread; client setup runs on workers, so it is queued.
        bus.addListener(FMLClientSetupEvent.class, event -> event.enqueueWork(
                () -> postToMods(new LegacyRegisterShadersEvent(Minecraft.getInstance().getResourceManager()))));
        // Last, so a fluid model a current mod registers itself is never taken first.
        bus.addListener(EventPriority.LOWEST, RegisterFluidModelsEvent.class, LegacyClientEventPosts::registerFluidModels);
        // Old armor items that chose their texture in code get an extension, registered after every other one.
        LegacyArmorExtensions.listen(bus);
        // NeoForge 26.1 and 26.2 still post their own container screen events.
        if (hostHas(HOST_CONTAINER_EVENT)) return;
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Render.Pre.class, event -> LegacyContainerScreenEvent.frameStarted(event.getScreen()));
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ScreenEvent.Render.Post.class, LegacyClientEventPosts::containerRendered);
    }

    // Every mod's bus, one priority at a time in mod order, as NeoForge posts a mod-bus event.
    private static void postToMods(Event event) {
        List<ModContainer> mods = ModList.get().getSortedMods();
        for (EventPriority phase : EventPriority.values()) {
            for (ModContainer mod : mods) {
                IEventBus bus = mod.getEventBus();
                if (bus == null) continue;
                try {
                    bus.post(phase, event);
                } catch (RuntimeException | LinkageError failure) {
                    LegacyLog.warn("An old " + event.getClass().getSimpleName() + " listener on the bus of " + mod.getNamespace()
                            + " threw; Retromod went on with the next one: " + failure);
                }
            }
        }
    }

    /** After a container screen drew: Background in screen space, then Foreground moved to the container's corner. */
    private static void containerRendered(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen) || LegacyContainerScreenEvent.postedBy(screen)) return;
        GuiGraphicsExtractor graphics = event.getGuiGraphics();
        int x = event.getMouseX(), y = event.getMouseY();
        int depth = depth(graphics.pose());
        LegacyContainerScreenEvent.Posting.set(true);
        try {
            NeoForge.EVENT_BUS.post(new LegacyContainerScreenEvent.Render.Background(screen, graphics, x, y));
            Matrix3x2fStack pose = graphics.pose();
            pose.pushMatrix();
            pose.translate(corner(screen, true), corner(screen, false));
            try {
                NeoForge.EVENT_BUS.post(new LegacyContainerScreenEvent.Render.Foreground(screen, graphics, x, y));
            } finally {
                pose.popMatrix();
            }
        } catch (RuntimeException | LinkageError failure) {
            // A listener that pushed and then threw would leave every later overlay of this frame shifted.
            for (int extra = depth(graphics.pose()) - depth; extra > 0; extra--) graphics.pose().popMatrix();
            LegacyLog.warnOnce("container render " + LegacyClientEventPosts.class.getName(), "An old container screen"
                    + " listener failed while " + screen.getClass().getName() + " drew: " + failure);
        } finally {
            LegacyContainerScreenEvent.Posting.set(false);
        }
    }

    // The pose stack's depth, counted on a copy, since JOML keeps it private.
    private static int depth(Matrix3x2fStack pose) {
        Matrix3x2fStack probe;
        try {
            probe = (Matrix3x2fStack) pose.clone();
        } catch (CloneNotSupportedException | RuntimeException noCopy) {
            return 0;
        }
        int depth = 0;
        try {
            while (depth < 1024) {
                probe.popMatrix();
                depth++;
            }
        } catch (IllegalStateException bottom) {
            // popMatrix refuses at the bottom of the stack.
        }
        return depth;
    }

    private static boolean hostHas(String name) {
        try {
            Class.forName(name, false, LegacyClientEventPosts.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        }
    }

    // The container's left or top edge, which the old foreground event drew from.
    private static int corner(AbstractContainerScreen<?> screen, boolean left) {
        try {
            Field field = left ? leftPos : topPos;
            if (field == null) {
                field = AbstractContainerScreen.class.getDeclaredField(left ? "leftPos" : "topPos");
                field.setAccessible(true);
                if (left) leftPos = field;
                else topPos = field;
            }
            return field.getInt(screen);
        } catch (ReflectiveOperationException | RuntimeException closed) {
            return 0;
        }
    }

    /**
     * Each fluid whose client extension still overrides the old texture reads gets a model built from them. Only an
     * extension from this copy's own jar counts, which proves it belongs to a translated mod.
     */
    private static void registerFluidModels(RegisterFluidModelsEvent event) {
        Module own = LegacyClientEventPosts.class.getModule();
        for (Fluid fluid : BuiltInRegistries.FLUID) {
            FluidModel.Unbaked model;
            try {
                IClientFluidTypeExtensions ext = IClientFluidTypeExtensions.of(fluid);
                if (ext == null || ext.getClass().getModule() != own) continue;
                model = LegacyFluidTypeReads.oldModel(fluid, ext);
            } catch (RuntimeException | LinkageError failure) {
                LegacyLog.warnOnce("fluid model " + fluid, "Retromod could not read the old textures of the fluid "
                        + BuiltInRegistries.FLUID.getKey(fluid) + ", so it keeps the missing texture: " + failure);
                continue;
            }
            if (model == null) continue;
            try {
                event.register(model, fluid);
            } catch (RuntimeException alreadyModeled) {
                // Another copy of this class, or the mod itself, gave the fluid a model already.
                LegacyLog.debug("Retromod left the fluid model of " + BuiltInRegistries.FLUID.getKey(fluid) + " as it was: " + alreadyModeled);
            }
        }
    }
}
