/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.archive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.*;

/**
 * Downloads, caches, and extracts old Fabric/NeoForge API JARs for embedding into legacy mods.
 */
public class ApiArchiveManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("retromod-archive");

    private static final Path ARCHIVE_DIR = Path.of("config/retromod/api-archive");

    private static final String FABRIC_MAVEN = "https://maven.fabricmc.net/";
    private static final String NEOFORGE_MAVEN = "https://maven.neoforged.net/releases/";
    private static final String FORGE_MAVEN = "https://maven.minecraftforge.net/";
    private static final String MAVEN_CENTRAL = "https://repo1.maven.org/maven2/";

    private static final Map<String, String> FABRIC_API_VERSIONS = new LinkedHashMap<>() {{
        put("1.21", "0.100.0+1.21");
        put("1.21.1", "0.102.0+1.21.1");
        put("1.21.2", "0.106.0+1.21.2");
        put("1.21.3", "0.107.0+1.21.3");
        put("1.21.4", "0.110.0+1.21.4");
        put("1.21.5", "0.115.0+1.21.5");
        put("1.21.6", "0.120.0+1.21.6");
        put("1.21.7", "0.125.0+1.21.7");
        put("1.21.8", "0.130.0+1.21.8");
        put("1.21.9", "0.134.0+1.21.9");
        put("1.21.10", "0.138.0+1.21.10");
        put("1.21.11", "0.141.0+1.21.11");
    }};

    private static final Map<String, String> NEOFORGE_VERSIONS = new LinkedHashMap<>() {{
        put("1.21", "21.0.0-beta");
        put("1.21.1", "21.1.0");
        put("1.21.3", "21.3.0");
        put("1.21.4", "21.4.0");
        put("1.21.5", "21.5.0");
        put("1.21.6", "21.6.0");
        put("1.21.7", "21.7.0");
        put("1.21.8", "21.8.0");
        put("1.21.9", "21.9.0");
        put("1.21.10", "21.10.0");
        put("1.21.11", "21.11.0");
    }};

    private final Map<String, Map<String, byte[]>> archiveCache = new ConcurrentHashMap<>();

    private final ExecutorService downloadExecutor = Executors.newFixedThreadPool(2);

    public ApiArchiveManager() {
        try {
            Files.createDirectories(ARCHIVE_DIR);
        } catch (IOException e) {
            LOGGER.error("Could not create archive directory", e);
        }
    }
    
    /**
     * Returns a class from an archived API version, loading the archive from disk if not cached.
     */
    public byte[] getArchivedClass(String loaderType, String mcVersion, String className) {
        String archiveKey = loaderType + "-" + mcVersion;

        Map<String, byte[]> archive = archiveCache.get(archiveKey);
        if (archive != null && archive.containsKey(className)) {
            return archive.get(className);
        }

        try {
            loadArchive(loaderType, mcVersion);
            archive = archiveCache.get(archiveKey);
            if (archive != null) {
                return archive.get(className);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load archive for {} {}", loaderType, mcVersion, e);
        }
        
        return null;
    }
    
    /**
     * Loads an archive from disk into the memory cache. Never downloads: if the archive isn't already
     * present it throws, pointing at {@link #downloadArchiveWithUserConsent}.
     */
    public void loadArchive(String loaderType, String mcVersion) throws IOException {
        String archiveKey = loaderType + "-" + mcVersion;

        if (archiveCache.containsKey(archiveKey)) {
            return;
        }

        Path archivePath = getArchivePath(loaderType, mcVersion);

        if (!Files.exists(archivePath)) {
            throw new IOException("API archive not present locally for "
                + loaderType + " " + mcVersion + " at " + archivePath + ". "
                + "Retromod does not auto-download archives - see "
                + "ApiArchiveManager.downloadArchiveWithUserConsent for the "
                + "explicit-consent download path, or manually place a JAR "
                + "at the path above.");
        }

        Map<String, byte[]> classes = extractClasses(archivePath);
        archiveCache.put(archiveKey, classes);

        LOGGER.info("Loaded archive {} with {} classes", archiveKey, classes.size());
    }

    /**
     * Downloads an archive only after the consent gate passes. The supplier is invoked after the URL is
     * resolved and logged, so the user sees the destination before consenting. This is the only path
     * that initiates an outbound HTTP request.
     *
     * @return true if the download succeeded; false if the user declined, the file already existed, or
     *         the download failed.
     */
    public boolean downloadArchiveWithUserConsent(String loaderType, String mcVersion,
                                                    java.util.function.BooleanSupplier consentSupplier)
            throws IOException {
        Path archivePath = getArchivePath(loaderType, mcVersion);
        if (Files.exists(archivePath)) {
            return false;
        }

        String url = getDownloadUrl(loaderType, mcVersion);
        LOGGER.info("Awaiting user consent to download: {} (for {} {})",
                url, loaderType, mcVersion);

        if (!consentSupplier.getAsBoolean()) {
            LOGGER.info("User declined download of {}", url);
            return false;
        }

        downloadArchive(loaderType, mcVersion, archivePath);
        return Files.exists(archivePath);
    }

    /**
     * Low-level HTTP download. Only call through {@link #downloadArchiveWithUserConsent}, which holds
     * the consent gate.
     */
    private void downloadArchive(String loaderType, String mcVersion, Path targetPath)
            throws IOException {

        String url = getDownloadUrl(loaderType, mcVersion);

        LOGGER.info("Downloading archive: {}", url);

        try {
            URL downloadUrl = URI.create(url).toURL();
            HttpURLConnection conn = (HttpURLConnection) downloadUrl.openConnection();
            conn.setRequestProperty("User-Agent", "Retromod/1.0");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);

            if (conn.getResponseCode() != 200) {
                throw new IOException("Failed to download: HTTP " + conn.getResponseCode());
            }

            try (InputStream in = conn.getInputStream();
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(targetPath.toFile()))) {

                byte[] buffer = new byte[8192];
                int read;
                long total = 0;

                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    total += read;
                }

                LOGGER.info("Downloaded {} bytes to {}", total, targetPath.getFileName());
            }

        } catch (Exception e) {
            Files.deleteIfExists(targetPath);
            throw new IOException("Download failed: " + url, e);
        }
    }

    private String getDownloadUrl(String loaderType, String mcVersion) {
        return switch (loaderType.toLowerCase()) {
            case "fabric" -> getFabricApiUrl(mcVersion);
            case "neoforge" -> getNeoForgeUrl(mcVersion);
            case "forge" -> getForgeUrl(mcVersion);
            default -> throw new IllegalArgumentException("Unknown loader type: " + loaderType);
        };
    }
    
    private String getFabricApiUrl(String mcVersion) {
        String apiVersion = FABRIC_API_VERSIONS.get(mcVersion);
        if (apiVersion == null) {
            throw new IllegalArgumentException("No Fabric API version known for MC " + mcVersion);
        }

        return FABRIC_MAVEN +
               "net/fabricmc/fabric-api/fabric-api/" + apiVersion + 
               "/fabric-api-" + apiVersion + ".jar";
    }
    
    private String getNeoForgeUrl(String mcVersion) {
        String nfVersion = NEOFORGE_VERSIONS.get(mcVersion);
        if (nfVersion == null) {
            throw new IllegalArgumentException("No NeoForge version known for MC " + mcVersion);
        }

        return NEOFORGE_MAVEN +
               "net/neoforged/neoforge/" + nfVersion + 
               "/neoforge-" + nfVersion + ".jar";
    }
    
    private String getForgeUrl(String mcVersion) {
        throw new UnsupportedOperationException(
            "Legacy Forge not supported for 1.21+. Use NeoForge instead.");
    }

    private Path getArchivePath(String loaderType, String mcVersion) {
        return ARCHIVE_DIR.resolve(loaderType + "-" + mcVersion + ".jar");
    }

    private Map<String, byte[]> extractClasses(Path jarPath) throws IOException {
        Map<String, byte[]> classes = new HashMap<>();

        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();

            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();

                if (entry.getName().endsWith(".class")) {
                    try (InputStream is = jar.getInputStream(entry)) {
                        String className = entry.getName().replace(".class", "");
                        classes.put(className, is.readAllBytes());
                    }
                }
            }
        }

        return classes;
    }

    /**
     * Pre-downloads archives for all known versions, but only if the consent gate passes. The supplier
     * is consulted once before any download starts; on denial the future completes immediately with no
     * network activity.
     */
    public CompletableFuture<Void> preloadAllArchives(java.util.function.BooleanSupplier consentSupplier) {
        if (!consentSupplier.getAsBoolean()) {
            LOGGER.info("User declined preload of all API archives");
            return CompletableFuture.completedFuture(null);
        }

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (String mcVersion : FABRIC_API_VERSIONS.keySet()) {
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    Path path = getArchivePath("fabric", mcVersion);
                    if (!Files.exists(path)) {
                        downloadArchive("fabric", mcVersion, path);
                    }
                } catch (Exception e) {
                    LOGGER.warn("Failed to preload Fabric API for {}", mcVersion, e);
                }
            }, downloadExecutor));
        }

        for (String mcVersion : NEOFORGE_VERSIONS.keySet()) {
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    Path path = getArchivePath("neoforge", mcVersion);
                    if (!Files.exists(path)) {
                        downloadArchive("neoforge", mcVersion, path);
                    }
                } catch (Exception e) {
                    LOGGER.warn("Failed to preload NeoForge for {}", mcVersion, e);
                }
            }, downloadExecutor));
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }
    
    /**
     * Returns all classes in an API whose name starts with the given package prefix.
     */
    public List<String> findClasses(String loaderType, String mcVersion, String packagePattern) {
        String archiveKey = loaderType + "-" + mcVersion;
        Map<String, byte[]> archive = archiveCache.get(archiveKey);

        if (archive == null) {
            try {
                loadArchive(loaderType, mcVersion);
                archive = archiveCache.get(archiveKey);
            } catch (Exception e) {
                return Collections.emptyList();
            }
        }

        if (archive == null) return Collections.emptyList();

        String pattern = packagePattern.replace('.', '/');
        List<String> matches = new ArrayList<>();

        for (String className : archive.keySet()) {
            if (className.startsWith(pattern)) {
                matches.add(className);
            }
        }
        
        return matches;
    }
    
    /**
     * Compares two API versions, returning the removed, added, and modified classes.
     */
    public ApiDiff compareVersions(String loaderType, String oldVersion, String newVersion)
            throws IOException {

        loadArchive(loaderType, oldVersion);
        loadArchive(loaderType, newVersion);

        Map<String, byte[]> oldClasses = archiveCache.get(loaderType + "-" + oldVersion);
        Map<String, byte[]> newClasses = archiveCache.get(loaderType + "-" + newVersion);

        Set<String> removed = new HashSet<>(oldClasses.keySet());
        removed.removeAll(newClasses.keySet());

        Set<String> added = new HashSet<>(newClasses.keySet());
        added.removeAll(oldClasses.keySet());

        Set<String> modified = new HashSet<>();
        for (String className : oldClasses.keySet()) {
            if (newClasses.containsKey(className)) {
                byte[] oldBytes = oldClasses.get(className);
                byte[] newBytes = newClasses.get(className);
                if (!Arrays.equals(oldBytes, newBytes)) {
                    modified.add(className);
                }
            }
        }
        
        return new ApiDiff(oldVersion, newVersion, removed, added, modified);
    }

    public void shutdown() {
        downloadExecutor.shutdown();
        try {
            downloadExecutor.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            downloadExecutor.shutdownNow();
        }
    }

    public void clearCache() {
        archiveCache.clear();
    }

    /** Class count per loaded archive. */
    public Map<String, Integer> getCacheStats() {
        Map<String, Integer> stats = new HashMap<>();
        for (var entry : archiveCache.entrySet()) {
            stats.put(entry.getKey(), entry.getValue().size());
        }
        return stats;
    }

    public record ApiDiff(
        String oldVersion,
        String newVersion,
        Set<String> removedClasses,
        Set<String> addedClasses,
        Set<String> modifiedClasses
    ) {
        public boolean hasChanges() {
            return !removedClasses.isEmpty() || !addedClasses.isEmpty() || !modifiedClasses.isEmpty();
        }
        
        public int totalChanges() {
            return removedClasses.size() + addedClasses.size() + modifiedClasses.size();
        }
    }
}
