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

    // Records built from the 1.20.5 to 1.21.1 constructor, and the repair supplier each was given.
    // Their items set durability themselves, so the record's is never applied.
    private static final Map<Object, Object> OLD_RECORD_REPAIRS = java.util.Collections.synchronizedMap(new IdentityHashMap<>());
    // The 1.21.1 armor material registry, rebuilt under its own id. One registry serves every mod,
    // so it is kept in the system properties, which all mods' copies of this class share.
    private static final String REGISTRY_PROPERTY = "retromod.legacyArmorMaterialRegistry";
    private static final String REGISTRY_ID = "retromod:legacy_armor_material";
    // An armor item whose material holder binds after items: its material and type, from adapt to
    // constructed on the same thread, then until the components event.
    private static final ThreadLocal<Object[]> PENDING_ARMOR = new ThreadLocal<>();
    private static final Map<Object, Object[]> DEFERRED_ARMOR = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static final java.util.Set<String> LISTENING_BUSES = java.util.concurrent.ConcurrentHashMap.newKeySet();
    // A 1.20.5 to 1.21.1 tool gave createAttributes its damage and speed, then only its tier to
    // its constructor. The two numbers wait here, on the thread building the tool.
    private static final ThreadLocal<float[]> LAST_TOOL_NUMBERS = new ThreadLocal<>();
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
        // From 1.20.5 to 1.21.1 an armor item named its material by registry holder.
        Class<?> holderClass = Class.forName("net.minecraft.core.Holder", false, loader);
        if (holderClass.isInstance(material)) {
            if (!(boolean) holderClass.getMethod("isBound").invoke(material)) {
                PENDING_ARMOR.set(new Object[]{material, type});
                return properties;
            }
            material = holderClass.getMethod("value").invoke(material);
        }
        Object record = armorRecord(material, typeClass, loader);
        if (OLD_RECORD_REPAIRS.containsKey(record)) return armorComponents(properties, record, type, loader);
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
        // A 1.20.5 to 1.21.1 tool passed its damage through createAttributes instead, which kept it.
        if (numbers.size() < 2 && LAST_TOOL_NUMBERS.get() != null) {
            numbers = List.of(lastToolDamage(), lastToolSpeed());
        }
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
            if (((Number) call(material, "getEnchantmentValue")).intValue() <= 0) result = withoutEnchantable(result, loader);
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
     * {@code new ArmorMaterial(defense, enchantmentValue, equipSound, repair, layers, toughness,
     * knockbackResistance)}, the 1.20.5 to 1.21.1 record, as the current one. The first layer names
     * the equipment asset, as it named the armor texture.
     */
    public static Object oldArmorMaterial(Object defense, int enchantmentValue, Object equipSound, Object repair,
            Object layers, float toughness, float knockbackResistance) {
        try {
            ClassLoader loader = LegacyEquipmentSupport.class.getClassLoader();
            Class<?> recordClass = Class.forName(ARMOR_MATERIAL, false, loader);
            List<?> layerList = (List<?>) layers;
            Object asset = layerList.isEmpty() ? identifier(loader, "minecraft:iron")
                    : layerList.get(0).getClass().getMethod("assetName").invoke(layerList.get(0));
            Object assetId = resourceKey(loader, "net.minecraft.world.item.equipment.EquipmentAssets", "ROOT_ID", asset);
            Object record = recordClass.getConstructors()[0].newInstance(0, defense, enchantmentValue, equipSound,
                    toughness, knockbackResistance, unusedRepairTag(loader), assetId);
            OLD_RECORD_REPAIRS.put(record, repair == null ? List.of() : repair);
            return record;
        } catch (Exception failure) {
            throw new IllegalStateException("Retromod could not build an old armor material", failure);
        }
    }

    /** {@code Registries.ARMOR_MATERIAL}, which 1.21.2 removed: the key of the rebuilt registry. */
    public static Object armorMaterialKey() {
        // A deferred register only needs the key, but its entries need the registry to exist.
        armorMaterialRegistry();
        return registryKey();
    }

    private static Object registryKey() {
        try {
            ClassLoader loader = LegacyEquipmentSupport.class.getClassLoader();
            Class<?> keyClass = Class.forName("net.minecraft.resources.ResourceKey", false, loader);
            Class<?> idClass = Class.forName("net.minecraft.resources.Identifier", false, loader);
            return keyClass.getMethod("createRegistryKey", idClass).invoke(null, identifier(loader, REGISTRY_ID));
        } catch (Exception failure) {
            throw new IllegalStateException("Retromod could not name the old armor material registry", failure);
        }
    }

    /**
     * {@code BuiltInRegistries.ARMOR_MATERIAL}: the rebuilt registry. On NeoForge it is also
     * registered with the loader, so a deferred register for it is filled as before.
     */
    public static Object armorMaterialRegistry() {
        return System.getProperties().computeIfAbsent(REGISTRY_PROPERTY, unused -> createArmorMaterialRegistry());
    }

    private static Object createArmorMaterialRegistry() {
        try {
            ClassLoader loader = LegacyEquipmentSupport.class.getClassLoader();
            Object key = registryKey();
            Class<?> keyClass = Class.forName("net.minecraft.resources.ResourceKey", false, loader);
            Class<?> builderClass;
            try {
                builderClass = Class.forName("net.neoforged.neoforge.registries.RegistryBuilder", false, loader);
            } catch (ClassNotFoundException notNeoForge) {
                Class<?> lifecycle = Class.forName("com.mojang.serialization.Lifecycle", false, loader);
                return Class.forName("net.minecraft.core.MappedRegistry", false, loader)
                        .getConstructor(keyClass, lifecycle).newInstance(key, lifecycle.getMethod("stable").invoke(null));
            }
            Object builder = builderClass.getConstructor(keyClass).newInstance(key);
            builder = builderClass.getMethod("sync", boolean.class).invoke(builder, false);
            Object registry = builderClass.getMethod("create").invoke(builder);
            // NewRegistryEvent comes after mod construction, where the first reader is.
            Class<?> newRegistryEvent = Class.forName("net.neoforged.neoforge.registries.NewRegistryEvent", false, loader);
            Method register = newRegistryEvent.getMethod("register", Class.forName("net.minecraft.core.Registry", false, loader));
            addModBusListener(newRegistryEvent, event -> {
                try {
                    register.invoke(event, registry);
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("Retromod could not register the old armor material registry", failure);
                }
            });
            return registry;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Retromod could not rebuild the old armor material registry", failure);
        }
    }

    /** Called by the armor base once the item exists: its components wait for its material. */
    public static void constructed(Object item) {
        Object[] pending = PENDING_ARMOR.get();
        if (pending == null) return;
        PENDING_ARMOR.remove();
        DEFERRED_ARMOR.put(item, pending);
        try {
            Class<?> event = Class.forName("net.neoforged.neoforge.event.ModifyDefaultComponentsEvent", false,
                    item.getClass().getClassLoader());
            addModBusListener(event, LegacyEquipmentSupport::applyDeferredArmor);
        } catch (ReflectiveOperationException notNeoForge) {
            // Only NeoForge registers a holder's registry after items; elsewhere nothing waits here.
        }
    }

    /** Sets each waiting armor item's components from its now bound material. */
    private static void applyDeferredArmor(Object event) {
        Map<Object, Object[]> waiting;
        synchronized (DEFERRED_ARMOR) {
            waiting = new java.util.HashMap<>(DEFERRED_ARMOR);
            DEFERRED_ARMOR.clear();
        }
        waiting.forEach((item, pending) -> {
            try {
                ClassLoader loader = item.getClass().getClassLoader();
                Object record = Class.forName("net.minecraft.core.Holder", false, loader).getMethod("value").invoke(pending[0]);
                Class<?> itemLike = Class.forName("net.minecraft.world.level.ItemLike", false, loader);
                java.util.function.Consumer<Object> components = builder -> setArmorComponents(builder, record, pending[1], loader);
                modifyComponents(event, itemLike, item, components, loader);
            } catch (ReflectiveOperationException | RuntimeException failure) {
                System.err.println("[Retromod] Could not give an old armor item its armor components: " + failure);
            }
        });
    }

    /**
     * {@code event.modify(item, components)}. 26.3 kept only the form that takes an
     * {@code Initializer}, which also receives the registries and the item.
     */
    private static void modifyComponents(Object event, Class<?> itemLike, Object item,
            java.util.function.Consumer<Object> components, ClassLoader loader) throws ReflectiveOperationException {
        try {
            event.getClass().getMethod("modify", itemLike, java.util.function.Consumer.class).invoke(event, item, components);
            return;
        } catch (NoSuchMethodException consumerFormRemoved) {
            // Falls through to the initializer form.
        }
        Class<?> initializer = Class.forName("net.neoforged.neoforge.event.ModifyDefaultComponentsEvent$Initializer", false, loader);
        Object run = java.lang.reflect.Proxy.newProxyInstance(initializer.getClassLoader(), new Class<?>[]{initializer},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "run" -> {
                        components.accept(arguments[0]);
                        yield null;
                    }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    case "toString" -> "Retromod armor components";
                    default -> java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, arguments);
                });
        event.getClass().getMethod("modify", itemLike, initializer).invoke(event, item, run);
    }

    /** The components humanoid armor gets from its material, without durability, which the item sets. */
    private static Object armorComponents(Object properties, Object record, Object type, ClassLoader loader) throws Exception {
        Class<?> componentType = Class.forName("net.minecraft.core.component.DataComponentType", false, loader);
        Method component = properties.getClass().getMethod("component", componentType, Object.class);
        for (Object[] value : armorComponentValues(record, type, loader)) {
            properties = component.invoke(properties, value[0], value[1]);
        }
        return repairable(properties, oldRepairIngredient(record), loader);
    }

    private static void setArmorComponents(Object builder, Object record, Object type, ClassLoader loader) {
        try {
            Class<?> componentType = Class.forName("net.minecraft.core.component.DataComponentType", false, loader);
            Method set = builder.getClass().getMethod("set", componentType, Object.class);
            for (Object[] value : armorComponentValues(record, type, loader)) set.invoke(builder, value[0], value[1]);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** Attribute modifiers, enchantability, and the equipment slot, sound, and asset. */
    private static List<Object[]> armorComponentValues(Object record, Object type, ClassLoader loader) throws Exception {
        Class<?> components = Class.forName("net.minecraft.core.component.DataComponents", false, loader);
        List<Object[]> values = new ArrayList<>();
        values.add(new Object[]{components.getField("ATTRIBUTE_MODIFIERS").get(null),
                record.getClass().getMethod("createAttributes", type.getClass()).invoke(record, type)});
        int enchantmentValue = ((Number) call(record, "enchantmentValue")).intValue();
        if (enchantmentValue > 0) {
            Object enchantable = Class.forName("net.minecraft.world.item.enchantment.Enchantable", false, loader)
                    .getConstructor(int.class).newInstance(enchantmentValue);
            values.add(new Object[]{components.getField("ENCHANTABLE").get(null), enchantable});
        }
        Class<?> equippable = Class.forName("net.minecraft.world.item.equipment.Equippable", false, loader);
        Object slot = type.getClass().getMethod("getSlot").invoke(type);
        Object builder = equippable.getMethod("builder", slot.getClass()).invoke(null, slot);
        builder = builder.getClass().getMethod("setEquipSound", Class.forName("net.minecraft.core.Holder", false, loader))
                .invoke(builder, call(record, "equipSound"));
        builder = builder.getClass().getMethod("setAsset", Class.forName("net.minecraft.resources.ResourceKey", false, loader))
                .invoke(builder, call(record, "assetId"));
        values.add(new Object[]{components.getField("EQUIPPABLE").get(null), builder.getClass().getMethod("build").invoke(builder)});
        return values;
    }

    /** The ingredient an old record's repair supplier gives, or null when it gives none it can still read. */
    private static Object oldRepairIngredient(Object record) {
        Object repair = OLD_RECORD_REPAIRS.get(record);
        if (!(repair instanceof java.util.function.Supplier<?> supplier)) return null;
        try {
            return supplier.get();
        } catch (LinkageError | RuntimeException removedOrEmpty) {
            // Ingredient.EMPTY, the usual "no repair", is gone, so reading it fails.
            return null;
        }
    }

    /** Listens on the mod bus of the mod being loaded, once per bus. */
    private static void addModBusListener(Class<?> event, java.util.function.Consumer<Object> listener)
            throws ReflectiveOperationException {
        ClassLoader loader = LegacyEquipmentSupport.class.getClassLoader();
        Object context = Class.forName("net.neoforged.fml.ModLoadingContext", false, loader).getMethod("get").invoke(null);
        Object container = context.getClass().getMethod("getActiveContainer").invoke(context);
        Object bus = container.getClass().getMethod("getEventBus").invoke(container);
        if (bus == null || !LISTENING_BUSES.add(System.identityHashCode(bus) + ":" + event.getName())) return;
        Class<?> busClass = Class.forName("net.neoforged.bus.api.IEventBus", false, loader);
        busClass.getMethod("addListener", Class.class, java.util.function.Consumer.class).invoke(bus, event, listener);
    }

    /** {@code DiggerItem.createAttributes(tier, damage, speed)}: the tier's bonus added to the damage, both on the main hand. */
    public static Object diggerAttributes(Object tier, float damage, float speed) {
        return toolAttributes(tier, damage, speed);
    }

    /** {@code SwordItem.createAttributes(tier, damage, speed)}, whose damage was a whole number. */
    public static Object swordAttributes(Object tier, int damage, float speed) {
        return toolAttributes(tier, damage, speed);
    }

    /** The damage the last {@code createAttributes} on this thread was given, or none. */
    public static float lastToolDamage() {
        float[] numbers = LAST_TOOL_NUMBERS.get();
        return numbers == null ? 0.0F : numbers[0];
    }

    /** The speed the last {@code createAttributes} on this thread was given, which ends its use. */
    public static float lastToolSpeed() {
        float[] numbers = LAST_TOOL_NUMBERS.get();
        LAST_TOOL_NUMBERS.remove();
        return numbers == null ? -3.0F : numbers[1];
    }

    private static Object toolAttributes(Object tier, float damage, float speed) {
        LAST_TOOL_NUMBERS.set(new float[]{damage, speed});
        try {
            ClassLoader loader = LegacyEquipmentSupport.class.getClassLoader();
            Class<?> item = Class.forName("net.minecraft.world.item.Item", false, loader);
            Class<?> attributes = Class.forName("net.minecraft.world.entity.ai.attributes.Attributes", false, loader);
            Class<?> modifier = Class.forName("net.minecraft.world.entity.ai.attributes.AttributeModifier", false, loader);
            Class<?> operation = Class.forName("net.minecraft.world.entity.ai.attributes.AttributeModifier$Operation", false, loader);
            Class<?> slotGroup = Class.forName("net.minecraft.world.entity.EquipmentSlotGroup", false, loader);
            Class<?> holder = Class.forName("net.minecraft.core.Holder", false, loader);
            Object addValue = operation.getField("ADD_VALUE").get(null);
            Object mainHand = slotGroup.getField("MAINHAND").get(null);
            float bonus = ((Number) call(tier, "getAttackDamageBonus")).floatValue();
            Object builder = Class.forName("net.minecraft.world.item.component.ItemAttributeModifiers", false, loader)
                    .getMethod("builder").invoke(null);
            Method add = builder.getClass().getMethod("add", holder, modifier, slotGroup);
            Constructor<?> newModifier = modifier.getConstructor(item.getField("BASE_ATTACK_DAMAGE_ID").getType(), double.class, operation);
            add.invoke(builder, attributes.getField("ATTACK_DAMAGE").get(null),
                    newModifier.newInstance(item.getField("BASE_ATTACK_DAMAGE_ID").get(null), (double) (damage + bonus), addValue), mainHand);
            add.invoke(builder, attributes.getField("ATTACK_SPEED").get(null),
                    newModifier.newInstance(item.getField("BASE_ATTACK_SPEED_ID").get(null), (double) speed, addValue), mainHand);
            return builder.getClass().getMethod("build").invoke(builder);
        } catch (Exception failure) {
            throw new IllegalStateException("Retromod could not build an old tool's attack attributes", failure);
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
            // A tier with no enchantability was simply not enchantable; the record refuses 0.
            int enchantmentValue = Math.max(1, ((Number) call(material, "getEnchantmentValue")).intValue());
            Object record = ctor.newInstance(incorrect, ((Number) call(material, "getUses")).intValue(),
                    ((Number) call(material, "getSpeed")).floatValue(),
                    ((Number) call(material, "getAttackDamageBonus")).floatValue(),
                    enchantmentValue, unusedRepairTag(loader));
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

    /** Removes the enchantable component the record's clamped value added. */
    private static Object withoutEnchantable(Object properties, ClassLoader loader) throws Exception {
        Class<?> componentType = Class.forName("net.minecraft.core.component.DataComponentType", false, loader);
        Object enchantable = Class.forName("net.minecraft.core.component.DataComponents", false, loader)
                .getField("ENCHANTABLE").get(null);
        return properties.getClass().getMethod("component", componentType, Object.class).invoke(properties, enchantable, null);
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
