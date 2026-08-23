/*
 * Retromod test support. Copyright (c) 2026 Bownlux. MIT License.
 */
package com.retromod.testutil;

import com.retromod.util.JarSignatureSanitizer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipFile;

/** Creates throwaway, genuinely signed test JARs from a non-production test key. */
public final class SignedJarTestSupport {

    private static final char[] PASSWORD = "changeit".toCharArray();
    private static final String KEYSTORE_BASE64 =
            "MIIEDgIBAzCCA7gGCSqGSIb3DQEHAaCCA6kEggOlMIIDoTCCATgGCSqGSIb3DQEHAaCCASkEggEl"
          + "MIIBITCCAR0GCyqGSIb3DQEMCgECoIG9MIG6MGYGCSqGSIb3DQEFDTBZMDgGCSqGSIb3DQEFDDAr"
          + "BBS45YxAYX1RiI2meQwk3GH+K7eNxwICJxACASAwDAYIKoZIhvcNAgkFADAdBglghkgBZQMEASoE"
          + "ENu+OnvGslLN1shwJbnkmEAEUJ0TIJW15iW+oSZsMCSRoJsXoHvZQaQ/dBE8HxeYKF2y684DsO4q"
          + "LC3VCaiRrSJ50PxGjJ7xG7y1Fppf+ROiGz53MTUYlyMA9ofJe/Nt/DJdMU4wKQYJKoZIhvcNAQkU"
          + "MRweGgByAGUAdAByAG8AbQBvAGQALQB0AGUAcwB0MCEGCSqGSIb3DQEJFTEUBBJUaW1lIDE3ODc0"
          + "NjMyOTgyMzIwggJhBgkqhkiG9w0BBwagggJSMIICTgIBADCCAkcGCSqGSIb3DQEHATBmBgkqhkiG9"
          + "w0BBQ0wWTA4BgkqhkiG9w0BBQwwKwQUg8CqP6apHB0CQf09CVL4QjzUdkcCAicQAgEgMAwGCCqG"
          + "SIb3DQIJBQAwHQYJYIZIAWUDBAEqBBC6kyY1uGecEietjCMjQ1tcgIIB0CMvAufbQ1XI/VFFSDdH"
          + "9V1rJixsUEoFUx+ulSgEVCdvQJwP33Gmmg4iJ7x5yjZ0fxv74VlJ4NZRdB7arJG7UtQvLroN1hs3"
          + "C/chd42fFT4d5SbrbuFreuN5/Qu2WSA/ZlhUuz/3IXEzPQ6AqSfQgxPf04xLZj0VpvXfmDbVLmIp"
          + "mHOhnZFPmr8lp8IL+rYVBP4Ixo/nIEct8t9e8m1kBlT1j+SbUIQ0a6CNTJYTZkxt5eqwoTqlpO5U"
          + "yorJESbgFXrrmViWe65d9FCzU9LsvQzQvoR50IKLINOeR5StRxWGyioVpnE51UoKg0xmRVObDpBW"
          + "asO+SJG4MOqpjdzPu7qWW+sQSPHyio4jEawT+9ajFRT07uhFbeZZnFljGj5E/f0thk03ZQTZplhr"
          + "EUE0c1TPZyAmUFGQUerwfkon763+CQI0kpScZp+ly/qWvKh1DY1qxRAQ8NZYr0ZwSpboSWw8T+ds"
          + "XegAIA38qLLEKfAG66DMjK83cxgTMryx6VRqBinBZxLWA+hlViEleu54TYv8KkvLwlvQ5TilSg54"
          + "GYkn2DeYBXR0nRX0vTWDMlMo7361WDZww9dWXAlw5AFVqE+WxUx17CXr8XPga6AvEDm/ME0wMTAN"
          + "BglghkgBZQMEAgEFAAQgpvjIFiya5gItQxS2qJ3bVp7DYVwOybl3Z00aOs7JFsMEFNqEKcZNW9b4"
          + "uTjHgXiefwEu4zlQAgInEA==";

