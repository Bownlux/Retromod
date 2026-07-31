/*
 * Compile-time stub for NeoForge's @Mod annotation.
 *
 * Same trick as the Forge stub: this lets RetromodNeoForge compile when
 * NeoForge isn't on the classpath (CLI / standalone build), and at RUNTIME
 * NeoForge's real annotation class shadows the stub.
 *
 * Important: this .class file must be excluded from the production JAR
 * (maven-jar-plugin <excludes> in pom.xml). If both this stub and
 * NeoForge's real Mod class end up on the classpath, NeoForge's secure
 * classloader fails when looking up @Mod-annotated classes - same family
 * of failure as the Forge variant.
 */
package net.neoforged.fml.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Mod {
    String value();
}
