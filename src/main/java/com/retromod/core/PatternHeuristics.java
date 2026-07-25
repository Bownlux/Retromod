/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 *
 * Pattern-based heuristics for resolving unknown API changes between MC versions.
 * Each rule encodes a repeatable naming-convention shift seen across versions.
 *
 * Resolution order: shim redirect tables (exact match), then these patterns,
 * then FuzzyMethodResolver. Patterns run before fuzzy resolution because string
 * comparisons are cheaper and deterministic.
 */
package com.retromod.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Resolves method, class, and field references not covered by the shim redirect
 * tables, using known naming-pattern transformations.
 *
 * <p>Rules are checked in registration order; the first match wins. Each carries
 * a confidence score (0.0-1.0): 0.95-0.99 for verified renames, 0.85-0.90 for
 * broad patterns with known exceptions, 0.60-0.70 for heuristic guesses.</p>
 *
 * <p>Thread-safe: the rule arrays are fixed after construction and match counters
 * are {@link AtomicInteger}s.</p>
 *
 * <p>Must not reference {@code Retromod} directly: the standalone CLI uses this
 * without Fabric on the classpath.</p>
 */
public class PatternHeuristics {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Patterns");

    /**
     * Result of a successful pattern match.
     *
     * @param newOwner    the redirected class (JVM internal name with '/')
     * @param newName     the redirected method/field name (null for class-only matches)
     * @param newDescriptor the redirected descriptor (null for class-only matches)
     * @param confidence  how reliable this match is (0.0-1.0)
     * @param rule        short identifier for the rule that matched (for logging)
     * @param explanation human-readable explanation of why this redirect exists
     */
    public record PatternResult(
        String newOwner,
        String newName,
        String newDescriptor,
        double confidence,
        String rule,
        String explanation
    ) {}

    private final PatternRule[] methodRules;
    private final ClassPatternRule[] classRules;
    private final PatternRule[] fieldRules;

    /** Renames GuiGraphics to GuiGraphicsExtractor, skipping already-renamed strings. */
    private static String renameGuiGraphics(String s) {
        if (s == null || !s.contains("GuiGraphics")) return s;
        if (s.contains("GuiGraphicsExtractor")) return s;
        return s.replace("GuiGraphics", "GuiGraphicsExtractor");
    }

    private final AtomicInteger methodMatches = new AtomicInteger(0);
    private final AtomicInteger classMatches = new AtomicInteger(0);
    private final AtomicInteger fieldMatches = new AtomicInteger(0);
    private final AtomicInteger totalAttempts = new AtomicInteger(0);

    public PatternHeuristics() {
        List<PatternRule> methods = new ArrayList<>();
        List<ClassPatternRule> classes = new ArrayList<>();
        List<PatternRule> fields = new ArrayList<>();

        registerMethodPatterns(methods);
        registerClassPatterns(classes);
        registerFieldPatterns(fields);

        this.methodRules = methods.toArray(new PatternRule[0]);
        this.classRules = classes.toArray(new ClassPatternRule[0]);
        this.fieldRules = fields.toArray(new PatternRule[0]);

        LOGGER.debug("Loaded {} method rules, {} class rules, {} field rules",
            methodRules.length, classRules.length, fieldRules.length);
    }

    /**
     * Resolves an unknown method reference. Returns null if no pattern applies,
     * in which case the caller falls through to {@link FuzzyMethodResolver}.
     *
     * @param owner      the class containing the method (JVM internal name)
     * @param name       the method name
     * @param descriptor the method descriptor
     * @return the pattern match result, or null if no pattern applies
     */
    public PatternResult resolveMethod(String owner, String name, String descriptor) {
        totalAttempts.incrementAndGet();
        for (PatternRule rule : methodRules) {
            PatternResult result = rule.tryMatch(owner, name, descriptor);
            if (result != null) {
                methodMatches.incrementAndGet();
                LOGGER.debug("Pattern match: {}.{}{} -> {}.{}{} (rule: {}, confidence: {})",
                    owner, name, descriptor,
                    result.newOwner(), result.newName(), result.newDescriptor(),
                    result.rule(), result.confidence());
                return result;
            }
        }
        return null;
    }

