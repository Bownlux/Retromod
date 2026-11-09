/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Keeps an old mod's {@code EditBox} text filter working on 26.1 and newer, whose edit box has none. */
public final class LegacyEditBoxFilter implements Consumer<String> {

    private static final String EDIT_BOX = "net.minecraft.client.gui.components.EditBox";
    private static final Map<Object, LegacyEditBoxFilter> FILTERS = Collections.synchronizedMap(new WeakHashMap<>());
    /** Stands for a responder that could not be read, which differs from no responder. */
    private static final Object UNKNOWN = new Object();

    private static volatile Class<?> editBox;
    private static MethodHandle getValue;
    private static MethodHandle setValue;
    private static MethodHandle getCursor;
    private static MethodHandle setCursor;
    private static MethodHandle setHighlight;
    private static MethodHandle setResponder;
    private static Field responderField;

    // The box is held weakly, because the box holds this object as its responder.
    private final WeakReference<Object> box;
    private Predicate<String> filter;
    private Consumer<String> responder;
    private String accepted;
    private boolean reverting;

    private LegacyEditBoxFilter(Object box, Consumer<String> responder) {
        this.box = new WeakReference<>(box);
        this.responder = responder;
    }

    /** The old {@code box.setFilter(filter)}: later edits the filter refuses are undone. */
    public static void setFilter(Object box, Predicate<String> filter) {
        LegacyEditBoxFilter state = FILTERS.get(box);
        if (state == null) {
            // The box's current responder must keep running behind the filter. When it cannot be
            // read, the filter is left out rather than risk dropping it.
            Object current = currentResponder(box);
            if (current == UNKNOWN) {
                LegacyLog.warnOnce("edit-box-filter", "Text field filters from older mods are not enforced here,"
                        + " because the field's responder could not be read.");
                return;
            }
            @SuppressWarnings("unchecked")
            Consumer<String> existing = (Consumer<String>) current;
            state = new LegacyEditBoxFilter(box, existing);
            FILTERS.put(box, state);
        }
        state.filter = filter;
        state.accepted = (String) call(getValue, box);
        call(setResponder, box, state);
    }

    /** The old {@code box.setResponder(responder)}: a filtered box runs it after its filter. */
    public static void setResponder(Object box, Consumer<String> responder) {
        resolve(box);
        LegacyEditBoxFilter state = FILTERS.get(box);
        if (state == null) {
            call(setResponder, box, responder);
            return;
        }
        state.responder = responder;
        // Installed again in case other code replaced it, as this call would have replaced that code's.
        call(setResponder, box, state);
    }

    /** Runs on every change the box makes, in place of the mod's responder. */
    @Override
    public void accept(String value) {
        if (reverting) return;
        Object target = box.get();
        if (target == null || filter == null || filter.test(value)) {
            accepted = value;
            if (responder != null) responder.accept(value);
            return;
        }
        // The old box refused such an edit outright: the text, the cursor and the responder stayed
        // as they were. Put the text back, and the cursor where it was before the edit.
        int cursor = cursorBeforeEdit((Integer) call(getCursor, target), value.length());
        reverting = true;
        try {
            call(setValue, target, accepted);
            call(setCursor, target, cursor);
            call(setHighlight, target, cursor);
        } finally {
            reverting = false;
        }
    }

    /** Where the cursor stood before an edit that changed the text to {@code editedLength} characters. */
    int cursorBeforeEdit(int cursor, int editedLength) {
        // Typing moves the cursor past the inserted text and backspace moves it back over the
        // removed text, so the change in length undoes either.
        int before = cursor - (editedLength - accepted.length());
        return Math.max(0, Math.min(before, accepted.length()));
    }

    /** The box's responder, or {@code UNKNOWN} when it cannot be read. */
    private static Object currentResponder(Object box) {
        resolve(box);
        if (responderField == null) return UNKNOWN;
        try {
            return responderField.get(box);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return UNKNOWN;
        }
    }

    /** Looks up the box's members once, from the class the game loaded. */
    private static void resolve(Object box) {
        if (editBox != null) return;
        synchronized (LegacyEditBoxFilter.class) {
            if (editBox != null) return;
            Class<?> type = box.getClass();
            while (type != null && !type.getName().equals(EDIT_BOX)) type = type.getSuperclass();
            if (type == null) throw new IllegalArgumentException("Not an edit box: " + box.getClass().getName());
            try {
                // This class is copied into the mod that uses it, so its own lookup sees what the mod sees.
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                getValue = generic(lookup.findVirtual(type, "getValue", MethodType.methodType(String.class)));
                setValue = generic(lookup.findVirtual(type, "setValue",
                        MethodType.methodType(void.class, String.class)));
                getCursor = generic(lookup.findVirtual(type, "getCursorPosition", MethodType.methodType(int.class)));
                setCursor = generic(lookup.findVirtual(type, "setCursorPosition",
                        MethodType.methodType(void.class, int.class)));
                setHighlight = generic(lookup.findVirtual(type, "setHighlightPos",
                        MethodType.methodType(void.class, int.class)));
                setResponder = generic(lookup.findVirtual(type, "setResponder",
                        MethodType.methodType(void.class, Consumer.class)));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("This Minecraft version's edit box has changed: " + e, e);
            }
            // The responder field is private, so reading it may be refused; the filter then stays off.
            try {
                Field field = type.getDeclaredField("responder");
                field.setAccessible(true);
                responderField = field;
            } catch (ReflectiveOperationException | RuntimeException e) {
                responderField = null;
            }
            editBox = type;
        }
    }

    private static MethodHandle generic(MethodHandle handle) {
        return handle.asType(handle.type().generic());
    }

    private static Object call(MethodHandle handle, Object... arguments) {
        try {
            return handle.invokeWithArguments(arguments);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }
}
