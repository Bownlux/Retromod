---
name: add-polyfill
description: Restore a removed API with a focused polyfill that delegates to current behavior. Use for ClassNotFoundException or NoSuchMethodError failures when a redirect alone is not enough.
argument-hint: "api-name (e.g. baubles, nei, old-fabric-api-module)"
---

# Add a Polyfill

Use a polyfill when an old mod needs behavior that the current game or loader no longer exposes. A good polyfill preserves the useful part of the old contract instead of merely hiding the first crash.

## When to Use

- Mod crashes with `ClassNotFoundException` for a class that no longer exists
- Mod crashes with `NoSuchMethodError` for a method with no replacement
- A mod loader API module was entirely removed (e.g. old Fabric API modules)
- A third-party mod API was discontinued (e.g. Baubles → Curios, NEI → JEI)

## Steps

1. **Confirm what disappeared.** Start with the exact owner, member, and descriptor from the crash. Check whether the API moved, changed shape, or was removed entirely. A rename belongs in a shim, not a polyfill.

2. **Build the smallest useful replacement.** Put Retromod-owned implementations under the existing embedded or stub package used by the surrounding provider, then redirect the old name to them. Do not introduce classes into loader-owned packages because that can create split-package failures.
   ```java
   package com.retromod.polyfill.stubs.myapi;

   /**
    * Keeps the old entry point while using the current API underneath.
    */
   public class RemovedClass {
       public void removedMethod() {
           ModernApi.doTheThing();
       }
       public Object getData() {
           // Bridge to the modern data source
           return ModernDataProvider.get();
       }
       public boolean isAvailable() { return true; }
   }
   ```

3. **Create the PolyfillProvider** at `src/main/java/com/retromod/polyfill/`:
   ```java
   public class MyApiPolyfillProvider implements PolyfillProvider {
       @Override public String getName() { return "My API Polyfill"; }
       @Override public String getCategory() { return "thirdparty"; } // or "fabric_api", "forge", "neoforge", "minecraft_vanilla"

       @Override
       public List<String> getRemovedClasses() {
           return List.of(
               "the/original/package/RemovedClass",
               "the/original/package/AnotherRemoved"
           );
       }

       @Override
       public List<String> getPolyfillClasses() {
           return List.of(
               "com/retromod/polyfill/stubs/myapi/RemovedClass",
               "com/retromod/polyfill/stubs/myapi/AnotherRemoved"
           );
       }

       @Override
       public void registerPolyfills(RetromodTransformer transformer) {
           transformer.registerClassRedirect(
               "the/original/package/RemovedClass",
               "com/retromod/polyfill/stubs/myapi/RemovedClass"
           );
       }
   }
   ```

4. **Register in ServiceLoader.** Add to:
   ```
   src/main/resources/META-INF/services/com.retromod.polyfill.PolyfillProvider
   ```

5. **Match the old type shape.** Preserve whether the API was a class or interface. Use default methods only when a harmless default matches the old contract. If inheritance changed, check whether `registerSuperclassRedirect()` is the established solution.

6. **Test the behavior.** Add a focused unit test, confirm the generated or embedded class can load, and run the affected mod. Loading alone is not enough when the API has visible behavior.

## Key Files
- Polyfill providers: `src/main/java/com/retromod/polyfill/`
- Stub implementations: `src/main/java/com/retromod/polyfill/stubs/`
- PolyfillProvider interface: `src/main/java/com/retromod/polyfill/PolyfillProvider.java`
- ServiceLoader: `src/main/resources/META-INF/services/com.retromod.polyfill.PolyfillProvider`
- Config categories: `config/retromod/config.json` → `polyfill_categories`

## Categories
- `fabric_api`: Removed Fabric API modules
- `minecraft_vanilla`: Removed vanilla MC classes (Material, LiteralText, etc.)
- `mixin_targets`: Removed MC classes used as Mixin targets
- `forge`: Legacy Forge APIs (SidedProxy, RegistryObject, capabilities)
- `neoforge`: Removed NeoForge APIs
- `thirdparty`: Third-party mod APIs (Baubles, NEI, CoFH, WAILA)
- `rendering`: Removed rendering APIs
- `entity`: Removed entity APIs

## Keep in Mind
- Delegate to a current API whenever one exists.
- Use a no-op only when the old behavior has no meaningful replacement, and document that limitation for users.
- Users can toggle polyfill categories in `config.json`.
- Counts change often. Check the service file instead of relying on a number in this guide.
