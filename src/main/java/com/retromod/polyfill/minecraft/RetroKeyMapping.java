/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.polyfill.minecraft;

/**
 * 1.3.0: bridge the {@code KeyMapping} (keybind) constructor break in 26.x. Pre-26.x a mod wrote
 * {@code new KeyMapping(name, [type,] code, categoryString)} where the last argument was the
 * category as a {@code String} translation key ({@code "key.categories.misc"}, or a mod's own).
 * 26.x replaced that {@code String} with a {@code KeyMapping.Category} record (builtin constants
 * {@code MOVEMENT}/{@code MISC}/... plus {@code Category.register(Identifier)}), so the old
 * constructors are gone and any mod that adds a keybind dies {@code NoSuchMethodError} at client
 * init. A {@code new KeyMapping(...)} is rewritten (constructor-to-factory redirect) to one of these
 * factories, which resolves the category string to a {@code Category} and calls the real
 * constructor.
 *
 * <p><b>Category resolution:</b> a vanilla {@code key.categories.*} string maps to the matching
 * builtin constant; any other (mod) string registers a {@code Category} under a derived
 * {@code retromod:} identifier (cached, so repeated keybinds in the same category register once);
 * if registration isn't possible the keybind falls back to {@code MISC} so it still works, just
 * listed under Miscellaneous.
 *
 * <p><b>Zero compile-time Minecraft dependency</b> (Retromod builds without MC, and a per-mod
 * embedded copy must load with only what the mod's module sees): every MC type is reached
 * reflectively and results are typed {@code Object}; the redirect appends a {@code CHECKCAST}.
 *
 * <p><b>Fail-safe:</b> any reflection failure yields {@code null} (the redirect's {@code CHECKCAST
 * null} passes), i.e. that one keybind goes inert rather than crashing construction.
 */
public final class RetroKeyMapping {

    private RetroKeyMapping() {}

    private static final String KEY_MAPPING = "net.minecraft.client.KeyMapping";
    private static final String CATEGORY = "net.minecraft.client.KeyMapping$Category";
    private static final String INPUT_TYPE = "com.mojang.blaze3d.platform.InputConstants$Type";
    private static final String IDENTIFIER = "net.minecraft.resources.Identifier";

    // Vanilla category translation key -> the 26.x KeyMapping.Category builtin constant field name.
    private static final java.util.Map<String, String> VANILLA = java.util.Map.of(
            "key.categories.movement", "MOVEMENT",
            "key.categories.misc", "MISC",
            "key.categories.multiplayer", "MULTIPLAYER",
            "key.categories.gameplay", "GAMEPLAY",
            "key.categories.inventory", "INVENTORY",
            "key.categories.creative", "CREATIVE");

    // Categories we registered for mod strings, so a mod's repeated keybinds don't re-register.
    private static final java.util.Map<String, Object> REGISTERED = new java.util.concurrent.ConcurrentHashMap<>();