    private SignedJarTestSupport() {}

    /** Writes and signs every supplied regular entry. */
    public static Path createSignedJar(Path directory, String name,
            Map<String, byte[]> entries) throws Exception {
        Path unsigned = directory.resolve("unsigned-" + name);
        Path signed = directory.resolve(name);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(unsigned))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        sign(unsigned, signed);
        Files.delete(unsigned);
        verifyEveryEntry(signed);
        return signed;
    }

    /** Rewrites one signed entry without updating its signing records. */
    public static void replaceEntryWithoutResigning(Path jarPath, String targetName,
            byte[] replacement) throws IOException {
        Path staged = Files.createTempFile(jarPath.getParent(), "mismatched-signed-", ".jar");
        try {
            try (JarFile input = new JarFile(jarPath.toFile(), false);
                 JarOutputStream output = new JarOutputStream(Files.newOutputStream(staged))) {
                var entries = input.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    output.putNextEntry(new JarEntry(entry.getName()));
                    if (!entry.isDirectory()) {
                        if (entry.getName().equals(targetName)) {
                            output.write(replacement);
                        } else {
                            try (InputStream stream = input.getInputStream(entry)) {
                                stream.transferTo(output);
                            }
                        }
                    }
                    output.closeEntry();
                }
            }
            Files.move(staged, jarPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    /** Reads every entry with standard JAR verification enabled. */
    public static void verifyEveryEntry(Path jarPath) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile(), true)) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                try (InputStream input = jar.getInputStream(entry)) {
                    input.transferTo(OutputStream.nullOutputStream());
                }
            }
        }
    }

    /** Fails if a verifier artifact or manifest digest remains. */
    public static boolean hasSigningMetadata(Path jarPath) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile(), false)) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (JarSignatureSanitizer.isSigningArtifact(entry.getName())) return true;
            }
            Manifest manifest = jar.getManifest();
            if (manifest == null) return false;
            if (hasSigningAttributes(manifest.getMainAttributes())) return true;
            return manifest.getEntries().values().stream()
                    .anyMatch(SignedJarTestSupport::hasSigningAttributes);
        }
    }

    public static Map<String, byte[]> entries(Object... namesAndBytes) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int index = 0; index < namesAndBytes.length; index += 2) {
            entries.put((String) namesAndBytes[index], (byte[]) namesAndBytes[index + 1]);
        }
        return entries;
    }

    private static boolean hasSigningAttributes(Attributes attributes) {
        for (Object key : attributes.keySet()) {
            String name = key.toString().toUpperCase(java.util.Locale.ROOT);
            if (name.equals("SIGNATURE-VERSION") || name.equals("MAGIC")
                    || name.endsWith("-DIGEST") || name.contains("-DIGEST-")) {
                return true;
            }
        }
        return false;
    }

    private static void sign(Path unsigned, Path signed) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = new ByteArrayInputStream(
                Base64.getDecoder().decode(KEYSTORE_BASE64))) {
            store.load(input, PASSWORD);
        }
        KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry) store.getEntry(
                "retromod-test", new KeyStore.PasswordProtection(PASSWORD));

        Class<?> builderType = Class.forName("jdk.security.jarsigner.JarSigner$Builder");
        Object builder = builderType.getConstructor(KeyStore.PrivateKeyEntry.class)
                .newInstance(entry);
        Object signer = builderType.getMethod("build").invoke(builder);
        try (ZipFile input = new ZipFile(unsigned.toFile());
             OutputStream output = Files.newOutputStream(signed)) {
            try {
                signer.getClass().getMethod("sign", ZipFile.class, OutputStream.class)
                        .invoke(signer, input, output);
            } catch (InvocationTargetException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof Exception exception) throw exception;
                throw failure;
            }
        }
    }
}
