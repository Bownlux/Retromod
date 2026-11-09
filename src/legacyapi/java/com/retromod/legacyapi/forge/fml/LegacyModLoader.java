/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.fml;

import com.retromod.shim.common.embedded.LegacyLog;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import net.minecraftforge.fml.IModLoadingState;
import net.minecraftforge.fml.ModLoader;
import net.minecraftforge.fml.ModLoadingWarning;
import net.minecraftforge.fml.ModStateManager;
import net.minecraftforge.fml.event.IModBusEvent;
import net.minecraftforge.forgespi.language.IModInfo;

/** Forge 1.20.1's {@code ModLoader.get()} and its instance calls, on the static ModLoader of Forge 26.2. */
public final class LegacyModLoader {

    private static volatile ModLoader instance;
    private static volatile boolean refused;

    private LegacyModLoader() {}

    /** {@code ModLoader.get()}: the host's object, so a null check or {@code ModLoader.get()::addWarning} passes. */
    public static ModLoader get() {
        ModLoader loader = instance;
        if (loader != null || refused) return loader;
        try {
            // The private constructor does nothing, and every call below ignores the object it is made on.
            Constructor<ModLoader> constructor = ModLoader.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            instance = loader = constructor.newInstance();
        } catch (ReflectiveOperationException | RuntimeException closed) {
            // A host that keeps FML's package closed gets null; the calls below take a null receiver too.
            refused = true;
        }
        return loader;
    }

    /** {@code addWarning(warning)}: logged, because Forge 26.2 stops every game event once it shows a warning. */
    public static void addWarning(ModLoader ignored, ModLoadingWarning warning) {
        // Logged once per distinct line, since some mods add the same warning from several places.
        String line = source(warning) + " reported a loading warning, which Retromod logged instead of passing "
                + "to Forge: " + text(warning);
        LegacyLog.warnOnce(line, line);
    }

    /** {@code getWarnings()}: the host's list, which holds no warning an old mod added. */
    public static List<ModLoadingWarning> getWarnings(ModLoader ignored) {
        return ModLoader.getWarnings();
    }

    /** {@code hasCompletedState(name)}: the host's state of that name, which fails for a name Forge never had. */
    public static boolean hasCompletedState(ModLoader ignored, String state) {
        return ModLoader.hasCompletedState(findState(state));
    }

    private static IModLoadingState findState(String state) {
        try {
            return ModStateManager.findState(state);
        } catch (IllegalArgumentException missing) {
            // Forge 26.2 dropped OBJECT_HOLDERS, which ran right after CREATE_REGISTRIES, so that state answers for it.
            if (!"OBJECT_HOLDERS".equals(state)) throw missing;
            return ModStateManager.findState("CREATE_REGISTRIES");
        }
    }

    /** {@code runEventGenerator(generator)}: one event per mod, built and posted by the host in mod order. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void runEventGenerator(ModLoader ignored, Function generator) {
        ModLoader.runEventGenerator(generator);
    }

    /** {@code postEvent(event)}: a mod-bus event, posted to every mod in mod order. */
    public static void postEvent(ModLoader ignored, Object event) {
        ModLoader.postEvent((IModBusEvent) event);
    }

    /** {@code postEventWithReturn(event)}: the same post, handing the event back. */
    public static Object postEventWithReturn(ModLoader ignored, Object event) {
        return ModLoader.postEventWithReturn((IModBusEvent) event);
    }

    /** {@code postEventWrapContainerInModOrder(event)}: the post with each mod's context active. */
    public static void postEventWrapContainerInModOrder(ModLoader ignored, Object event) {
        ModLoader.postEventWrapContainerInModOrder((IModBusEvent) event);
    }

    /** {@code postEventWithWrapInModOrder(event, pre, post)}: the post with a call before and after each mod. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void postEventWithWrapInModOrder(ModLoader ignored, Object event, BiConsumer pre, BiConsumer post) {
        ModLoader.postEventWithWrapInModOrder((IModBusEvent) event, pre, post);
    }

    private static String source(ModLoadingWarning warning) {
        try {
            IModInfo mod = warning.modInfo();
            return mod == null ? "A mod" : "The mod " + mod.getModId();
        } catch (RuntimeException | LinkageError unreadable) {
            return "A mod";
        }
    }

    // Forge's text when it can format the message this early, otherwise the message key and its values.
    private static String text(ModLoadingWarning warning) {
        try {
            return warning.formatToString();
        } catch (RuntimeException | LinkageError unformatted) {
            return warning == null ? "null" : warning.i18nMessage() + " " + warning.context();
        }
    }
}