    /**
     * Resolves an unknown class reference: package relocations and class renames.
     *
     * @param className the class to resolve (JVM internal name with '/')
     * @return the pattern match result, or null if no pattern applies
     */
    public PatternResult resolveClass(String className) {
        totalAttempts.incrementAndGet();
        for (ClassPatternRule rule : classRules) {
            PatternResult result = rule.tryMatch(className);
            if (result != null) {
                classMatches.incrementAndGet();
                LOGGER.debug("Class pattern match: {} -> {} (rule: {})",
                    className, result.newOwner(), result.rule());
                return result;
            }
        }
        return null;
    }

    /**
     * Resolves an unknown field reference using pattern heuristics.
     *
     * @param owner      the class containing the field (JVM internal name)
     * @param name       the field name
     * @param descriptor the field descriptor ("J" for long, etc.)
     * @return the pattern match result, or null if no pattern applies
     */
    public PatternResult resolveField(String owner, String name, String descriptor) {
        totalAttempts.incrementAndGet();
        for (PatternRule rule : fieldRules) {
            PatternResult result = rule.tryMatch(owner, name, descriptor);
            if (result != null) {
                fieldMatches.incrementAndGet();
                LOGGER.debug("Field pattern match: {}.{} -> {}.{} (rule: {})",
                    owner, name, result.newOwner(), result.newName(), result.rule());
                return result;
            }
        }
        return null;
    }

    public int getMethodMatches() { return methodMatches.get(); }
    public int getClassMatches() { return classMatches.get(); }
    public int getFieldMatches() { return fieldMatches.get(); }
    public int getTotalAttempts() { return totalAttempts.get(); }
    public int getTotalMatches() { return methodMatches.get() + classMatches.get() + fieldMatches.get(); }

