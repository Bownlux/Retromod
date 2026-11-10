// Compile-only stub of a Forge 26.2 host type for src/legacyapi; never packaged.
package net.minecraftforge.fml.common;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import net.minecraftforge.api.distmarker.Dist;

@Retention(RetentionPolicy.RUNTIME)
public @interface Mod {
    String value();

    @Retention(RetentionPolicy.RUNTIME)
    @interface EventBusSubscriber {
        Dist[] value() default {Dist.CLIENT, Dist.DEDICATED_SERVER};

        String modid() default "";

        Bus bus() default Bus.BOTH;

        enum Bus {
            FORGE,
            MOD,
            BOTH
        }
    }
}
