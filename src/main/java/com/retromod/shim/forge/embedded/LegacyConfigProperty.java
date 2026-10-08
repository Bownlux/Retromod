/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

/**
 * Stand-in for the 1.12.2 config {@code Property}. It holds the mod's default and returns it in
 * whichever type the mod asks for; see {@link LegacyConfiguration}.
 */
public final class LegacyConfigProperty {

    private final String name;
    private Object value;

    public LegacyConfigProperty(Object name, Object value) {
        this.name = String.valueOf(name);
        this.value = value;
    }

    public Object getName() {
        return name;
    }

    public int getInt() {
        return getInt(0);
    }

    public int getInt(int fallback) {
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public long getLong() {
        return getLong(0L);
    }

    public long getLong(long fallback) {
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public double getDouble() {
        return getDouble(0.0);
    }

    public double getDouble(double fallback) {
        if (value instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public boolean getBoolean() {
        return getBoolean(false);
    }

    public boolean getBoolean(boolean fallback) {
        if (value instanceof Boolean b) return b;
        String text = String.valueOf(value).trim();
        if (text.equalsIgnoreCase("true")) return true;
        if (text.equalsIgnoreCase("false")) return false;
        return fallback;
    }

    public Object getString() {
        if (value instanceof Object[] array) return array.length == 0 ? "" : String.valueOf(array[0]);
        return String.valueOf(value);
    }

    public Object getStringList() {
        if (value instanceof String[] strings) return strings;
        if (value instanceof Object[] array) {
            String[] out = new String[array.length];
            for (int i = 0; i < array.length; i++) out[i] = String.valueOf(array[i]);
            return out;
        }
        return value == null ? new String[0] : new String[]{String.valueOf(value)};
    }

    public Object getIntList() {
        return value instanceof int[] ints ? ints : new int[0];
    }

    public Object getDoubleList() {
        return value instanceof double[] doubles ? doubles : new double[0];
    }

    public Object getBooleanList() {
        return value instanceof boolean[] booleans ? booleans : new boolean[0];
    }

    public boolean isIntValue() {
        return value instanceof Integer;
    }

    public boolean isBooleanValue() {
        return value instanceof Boolean;
    }

    public boolean isDoubleValue() {
        return value instanceof Double;
    }

    public boolean isList() {
        return value != null && value.getClass().isArray();
    }

    public boolean wasRead() {
        return false;
    }

    public boolean hasChanged() {
        return false;
    }

    public void set(Object newValue) {
        value = newValue;
    }

    public void set(int newValue) {
        value = newValue;
    }

    public void set(boolean newValue) {
        value = newValue;
    }

    public void set(double newValue) {
        value = newValue;
    }

    public void set(long newValue) {
        value = newValue;
    }

    public void setComment(Object comment) {
    }

    public Object getComment() {
        return "";
    }

    public LegacyConfigProperty setLanguageKey(Object key) {
        return this;
    }

    public LegacyConfigProperty setRequiresMcRestart(boolean value) {
        return this;
    }

    public LegacyConfigProperty setRequiresWorldRestart(boolean value) {
        return this;
    }

    public LegacyConfigProperty setShowInGui(boolean value) {
        return this;
    }

    public LegacyConfigProperty setMinValue(int min) {
        return this;
    }

    public LegacyConfigProperty setMaxValue(int max) {
        return this;
    }

    public LegacyConfigProperty setMinValue(double min) {
        return this;
    }

    public LegacyConfigProperty setMaxValue(double max) {
        return this;
    }

    public LegacyConfigProperty setValidValues(Object values) {
        return this;
    }

    public LegacyConfigProperty setDefaultValue(Object def) {
        return this;
    }

    public LegacyConfigProperty setDefaultValue(int def) {
        return this;
    }

    public LegacyConfigProperty setDefaultValue(boolean def) {
        return this;
    }

    public LegacyConfigProperty setDefaultValue(double def) {
        return this;
    }

    public LegacyConfigProperty setConfigEntryClass(Object type) {
        return this;
    }

    @Override
    public String toString() {
        return String.valueOf(getString());
    }
}
