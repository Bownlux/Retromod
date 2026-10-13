/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Runtime half of {@link com.retromod.shim.common.LegacyEquipmentBridge}: turns an old armor or tool
 * constructor's arguments into the item components that replaced {@code ArmorItem},
 * {@code SwordItem}, and the other tool classes.
 *
 * <p>An old material is one of three things: a mod class implementing the old interface, a view
 * of a vanilla material, or, from a mod built for 1.21.2 to 1.21.4, the current record itself. The
 * first is read through its old methods and built into the current record once. The others are
 * used as they are. Everything is reflective because this class is embedded into each mod and
 * compiled without Minecraft; the names are Mojang's, which the hosts this runs on use at runtime.
 *
 * <p>Anything unexpected leaves the {@code Item.Properties} as they were, so the item still
 * registers as a plain item, which is what happened before this bridge existed.
 */
public final class LegacyEquipmentSupport {

    private static final String ARMOR_TYPE = "net.minecraft.world.item.equipment.ArmorType";
    private static final String ARMOR_MATERIAL = "net.minecraft.world.item.equipment.ArmorMaterial";
    private static final String TOOL_MATERIAL = "net.minecraft.world.item.ToolMaterial";
    private static final String HANDLE = "retromod$handle";

    private static final Map<Object, Object> ARMOR_RECORDS = new IdentityHashMap<>();
    private static final Map<Object, Object> TOOL_RECORDS = new IdentityHashMap<>();
    private static volatile boolean warned;

    private LegacyEquipmentSupport() {}

    /**
     * Applies the removed base's behavior to {@code properties}.
     *
     * @param key       which base: {@code armor}, {@code horse}, {@code sword}, {@code pickaxe},
     *                  {@code axe}, {@code shovel}, {@code hoe}, or {@code digger}
     * @param args      every argument the old constructor received, boxed
     * @param properties the {@code Item.Properties} the old constructor received
     */
    public static Object adapt(String key, Object[] args, Object properties) {
        try {
            ClassLoader loader = properties.getClass().getClassLoader();
            if ("armor".equals(key)) return armor(args, properties, loader);
            if ("horse".equals(key)) return horseArmor(args, properties, loader);
            return tool(key, args, properties, loader);
        } catch (Throwable failure) {
            if (!warned) {
                warned = true;
                Throwable cause = failure instanceof java.lang.reflect.InvocationTargetException invoked
                        && invoked.getCause() != null ? invoked.getCause() : failure;
                System.err.println("[Retromod] Could not rebuild an old " + key + " item's components, so it "
                        + "loads as a plain item: " + cause);
            }
            return properties;
        }
    }

    private static Object armor(Object[] args, Object properties, ClassLoader loader) throws Exception {
        Class<?> typeClass = Class.forName(ARMOR_TYPE, false, loader);
        Object type = null;
        Object material = null;
        for (Object arg : args) {
            if (typeClass.isInstance(arg)) type = arg;
            else if (arg != properties && material == null && arg != null) material = arg;
        }
        if (type == null || material == null) return properties;
        Object record = armorRecord(material, typeClass, loader);
        Class<?> recordClass = Class.forName(ARMOR_MATERIAL, false, loader);
        Object result = properties.getClass().getMethod("humanoidArmor", recordClass, typeClass)
                .invoke(properties, record, type);
        if (isLegacy(material, recordClass)) {
            int durability = ((Number) call(material, "getDurabilityForType", type)).intValue();
            result = result.getClass().getMethod("durability", int.class).invoke(result, durability);
            result = repairable(result, repairIngredient(material), loader);
        }
        return result;
    }

