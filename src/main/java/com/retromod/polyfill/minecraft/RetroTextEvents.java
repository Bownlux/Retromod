/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.polyfill.minecraft;

/**
 * 1.3.0 (top-50/60 corpus audits, 14+ mods): bridge the {@code ClickEvent}/{@code HoverEvent}
 * constructor breaks across the 1.21.5 text-component rework. Pre-1.21.5 a mod wrote
 * {@code new ClickEvent(ClickEvent.Action, String)}; on 26.x {@code ClickEvent} is a sealed
 * INTERFACE whose per-action record subtypes ({@code RunCommand}, {@code OpenUrl}, {@code ChangePage},
 * ...) each take a TYPED value, so the old constructor is gone and a plain class-move/redirect can't
 * express it. A {@code new ClickEvent(action, value)} is instead rewritten to
 * {@code RetroTextEvents.clickEvent(action, value)} (a constructor-to-factory redirect), which
 * dispatches on the action enum to the right subtype - exactly the dispatch the old constructor did
 * internally, so this is correct by construction.
 *
 * <p><b>Zero compile-time Minecraft dependency</b> (Retromod builds without MC, and a per-mod embedded
 * copy - Forge/NeoForge JPMS split-package safety - must load with only what the mod's module sees):
 * every MC type is reached reflectively and results are typed {@code Object}; the constructor-to-factory
 * redirect appends a {@code CHECKCAST} to the original type.
 *
 * <p><b>Fail-safe:</b> an unknown/unmappable action, an unparseable value, or any reflection failure
 * yields {@code null} (the redirect's {@code CHECKCAST null} passes), i.e. that one click/hover goes
 * inert rather than crashing construction - the same soft-fail posture as the rest of Retromod. The
 * common actions a distributed mod actually uses (open-url, run/suggest-command, copy, change-page,
 * open-file) all map exactly.
 */
public final class RetroTextEvents {

    private RetroTextEvents() {}

    private static final String CLICK = "net.minecraft.network.chat.ClickEvent$";
    private static final String HOVER = "net.minecraft.network.chat.HoverEvent$";

    /**
     * Old {@code new ClickEvent(ClickEvent.Action, String)} -> the matching 26.x subtype record.
     * {@code action} is the {@code ClickEvent.Action} enum (reached as {@link Enum}); {@code value}
     * is the legacy single string.
     */
    public static Object clickEvent(Object action, String value) {
        String name = enumName(action);
        if (name == null) return null;
        try {
            switch (name) {
                case "OPEN_URL":          return newInst(CLICK + "OpenUrl", java.net.URI.class, java.net.URI.create(value));
                case "RUN_COMMAND":       return newInst(CLICK + "RunCommand", String.class, value);
                case "SUGGEST_COMMAND":   return newInst(CLICK + "SuggestCommand", String.class, value);
                case "COPY_TO_CLIPBOARD": return newInst(CLICK + "CopyToClipboard", String.class, value);
                case "CHANGE_PAGE":       return newInst(CLICK + "ChangePage", int.class, Integer.parseInt(value.trim()));
                case "OPEN_FILE":         return newInst(CLICK + "OpenFile", java.io.File.class, new java.io.File(value));
                default:                  return null; // CUSTOM / SHOW_DIALOG: not expressible from (action, String)
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Old {@code new HoverEvent(HoverEvent.Action, Object)} -> the matching 26.x subtype. {@code SHOW_TEXT}
     * takes its {@code Component}, and {@code SHOW_ITEM} the stack of the rebuilt {@code ItemStackInfo}.
     * {@code SHOW_ENTITY} took a now-restructured value object, so it falls through to the inert {@code null}.
     */
    public static Object hoverEvent(Object action, Object value) {
        String name = enumName(action);
        if (name == null) return null;
        try {
            if ("SHOW_TEXT".equals(name)) {
                return newInst(HOVER + "ShowText", Class.forName("net.minecraft.network.chat.Component"), value);
            }
            if ("SHOW_ITEM".equals(name)) return showItem(value);
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Old {@code ClickEvent.getValue()}: the single string the event carried. Each 26.x subtype record holds that
     * value as its one component, a URI, path, command, page number or text, so it is read back as a string.
     */
    public static String clickValue(Object event) {
        if (event == null) return null;
        try {
            java.lang.reflect.RecordComponent[] parts = event.getClass().getRecordComponents();
            if (parts == null || parts.length != 1) return null;
            java.lang.reflect.Method accessor = parts[0].getAccessor();
            accessor.setAccessible(true);
            Object value = accessor.invoke(event);
            // A dialog holder or other new value has no old string form.
            return value instanceof String || value instanceof java.net.URI || value instanceof Number ? String.valueOf(value) : null;
        } catch (Throwable unreadable) {
            return null;
        }
    }

    /**
     * {@code SHOW_ITEM} with the rebuilt {@code ItemStackInfo}: its stack, as the item template 26.1 shows, or as the
     * stack itself where the hover still takes one. An empty stack shows nothing, as before.
     */
    private static Object showItem(Object info) throws Exception {
        if (info == null) return null;
        Object stack = info.getClass().getMethod("getItemStack").invoke(info);
        if (stack == null || Boolean.TRUE.equals(stack.getClass().getMethod("isEmpty").invoke(stack))) return null;
        Class<?> stackType = Class.forName("net.minecraft.world.item.ItemStack");
        Class<?> showItem = Class.forName(HOVER + "ShowItem");
        for (java.lang.reflect.Constructor<?> ctor : showItem.getConstructors()) {
            Class<?>[] params = ctor.getParameterTypes();
            if (params.length != 1) continue;
            if (params[0] == stackType) return ctor.newInstance(stack);
            Object template = params[0].getMethod("fromNonEmptyStack", stackType).invoke(null, stack);
            return ctor.newInstance(template);
        }
        return null;
    }

    private static String enumName(Object action) {
        try {
            return action instanceof Enum<?> ? ((Enum<?>) action).name() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object newInst(String cls, Class<?> paramType, Object arg) throws Exception {
        Class<?> c = Class.forName(cls);
        java.lang.reflect.Constructor<?> ctor = c.getConstructor(paramType);
        ctor.setAccessible(true);
        return ctor.newInstance(arg);
    }
}
