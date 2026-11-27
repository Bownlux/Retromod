/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge.legacy118;

import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.RecordItem;

/** Calls in Forge 1.18.2's shapes, compiled against stand-ins, for the cases the main class checks; they return plain types. */
public final class Era118Cases {

    private Era118Cases() {}

    /** #412: a menu type built from its supplier alone, as 1.18.2 allowed. */
    public static Object menuType() {
        return new MenuType<>((id, inventory) -> null);
    }

    /** #408: a music disc from Forge's supplier constructor, which 1.19 gave a length argument. */
    public static Object disc() {
        return new RecordItem(15, () -> null, new Item.Properties());
    }

    /** #408: the name of each interface the old render properties class implements once translated. */
    public static String[] renderPropertiesInterfaces() throws ClassNotFoundException {
        Class<?> type = Class.forName(Era118Cases.class.getPackageName() + ".Era118RenderProperties", false,
                Era118Cases.class.getClassLoader());
        Class<?>[] interfaces = type.getInterfaces();
        String[] names = new String[interfaces.length];
        for (int i = 0; i < interfaces.length; i++) names[i] = interfaces[i].getName();
        return names;
    }
}