    private void registerMethodPatterns(List<PatternRule> rules) {

        // 26.1 reworked rendering into an extract-then-submit model: render*()
        // methods on GUI classes became extract*RenderState(), and GuiGraphics
        // became GuiGraphicsExtractor.

        // Screen.render -> Screen.extractRenderState
        rules.add((owner, name, desc) -> {
            if (name.equals("render") && isScreenClass(owner) && desc.contains("GuiGraphics")) {
                return new PatternResult(owner, "extractRenderState",
                    renameGuiGraphics(desc),
                    0.95, "render->extractRenderState",
                    "26.1 renamed Screen.render to extractRenderState");
            }
            return null;
        });

        // renderWidget -> extractWidgetRenderState
        rules.add((owner, name, desc) -> {
            if (name.equals("renderWidget") && desc.contains("GuiGraphics")) {
                return new PatternResult(owner, "extractWidgetRenderState",
                    renameGuiGraphics(desc),
                    0.95, "renderWidget->extractWidgetRenderState",
                    "26.1 renamed widget rendering to extract pattern");
            }
            return null;
        });

        // Generic render*() -> extract*() across the gui/ hierarchy (confidence 0.7).
        rules.add((owner, name, desc) -> {
            if (name.startsWith("render") && name.length() > 6
                && Character.isUpperCase(name.charAt(6))
                && owner.startsWith("net/minecraft/client/gui/")) {
                String suffix = name.substring(6);
                String newName = "extract" + suffix;
                return new PatternResult(owner, newName,
                    renameGuiGraphics(desc),
                    0.7, "render*->extract*",
                    "26.1 render verb migration pattern");
            }
            return null;
        });

        // The GuiGraphics -> GuiGraphicsExtractor rename also shortened several
        // drawing method names.

        // drawString -> text
        rules.add((owner, name, desc) -> {
            if (name.equals("drawString") && isGuiGraphics(owner)) {
                return new PatternResult(
                    renameGuiGraphics(owner),
                    "text", desc, 0.95, "drawString->text",
                    "26.1 renamed GuiGraphics.drawString to text()");
            }
            return null;
        });

        // drawCenteredString -> centeredText
        rules.add((owner, name, desc) -> {
            if (name.equals("drawCenteredString") && isGuiGraphics(owner)) {
                return new PatternResult(
                    renameGuiGraphics(owner),
                    "centeredText", desc, 0.95, "drawCenteredString->centeredText",
                    "26.1 renamed drawCenteredString to centeredText()");
            }
            return null;
        });

        // renderOutline -> outline
        rules.add((owner, name, desc) -> {
            if (name.equals("renderOutline") && isGuiGraphics(owner)) {
                return new PatternResult(
                    renameGuiGraphics(owner),
                    "outline", desc, 0.95, "renderOutline->outline",
                    "26.1 renamed renderOutline to outline()");
            }
            return null;
        });

        // renderItem -> item
        rules.add((owner, name, desc) -> {
            if (name.equals("renderItem") && isGuiGraphics(owner)) {
                return new PatternResult(
                    renameGuiGraphics(owner),
                    "item", desc, 0.95, "renderItem->item",
                    "26.1 simplified renderItem to item()");
            }
            return null;
        });

        // getDayTime() -> getOverworldClockTime(), Level classes, ()J only.
        rules.add((owner, name, desc) -> {
            if (name.equals("getDayTime") && desc.equals("()J")
                && (owner.contains("Level") || owner.contains("level"))) {
                return new PatternResult(owner, "getOverworldClockTime", desc,
                    0.95, "getDayTime->getOverworldClockTime",
                    "26.1 renamed Level.getDayTime()");
            }
            return null;
        });

        // displayClientMessage(Component, boolean) split into sendSystemMessage
        // (chat) and sendOverlayMessage (action bar). Default to the common chat
        // case; 0.8 since the overlay case also exists.
        rules.add((owner, name, desc) -> {
            if (name.equals("displayClientMessage") && owner.contains("player/Player")) {
                return new PatternResult(owner, "sendSystemMessage",
                    "(Lnet/minecraft/network/chat/Component;)V",
                    0.8, "displayClientMessage->sendSystemMessage",
                    "26.1 split displayClientMessage into sendSystemMessage/sendOverlayMessage");
            }
            return null;
        });

        // 26.1 made no-cull the default, dropping the *NoCull suffix
        // (entityCutoutNoCull -> entityCutout), and moved RenderType into a
        // rendertype/ subpackage.
        rules.add((owner, name, desc) -> {
            if (name.endsWith("NoCull") && owner.contains("RenderType")) {
                String newName = name.substring(0, name.length() - 6);
                return new PatternResult(
                    owner.replace("client/renderer/RenderType", "client/renderer/rendertype/RenderType"),
                    newName, desc.replace("client/renderer/RenderType", "client/renderer/rendertype/RenderType"),
                    0.85, "*NoCull->remove suffix",
                    "26.1 made no-cull the default for render types");
            }
            return null;
        });

        // Fabric's keybinding module became keymapping, matching MC's shift from
        // "key bindings" to "key mappings".
        rules.add((owner, name, desc) -> {
            if (name.equals("registerKeyBinding") && owner.contains("keybinding")) {
                return new PatternResult(
                    owner.replace("keybinding", "keymapping")
                         .replace("KeyBindingHelper", "KeyMappingHelper"),
                    "registerKeyMapping", desc, 0.95,
                    "registerKeyBinding->registerKeyMapping",
                    "26.1 Fabric API renamed keybinding to keymapping");
            }
            return null;
        });

        // 26.1 turned some Blaze3D classes into records, so getX() becomes x().
        // Catch-all for the Blaze3D package (confidence 0.6).
        rules.add((owner, name, desc) -> {
            if (name.startsWith("get") && name.length() > 3
                && Character.isUpperCase(name.charAt(3))
                && desc.startsWith("()") && !desc.equals("()V")
                && owner.startsWith("net/minecraft/")
                && owner.contains("blaze3d")) {
                String fieldName = Character.toLowerCase(name.charAt(3)) + name.substring(4);
                return new PatternResult(owner, fieldName, desc,
                    0.6, "getX->x (record accessor)",
                    "26.1 may use record-style accessors for Blaze3D classes");
            }
            return null;
        });

        // Fabric API's 26.1 networking rename moved the DIRECTION word to the FRONT and the channel
        // to the back: playS2C -> clientboundPlay, configurationS2C -> clientboundConfiguration (and
        // ...C2S -> serverbound...). A naive replace("S2C","Clientbound") produced the WRONG spelling
        // "playClientbound" (which 26.1 does not have), so a mod that fell through the explicit
        // Fabric_1_21_11_to_26_1 table died NoSuchMethodError at init (AppleSkin's SyncHandler, found
        // in an in-game 26.2 launch). Reconstruct the actual 26.1 name instead of a token replace.
        rules.add((owner, name, desc) -> {
            if (!owner.contains("networking")) return null;
            if (name.endsWith("S2C") && name.length() > 3) {
                return new PatternResult(owner, "clientbound" + capitalize(name.substring(0, name.length() - 3)),
                    desc, 0.9, "<channel>S2C->clientbound<Channel>",
                    "26.1 Fabric API: <channel>S2C -> clientbound<Channel>");
            }
            if (name.endsWith("C2S") && name.length() > 3) {
                return new PatternResult(owner, "serverbound" + capitalize(name.substring(0, name.length() - 3)),
                    desc, 0.9, "<channel>C2S->serverbound<Channel>",
                    "26.1 Fabric API: <channel>C2S -> serverbound<Channel>");
            }
            return null;
        });
    }

