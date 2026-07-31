/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Advanced Mixin Target Redirector
 * 
 * YOUR IDEA IMPLEMENTED:
 * Retromod acts as the "middle man" between old Mixin targets and new MC code.
 * 
 * HOW IT WORKS:
 * 1. Mixin class has @Inject(method = "oldMethod")
 * 2. Retromod intercepts the class during transformation
 * 3. Finds the annotation, looks up "oldMethod" in our mapping
 * 4. Changes annotation to "newMethod"
 * 5. Mixin now targets the correct method!
 * 
 * This is basically what you described: we're the translator/interceptor.
 */
public class MixinTargetRedirector {
    
    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-MixinRedirect");
    
    // Mapping: "OldClass.oldMethod" -> "NewClass.newMethod"
    // This is the "translation table" you described
    private static final Map<String, String> METHOD_REDIRECTS = new ConcurrentHashMap<>();
    
    // Mapping: "OldClass.oldMethodDesc" -> "NewClass.newMethodDesc" (with signatures)
    private static final Map<String, String> METHOD_SIGNATURE_REDIRECTS = new ConcurrentHashMap<>();
    
    // Mapping: "old/package/OldClass" -> "new/package/NewClass"
    private static final Map<String, String> CLASS_REDIRECTS = new ConcurrentHashMap<>();
    
    static {
        // Method renames from 1.20.x to 1.21.x
        
        // Block breaking
        METHOD_REDIRECTS.put("breakBlock", "destroyBlock");
        METHOD_REDIRECTS.put("onBlockBreak", "onDestroyBlock");
        
        // Entity methods
        METHOD_REDIRECTS.put("onEntityCollision", "entityInside");
        METHOD_REDIRECTS.put("canEntitySpawn", "isValidSpawn");
        METHOD_REDIRECTS.put("getEntityWorld", "level");
        
        // Player methods
        METHOD_REDIRECTS.put("onPlayerInteract", "useOn");
        METHOD_REDIRECTS.put("getHeldItem", "getItemInHand");
        METHOD_REDIRECTS.put("getHeldItemMainhand", "getMainHandItem");
        METHOD_REDIRECTS.put("getHeldItemOffhand", "getOffhandItem");
        
        // World methods
        METHOD_REDIRECTS.put("setBlockState", "setBlock");
        METHOD_REDIRECTS.put("getBlockState", "getBlockState"); // Same but signature changed
        METHOD_REDIRECTS.put("notifyBlockUpdate", "sendBlockUpdated");
        METHOD_REDIRECTS.put("markBlockRangeForRenderUpdate", "setBlocksDirty");
        
        // Rendering (1.20 → 1.21 had big changes)
        METHOD_REDIRECTS.put("render", "render"); // Same name, different params
        METHOD_REDIRECTS.put("renderTileEntity", "render");
        METHOD_REDIRECTS.put("renderTileEntityAt", "render");
        METHOD_REDIRECTS.put("renderModel", "renderSingleBlock");
        
        // Inventory
        METHOD_REDIRECTS.put("getStackInSlot", "getItem");
        METHOD_REDIRECTS.put("setInventorySlotContents", "setItem");
        METHOD_REDIRECTS.put("getSizeInventory", "getContainerSize");
        
        // Network
        METHOD_REDIRECTS.put("sendPacket", "send");
        METHOD_REDIRECTS.put("handlePacket", "handle");
        
        // Classes that moved or changed names
        
        CLASS_REDIRECTS.put("net/minecraft/util/math/BlockPos", "net/minecraft/core/BlockPos");
        CLASS_REDIRECTS.put("net/minecraft/util/math/Vec3d", "net/minecraft/world/phys/Vec3");
        CLASS_REDIRECTS.put("net/minecraft/world/World", "net/minecraft/world/level/Level");
        CLASS_REDIRECTS.put("net/minecraft/entity/player/EntityPlayer", "net/minecraft/world/entity/player/Player");
        CLASS_REDIRECTS.put("net/minecraft/item/ItemStack", "net/minecraft/world/item/ItemStack");
        CLASS_REDIRECTS.put("net/minecraft/block/state/IBlockState", "net/minecraft/world/level/block/state/BlockState");
        CLASS_REDIRECTS.put("net/minecraft/tileentity/TileEntity", "net/minecraft/world/level/block/entity/BlockEntity");
        CLASS_REDIRECTS.put("net/minecraft/client/renderer/tileentity/TileEntityRenderer", 
                           "net/minecraft/client/renderer/blockentity/BlockEntityRenderer");
        
        // Signature changes use "methodName|oldDesc" -> "newMethodName|newDesc".
        
        // Example: render method gained a parameter
        METHOD_SIGNATURE_REDIRECTS.put(
            "render|(Lnet/minecraft/client/util/math/MatrixStack;F)V",
            "render|(Lnet/minecraft/client/gui/GuiGraphics;IF)V"
        );
    }
    
