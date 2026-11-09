/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.fmlhost;

import java.util.List;

/** Stands in for Forge 26.2's ModLoadingWarning record, whose text Forge formats from a message key. */
public final class ModLoadingWarning {

    private final IModInfo modInfo;
    private final String i18nMessage;
    private final List<Object> context;

    public ModLoadingWarning(IModInfo modInfo, String i18nMessage, Object... context) {
        this.modInfo = modInfo;
        this.i18nMessage = i18nMessage;
        this.context = List.of(context);
    }

    public String formatToString() {
        return i18nMessage + " " + context;
    }

    public IModInfo modInfo() {
        return modInfo;
    }

    public String i18nMessage() {
        return i18nMessage;
    }

    public List<Object> context() {
        return context;
    }
}
