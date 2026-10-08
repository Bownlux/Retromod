/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stand-in for the 1.12.2 {@code net.minecraftforge.common.config.Configuration}. A 1.12 mod reads
 * its config in pre-init, before content registration, so the class going missing stopped the mod
 * there. Every value is the mod's own default: the old {@code .cfg} file is not read or written.
 *
 * <p>Reference parameters are {@code Object} and reference results other than the stand-ins are
 * {@code Object}, because the transform erases calls on these classes to that shape. The
 * {@code String} and {@code String[]} overloads of {@code get} share one erased method and are
 * told apart by the default's runtime type.
 */
public final class LegacyConfiguration {

    public static final String CATEGORY_GENERAL = "general";
    public static final String CATEGORY_CLIENT = "client";
    public static final String CATEGORY_SPLITTER = ".";
    public static final String NEW_LINE = System.lineSeparator();
    public static final String DEFAULT_ENCODING = "UTF-8";
    public static final String ALLOWED_CHARS = "._-";

    private final Object file;
    private final Map<String, LegacyConfigCategory> categories = new LinkedHashMap<>();

    public LegacyConfiguration() {
        this(null);
    }

    public LegacyConfiguration(Object file) {
        this.file = file;
        LegacyForgeEvents.note("1.12 config files (defaults are used)", file);
    }

    public LegacyConfiguration(Object file, Object version) {
        this(file);
    }

    public LegacyConfiguration(Object file, boolean caseSensitive) {
        this(file);
    }

    public LegacyConfiguration(Object file, Object version, boolean caseSensitive) {
        this(file);
    }

    public void load() {
    }

    public void save() {
    }

    public boolean hasChanged() {
        return false;
    }

    public Object getConfigFile() {
        return file;
    }

    public Object toString(Object ignored) {
        return String.valueOf(file);
    }

    // get(category, key, default...) for every 1.12 overload, erased

    public LegacyConfigProperty get(Object category, Object key, Object def) {
        return property(category, key, def);
    }

    public LegacyConfigProperty get(Object category, Object key, Object def, Object comment) {
        return property(category, key, def);
    }

    public LegacyConfigProperty get(Object category, Object key, Object def, Object comment, Object type) {
        return property(category, key, def);
    }

    public LegacyConfigProperty get(Object category, Object key, Object def, Object comment,
                                    Object a, Object b) {
        return property(category, key, def);
    }

    public LegacyConfigProperty get(Object category, Object key, int def) {
        return property(category, key, def);
    }

    public LegacyConfigProperty get(Object category, Object key, int def, Object comment) {
        return property(category, key, def);
    }

    public LegacyConfigProperty get(Object category, Object key, int def, Object comment, int min, int max) {
        return property(category, key, def);
    }

    public LegacyConfigProperty get(Object category, Object key, boolean def) {
        return property(category, key, def);
    }

    public LegacyConfigProperty get(Object category, Object key, boolean def, Object comment) {
        return property(category, key, def);
    }

    public LegacyConfigProperty get(Object category, Object key, double def) {
        return property(category, key, def);
    }

    public LegacyConfigProperty get(Object category, Object key, double def, Object comment) {
        return property(category, key, def);
    }

    public LegacyConfigProperty get(Object category, Object key, double def, Object comment,
                                    double min, double max) {
        return property(category, key, def);
    }

    public LegacyConfigProperty get(Object category, Object key, Object def, Object comment, int min, int max) {
        return property(category, key, def);
    }

    public LegacyConfigProperty get(Object category, Object key, Object def, Object comment,
                                    double min, double max) {
        return property(category, key, def);
    }

    // typed getters: getInt(name, category, default, min, max, comment[, langKey])

    public int getInt(Object name, Object category, int def, int min, int max, Object comment) {
        return def;
    }

    public int getInt(Object name, Object category, int def, int min, int max, Object comment, Object key) {
        return def;
    }

    public boolean getBoolean(Object name, Object category, boolean def, Object comment) {
        return def;
    }

    public boolean getBoolean(Object name, Object category, boolean def, Object comment, Object key) {
        return def;
    }

    public float getFloat(Object name, Object category, float def, float min, float max, Object comment) {
        return def;
    }

    public float getFloat(Object name, Object category, float def, float min, float max, Object comment,
                          Object key) {
        return def;
    }

    public Object getString(Object name, Object category, Object def, Object comment) {
        return def;
    }

    public Object getString(Object name, Object category, Object def, Object comment, Object extra) {
        return def;
    }

    public Object getString(Object name, Object category, Object def, Object comment, Object key,
                            Object pattern) {
        return def;
    }

    public Object getStringList(Object name, Object category, Object def, Object comment) {
        return def;
    }

    public Object getStringList(Object name, Object category, Object def, Object comment, Object extra) {
        return def;
    }

    public Object getStringList(Object name, Object category, Object def, Object comment, Object valid,
                                Object key) {
        return def;
    }

    // categories

    public LegacyConfigCategory getCategory(Object name) {
        return categories.computeIfAbsent(String.valueOf(name), LegacyConfigCategory::new);
    }

    public boolean hasCategory(Object name) {
        return categories.containsKey(String.valueOf(name));
    }

    public boolean hasKey(Object category, Object key) {
        return hasCategory(category) && getCategory(category).containsKey(key);
    }

    public Object getCategoryNames() {
        return categories.keySet();
    }

    public void removeCategory(Object category) {
    }

    public LegacyConfiguration setCategoryComment(Object category, Object comment) {
        return this;
    }

    public void addCustomCategoryComment(Object category, Object comment) {
    }

    public LegacyConfiguration setCategoryLanguageKey(Object category, Object key) {
        return this;
    }

    public LegacyConfiguration setCategoryRequiresMcRestart(Object category, boolean value) {
        return this;
    }

    public LegacyConfiguration setCategoryRequiresWorldRestart(Object category, boolean value) {
        return this;
    }

    public LegacyConfiguration setCategoryPropertyOrder(Object category, Object order) {
        return this;
    }

    public LegacyConfiguration setCategoryConfigEntryClass(Object category, Object type) {
        return this;
    }

    private LegacyConfigProperty property(Object category, Object key, Object def) {
        return getCategory(category).property(String.valueOf(key), def);
    }
}