    /**
     * Transform a Mixin class to redirect targets to new methods.
     * 
     * This is the "interceptor" you described!
     */
    public byte[] transformMixinClass(byte[] classBytes) {
        try {
            ClassReader reader = new ClassReader(classBytes);
            ClassNode classNode = new ClassNode();
            reader.accept(classNode, 0);
            
            boolean modified = false;
            int redirectCount = 0;
            
            // Check if this is a Mixin class
            if (!isMixinClass(classNode)) {
                return classBytes; // Not a Mixin, don't transform
            }
            
            // Transform @Mixin annotation (target class)
            modified |= transformMixinAnnotation(classNode);
            
            // Transform each method's annotations
            for (MethodNode method : classNode.methods) {
                int before = redirectCount;
                redirectCount += transformMethodAnnotations(method);
                if (redirectCount > before) modified = true;
            }
            
            if (modified) {
                LOGGER.debug("Redirected {} Mixin target(s) in {}", redirectCount, classNode.name);
                
                ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
                classNode.accept(writer);
                return writer.toByteArray();
            }
            
            return classBytes;
            
        } catch (Exception e) {
            LOGGER.debug("Could not transform Mixin class: {}", e.getMessage());
            return classBytes;
        }
    }
    
    /**
     * Check if class has @Mixin annotation.
     */
    private boolean isMixinClass(ClassNode classNode) {
        if (classNode.visibleAnnotations == null) return false;
        
        for (AnnotationNode ann : classNode.visibleAnnotations) {
            if (ann.desc.contains("spongepowered/asm/mixin/Mixin")) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Transform @Mixin(TargetClass.class) to point to new class.
     */
    private boolean transformMixinAnnotation(ClassNode classNode) {
        if (classNode.visibleAnnotations == null) return false;
        
        boolean modified = false;
        
        for (AnnotationNode ann : classNode.visibleAnnotations) {
            if (ann.desc.contains("spongepowered/asm/mixin/Mixin")) {
                modified |= transformAnnotationValues(ann, "value", true);
                modified |= transformAnnotationValues(ann, "targets", false);
            }
        }
        
        return modified;
    }
    
    /**
     * Transform annotation values (class references).
     */
    private boolean transformAnnotationValues(AnnotationNode ann, String key, boolean isClassType) {
        if (ann.values == null) return false;
        
        boolean modified = false;
        
        for (int i = 0; i < ann.values.size(); i += 2) {
            String name = (String) ann.values.get(i);
            Object value = ann.values.get(i + 1);
            
            if (name.equals(key)) {
                if (value instanceof List) {
                    List<?> list = (List<?>) value;
                    List<Object> newList = new ArrayList<>();
                    
                    for (Object item : list) {
                        Object transformed = transformClassReference(item, isClassType);
                        newList.add(transformed);
                        if (transformed != item) modified = true;
                    }
                    
                    ann.values.set(i + 1, newList);
                } else {
                    Object transformed = transformClassReference(value, isClassType);
                    if (transformed != value) {
                        ann.values.set(i + 1, transformed);
                        modified = true;
                    }
                }
            }
        }
        
        return modified;
    }
    
    /**
     * Transform a class reference to new class.
     */
    private Object transformClassReference(Object ref, boolean isType) {
        if (isType && ref instanceof Type) {
            Type type = (Type) ref;
            String className = type.getInternalName();
            String newClass = CLASS_REDIRECTS.get(className);
            
            if (newClass != null) {
                LOGGER.debug("  Redirecting Mixin target: {} → {}", className, newClass);
                return Type.getObjectType(newClass);
            }
        } else if (ref instanceof String) {
            String className = (String) ref;
            String internal = className.replace('.', '/');
            String newClass = CLASS_REDIRECTS.get(internal);
            
            if (newClass != null) {
                LOGGER.debug("  Redirecting Mixin target: {} → {}", className, newClass.replace('/', '.'));
                return newClass.replace('/', '.');
            }
        }
        
        return ref;
    }
    
    /**
     * Transform method annotations (@Inject, @Redirect, etc.)
     * 
     * This is the core of your idea: redirecting method targets!
     */
    private int transformMethodAnnotations(MethodNode method) {
        int count = 0;
        
        if (method.visibleAnnotations == null) return 0;
        
        for (AnnotationNode ann : method.visibleAnnotations) {
            // Handle @Inject, @Redirect, @ModifyVariable, @ModifyArg, etc.
            if (isMixinMethodAnnotation(ann.desc)) {
                count += transformMethodTargets(ann);
            }
        }
        
        return count;
    }
    
    /**
     * Check if annotation is a Mixin method annotation.
     */
    private boolean isMixinMethodAnnotation(String desc) {
        return desc.contains("Inject") ||
               desc.contains("Redirect") ||
               desc.contains("ModifyVariable") ||
               desc.contains("ModifyArg") ||
               desc.contains("ModifyArgs") ||
               desc.contains("ModifyConstant") ||
               desc.contains("ModifyExpressionValue") ||
               desc.contains("Overwrite") ||
               desc.contains("Accessor") ||
               desc.contains("Invoker");
    }
    
    /**
     * Transform the "method" value in Mixin annotations.
     * 
     * @Inject(method = "oldMethod") → @Inject(method = "newMethod")
     */
    private int transformMethodTargets(AnnotationNode ann) {
        if (ann.values == null) return 0;
        
        int count = 0;
        
        for (int i = 0; i < ann.values.size(); i += 2) {
            String name = (String) ann.values.get(i);
            Object value = ann.values.get(i + 1);
            
            if (name.equals("method")) {
                if (value instanceof List) {
                    List<?> methods = (List<?>) value;
                    List<String> newMethods = new ArrayList<>();
                    
                    for (Object m : methods) {
                        String oldMethod = (String) m;
                        String newMethod = redirectMethodTarget(oldMethod);
                        newMethods.add(newMethod);
                        if (!newMethod.equals(oldMethod)) count++;
                    }
                    
                    ann.values.set(i + 1, newMethods);
                    
                } else if (value instanceof String) {
                    String oldMethod = (String) value;
                    String newMethod = redirectMethodTarget(oldMethod);
                    
                    if (!newMethod.equals(oldMethod)) {
                        ann.values.set(i + 1, newMethod);
                        count++;
                    }
                }
            }
        }
        
        return count;
    }
    
    /**
     * Redirect a method target string.
     * 
     * Handles formats:
     * - "methodName" → simple name
     * - "methodName(Larg;)V" → name with descriptor
     * - "Lowner;methodName(Larg;)V" → full reference
     */
    private String redirectMethodTarget(String target) {
        // Check for simple name redirect
        if (!target.contains("(") && !target.contains(";")) {
            String redirect = METHOD_REDIRECTS.get(target);
            if (redirect != null) {
                LOGGER.debug("  Redirecting method: {} → {}", target, redirect);
                return redirect;
            }
            return target;
        }
        
        // Check for name+descriptor redirect
        String key = extractMethodKey(target);
        String redirect = METHOD_SIGNATURE_REDIRECTS.get(key);
        if (redirect != null) {
            String newTarget = applySignatureRedirect(target, redirect);
            LOGGER.debug("  Redirecting method+sig: {} → {}", target, newTarget);
            return newTarget;
        }
        
        // Try just redirecting the method name part
        String methodName = extractMethodName(target);
        String newName = METHOD_REDIRECTS.get(methodName);
        if (newName != null) {
            String newTarget = target.replace(methodName, newName);
            LOGGER.debug("  Redirecting method name: {} → {}", target, newTarget);
            return newTarget;
        }
        
        return target;
    }
    
    /**
     * Extract method name from target string.
     */
    private String extractMethodName(String target) {
        // "methodName(Larg;)V" → "methodName"
        int parenIndex = target.indexOf('(');
        if (parenIndex > 0) {
            // Check for owner prefix "Lowner;methodName"
            int semiIndex = target.lastIndexOf(';', parenIndex);
            if (semiIndex > 0) {
                return target.substring(semiIndex + 1, parenIndex);
            }
            return target.substring(0, parenIndex);
        }
        return target;
    }
    
    /**
     * Extract method key for signature redirect lookup.
     */
    private String extractMethodKey(String target) {
        String name = extractMethodName(target);
        int parenIndex = target.indexOf('(');
        if (parenIndex > 0) {
            String desc = target.substring(parenIndex);
            return name + "|" + desc;
        }
        return name;
    }
    
    /**
     * Apply a signature redirect to a target.
     */
    private String applySignatureRedirect(String target, String redirect) {
        String[] parts = redirect.split("\\|");
        if (parts.length == 2) {
            String newName = parts[0];
            String newDesc = parts[1];
            
            // Rebuild target with new name and descriptor
            int parenIndex = target.indexOf('(');
            if (parenIndex > 0) {
                String prefix = "";
                int semiIndex = target.lastIndexOf(';', parenIndex);
                if (semiIndex > 0) {
                    prefix = target.substring(0, semiIndex + 1);
                }
                return prefix + newName + newDesc;
            }
            return newName + newDesc;
        }
        return target;
    }
    
    // Redirect registration
    
    /**
     * Register a method name redirect.
     */
    public static void registerMethodRedirect(String oldMethod, String newMethod) {
        METHOD_REDIRECTS.put(oldMethod, newMethod);
    }
    
    /**
     * Register a method signature redirect.
     */
    public static void registerSignatureRedirect(String oldMethod, String oldDesc, 
                                                  String newMethod, String newDesc) {
        METHOD_SIGNATURE_REDIRECTS.put(oldMethod + "|" + oldDesc, newMethod + "|" + newDesc);
    }
    
    /**
     * Register a class redirect.
     */
    public static void registerClassRedirect(String oldClass, String newClass) {
        CLASS_REDIRECTS.put(oldClass.replace('.', '/'), newClass.replace('.', '/'));
    }
    
    /**
     * Load redirects from a mapping file (for AOT pre-computation).
     */
    public static void loadRedirectsFromMappings(java.nio.file.Path mappingFile) {
        // TODO: Load from Yarn/Mojmap diff files
        // This would allow automatic generation of redirects
        LOGGER.info("Loading redirects from: {}", mappingFile);
    }
}
