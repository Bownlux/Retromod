/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.gui;

import com.retromod.util.McReflect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.Consumer;

/**
 * Adds a "Retromod" button to the title screen across all mod loaders.
 *
 * Detects the running loader and uses its event system (Fabric ScreenEvents.AFTER_INIT,
 * NeoForge/Forge EVENT_BUS + ScreenEvent.Init.Post). All API calls go through reflection
 * so Retromod compiles without loader-specific event classes on the classpath. The button
 * opens the Retromod mod manager (file picker + transformation).
 */
public final class TitleScreenButtonInjector {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-TitleButton");
    private static boolean registered = false;

    // cached after first successful lookup
    private static Class<?> titleScreenClass;
    private static Class<?> screenClass;
    private static Class<?> textClass;
    private static Class<?> buttonWidgetClass;

    private TitleScreenButtonInjector() {}

    /**
     * Registers the title screen button injector. Auto-detects the mod loader and uses its
     * event system. Idempotent: only registers once.
     */
    public static synchronized void register() {
        if (registered) return;

        if (!resolveMcClasses()) {
            LOGGER.warn("Could not resolve Minecraft GUI classes - title screen button unavailable");
            return;
        }

        boolean success = false;

        if (McReflect.isFabric()) {
            success = tryFabricScreenEvents();
        }

        if (!success && McReflect.isNeoForge()) {
            success = tryNeoForgeEvents();
        }

        if (!success && McReflect.isForge()) {
            success = tryForgeEvents();
        }

        if (success) {
            registered = true;
            LOGGER.info("Title screen button injector registered");
        } else {
            LOGGER.info("Title screen button not available (no supported event system found)");
        }
    }