    private void registerClassPatterns(List<ClassPatternRule> rules) {

        // GeckoLib 5.5 package rename (third-party, but widely depended on):
        // software.bernie.geckolib -> com.geckolib
        rules.add(className -> {
            if (className.startsWith("software/bernie/geckolib")) {
                return new PatternResult(
                    className.replace("software/bernie/geckolib", "com/geckolib"),
                    null, null, 0.95, "GeckoLib package rename",
                    "GeckoLib 5.5 moved from software.bernie.geckolib to com.geckolib");
            }
            return null;
        });

        // RenderType/RenderTypes moved to rendertype/ subpackage in 26.1
        rules.add(className -> {
            if (className.equals("net/minecraft/client/renderer/RenderType")
                || className.equals("net/minecraft/client/renderer/RenderTypes")) {
                return new PatternResult(
                    className.replace("client/renderer/Render", "client/renderer/rendertype/Render"),
                    null, null, 0.95, "RenderType package move",
                    "26.1 moved RenderType to rendertype subpackage");
            }
            return null;
        });

        // GuiGraphics -> GuiGraphicsExtractor (26.1 rendering rename)
        rules.add(className -> {
            if (className.equals("net/minecraft/client/gui/GuiGraphics")) {
                return new PatternResult(
                    "net/minecraft/client/gui/GuiGraphicsExtractor",
                    null, null, 0.99, "GuiGraphics->GuiGraphicsExtractor",
                    "26.1 renamed GuiGraphics to GuiGraphicsExtractor");
            }
            return null;
        });

        // PlayerFaceRenderer -> PlayerFaceExtractor
        rules.add(className -> {
            if (className.equals("net/minecraft/client/gui/components/PlayerFaceRenderer")) {
                return new PatternResult(
                    "net/minecraft/client/gui/components/PlayerFaceExtractor",
                    null, null, 0.99, "PlayerFaceRenderer->PlayerFaceExtractor",
                    "26.1 renamed PlayerFaceRenderer to PlayerFaceExtractor");
            }
            return null;
        });

        // Fabric keybinding -> keymapping package rename
        rules.add(className -> {
            if (className.contains("fabricmc/fabric/api/client/keybinding")) {
                return new PatternResult(
                    className.replace("keybinding", "keymapping")
                             .replace("KeyBindingHelper", "KeyMappingHelper"),
                    null, null, 0.95, "keybinding->keymapping",
                    "26.1 Fabric API renamed keybinding package to keymapping");
            }
            return null;
        });

        // Fabric WorldRenderEvents -> LevelRenderEvents (World->Level migration).
        rules.add(className -> {
            if (className.contains("rendering/v1/world/WorldRenderEvents")) {
                return new PatternResult(
                    className.replace("world/WorldRenderEvents", "level/LevelRenderEvents"),
                    null, null, 0.95, "WorldRenderEvents->LevelRenderEvents",
                    "26.1 Fabric API moved world events to level package");
            }
            return null;
        });

        // Generic World -> Level in Fabric API class names; not all were renamed
        // (confidence 0.7).
        rules.add(className -> {
            if (className.contains("fabricmc/fabric/") && className.contains("World")
                && !className.contains("Level")) {
                return new PatternResult(
                    className.replace("World", "Level"),
                    null, null, 0.7, "World->Level (Fabric API)",
                    "26.1 Fabric API renamed World to Level in many classes");
            }
            return null;
        });

        // LivingEntityFeatureRendererRegistrationCallback -> LivingEntityRenderLayerRegistrationCallback
        rules.add(className -> {
            if (className.contains("LivingEntityFeatureRendererRegistrationCallback")) {
                return new PatternResult(
                    className.replace("LivingEntityFeatureRendererRegistrationCallback",
                                     "LivingEntityRenderLayerRegistrationCallback"),
                    null, null, 0.99, "LivingEntityFeatureRendererRegistrationCallback rename",
                    "26.1 Fabric API renamed the callback");
            }
            return null;
        });
    }

