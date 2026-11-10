/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 * 
 * VIRTUAL MOD LOADER
 * 
 * For mods from 1.8-1.12 era, the Forge mod loading system was completely different.
 * Modern mod loaders (Fabric, NeoForge) have incompatible initialization lifecycles.
 * 
 * This Virtual Mod Loader:
 * 1. Emulates the old Forge FML lifecycle
 * 2. Translates @Mod annotations to modern equivalents
 * 3. Bridges old event buses to modern event systems
 * 4. Handles registry differences (numeric IDs vs string IDs)
 */
package com.retromod.legacy;

import java.io.*;
import java.util.*;
import java.util.jar.*;
import java.util.function.*;

/**
 * Emulates legacy mod loader behavior for very old mods.
 */
public class VirtualModLoader {
    
    // Registered virtual mods
    private final Map<String, VirtualModInstance> loadedMods = new LinkedHashMap<>();
    
    // Event bus emulation
    private final VirtualEventBus eventBus = new VirtualEventBus();
    
    // Registry emulation (for numeric ID mods)
    private final VirtualRegistry blockRegistry = new VirtualRegistry("block");
    private final VirtualRegistry itemRegistry = new VirtualRegistry("item");
    private final VirtualRegistry entityRegistry = new VirtualRegistry("entity");
    
    /**
     * Embed virtual mod loader components into a JAR.
     */
    public void embedComponents(JarOutputStream out, 
            LegacyModSupport.ModLoaderType loader,
            LegacyModSupport.Epoch epoch) throws IOException {
        
        // Embed core virtual loader classes
        embedResource(out, "com/retromod/legacy/shim/VirtualMod.class");
        embedResource(out, "com/retromod/legacy/shim/VirtualMinecraftForge.class");
        embedResource(out, "com/retromod/legacy/shim/VirtualEventBus.class");
        embedResource(out, "com/retromod/legacy/shim/VirtualSubscribeEvent.class");
        
        // Embed event shims
        embedResource(out, "com/retromod/legacy/shim/VirtualPreInitEvent.class");
        embedResource(out, "com/retromod/legacy/shim/VirtualInitEvent.class");
        embedResource(out, "com/retromod/legacy/shim/VirtualPostInitEvent.class");
        
        // Embed registry shims for pre-flattening mods
        if (epoch.order <= 1) {
            embedResource(out, "com/retromod/legacy/shim/VirtualGameRegistry.class");
            embedResource(out, "com/retromod/legacy/shim/VirtualOreDictionary.class");
            embedResource(out, "com/retromod/legacy/shim/NumericIdMapper.class");
        }
        
        // Embed model loader shims
        if (loader == LegacyModSupport.ModLoaderType.FORGE_LEGACY) {
            embedResource(out, "com/retromod/legacy/shim/VirtualModelLoader.class");
            embedResource(out, "com/retromod/legacy/shim/VirtualModelResourceLocation.class");
        }
        
        // Embed LiteLoader compatibility
        if (loader == LegacyModSupport.ModLoaderType.LITELOADER) {
            embedResource(out, "com/retromod/legacy/shim/LiteLoaderBridge.class");
        }
    }
    
    private void embedResource(JarOutputStream out, String resourcePath) throws IOException {
        try (InputStream is = getClass().getResourceAsStream("/" + resourcePath)) {
            if (is != null) {
                out.putNextEntry(new JarEntry(resourcePath));
                // A copy from the release jar names Retromod's relocated Gson and SLF4J; the mod sees the game's.
                out.write(com.retromod.util.ShadedLibraryNames.toHostNames(is.readAllBytes()));
                out.closeEntry();
            }
        }
    }
    
    /**
     * Initialize a virtual mod from class data.
     */
    public VirtualModInstance initializeMod(String modId, String modClass, 
            Map<String, Object> metadata) {
        
        VirtualModInstance instance = new VirtualModInstance(modId, modClass, metadata);
        loadedMods.put(modId, instance);
        return instance;
    }
    
    /**
     * Run the virtual initialization lifecycle.
     * Translates old FML events to modern initialization.
     */
    public void runInitialization(Consumer<String> statusCallback) {
        statusCallback.accept("Running virtual pre-initialization...");
        eventBus.post(new VirtualPreInitEvent());
        
        statusCallback.accept("Running virtual initialization...");
        eventBus.post(new VirtualInitEvent());
        
        statusCallback.accept("Running virtual post-initialization...");
        eventBus.post(new VirtualPostInitEvent());
        
        statusCallback.accept("Virtual mod loader initialization complete.");
    }
    
    public VirtualEventBus getEventBus() {
        return eventBus;
    }
    
    public VirtualRegistry getBlockRegistry() {
        return blockRegistry;
    }
    
    public VirtualRegistry getItemRegistry() {
        return itemRegistry;
    }
    
    // INNER CLASSES
    
    /**
     * Represents a loaded virtual mod.
     */
    public static class VirtualModInstance {
        public final String modId;
        public final String modClass;
        public final Map<String, Object> metadata;
        public Object instance;
        
        VirtualModInstance(String modId, String modClass, Map<String, Object> metadata) {
            this.modId = modId;
            this.modClass = modClass;
            this.metadata = metadata;
        }
    }
    
    /**
     * Emulates the old Forge event bus.
     */
    public static class VirtualEventBus {
        private final Map<Class<?>, List<Consumer<Object>>> handlers = new HashMap<>();
        
        public void register(Object handler) {
            // Scan handler for @SubscribeEvent methods
            // This is handled at transformation time
        }
        
        public void post(Object event) {
            List<Consumer<Object>> eventHandlers = handlers.get(event.getClass());
            if (eventHandlers != null) {
                for (Consumer<Object> handler : eventHandlers) {
                    try {
                        handler.accept(event);
                    } catch (Exception e) {
                        System.err.println("Error in event handler: " + e);
                    }
                }
            }
        }
        
        public <T> void addHandler(Class<T> eventType, Consumer<T> handler) {
            handlers.computeIfAbsent(eventType, k -> new ArrayList<>())
                    .add(e -> handler.accept(eventType.cast(e)));
        }
    }
    
    /**
     * Emulates legacy registry behavior.
     */
    public static class VirtualRegistry {
        private final String name;
        private final Map<Integer, String> numericToString = new HashMap<>();
        private final Map<String, Integer> stringToNumeric = new HashMap<>();
        private final Map<String, Object> objects = new HashMap<>();
        private int nextId = 0;
        
        VirtualRegistry(String name) {
            this.name = name;
        }
        
        /**
         * Register with numeric ID (pre-1.13 style).
         */
        public void register(int id, String name, Object object) {
            numericToString.put(id, name);
            stringToNumeric.put(name, id);
            objects.put(name, object);
        }
        
        /**
         * Register with auto-assigned ID (modern style).
         */
        public void register(String name, Object object) {
            int id = nextId++;
            register(id, name, object);
        }
        
        public Object get(int numericId) {
            String name = numericToString.get(numericId);
            return name != null ? objects.get(name) : null;
        }
        
        public Object get(String name) {
            return objects.get(name);
        }
        
        public int getNumericId(String name) {
            return stringToNumeric.getOrDefault(name, -1);
        }
        
        public String getStringId(int numericId) {
            return numericToString.get(numericId);
        }
    }
    
    // Event classes
    public static class VirtualPreInitEvent {}
    public static class VirtualInitEvent {}
    public static class VirtualPostInitEvent {}
}