    /** Old {@code new KeyMapping(String, InputConstants.Type, int, String)}. */
    public static Object create(String name, Object type, int code, String category) {
        try {
            Class<?> keyMapping = Class.forName(KEY_MAPPING);
            Class<?> categoryCls = Class.forName(CATEGORY);
            Class<?> inputType = Class.forName(INPUT_TYPE);
            Object cat = resolveCategory(categoryCls, category);
            if (cat == null) return null;
            return keyMapping.getConstructor(String.class, inputType, int.class, categoryCls)
                    .newInstance(name, type, hostCode(type, code), cat);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Old {@code new KeyMapping(String, int, String)} (defaults the input type to KEYSYM). */
    public static Object createDefault(String name, int code, String category) {
        try {
            Class<?> keyMapping = Class.forName(KEY_MAPPING);
            Class<?> categoryCls = Class.forName(CATEGORY);
            Object cat = resolveCategory(categoryCls, category);
            if (cat == null) return null;
            return keyMapping.getConstructor(String.class, int.class, categoryCls)
                    .newInstance(name, hostKeyCode(code), cat);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object resolveCategory(Class<?> categoryCls, String category) {
        if (category != null) {
            String constant = VANILLA.get(category);
            if (constant != null) {
                Object c = staticField(categoryCls, constant);
                if (c != null) return c;
            }
            Object cached = REGISTERED.get(category);
            if (cached != null) return cached;
            Object registered = tryRegister(categoryCls, category);
            if (registered != null) {
                REGISTERED.put(category, registered);
                return registered;
            }
        }
        return staticField(categoryCls, "MISC"); // fail-safe: a MISC keybind still works
    }

    private static Object tryRegister(Class<?> categoryCls, String category) {
        try {
            Class<?> idCls = Class.forName(IDENTIFIER);
            // 26.x has fromNamespaceAndPath, not of; with of every mod category fell to MISC.
            Object id = idCls.getMethod("fromNamespaceAndPath", String.class, String.class)
                    .invoke(null, "retromod", sanitize(category));
            return categoryCls.getMethod("register", idCls).invoke(null, id);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String sanitize(String s) {
        String out = s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9._/-]", "_");
        return out.isEmpty() ? "custom" : out;
    }

    private static Object staticField(Class<?> cls, String field) {
        try {
            return cls.getField(field).get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    // NeoForge's conflict-context constructors lost their String category at 1.21.9 too, so a
    // NeoForge keybind that names an IKeyConflictContext died with NoSuchMethodError on 26.x.

    /** Old NeoForge {@code new KeyMapping(String, IKeyConflictContext, Type, int, String)}. */
    public static Object createConflict(String name, Object context, Object type, int code,
            String category) {
        return construct(name, context, type, hostCode(type, code), category(category));
    }

    /** Old NeoForge {@code new KeyMapping(String, IKeyConflictContext, Key, String)}. */
    public static Object createConflictKey(String name, Object context, Object key,
            String category) {
        return construct(name, context, key, category(category));
    }

    /** Old NeoForge {@code new KeyMapping(String, IKeyConflictContext, KeyModifier, Type, int, String)}. */
    public static Object createConflictModifier(String name, Object context, Object modifier,
            Object type, int code, String category) {
        return construct(name, context, modifier, type, hostCode(type, code), category(category));
    }

    /** Old NeoForge {@code new KeyMapping(String, IKeyConflictContext, KeyModifier, Key, String)}. */
    public static Object createConflictModifierKey(String name, Object context, Object modifier,
            Object key, String category) {
        return construct(name, context, modifier, key, category(category));
    }

    // 1.21.9 to 26.2 mods already pass a Category, so on 26.3 only the key code needs translating.

    /** {@code new KeyMapping(String, int, Category)} from a mod built before 26.3. */
    public static Object createWithCategory(String name, int code, Object category) {
        return construct(name, hostKeyCode(code), category);
    }

    /** {@code new KeyMapping(String, Type, int, Category)} from a mod built before 26.3. */
    public static Object createTypedWithCategory(String name, Object type, int code,
            Object category) {
        return construct(name, type, hostCode(type, code), category);
    }

    /** {@code new KeyMapping(String, Type, int, Category, int)} from a mod built before 26.3. */
    public static Object createTypedWithCategoryOrder(String name, Object type, int code,
            Object category, int order) {
        return construct(name, type, hostCode(type, code), category, order);
    }

    /** NeoForge {@code new KeyMapping(String, IKeyConflictContext, Type, int, Category)}. */
    public static Object createConflictWithCategory(String name, Object context, Object type,
            int code, Object category) {
        return construct(name, context, type, hostCode(type, code), category);
    }

    /** NeoForge {@code new KeyMapping(String, IKeyConflictContext, KeyModifier, Type, int, Category)}. */
    public static Object createConflictModifierWithCategory(String name, Object context,
            Object modifier, Object type, int code, Object category) {
        return construct(name, context, modifier, type, hostCode(type, code), category);
    }

    /**
     * Old {@code InputConstants.isKeyDown(Window, int)} with a literal GLFW code; 26.3 dropped the
     * window. {@code LegacyKeyCodeAdapter} routes only literal arguments here.
     */
    public static boolean isKeyDown(Object window, int glfwKey) {
        return isKeyDownUntranslated(window, hostKeyCode(glfwKey));
    }

    /**
     * Old {@code InputConstants.isKeyDown(Window, int)} with a code the mod read at runtime, which
     * the host already numbered, so it is passed through.
     */
    public static boolean isKeyDownUntranslated(Object window, int hostKey) {
        try {
            java.lang.reflect.Method isKeyDown = isKeyDownMethod;
            if (isKeyDown == null) {
                isKeyDownMethod = isKeyDown =
                        Class.forName(INPUT_CONSTANTS).getMethod("isKeyDown", int.class);
            }
            return (Boolean) isKeyDown.invoke(null, hostKey);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * {@code InputConstants.Type.getOrCreate(int)} with a literal GLFW code. A runtime value, such as
     * a mouse event's button, is already numbered by the host and keeps the native call.
     */
    public static Object getOrCreate(Object type, int code) {
        try {
            return Class.forName(INPUT_TYPE).getMethod("getOrCreate", int.class)
                    .invoke(type, hostCode(type, code));
        } catch (Throwable t) {
            return null;
        }
    }

    private static final String INPUT_CONSTANTS = "com.mojang.blaze3d.platform.InputConstants";
    private static volatile java.lang.reflect.Method isKeyDownMethod;

    /**
     * Calls the public KeyMapping constructor these arguments fit. A null result would only surface
     * later as an unrelated NullPointerException at registration, so a failure is reported here,
     * with the real constructor's own exception when it threw.
     */
    private static Object construct(Object... args) {
        java.lang.reflect.Constructor<?>[] constructors;
        try {
            constructors = Class.forName(KEY_MAPPING).getConstructors();
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Retromod: KeyMapping is not on this host", e);
        }
        for (java.lang.reflect.Constructor<?> ctor : constructors) {
            if (!fits(ctor.getParameterTypes(), args)) continue;
            try {
                return ctor.newInstance(args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof Error error) throw error;
                throw new IllegalStateException(cause);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Retromod could not build keybind " + args[0], e);
            }
        }
        throw new IllegalStateException("Retromod found no KeyMapping constructor for keybind "
                + args[0] + " on this host");
    }

    private static boolean fits(Class<?>[] params, Object[] args) {
        if (params.length != args.length) return false;
        for (int i = 0; i < params.length; i++) {
            if (params[i] == int.class) {
                if (!(args[i] instanceof Integer)) return false;
            } else if (args[i] != null && !params[i].isInstance(args[i])) {
                return false;
            }
        }
        return true;
    }

    private static Object category(String category) {
        try {
            return resolveCategory(Class.forName(CATEGORY), category);
        } catch (Throwable t) {
            return null;
        }
    }

    // 26.3 moved input from GLFW to SDL and renumbered every key and mouse button: KEY_R went from
    // 82 to 21 and the left mouse button from 0 to 1. Every mod built before 26.3 inlined the GLFW
    // numbers, so its keybinds landed on unrelated keys with no error. Each code is translated by
    // its InputConstants name to the host's value, so a GLFW host is left untouched.

    /**
     * Every KEY_ and MOUSE_BUTTON_ constant 26.2 declared, which are the GLFW codes, read from the
     * 26.2 client jar.
     */
    private static final String GLFW_CONSTANTS =
            "KEY_0=48,KEY_1=49,KEY_2=50,KEY_3=51,KEY_4=52,KEY_5=53,KEY_6=54,KEY_7=55,KEY_8=56,"
            + "KEY_9=57,KEY_A=65,KEY_B=66,KEY_C=67,KEY_D=68,KEY_E=69,KEY_F=70,KEY_G=71,KEY_H=72,"
            + "KEY_I=73,KEY_J=74,KEY_K=75,KEY_L=76,KEY_M=77,KEY_N=78,KEY_O=79,KEY_P=80,KEY_Q=81,"
            + "KEY_R=82,KEY_S=83,KEY_T=84,KEY_U=85,KEY_V=86,KEY_W=87,KEY_X=88,KEY_Y=89,KEY_Z=90,"
            + "KEY_F1=290,KEY_F2=291,KEY_F3=292,KEY_F4=293,KEY_F5=294,KEY_F6=295,KEY_F7=296,KEY_F8=297,"
            + "KEY_F9=298,KEY_F10=299,KEY_F11=300,KEY_F12=301,KEY_F13=302,KEY_F14=303,KEY_F15=304,"
            + "KEY_F16=305,KEY_F17=306,KEY_F18=307,KEY_F19=308,KEY_F20=309,KEY_F21=310,KEY_F22=311,"
            + "KEY_F23=312,KEY_F24=313,KEY_F25=314,KEY_NUMLOCK=282,KEY_NUMPAD0=320,KEY_NUMPAD1=321,"
            + "KEY_NUMPAD2=322,KEY_NUMPAD3=323,KEY_NUMPAD4=324,KEY_NUMPAD5=325,KEY_NUMPAD6=326,"
            + "KEY_NUMPAD7=327,KEY_NUMPAD8=328,KEY_NUMPAD9=329,KEY_NUMPADCOMMA=330,KEY_NUMPADENTER=335,"
            + "KEY_NUMPADEQUALS=336,KEY_DOWN=264,KEY_LEFT=263,KEY_RIGHT=262,KEY_UP=265,KEY_ADD=334,"
            + "KEY_APOSTROPHE=39,KEY_BACKSLASH=92,KEY_COMMA=44,KEY_EQUALS=61,KEY_GRAVE=96,"
            + "KEY_LBRACKET=91,KEY_MINUS=45,KEY_MULTIPLY=332,KEY_PERIOD=46,KEY_RBRACKET=93,"
            + "KEY_SEMICOLON=59,KEY_SLASH=47,KEY_SPACE=32,KEY_TAB=258,KEY_LALT=342,KEY_LCONTROL=341,"
            + "KEY_LSHIFT=340,KEY_LSUPER=343,KEY_RALT=346,KEY_RCONTROL=345,KEY_RSHIFT=344,"
            + "KEY_RSUPER=347,KEY_RETURN=257,KEY_ESCAPE=256,KEY_BACKSPACE=259,KEY_DELETE=261,"
            + "KEY_END=269,KEY_HOME=268,KEY_INSERT=260,KEY_PAGEDOWN=267,KEY_PAGEUP=266,"
            + "KEY_CAPSLOCK=280,KEY_PAUSE=284,KEY_SCROLLLOCK=281,KEY_PRINTSCREEN=283,"
            + "MOUSE_BUTTON_LEFT=0,MOUSE_BUTTON_RIGHT=1,MOUSE_BUTTON_MIDDLE=2,MOUSE_BUTTON_4=3,"
            + "MOUSE_BUTTON_5=4,MOUSE_BUTTON_6=5,MOUSE_BUTTON_7=6,MOUSE_BUTTON_8=7";

    /** 26.3 renamed these two; everything else kept its name. */
    private static final java.util.Map<String, String> RENAMED = java.util.Map.of(
            "KEY_LSUPER", "KEY_LGUI",
            "KEY_RSUPER", "KEY_RGUI");

    private static final java.util.Map<Integer, String> GLFW_KEYS = new java.util.HashMap<>();
    private static final java.util.Map<Integer, String> GLFW_MOUSE = new java.util.HashMap<>();

    static {
        for (String entry : GLFW_CONSTANTS.split(",")) {
            String[] pair = entry.split("=");
            int code = Integer.parseInt(pair[1]);
            (pair[0].startsWith("MOUSE_") ? GLFW_MOUSE : GLFW_KEYS).put(code, pair[0]);
        }
    }

    /** Host value per constant name, or null when the host is not SDL-numbered. */
    private static volatile java.util.Map<String, Integer> hostValues;
    private static volatile boolean hostChecked;

    /** The host's code for a keyboard key a pre-26.3 mod wrote as a GLFW code. */
    public static int hostKeyCode(int glfwKey) {
        return translate(glfwKey, false, hostValues());
    }

    /** The host's code for a key or mouse button, by the input type the mod passed. */
    public static int hostCode(Object type, int code) {
        boolean mouse = type instanceof Enum<?> e && "MOUSE".equals(e.name());
        return translate(code, mouse, hostValues());
    }

    /**
     * Translate one code. {@code host} maps constant names to host values, or is null on a GLFW
     * host, where nothing changes. A code with no 26.3 counterpart, such as F25, is left unbound
     * instead of landing on whatever key now shares its number.
     */
    static int translate(int code, boolean mouse, java.util.Map<String, Integer> host) {
        if (host == null || code < 0) return code;
        String name = (mouse ? GLFW_MOUSE : GLFW_KEYS).get(code);
        if (name == null) return -1;
        Integer value = host.get(RENAMED.getOrDefault(name, name));
        return value != null ? value : -1;
    }

    /** Reads the host's constants once; null on a GLFW host or when they cannot be read. */
    private static java.util.Map<String, Integer> hostValues() {
        if (hostChecked) return hostValues;
        java.util.Map<String, Integer> values = null;
        try {
            Class<?> constants = Class.forName(INPUT_CONSTANTS);
            // GLFW numbers A as 65, SDL as 4. Anything else is a host this table does not know.
            if (constants.getField("KEY_A").getInt(null) == 4) {
                values = new java.util.HashMap<>();
                for (java.lang.reflect.Field field : constants.getFields()) {
                    String name = field.getName();
                    if (field.getType() == int.class
                            && (name.startsWith("KEY_") || name.startsWith("MOUSE_BUTTON_"))) {
                        values.put(name, field.getInt(null));
                    }
                }
            }
        } catch (Throwable ignored) {
            values = null;
        }
        hostValues = values;
        hostChecked = true;
        return values;
    }
}
