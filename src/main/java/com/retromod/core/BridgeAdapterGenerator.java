/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.core;

import org.objectweb.asm.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * <p>When MC changes a method's parameter types, an old mod's body still uses the old
 * types and a plain rename fails JVM verification. So the original is renamed to
 * {@code methodName$bridge_old} and a new bridge method takes the original name with the
 * new signature, unpacking the event object into primitives and forwarding to the original.
 *
 * <p>Hooks into {@link RetromodTransformer}'s visitor pipeline: {@link #checkAndRename}
 * from {@code visitMethod()}, {@link #generateBridges} from {@code visitEnd()}.
 *
 * <p>Not thread-safe: one instance per class transformation, {@link #reset()} before reuse.
 * The static {@link #BRIDGES} list and static lookups are safe for concurrent use.
 *
 * <p>Must not reference {@code Retromod} directly: the transformer also runs from the
 * standalone CLI where Fabric classes are absent.
 */
public class BridgeAdapterGenerator {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Bridge");

    // 26.1 event types that replaced primitive params in the input event overhaul.
    private static final String MOUSE_EVENT = "net/minecraft/client/input/MouseButtonEvent";
    private static final String KEY_EVENT = "net/minecraft/client/input/KeyEvent";
    private static final String CHAR_EVENT = "net/minecraft/client/input/CharacterEvent";

    /**
     * One signature change.
     *
     * @param methodName    method name (unchanged across versions)
     * @param oldDescriptor old JVM descriptor
     * @param newDescriptor new JVM descriptor using event objects
     * @param bridgeOldName name given to the renamed original
     */
    public record BridgeDef(
        String methodName,
        String oldDescriptor,
        String newDescriptor,
        String bridgeOldName
    ) {}

    /** Signature bridges for MC 26.1's input event API overhaul. */
    private static final List<BridgeDef> BRIDGES = List.of(
        // mouseClicked(double x, double y, int button) -> mouseClicked(MouseButtonEvent, boolean)
        new BridgeDef("mouseClicked",
            "(DDI)Z",
            "(L" + MOUSE_EVENT + ";Z)Z",
            "mouseClicked$bridge_old"),

        // mouseReleased(double x, double y, int button) -> mouseReleased(MouseButtonEvent)
        new BridgeDef("mouseReleased",
            "(DDI)Z",
            "(L" + MOUSE_EVENT + ";)Z",
            "mouseReleased$bridge_old"),

        // mouseDragged(double x, double y, int button, double dx, double dy)
        //   -> mouseDragged(MouseButtonEvent, double dx, double dy)
        new BridgeDef("mouseDragged",
            "(DDIDD)Z",
            "(L" + MOUSE_EVENT + ";DD)Z",
            "mouseDragged$bridge_old"),

        // keyPressed(int key, int scancode, int modifiers) -> keyPressed(KeyEvent)
        new BridgeDef("keyPressed",
            "(III)Z",
            "(L" + KEY_EVENT + ";)Z",
            "keyPressed$bridge_old"),

        // keyReleased(int key, int scancode, int modifiers) -> keyReleased(KeyEvent)
        new BridgeDef("keyReleased",
            "(III)Z",
            "(L" + KEY_EVENT + ";)Z",
            "keyReleased$bridge_old"),

        // charTyped(char codepoint, int modifiers) -> charTyped(CharacterEvent)
        new BridgeDef("charTyped",
            "(CI)Z",
            "(L" + CHAR_EVENT + ";)Z",
            "charTyped$bridge_old")
    );

    private static final Set<String> BRIDGE_METHOD_NAMES;
    static {
        Set<String> names = new HashSet<>();
        for (BridgeDef b : BRIDGES) {
            names.add(b.methodName());
        }
        BRIDGE_METHOD_NAMES = Collections.unmodifiableSet(names);
    }

    /** Bridges to generate for the current class. */
    private final List<BridgeDef> pendingBridges = new ArrayList<>(BRIDGES.size());

    private String currentClassName;

    public BridgeAdapterGenerator() {}

    /**
     * If a method has an old signature that needs bridging, returns the name to rename it
     * to (so the bridge can take the original name with the new signature), else null.
     * Called from {@code RetromodClassVisitor.visitMethod()}.
     *
     * @param className  class being transformed (JVM internal name)
     * @param methodName the method name
     * @param descriptor the method descriptor
     */
    public String checkAndRename(String className, String methodName, String descriptor) {
        this.currentClassName = className;

        for (BridgeDef bridge : BRIDGES) {
            if (bridge.methodName().equals(methodName) && bridge.oldDescriptor().equals(descriptor)) {
                pendingBridges.add(bridge);
                LOGGER.debug("Bridge needed: {}.{}{} -> renamed to {}",
                    className, methodName, descriptor, bridge.bridgeOldName());
                return bridge.bridgeOldName();
            }
        }

        // ClassRemapper may have rewritten class names in the descriptor; fall back to
        // matching on the primitive-param shape.
        for (BridgeDef bridge : BRIDGES) {
            if (bridge.methodName().equals(methodName) && descriptorMatchesOldPattern(bridge, descriptor)) {
                pendingBridges.add(bridge);
                LOGGER.debug("Bridge needed (remapped desc): {}.{}{} -> {}",
                    className, methodName, descriptor, bridge.bridgeOldName());
                return bridge.bridgeOldName();
            }
        }
        return null;
    }

    /**
     * Emit all pending bridge methods. Called from {@code RetromodClassVisitor.visitEnd()}
     * after all original methods have been visited and renamed.
     *
     * @param cv ClassVisitor to emit bridge methods into
     */
    public void generateBridges(ClassVisitor cv) {
        for (BridgeDef bridge : pendingBridges) {
            generateBridgeMethod(cv, bridge);
        }
        if (!pendingBridges.isEmpty()) {
            LOGGER.info("Generated {} bridge adapter(s) for {}", pendingBridges.size(), currentClassName);
        }
    }

    public boolean hasPendingBridges() {
        return !pendingBridges.isEmpty();
    }

    /** Clear state before transforming the next class. */
    public void reset() {
        pendingBridges.clear();
        currentClassName = null;
    }

    /**
     * Method names with pending bridges for the current class, for super-call redirection:
     * a {@code super.mouseClicked(old params)} call must be rewritten to the new signature.
     *
     * @return unmodifiable set of method names with pending bridges
     */
    public Set<String> getBridgedMethodNames() {
        Set<String> names = new HashSet<>(pendingBridges.size());
        for (BridgeDef bridge : pendingBridges) {
            names.add(bridge.methodName());
        }
        return Collections.unmodifiableSet(names);
    }

    /** Whether a bridge definition exists for this method name. */
    public static boolean isKnownBridgeMethod(String methodName) {
        return BRIDGE_METHOD_NAMES.contains(methodName);
    }

    /**
     * Old (pre-26.1) descriptor for a bridge method, or null if none. Used by AutoFixEngine
     * to detect signature mismatches.
     */
    public static String getOldDescriptor(String methodName) {
        for (BridgeDef b : BRIDGES) {
            if (b.methodName().equals(methodName)) {
                return b.oldDescriptor();
            }
        }
        return null;
    }

    /**
     * New (26.1) descriptor for a bridge method, or null if none. Used by AutoFixEngine
     * to register the correct redirect.
     */
    public static String getNewDescriptor(String methodName) {
        for (BridgeDef b : BRIDGES) {
            if (b.methodName().equals(methodName)) {
                return b.newDescriptor();
            }
        }
        return null;
    }

    /** Match a descriptor's primitive-param shape against the old signature after remapping. */
    private boolean descriptorMatchesOldPattern(BridgeDef bridge, String descriptor) {
        return switch (bridge.methodName()) {
            case "mouseClicked", "mouseReleased" -> descriptor.equals("(DDI)Z");
            case "mouseDragged" -> descriptor.equals("(DDIDD)Z");
            case "keyPressed", "keyReleased" ->
                descriptor.equals("(III)Z") && !descriptor.contains("KeyEvent");
            case "charTyped" ->
                descriptor.equals("(CI)Z") || descriptor.equals("(II)Z");
            default -> false;
        };
    }

    /** Emit one bridge method: new 26.1 signature, delegating to the renamed original. */
    private void generateBridgeMethod(ClassVisitor cv, BridgeDef bridge) {
        MethodVisitor mv = cv.visitMethod(
            Opcodes.ACC_PUBLIC,
            bridge.methodName(),
            bridge.newDescriptor(),
            null, null);
        mv.visitCode();

        switch (bridge.methodName()) {
            case "mouseClicked" -> generateMouseClickedBridge(mv, bridge);
            case "mouseReleased" -> generateMouseReleasedBridge(mv, bridge);
            case "mouseDragged" -> generateMouseDraggedBridge(mv, bridge);
            case "keyPressed", "keyReleased" -> generateKeyBridge(mv, bridge);
            case "charTyped" -> generateCharTypedBridge(mv, bridge);
        }

        mv.visitEnd();
    }

    // mouseClicked(MouseButtonEvent, boolean) -> $bridge_old(x, y, button); consumed dropped.
    private void generateMouseClickedBridge(MethodVisitor mv, BridgeDef bridge) {
        mv.visitVarInsn(Opcodes.ALOAD, 0);

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MOUSE_EVENT, "x", "()D", false);

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MOUSE_EVENT, "y", "()D", false);

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MOUSE_EVENT, "button", "()I", false);

        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, currentClassName,
            bridge.bridgeOldName(), "(DDI)Z", false);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(6, 3);
    }

    // mouseReleased(MouseButtonEvent) -> $bridge_old(x, y, button).
    private void generateMouseReleasedBridge(MethodVisitor mv, BridgeDef bridge) {
        mv.visitVarInsn(Opcodes.ALOAD, 0);

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MOUSE_EVENT, "x", "()D", false);

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MOUSE_EVENT, "y", "()D", false);

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MOUSE_EVENT, "button", "()I", false);

        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, currentClassName,
            bridge.bridgeOldName(), "(DDI)Z", false);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(6, 2);
    }

    // mouseDragged(MouseButtonEvent, dragX, dragY) -> $bridge_old(x, y, button, dragX, dragY).
    private void generateMouseDraggedBridge(MethodVisitor mv, BridgeDef bridge) {
        mv.visitVarInsn(Opcodes.ALOAD, 0);

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MOUSE_EVENT, "x", "()D", false);

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MOUSE_EVENT, "y", "()D", false);

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MOUSE_EVENT, "button", "()I", false);

        mv.visitVarInsn(Opcodes.DLOAD, 2);  // dragX
        mv.visitVarInsn(Opcodes.DLOAD, 4);  // dragY

        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, currentClassName,
            bridge.bridgeOldName(), "(DDIDD)Z", false);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(10, 6);
    }

    // keyPressed/keyReleased(KeyEvent) -> $bridge_old(key, scancode, modifiers). Same shape for both.
    private void generateKeyBridge(MethodVisitor mv, BridgeDef bridge) {
        mv.visitVarInsn(Opcodes.ALOAD, 0);

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, KEY_EVENT, "key", "()I", false);

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, KEY_EVENT, "scancode", "()I", false);

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, KEY_EVENT, "modifiers", "()I", false);

        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, currentClassName,
            bridge.bridgeOldName(), "(III)Z", false);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(4, 2);
    }

    // charTyped(CharacterEvent) -> $bridge_old((char) codepoint, 0); modifiers gone in 26.1.
    private void generateCharTypedBridge(MethodVisitor mv, BridgeDef bridge) {
        mv.visitVarInsn(Opcodes.ALOAD, 0);

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CHAR_EVENT, "codepoint", "()I", false);
        mv.visitInsn(Opcodes.I2C);

        mv.visitInsn(Opcodes.ICONST_0);  // modifiers = 0

        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, currentClassName,
            bridge.bridgeOldName(), "(CI)Z", false);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(3, 2);
    }
}