    /**
     * The old horse armor constructor took a protection value and the name of its texture,
     * {@code textures/entity/horse/armor/horse_armor_<name>.png}, which Forge also accepted as a
     * full texture location. A material holding just that body protection is built for it.
     */
    private static Object horseArmor(Object[] args, Object properties, ClassLoader loader) throws Exception {
        if (args.length < 2 || !(args[0] instanceof Number protection) || args[1] == null) return properties;
        String texture = String.valueOf(args[1]);
        String namespace = texture.contains(":") ? texture.substring(0, texture.indexOf(':')) : "minecraft";
        String name = texture.substring(texture.indexOf(':') + 1);
        name = name.substring(name.lastIndexOf('/') + 1).replaceFirst("^horse_armor_", "").replaceFirst("\\.png$", "");
        Class<?> typeClass = Class.forName(ARMOR_TYPE, false, loader);
        @SuppressWarnings({"unchecked", "rawtypes"})
        Map<Object, Integer> defense = new EnumMap(typeClass);
        for (Object type : typeClass.getEnumConstants()) {
            defense.put(type, ((Enum<?>) type).name().equals("BODY") ? protection.intValue() : 0);
        }
        Object sound = Class.forName("net.minecraft.sounds.SoundEvents", false, loader).getField("HORSE_ARMOR").get(null);
        Object assetId = resourceKey(loader, "net.minecraft.world.item.equipment.EquipmentAssets", "ROOT_ID",
                identifier(loader, namespace + ":" + name));
        Class<?> recordClass = Class.forName(ARMOR_MATERIAL, false, loader);
        Object record = recordClass.getConstructors()[0].newInstance(0, defense, 0, sound, 0.0F, 0.0F,
                unusedRepairTag(loader), assetId);
        return properties.getClass().getMethod("horseArmor", recordClass).invoke(properties, record);
    }

    private static Object tool(String key, Object[] args, Object properties, ClassLoader loader) throws Exception {
        Class<?> toolClass = Class.forName(TOOL_MATERIAL, false, loader);
        Class<?> tagClass = Class.forName("net.minecraft.tags.TagKey", false, loader);
        Object material = null;
        Object blocks = null;
        List<Float> numbers = new ArrayList<>();
        for (Object arg : args) {
            if (arg instanceof Number number) numbers.add(number.floatValue());
            else if (tagClass.isInstance(arg)) blocks = arg;
            else if (arg != properties && material == null && arg != null) material = arg;
        }
        // A 1.20.5 to 1.21.1 tool passed its damage through createAttributes instead, which is gone.
        if (material == null || numbers.size() < 2) return properties;
        Object record = toolRecord(material, toolClass, loader);
        Object result;
        if ("digger".equals(key)) {
            if (blocks == null) return properties;
            result = properties.getClass().getMethod("tool", toolClass, tagClass, float.class, float.class, float.class)
                    .invoke(properties, record, blocks, numbers.get(0), numbers.get(1), 0.0F);
        } else {
            result = properties.getClass().getMethod(key, toolClass, float.class, float.class)
                    .invoke(properties, record, numbers.get(0), numbers.get(1));
        }
        if (isLegacy(material, toolClass)) {
            result = repairable(result, repairIngredient(material), loader);
        }
        return result;
    }

    /** The current armor record for an old material, built once per material. */
    private static Object armorRecord(Object material, Class<?> typeClass, ClassLoader loader) throws Exception {
        Class<?> recordClass = Class.forName(ARMOR_MATERIAL, false, loader);
        if (recordClass.isInstance(material)) return material;
        Object viewed = unwrapView(material);
        if (viewed != null) return viewed;
        synchronized (ARMOR_RECORDS) {
            Object cached = ARMOR_RECORDS.get(material);
            if (cached != null) return cached;
            @SuppressWarnings({"unchecked", "rawtypes"})
            Map<Object, Integer> defense = new EnumMap(typeClass);
            for (Object type : typeClass.getEnumConstants()) {
                defense.put(type, defenseFor(material, type));
            }
            Object sound = call(material, "getEquipSound");
            Object soundHolder = holderOf(sound, "SOUND_EVENT", loader);
            Object repairTag = unusedRepairTag(loader);
            Object assetId = resourceKey(loader, "net.minecraft.world.item.equipment.EquipmentAssets", "ROOT_ID",
                    identifier(loader, String.valueOf(call(material, "getName"))));
            Constructor<?> ctor = recordClass.getConstructors()[0];
            Object record = ctor.newInstance(0, defense, ((Number) call(material, "getEnchantmentValue")).intValue(),
                    soundHolder, ((Number) call(material, "getToughness")).floatValue(),
                    ((Number) call(material, "getKnockbackResistance")).floatValue(), repairTag, assetId);
            ARMOR_RECORDS.put(material, record);
            return record;
        }
    }

    /** An old material's table has no entry for {@code BODY}, which arrived with wolf armor. */
    private static int defenseFor(Object material, Object type) {
        try {
            return ((Number) call(material, "getDefenseForType", type)).intValue();
        } catch (Throwable missing) {
            return 0;
        }
    }

