/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26;

import com.retromod.core.RetromodVersion;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** The host Fabric API signatures of one 26.x line, read from the bundled {@code surface-<line>.tsv}. */
public final class HostSurface {

    /** Host lines with a bundled surface file, oldest first. */
    public static final List<String> INDEXED_LINES = List.of("26.1", "26.2", "26.3", "26.4");

    /** The answer for a member lookup. */
    public enum Presence { PRESENT, MISSING, UNKNOWN }

    /** Public methods every class inherits from {@code java/lang/Object}. */
    private static final Set<String> OBJECT_METHODS = Set.of("equals(Ljava/lang/Object;)Z",
            "hashCode()I", "toString()Ljava/lang/String;", "getClass()Ljava/lang/Class;",
            "notify()V", "notifyAll()V", "wait()V", "wait(J)V", "wait(JI)V");

    private static final String RESOURCE_PATTERN = "retromod/loaderapi/fabric-26x/surface-%s.tsv";
    private static final Map<String, HostSurface> LOADED = new ConcurrentHashMap<>();

    private final String line;
    private final String minFabricApi;
    private final String minFabricLoader;
    private final Map<String, String[]> classes = new HashMap<>();
    private final Set<String> members = new HashSet<>();

    private HostSurface(String line, String minFabricApi, String minFabricLoader) {
        this.line = line;
        this.minFabricApi = minFabricApi;
        this.minFabricLoader = minFabricLoader;
    }

    /** The surface for {@code hostVersion}; a host past the newest indexed line uses the newest. */
    public static HostSurface forHost(String hostVersion) {
        return forLine(lineFor(hostVersion));
    }

    /** The indexed line that serves {@code hostVersion}, such as 26.1 for 26.1.2. */
    public static String lineFor(String hostVersion) {
        String chosen = INDEXED_LINES.get(0);
        for (String line : INDEXED_LINES) {
            if (RetromodVersion.compareMcVersions(majorMinor(hostVersion), line) >= 0) chosen = line;
        }
        return chosen;
    }

    /** The surface of one indexed line. */
    public static HostSurface forLine(String line) {
        return LOADED.computeIfAbsent(line, HostSurface::load);
    }

    private static String majorMinor(String version) {
        String[] parts = version == null ? new String[0] : version.trim().split("[.-]");
        return parts.length >= 2 ? parts[0] + "." + parts[1] : version;
    }

    private static HostSurface load(String line) {
        String resource = String.format(RESOURCE_PATTERN, line);
        try (InputStream in = HostSurface.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException(resource + " is missing from the Retromod jar");
            return parse(line, in);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + resource, e);
        }
    }

    static HostSurface parse(String line, InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String minApi = null;
        String minLoader = null;
        Map<String, String[]> classRows = new HashMap<>();
        Set<String> memberRows = new HashSet<>();
        String text;
        while ((text = reader.readLine()) != null) {
            if (text.startsWith("# min_fabric_api\t")) minApi = text.substring(17).trim();
            if (text.startsWith("# min_fabric_loader\t")) minLoader = text.substring(20).trim();
            if (text.startsWith("#") || text.startsWith("kind\t") || text.isBlank()) continue;
            String[] cells = text.split("\t", -1);
            if ("C".equals(cells[0])) {
                classRows.put(cells[1], new String[]{cells[5],
                        cells[6].isEmpty() ? "" : cells[6]});
            } else {
                memberRows.add(cells[0] + " " + cells[1] + "." + cells[2]
                        + ("F".equals(cells[0]) ? "" : cells[3]));
            }
        }
        HostSurface surface = new HostSurface(line, minApi, minLoader);
        surface.classes.putAll(classRows);
        surface.members.addAll(memberRows);
        return surface;
    }

    /** The host line this surface describes. */
    public String line() {
        return line;
    }

    /** The oldest host Fabric API build this surface was generated from, such as 0.145.1. */
    public String minFabricApi() {
        return minFabricApi;
    }

    /** The Fabric Loader range the oldest build declares, such as {@code >=0.18.4}. */
    public String minFabricLoader() {
        return minFabricLoader;
    }

    /** Whether the host has the Fabric API class {@code owner}. */
    public boolean hasClass(String owner) {
        return classes.containsKey(owner);
    }

    /**
     * Whether a method ({@code desc} starting with a parenthesis) or field the code names on
     * {@code owner} resolves on the host, searching its Fabric API supertypes. UNKNOWN when the
     * search reaches a supertype outside Fabric API, which this surface does not describe.
     */
    public Presence member(String owner, String name, String desc) {
        boolean method = desc != null && desc.startsWith("(");
        String suffix = method ? name + desc : name;
        String kind = method ? "M " : "F ";
        if (!classes.containsKey(owner)) return Presence.MISSING;
        Deque<String> pending = new ArrayDeque<>(List.of(owner));
        Set<String> seen = new HashSet<>();
        boolean leftFabricApi = false;
        while (!pending.isEmpty()) {
            String current = pending.pop();
            if (!seen.add(current)) continue;
            if (members.contains(kind + current + "." + suffix)) return Presence.PRESENT;
            String[] supertypes = classes.get(current);
            if ("java/lang/Object".equals(current)) {
                if (method && OBJECT_METHODS.contains(suffix)) return Presence.PRESENT;
                continue;
            }
            if (supertypes == null) {
                leftFabricApi = true;
                continue;
            }
            if (!supertypes[0].isEmpty()) pending.add(supertypes[0]);
            for (String itf : supertypes[1].split(",")) {
                if (!itf.isEmpty()) pending.add(itf);
            }
        }
        return leftFabricApi ? Presence.UNKNOWN : Presence.MISSING;
    }

    /** Number of class and member rows, for tests. */
    int size() {
        return classes.size() + members.size();
    }
}