    /**
     * Resolves the Minecraft classes needed for button creation, handling
     * yarn/mojang/intermediary name differences via McReflect.
     */
    private static boolean resolveMcClasses() {
        try {
            titleScreenClass = McReflect.findClass(
                "net.minecraft.client.gui.screen.TitleScreen",   // yarn
                "net.minecraft.client.gui.screens.TitleScreen"   // mojang
            );

            screenClass = McReflect.findClass(
                "net.minecraft.client.gui.screen.Screen",   // yarn
                "net.minecraft.client.gui.screens.Screen"   // mojang
            );

            textClass = McReflect.findClass(
                "net.minecraft.text.Text",                    // yarn
                "net.minecraft.network.chat.Component"        // mojang
            );

            buttonWidgetClass = McReflect.findClass(
                "net.minecraft.client.gui.widget.ButtonWidget",  // yarn
                "net.minecraft.client.gui.components.Button"     // mojang
            );

            return titleScreenClass != null && screenClass != null
                && textClass != null && buttonWidgetClass != null;

        } catch (Exception e) {
            LOGGER.debug("Failed to resolve MC GUI classes: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Registers via Fabric API's ScreenEvents.AFTER_INIT. Fabric API class names
     * (net.fabricmc.fabric.api.*) are not obfuscated, so Class.forName works in dev and prod.
     */
    private static boolean tryFabricScreenEvents() {
        try {
            Class<?> screenEventsClass = Class.forName(
                "net.fabricmc.fabric.api.client.screen.v1.ScreenEvents");
            Object afterInitEvent = screenEventsClass.getField("AFTER_INIT").get(null);

            Class<?> afterInitInterface = Class.forName(
                "net.fabricmc.fabric.api.client.screen.v1.ScreenEvents$AfterInit");

            Object proxy = Proxy.newProxyInstance(
                afterInitInterface.getClassLoader(),
                new Class<?>[]{afterInitInterface},
                (proxyObj, method, args) -> {
                    // afterInit(client, screen, scaledWidth, scaledHeight)
                    if (method.getParameterCount() == 4 && args != null) {
                        Object screen = args[1];
                        if (titleScreenClass.isInstance(screen)) {
                            addRetromodButton(screen);
                            RestartPrompt.maybeShow(screen);
                        }
                    }
                    return null;
                }
            );

            // Resolve register() on the exported API superclass Event, not the concrete
            // ArrayBackedEvent: the latter is in a non-exported impl package and Java 25's
            // module access blocks reflective invoke on it. invoke() still dispatches
            // virtually to the impl at runtime.
            Class<?> eventApiClass;
            try {
                eventApiClass = Class.forName("net.fabricmc.fabric.api.event.Event");
            } catch (ClassNotFoundException notFound) {
                // older Fabric API: fall back to the concrete class
                eventApiClass = afterInitEvent.getClass();
            }
            Method registerMethod = null;
            try {
                // erasure: Event<T>.register(T) -> register(Object)
                registerMethod = eventApiClass.getMethod("register", Object.class);
            } catch (NoSuchMethodException noSuch) {
                // very old Fabric API: walk methods by name/arity
                for (Method m : eventApiClass.getMethods()) {
                    if ("register".equals(m.getName()) && m.getParameterCount() == 1) {
                        registerMethod = m;
                        break;
                    }
                }
            }
            if (registerMethod == null) {
                throw new NoSuchMethodException(
                        "register(Object) not found on Event API class " + eventApiClass.getName());
            }
            registerMethod.invoke(afterInitEvent, proxy);

            LOGGER.debug("Registered via Fabric ScreenEvents.AFTER_INIT");
            return true;

        } catch (ClassNotFoundException e) {
            LOGGER.debug("Fabric API ScreenEvents not available (Fabric API may not be installed)");
            return false;
        } catch (Exception e) {
            LOGGER.warn("Failed to register Fabric screen events: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Registers via NeoForge's event bus:
     * NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ScreenEvent.Init.Post.class, consumer)
     */
    private static boolean tryNeoForgeEvents() {
        try {
            Class<?> neoForgeClass = Class.forName("net.neoforged.neoforge.common.NeoForge");
            Object eventBus = neoForgeClass.getField("EVENT_BUS").get(null);

            Class<?> screenEventClass = Class.forName(
                "net.neoforged.neoforge.client.event.ScreenEvent$Init$Post");

            Class<?> priorityClass = Class.forName("net.neoforged.bus.api.EventPriority");
            Object normalPriority = priorityClass.getField("NORMAL").get(null);

            Consumer<Object> consumer = event -> handleForgeScreenEvent(event);

            Method addListener = findAddListenerMethod(eventBus.getClass(), priorityClass);
            if (addListener != null) {
                addListener.invoke(eventBus, normalPriority, false, screenEventClass, consumer);
            } else {
                // fall back to the simpler addListener(Class, Consumer) overload
                Method simpleAdd = eventBus.getClass().getMethod("addListener", Class.class, Consumer.class);
                simpleAdd.invoke(eventBus, screenEventClass, consumer);
            }

            LOGGER.debug("Registered via NeoForge EVENT_BUS");
            return true;

        } catch (ClassNotFoundException e) {
            LOGGER.debug("NeoForge event bus not available");
            return false;
        } catch (Exception e) {
            LOGGER.warn("Failed to register NeoForge screen events: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Registers via Forge's event bus (legacy Forge, pre-NeoForge).
     */
    private static boolean tryForgeEvents() {
        try {
            Class<?> forgeClass = Class.forName("net.minecraftforge.common.MinecraftForge");
            Object eventBus = forgeClass.getField("EVENT_BUS").get(null);

            // event class name changed across Forge versions
            Class<?> screenEventClass = null;
            String[] eventClassNames = {
                "net.minecraftforge.client.event.ScreenEvent$Init$Post",
                "net.minecraftforge.client.event.ScreenEvent$InitScreenEvent$Post"
            };
            for (String name : eventClassNames) {
                try {
                    screenEventClass = Class.forName(name);
                    break;
                } catch (ClassNotFoundException ignored) {}
            }

            if (screenEventClass == null) {
                LOGGER.debug("Forge ScreenEvent class not found");
                return false;
            }

            Class<?> priorityClass = Class.forName("net.minecraftforge.eventbus.api.EventPriority");
            Object normalPriority = priorityClass.getField("NORMAL").get(null);

            Consumer<Object> consumer = event -> handleForgeScreenEvent(event);

            Method addListener = findAddListenerMethod(eventBus.getClass(), priorityClass);
            if (addListener != null) {
                addListener.invoke(eventBus, normalPriority, false, screenEventClass, consumer);
            } else {
                Method simpleAdd = eventBus.getClass().getMethod("addListener", Class.class, Consumer.class);
                simpleAdd.invoke(eventBus, screenEventClass, consumer);
            }

            LOGGER.debug("Registered via Forge EVENT_BUS");
            return true;

        } catch (ClassNotFoundException e) {
            LOGGER.debug("Forge event bus not available");
            return false;
        } catch (Exception e) {
            LOGGER.warn("Failed to register Forge screen events: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Handles a Forge/NeoForge ScreenEvent.Init.Post; both loaders expose event.getScreen().
     */
    private static void handleForgeScreenEvent(Object event) {
        try {
            Method getScreen = event.getClass().getMethod("getScreen");
            Object screen = getScreen.invoke(event);

            if (titleScreenClass.isInstance(screen)) {
                addRetromodButton(screen);
                RestartPrompt.maybeShow(screen);
            }
        } catch (Exception e) {
            LOGGER.debug("Error handling screen event: {}", e.getMessage());
        }
    }

    /**
     * Adds the Retromod button to the title screen, resolving MC class/method names
     * across loaders via McReflect. The button opens the Retromod mod manager.
     */
    private static void addRetromodButton(Object screen) {
        try {
            int width = McReflect.getIntField(screen, screenClass, 854, "width");

            // Keep the button clear of the title screen's centered controls.
            int buttonX = width - 82;
            int buttonY = 2;

            // Minecraft owns this callback, which keeps screen changes on the render thread.
            Class<?> pressActionClass = findPressActionClass();
            if (pressActionClass == null) {
                LOGGER.debug("Could not find PressAction / OnPress class");
                return;
            }
            Object pressAction = Proxy.newProxyInstance(
                pressActionClass.getClassLoader(),
                new Class<?>[]{pressActionClass},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() != Object.class) {
                        LOGGER.debug("Opening the Retromod screen from the title menu");
                        try {
                            MainScreenFactory.open(screen);
                        } catch (Throwable t) {
                            LOGGER.warn("Could not open the Retromod screen: {}",
                                    t.getMessage(), t);
                        }
                    }
                    return null;
                }
            );

            // A text button works on versions where custom sprite registration is unreliable.
            Object button = buildPlainTextButton(buttonX, buttonY, pressAction, pressActionClass);
            if (button == null) {
                LOGGER.warn("Could not create the Retromod title screen button");
                return;
            }

            // Prefer addRenderableWidget because some Yarn versions only render children added
            // through addDrawableChild and do not give them click handling.
            Method addMethod = McReflect.findMethod(screenClass,
                "addRenderableWidget", "addDrawableChild", "addWidget");
            if (addMethod != null) {
                addMethod.setAccessible(true);
                addMethod.invoke(screen, button);
                LOGGER.debug("Added the Retromod title button with {} at ({}, {})",
                        addMethod.getName(), buttonX, buttonY);
            } else {
                LOGGER.warn("Could not find a supported Screen method for adding the Retromod button");
            }

        } catch (Exception e) {
            LOGGER.warn("Could not add the Retromod title screen button: {}", e.getMessage(), e);
        }
    }

    /**
     * Builds a wide ButtonWidget labeled "Retromod" via the same Button.builder() API
     * vanilla uses, so click events are wired by the framework.
     */
    private static Object buildPlainTextButton(int x, int y, Object pressAction,
                                                 Class<?> pressActionClass) {
        try {
            Method literal = McReflect.findMethod(textClass,
                new Class[]{String.class}, "literal");
            if (literal == null) {
                LOGGER.warn("Could not create the Retromod button label because "
                        + "Component.literal was not found");
                return null;
            }
            Object text = literal.invoke(null, "Retromod");

            Method builder = McReflect.findMethod(buttonWidgetClass,
                new Class[]{textClass, pressActionClass}, "builder");
            if (builder == null) {
                LOGGER.warn("Could not create the Retromod button because Button.builder was not found");
                return null;
            }
            Object b = builder.invoke(null, text, pressAction);

            // width 80 fits "Retromod", height 20 is standard
            Method dim = McReflect.findMethod(b.getClass(),
                new Class[]{int.class, int.class, int.class, int.class},
                "dimensions", "bounds", "pos");
            if (dim != null) b = dim.invoke(b, x, y, 80, 20);

            Method build = McReflect.findMethod(b.getClass(), "build");
            return build != null ? build.invoke(b) : null;
        } catch (Exception e) {
            LOGGER.warn("Could not build the Retromod title screen button: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Builds a 20x20 logo button, trying SpriteIconButton first and falling back to a small
     * text button labeled "R" if that API is unavailable on this MC version.
     */
    private static Object buildLogoButton(int x, int y, Object pressAction,
                                           Class<?> pressActionClass) {
        Object iconBtn = trySpriteIconButton(x, y, pressAction, pressActionClass);
        if (iconBtn != null) return iconBtn;

        // fall back to a regular ButtonWidget labeled "R"
        try {
            Method literal = McReflect.findMethod(textClass,
                new Class[]{String.class}, "literal");
            if (literal == null) return null;
            Object text = literal.invoke(null, "R");

            Method builder = McReflect.findMethod(buttonWidgetClass,
                new Class[]{textClass, pressActionClass}, "builder");
            if (builder == null) return null;
            Object b = builder.invoke(null, text, pressAction);

            Method dim = McReflect.findMethod(b.getClass(),
                new Class[]{int.class, int.class, int.class, int.class},
                "dimensions", "bounds", "pos");
            if (dim != null) b = dim.invoke(b, x, y, 20, 20);

            Method build = McReflect.findMethod(b.getClass(), "build");
            return build != null ? build.invoke(b) : null;
        } catch (Exception e) {
            LOGGER.debug("Fallback text button failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Builds an icon-only button via MC's SpriteIconButton API, or null if it's unavailable.
     */
    private static Object trySpriteIconButton(int x, int y, Object pressAction,
                                                Class<?> pressActionClass) {
        try {
            Class<?> sib = McReflect.findClass(
                "net.minecraft.client.gui.widget.SpriteIconButton",
                "net.minecraft.client.gui.components.SpriteIconButton"
            );
            if (sib == null) return null;

            Class<?> identifierClass = McReflect.findClass(
                "net.minecraft.util.Identifier",
                "net.minecraft.resources.ResourceLocation"
            );
            if (identifierClass == null) return null;

            // build "retromod:retromod_logo": newer MC has fromNamespaceAndPath, older has the ctor
            Object spriteId = null;
            try {
                Method fromNs = identifierClass.getMethod("fromNamespaceAndPath",
                    String.class, String.class);
                spriteId = fromNs.invoke(null, "retromod", "retromod_logo");
            } catch (NoSuchMethodException ignored) {
                try {
                    spriteId = identifierClass.getConstructor(String.class, String.class)
                        .newInstance("retromod", "retromod_logo");
                } catch (Exception e) {
                    return null;
                }
            }
            if (spriteId == null) return null;

            Method literal = McReflect.findMethod(textClass,
                new Class[]{String.class}, "literal");
            if (literal == null) return null;
            Object message = literal.invoke(null, "Retromod");

            Method builderMethod = McReflect.findMethod(sib,
                new Class[]{textClass, pressActionClass, boolean.class}, "builder");
            if (builderMethod == null) {
                builderMethod = McReflect.findMethod(sib,
                    new Class[]{textClass, pressActionClass}, "builder");
                if (builderMethod == null) return null;
            }
            Object builder = builderMethod.getParameterCount() == 3
                ? builderMethod.invoke(null, message, pressAction, true)
                : builderMethod.invoke(null, message, pressAction);

            Method widthMethod = McReflect.findMethod(builder.getClass(),
                new Class[]{int.class}, "width");
            if (widthMethod != null) builder = widthMethod.invoke(builder, 20);

            Method spriteMethod = McReflect.findMethod(builder.getClass(),
                new Class[]{identifierClass, int.class, int.class}, "sprite");
            if (spriteMethod != null) {
                builder = spriteMethod.invoke(builder, spriteId, 20, 20);
            }

            Method build = McReflect.findMethod(builder.getClass(), "build");
            Object btn = build != null ? build.invoke(builder) : null;
            if (btn == null) return null;

            // builder doesn't set position
            Method setX = McReflect.findMethod(btn.getClass(), new Class[]{int.class}, "setX");
            Method setY = McReflect.findMethod(btn.getClass(), new Class[]{int.class}, "setY");
            if (setX != null) setX.invoke(btn, x);
            if (setY != null) setY.invoke(btn, y);

            LOGGER.debug("Built SpriteIconButton with retromod_logo");
            return btn;
        } catch (Exception e) {
            LOGGER.debug("SpriteIconButton not available: {}", e.getMessage());
            return null;
        }
    }

    // unused: settings are now reached via the Retromod logo button itself
    @SuppressWarnings("unused")
    private static void addSettingsButton(Object screen, int x, int y,
            Class<?> pressActionClass, Method literalMethod,
            Method builderMethod, Method addMethod) {
        try {
            Object settingsText = literalMethod.invoke(null, "\u2699");

            Object settingsAction = Proxy.newProxyInstance(
                pressActionClass.getClassLoader(),
                new Class<?>[]{pressActionClass},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() != Object.class) {
                        ConfigScreenFactory.open(screen);
                    }
                    return null;
                }
            );

            Object builder = builderMethod.invoke(null, settingsText, settingsAction);

            Method dimMethod = McReflect.findMethod(builder.getClass(),
                new Class[]{int.class, int.class, int.class, int.class},
                "dimensions", "bounds", "pos");
            if (dimMethod != null) {
                builder = dimMethod.invoke(builder, x, y, 20, 20);
            }

            Method buildMethod = McReflect.findMethod(builder.getClass(), "build");
            if (buildMethod != null) {
                Object button = buildMethod.invoke(builder);
                if (addMethod != null) {
                    addMethod.setAccessible(true);
                    addMethod.invoke(screen, button);
                }
            }

            LOGGER.debug("Retromod settings button added to title screen");
        } catch (Exception e) {
            LOGGER.debug("Could not add settings button: {}", e.getMessage());
        }
    }

    /**
     * Opens the Retromod mod manager when the button is clicked.
     */
    private static void openRetromodManager(Object screen) {
        try {
            Object client = McReflect.getField(screen, screenClass, "client", "minecraft");
            if (client == null) {
                // fall back to static getInstance()
                Class<?> mcClass = McReflect.findClass(
                    "net.minecraft.client.MinecraftClient",  // yarn
                    "net.minecraft.client.Minecraft"         // mojang
                );
                if (mcClass != null) {
                    Method getInstance = McReflect.findMethod(mcClass, "getInstance");
                    if (getInstance != null) {
                        client = getInstance.invoke(null);
                    }
                }
            }

            if (client == null) {
                LOGGER.error("Could not get Minecraft client instance");
                return;
            }

            RetromodScreen retroScreen = new RetromodScreen(client);
            retroScreen.open();

        } catch (Exception e) {
            LOGGER.error("Could not open Retromod manager", e);
        }
    }

    /**
     * Finds the PressAction / OnPress inner class of ButtonWidget / Button.
     */
    private static Class<?> findPressActionClass() {
        Class<?> c = McReflect.findClass(
            "net.minecraft.client.gui.widget.ButtonWidget$PressAction",  // yarn
            "net.minecraft.client.gui.components.Button$OnPress"         // mojang
        );
        if (c != null) return c;

        // fall back to the single-method inner interface of ButtonWidget
        if (buttonWidgetClass != null) {
            for (Class<?> inner : buttonWidgetClass.getDeclaredClasses()) {
                if (inner.isInterface() && inner.getDeclaredMethods().length == 1) {
                    return inner;
                }
            }
        }
        return null;
    }

    /**
     * Finds addListener(EventPriority, boolean, Class, Consumer).
     */
    private static Method findAddListenerMethod(Class<?> busClass, Class<?> priorityClass) {
        for (Method m : busClass.getMethods()) {
            if ("addListener".equals(m.getName())) {
                Class<?>[] params = m.getParameterTypes();
                if (params.length == 4
                    && params[0] == priorityClass
                    && params[1] == boolean.class
                    && params[2] == Class.class
                    && Consumer.class.isAssignableFrom(params[3])) {
                    return m;
                }
            }
        }
        return null;
    }
}