    /**
     * The current {@code ToolMaterial} for an old tier, for hosts that still have
     * {@code AxeItem}, {@code ShovelItem}, and {@code HoeItem} with record constructors.
     */
    public static Object toolMaterial(Object tier) {
        try {
            ClassLoader loader = tier.getClass().getClassLoader();
            return toolRecord(tier, Class.forName(TOOL_MATERIAL, false, loader), loader);
        } catch (Exception failure) {
            throw new IllegalStateException("Retromod could not build a tool material from "
                    + tier.getClass().getName(), failure);
        }
    }

    /** The color old dyeable armor reported before it was dyed. */
    private static final int UNDYED = 10511680;

    /** {@code DyeableLeatherItem.getColor(stack)}: the dyed color, or the leather default. */
    public static int dyeColor(Object stack) {
        try {
            Object color = stackComponent(stack, "get");
            return color == null ? UNDYED : ((Number) call(color, "rgb")).intValue();
        } catch (Exception failure) {
            return UNDYED;
        }
    }

    /** {@code DyeableLeatherItem.hasCustomColor(stack)}. */
    public static boolean hasDyeColor(Object stack) {
        try {
            return Boolean.TRUE.equals(stackComponent(stack, "has"));
        } catch (Exception failure) {
            return false;
        }
    }

    /** {@code DyeableLeatherItem.clearColor(stack)}. */
    public static void clearDyeColor(Object stack) {
        try {
            stackComponent(stack, "remove");
        } catch (Exception ignored) {
            // Nothing was dyed, or the stack is not a dyeable item.
        }
    }

    /** {@code DyeableLeatherItem.setColor(stack, color)}: stored as the {@code dyed_color} component. */
    public static void setDyeColor(Object stack, int color) {
        try {
            ClassLoader loader = stack.getClass().getClassLoader();
            Class<?> dyed = Class.forName("net.minecraft.world.item.component.DyedItemColor", false, loader);
            Object value = null;
            for (Constructor<?> ctor : dyed.getConstructors()) {
                // 26.x takes the color alone; 1.20.5 to 1.21.4 also took whether the tooltip shows it.
                if (ctor.getParameterCount() == 1) value = ctor.newInstance(color);
                else if (ctor.getParameterCount() == 2) value = ctor.newInstance(color, true);
            }
            Class<?> componentType = Class.forName("net.minecraft.core.component.DataComponentType", false, loader);
            Object type = Class.forName("net.minecraft.core.component.DataComponents", false, loader)
                    .getField("DYED_COLOR").get(null);
            stack.getClass().getMethod("set", componentType, Object.class).invoke(stack, type, value);
        } catch (Exception failure) {
            throw new IllegalStateException("Retromod could not dye " + stack, failure);
        }
    }

    private static Object stackComponent(Object stack, String method) throws Exception {
        ClassLoader loader = stack.getClass().getClassLoader();
        Class<?> componentType = Class.forName("net.minecraft.core.component.DataComponentType", false, loader);
        Object type = Class.forName("net.minecraft.core.component.DataComponents", false, loader)
                .getField("DYED_COLOR").get(null);
        return stack.getClass().getMethod(method, componentType).invoke(stack, type);
    }

    /** The current tool record for an old tier, built once per tier. */
    private static Object toolRecord(Object material, Class<?> toolClass, ClassLoader loader) throws Exception {
        if (toolClass.isInstance(material)) return material;
        Object viewed = unwrapView(material);
        if (viewed != null) return viewed;
        synchronized (TOOL_RECORDS) {
            Object cached = TOOL_RECORDS.get(material);
            if (cached != null) return cached;
            Object incorrect;
            try {
                incorrect = call(material, "getIncorrectBlocksForDrops");
            } catch (Throwable before1_20_5) {
                incorrect = incorrectForLevel(((Number) call(material, "getLevel")).intValue(), loader);
            }
            Constructor<?> ctor = toolClass.getConstructors()[0];
            Object record = ctor.newInstance(incorrect, ((Number) call(material, "getUses")).intValue(),
                    ((Number) call(material, "getSpeed")).floatValue(),
                    ((Number) call(material, "getAttackDamageBonus")).floatValue(),
                    ((Number) call(material, "getEnchantmentValue")).intValue(), unusedRepairTag(loader));
            TOOL_RECORDS.put(material, record);
            return record;
        }
    }

