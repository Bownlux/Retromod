/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.registries;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.retromod.shim.common.embedded.LegacyEnchantmentRegistry;
import com.retromod.shim.common.embedded.LegacyLog;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.IForgeRegistry;
import net.minecraftforge.registries.tags.ITagManager;

/** Forge's registry interface over a registry each world loads from data packs, for a {@code ForgeRegistries} field Forge 26.2 removed. */
public final class DynamicForgeRegistryView<V> implements IForgeRegistry<V> {

    // This class's name before the embedder moves it under a mod's own package.
    private static final String HOME = "com.retromod.legacyapi.forge.registries.DynamicForgeRegistryView";
    // The class LegacyEnchantmentBridge puts in place of the old Enchantment in a jar written against it.
    private static final String ENCHANTMENT_STAND_IN = "com.retromod.generated.LegacyEnchantment";

    private static DynamicForgeRegistryView<Object> enchantments;

    private final ResourceKey<Registry<V>> key;
    private final Class<?> standIn;
    private final Method standInFromKey;
    private final Field standInId;
    private Codec<V> codec;

    private DynamicForgeRegistryView(ResourceKey<Registry<V>> key, Class<?> standIn) {
        this.key = key;
        // A jar written against the old class sees entries as its own stand-ins, built from a key and naming an id.
        Method fromKey = null;
        Field id = null;
        if (standIn != null) {
            try {
                fromKey = standIn.getMethod("fromKey", ResourceKey.class);
                id = standIn.getField("id");
            } catch (NoSuchMethodException | NoSuchFieldException e) {
                fromKey = null;
                id = null;
            }
        }
        this.standIn = fromKey == null ? null : standIn;
        this.standInFromKey = fromKey;
        this.standInId = id;
    }

    /** {@code ForgeRegistries.ENCHANTMENTS}, which Forge dropped when enchantments moved to data packs. */
    public static synchronized IForgeRegistry<Object> enchantments() {
        if (enchantments == null) {
            enchantments = new DynamicForgeRegistryView<>(cast(Registries.ENCHANTMENT), sibling(ENCHANTMENT_STAND_IN));
        }
        return enchantments;
    }

    /** {@code DeferredRegister.create(registry, modid)} in old code: a register over a view is optional. */
    public static <B> DeferredRegister<B> createRegister(IForgeRegistry<B> registry, String modid) {
        if (!isView(registry)) return DeferredRegister.create(registry, modid);
        // Forge knows no such registry, and a required register over one fails all mod loading at registry events.
        LegacyLog.warnOnce("dynamic-registry-deferred " + registry.getRegistryName() + " " + modid, "Mod " + modid
                + " registers entries in " + registry.getRegistryName() + " from code. Minecraft now loads that"
                + " registry from data packs, so those entries are never added: their RegistryObject reports"
                + " isPresent() false, and get() throws \"Registry Object not present\".");
        return DeferredRegister.createOptional(registry.getRegistryKey(), modid);
    }

    // Any mod's copy counts, because a library may build a register from a view another jar read.
    private static boolean isView(Object registry) {
        return registry != null && registry.getClass().getName().endsWith(HOME);
    }

    @Override
    public ResourceKey<Registry<V>> getRegistryKey() {
        return key;
    }

    @Override
    public Identifier getRegistryName() {
        return key.identifier();
    }

    @Override
    public void register(String name, V value) {
        refuse(name);
    }

    @Override
    public void register(Identifier name, V value) {
        refuse(String.valueOf(name));
    }

    @Override
    public boolean containsKey(Identifier id) {
        Registry<V> registry = live();
        return registry != null && id != null && registry.containsKey(id);
    }

    @Override
    public boolean containsValue(V value) {
        Identifier id = idOf(value);
        return id != null && containsKey(id);
    }

    @Override
    public boolean isEmpty() {
        Registry<V> registry = live();
        return registry == null || registry.size() == 0;
    }

    @Override
    public V getValue(Identifier id) {
        Registry<V> registry = live();
        return registry == null || id == null ? null : toMod(registry, registry.getValue(id));
    }

    @Override
    public Identifier getKey(V value) {
        return idOf(value);
    }

    // A data registry has no default entry.
    @Override
    public Identifier getDefaultKey() {
        return null;
    }

    @Override
    public Optional<ResourceKey<V>> getResourceKey(V value) {
        Identifier id = idOf(value);
        return id == null ? Optional.empty() : Optional.of(ResourceKey.create(key, id));
    }

    @Override
    public Set<Identifier> getKeys() {
        Registry<V> registry = live();
        return registry == null ? Set.of() : registry.keySet();
    }

    @Override
    public Collection<V> getValues() {
        Registry<V> registry = live();
        List<V> values = new ArrayList<>();
        if (registry == null) return values;
        for (V value : registry) values.add(toMod(registry, value));
        return values;
    }