    private void registerFieldPatterns(List<PatternRule> rules) {

        // Window.window -> Window.handle (GLFW window handle field).
        rules.add((owner, name, desc) -> {
            if (name.equals("window") && owner.contains("blaze3d/platform/Window")) {
                return new PatternResult(owner, "handle", desc, 0.95,
                    "Window.window->handle",
                    "26.1 renamed Window.window field to handle");
            }
            return null;
        });

        // KeyMapping.boundKey -> KeyMapping.key
        rules.add((owner, name, desc) -> {
            if (name.equals("boundKey") && owner.contains("KeyMapping")) {
                return new PatternResult(owner, "key", desc, 0.95,
                    "KeyMapping.boundKey->key",
                    "26.1 renamed KeyMapping.boundKey to key");
            }
            return null;
        });

        // 26.1 split EntityType.BOAT into per-wood variants; default to OAK_BOAT.
        rules.add((owner, name, desc) -> {
            if (name.equals("BOAT") && owner.contains("EntityType")) {
                return new PatternResult(owner, "OAK_BOAT", desc, 0.85,
                    "BOAT->OAK_BOAT",
                    "26.1 split EntityType.BOAT into per-wood types");
            }
            return null;
        });

        // Same per-wood split: CHEST_BOAT -> OAK_CHEST_BOAT.
        rules.add((owner, name, desc) -> {
            if (name.equals("CHEST_BOAT") && owner.contains("EntityType")) {
                return new PatternResult(owner, "OAK_CHEST_BOAT", desc, 0.85,
                    "CHEST_BOAT->OAK_CHEST_BOAT",
                    "26.1 split EntityType.CHEST_BOAT into per-wood types");
            }
            return null;
        });
    }

    private static boolean isScreenClass(String owner) {
        return owner.contains("gui/screens/") || owner.endsWith("Screen");
    }

    /** True for GuiGraphics (pre-26.1) and GuiGraphicsExtractor (26.1). */
    private static boolean isGuiGraphics(String owner) {
        return owner.contains("GuiGraphics");
    }

    /** "play" -> "Play"; used to reorder the Fabric networking S2C/C2S rename. */
    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Method and field pattern rule; returns a match or null. */
    @FunctionalInterface
    interface PatternRule {
        PatternResult tryMatch(String owner, String name, String descriptor);
    }

    /** Class pattern rule; returns a match or null. */
    @FunctionalInterface
    interface ClassPatternRule {
        PatternResult tryMatch(String className);
    }
}