    /** Before 1.20.5 a tier named its mining level as a number: wood 0 up to netherite 4. */
    private static Object incorrectForLevel(int level, ClassLoader loader) throws Exception {
        String[] tags = {"INCORRECT_FOR_WOODEN_TOOL", "INCORRECT_FOR_STONE_TOOL", "INCORRECT_FOR_IRON_TOOL",
                "INCORRECT_FOR_DIAMOND_TOOL", "INCORRECT_FOR_NETHERITE_TOOL"};
        String tag = tags[Math.max(0, Math.min(tags.length - 1, level))];
        return Class.forName("net.minecraft.tags.BlockTags", false, loader).getField(tag).get(null);
    }

    /**
     * Answers a view's old method from the vanilla record it wraps. Generated views call this, so
     * a mod that reads a vanilla material through the old methods still gets real values.
     */
    public static Object view(Object record, String method, Object arg) {
        try {
            ClassLoader loader = record.getClass().getClassLoader();
            switch (method) {
                case "getDurabilityForType":
                    return arg.getClass().getMethod("getDurability", int.class)
                            .invoke(arg, ((Number) call(record, "durability")).intValue());
                case "getDefenseForType":
                    Object defense = ((Map<?, ?>) call(record, "defense")).get(arg);
                    return defense == null ? 0 : defense;
                case "getEquipSound":
                    return call(call(record, "equipSound"), "value");
                case "getName":
                    return String.valueOf(call(call(record, "assetId"), "identifier"));
                case "getUses":
                    return call(record, "durability");
                case "getLevel":
                    return levelFor(call(record, "incorrectBlocksForDrops"), loader);
                case "getIncorrectBlocksForDrops":
                    return call(record, "incorrectBlocksForDrops");
                case "getRepairIngredient":
                    Object tag = call(record, record.getClass().getName().endsWith("ToolMaterial")
                            ? "repairItems" : "repairIngredient");
                    return ingredientOfTag(tag, loader);
                default:
                    // getEnchantmentValue, getToughness, getKnockbackResistance, getSpeed,
                    // getAttackDamageBonus: the record accessor drops the "get" and lowers the next letter.
                    String accessor = Character.toLowerCase(method.charAt(3)) + method.substring(4);
                    return call(record, accessor);
            }
        } catch (Throwable failure) {
            throw new IllegalStateException("Retromod could not answer " + method + " for a vanilla material", failure);
        }
    }

    private static int levelFor(Object incorrectTag, ClassLoader loader) throws Exception {
        String[] tags = {"INCORRECT_FOR_WOODEN_TOOL", "INCORRECT_FOR_STONE_TOOL", "INCORRECT_FOR_IRON_TOOL",
                "INCORRECT_FOR_DIAMOND_TOOL", "INCORRECT_FOR_NETHERITE_TOOL"};
        Class<?> blockTags = Class.forName("net.minecraft.tags.BlockTags", false, loader);
        for (int level = 0; level < tags.length; level++) {
            if (blockTags.getField(tags[level]).get(null).equals(incorrectTag)) return level;
        }
        return 0;
    }