    @Override
    public Set<Map.Entry<ResourceKey<V>, V>> getEntries() {
        Registry<V> registry = live();
        if (registry == null) return Set.of();
        if (standIn == null) return registry.entrySet();
        Set<Map.Entry<ResourceKey<V>, V>> entries = new LinkedHashSet<>();
        for (Map.Entry<ResourceKey<V>, V> entry : registry.entrySet()) {
            entries.add(Map.entry(entry.getKey(), toMod(registry, entry.getValue())));
        }
        return entries;
    }

    @Override
    public Iterator<V> iterator() {
        return getValues().iterator();
    }

    // Writes an entry as its id, and looks each id up in the world loaded when the codec runs.
    @Override
    public synchronized Codec<V> getCodec() {
        if (codec == null) codec = Identifier.CODEC.flatXmap(this::decode, this::encode);
        return codec;
    }

    @Override
    public Optional<Holder<V>> getHolder(ResourceKey<V> entry) {
        return getDelegate(entry).map(holder -> holder);
    }

    @Override
    public Optional<Holder<V>> getHolder(Identifier id) {
        return getDelegate(id).map(holder -> holder);
    }

    @Override
    public Optional<Holder<V>> getHolder(V value) {
        return getDelegate(value).map(holder -> holder);
    }

    // Data registries take their tags from data packs too, so Forge's tag manager has nothing to manage.
    @Override
    public ITagManager<V> tags() {
        return null;
    }

    @Override
    public Optional<Holder.Reference<V>> getDelegate(ResourceKey<V> entry) {
        Registry<V> registry = live();
        return registry == null || entry == null ? Optional.empty() : registry.get(entry);
    }

    @Override
    public Holder.Reference<V> getDelegateOrThrow(ResourceKey<V> entry) {
        return getDelegate(entry).orElseThrow(() -> missing(entry));
    }

    @Override
    public Optional<Holder.Reference<V>> getDelegate(Identifier id) {
        Registry<V> registry = live();
        return registry == null || id == null ? Optional.empty() : registry.get(id);
    }

    @Override
    public Holder.Reference<V> getDelegateOrThrow(Identifier id) {
        return getDelegate(id).orElseThrow(() -> missing(id));
    }

    @Override
    public Optional<Holder.Reference<V>> getDelegate(V value) {
        Identifier id = idOf(value);
        return id == null ? Optional.empty() : getDelegate(id);
    }

    @Override
    public Holder.Reference<V> getDelegateOrThrow(V value) {
        return getDelegate(value).orElseThrow(() -> missing(value));
    }

    @Override
    public <T> T getSlaveMap(SlaveKey<T> name) {
        return null;
    }

    // The world's own registry, found the way BuiltInRegistries.ENCHANTMENT's view finds it: the client's on the
    // client thread, a running server's, or the registries of a data pack load in progress.
    private Registry<V> live() {
        return cast(LegacyEnchantmentRegistry.loaded());
    }

    // A stand-in names its own id, which holds before any world loads; a host value is looked up.
    private Identifier idOf(Object value) {
        if (value == null) return null;
        if (standIn != null && standIn.isInstance(value)) {
            Object id = read(standInId, value);
            return id == null ? null : Identifier.tryParse(id.toString());
        }
        Registry<V> registry = live();
        return registry == null ? null : registry.getKey(cast(value));
    }

    private DataResult<V> decode(Identifier id) {
        V value = getValue(id);
        return value != null ? DataResult.success(value) : DataResult.error(() -> "No " + id + " in "
                + key.identifier() + ". Minecraft fills it from data packs when a world loads.");
    }

    private DataResult<Identifier> encode(V value) {
        Identifier id = idOf(value);
        return id != null ? DataResult.success(id)
                : DataResult.error(() -> value + " is not an entry of " + key.identifier() + " in the loaded world.");
    }

    private V toMod(Registry<V> registry, V value) {
        if (value == null || standIn == null) return value;
        try {
            return cast(standInFromKey.invoke(null, ResourceKey.create(key, registry.getKey(value))));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not give an old mod the entry " + registry.getKey(value)
                    + " of " + key.identifier(), e);
        }
    }

    private static Object read(Field field, Object owner) {
        try {
            return field.get(owner);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Retromod could not read the id of " + owner, e);
        }
    }

    // The world fills this registry from data packs, so an entry added from code stays out of it.
    private void refuse(String name) {
        LegacyLog.warnOnce("dynamic-registry-register " + key.identifier(), "An old mod registered " + name
                + " in " + key.identifier() + " from code. Minecraft now loads that registry from data packs,"
                + " so Retromod leaves the entry out and the game never has it.");
    }

    private IllegalStateException missing(Object entry) {
        return new IllegalStateException("No " + entry + " in " + key.identifier()
                + ". Minecraft fills it from data packs when a world loads, so it is empty before that.");
    }

    // Each mod's copy of this class sits under that mod's own package, beside the jar's other Retromod classes.
    private static Class<?> sibling(String name) {
        String self = DynamicForgeRegistryView.class.getName();
        if (!self.endsWith(HOME)) return null;
        String prefix = self.substring(0, self.length() - HOME.length());
        try {
            return Class.forName(prefix + name, false, DynamicForgeRegistryView.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value) {
        return (T) value;
    }

}