    private static Object ingredientOfTag(Object tag, ClassLoader loader) throws Exception {
        Object items = Class.forName("net.minecraft.core.registries.BuiltInRegistries", false, loader)
                .getField("ITEM").get(null);
        Object holderSet = null;
        for (Method method : items.getClass().getMethods()) {
            if (method.getName().equals("getOrThrow") && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].getName().equals("net.minecraft.tags.TagKey")) {
                holderSet = method.invoke(items, tag);
                break;
            }
        }
        Class<?> holderSetClass = Class.forName("net.minecraft.core.HolderSet", false, loader);
        return Class.forName("net.minecraft.world.item.crafting.Ingredient", false, loader)
                .getMethod("of", holderSetClass).invoke(null, holderSet);
    }

    /** Sets the repair items from an old ingredient, which has no tag to give the record. */
    private static Object repairable(Object properties, Object ingredient, ClassLoader loader) throws Exception {
        if (ingredient == null) return properties;
        Stream<?> holders = (Stream<?>) ingredient.getClass().getMethod("items").invoke(ingredient);
        List<?> list = holders.toList();
        if (list.isEmpty()) return properties;
        Class<?> holderSetClass = Class.forName("net.minecraft.core.HolderSet", false, loader);
        Object set = holderSetClass.getMethod("direct", List.class).invoke(null, list);
        Object repairable = Class.forName("net.minecraft.world.item.enchantment.Repairable", false, loader)
                .getConstructor(holderSetClass).newInstance(set);
        Object component = Class.forName("net.minecraft.core.component.DataComponents", false, loader)
                .getField("REPAIRABLE").get(null);
        Class<?> componentType = Class.forName("net.minecraft.core.component.DataComponentType", false, loader);
        return properties.getClass().getMethod("component", componentType, Object.class)
                .invoke(properties, component, repairable);
    }

    /** The record wants a tag; the real repair items are set from the old ingredient afterwards. */
    private static Object unusedRepairTag(ClassLoader loader) throws Exception {
        Object registry = Class.forName("net.minecraft.core.registries.Registries", false, loader)
                .getField("ITEM").get(null);
        Class<?> keyClass = Class.forName("net.minecraft.resources.ResourceKey", false, loader);
        Class<?> idClass = Class.forName("net.minecraft.resources.Identifier", false, loader);
        return Class.forName("net.minecraft.tags.TagKey", false, loader).getMethod("create", keyClass, idClass)
                .invoke(null, registry, identifier(loader, "retromod:legacy_repair_items"));
    }

    private static Object holderOf(Object value, String registryField, ClassLoader loader) throws Exception {
        Class<?> holderClass = Class.forName("net.minecraft.core.Holder", false, loader);
        if (holderClass.isInstance(value)) return value;
        Object registry = Class.forName("net.minecraft.core.registries.BuiltInRegistries", false, loader)
                .getField(registryField).get(null);
        return Class.forName("net.minecraft.core.Registry", false, loader).getMethod("wrapAsHolder", Object.class)
                .invoke(registry, value);
    }

    private static Object resourceKey(ClassLoader loader, String rootOwner, String rootField, Object id) throws Exception {
        Object root = Class.forName(rootOwner, false, loader).getField(rootField).get(null);
        Class<?> keyClass = Class.forName("net.minecraft.resources.ResourceKey", false, loader);
        Class<?> idClass = Class.forName("net.minecraft.resources.Identifier", false, loader);
        return keyClass.getMethod("create", keyClass, idClass).invoke(null, root, id);
    }

    /** An old material name such as {@code "ruby"} or {@code "modid:ruby"}, as an identifier. */
    private static Object identifier(ClassLoader loader, String name) throws Exception {
        Class<?> idClass = Class.forName("net.minecraft.resources.Identifier", false, loader);
        Object id = idClass.getMethod("tryParse", String.class).invoke(null, name.toLowerCase(java.util.Locale.ROOT));
        if (id == null) {
            String safe = name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_");
            id = idClass.getMethod("fromNamespaceAndPath", String.class, String.class).invoke(null, "minecraft", safe);
        }
        return id;
    }

    private static boolean isLegacy(Object material, Class<?> recordClass) {
        return !recordClass.isInstance(material) && unwrapView(material) == null;
    }

    private static Object unwrapView(Object material) {
        try {
            Field handle = material.getClass().getField(HANDLE);
            return handle.get(material);
        } catch (ReflectiveOperationException notAView) {
            return null;
        }
    }

    /**
     * The old material's repair ingredient, or null for none. A tier that could not be repaired
     * returned {@code Ingredient.EMPTY}, which 1.21.2 removed, so reading it fails with a linkage
     * error; that tier still cannot be repaired. A tier that built its ingredient from an
     * {@code ItemStack} cannot answer while items register on 26.x, which binds item components
     * later, so that item is not repairable either rather than losing its tool behavior.
     */
    private static Object repairIngredient(Object material) throws Exception {
        try {
            return call(material, "getRepairIngredient");
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof LinkageError) return null;
            if (e.getCause() instanceof NullPointerException npe && String.valueOf(npe.getMessage()).contains("not bound")) {
                return null;
            }
            throw e;
        }
    }

    private static Object call(Object target, String name, Object... args) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == args.length) {
                    method.setAccessible(true);
                    return method.invoke(target, args);
                }
            }
        }
        for (Method method : target.getClass().getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == args.length) {
                return method.invoke(target, args);
            }
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }
}
