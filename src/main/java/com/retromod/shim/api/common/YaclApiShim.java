/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.AuxiliaryVersionShim;

/** YACL (Yet Another Config Lib) v2 -> v3 API restructure. Loader-agnostic. */
public class YaclApiShim implements AuxiliaryVersionShim {
    
    @Override
    public String getShimName() {
        return "YACL API Compatibility";
    }
    
    @Override
    public String getSourceVersion() {
        return "2.0.0";
    }
    
    @Override
    public String getTargetVersion() {
        return "3.4.0";
    }
    
    @Override
    public String getModLoaderType() {
        return "common";
    }
    
    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // package moved: dev.isxander.yacl.api -> dev.isxander.yacl3.api
        transformer.registerClassRedirect(
            "dev/isxander/yacl/api/YetAnotherConfigLib",
            "dev/isxander/yacl3/api/YetAnotherConfigLib"
        );
        
        transformer.registerClassRedirect(
            "dev/isxander/yacl/api/ConfigCategory",
            "dev/isxander/yacl3/api/ConfigCategory"
        );
        
        transformer.registerClassRedirect(
            "dev/isxander/yacl/api/Option",
            "dev/isxander/yacl3/api/Option"
        );
        
        transformer.registerClassRedirect(
            "dev/isxander/yacl/api/OptionGroup",
            "dev/isxander/yacl3/api/OptionGroup"
        );
        
        transformer.registerClassRedirect(
            "dev/isxander/yacl/api/ButtonOption",
            "dev/isxander/yacl3/api/ButtonOption"
        );
        
        transformer.registerClassRedirect(
            "dev/isxander/yacl/api/ListOption",
            "dev/isxander/yacl3/api/ListOption"
        );
        
        // controllers package moved, with a Builder suffix added to each type
        transformer.registerClassRedirect(
            "dev/isxander/yacl/api/controller/BooleanController",
            "dev/isxander/yacl3/api/controller/BooleanControllerBuilder"
        );
        
        transformer.registerClassRedirect(
            "dev/isxander/yacl/api/controller/StringController",
            "dev/isxander/yacl3/api/controller/StringControllerBuilder"
        );
        
        transformer.registerClassRedirect(
            "dev/isxander/yacl/api/controller/IntegerSliderController",
            "dev/isxander/yacl3/api/controller/IntegerSliderControllerBuilder"
        );
        
        transformer.registerClassRedirect(
            "dev/isxander/yacl/api/controller/DoubleSliderController",
            "dev/isxander/yacl3/api/controller/DoubleSliderControllerBuilder"
        );
        
        transformer.registerClassRedirect(
            "dev/isxander/yacl/api/controller/FloatSliderController",
            "dev/isxander/yacl3/api/controller/FloatSliderControllerBuilder"
        );
        
        transformer.registerClassRedirect(
            "dev/isxander/yacl/api/controller/EnumController",
            "dev/isxander/yacl3/api/controller/EnumControllerBuilder"
        );
        
        transformer.registerClassRedirect(
            "dev/isxander/yacl/api/controller/ColorController",
            "dev/isxander/yacl3/api/controller/ColorControllerBuilder"
        );
        
        transformer.registerClassRedirect(
            "dev/isxander/yacl/api/controller/TickBoxController",
            "dev/isxander/yacl3/api/controller/TickBoxControllerBuilder"
        );
        
        // createBuilder() factories on the renamed types
        transformer.registerMethodRedirect(
            "dev/isxander/yacl/api/YetAnotherConfigLib",
            "createBuilder",
            "()Ldev/isxander/yacl/api/YetAnotherConfigLib$Builder;",
            "dev/isxander/yacl3/api/YetAnotherConfigLib",
            "createBuilder",
            "()Ldev/isxander/yacl3/api/YetAnotherConfigLib$Builder;"
        );

        transformer.registerMethodRedirect(
            "dev/isxander/yacl/api/ConfigCategory",
            "createBuilder",
            "()Ldev/isxander/yacl/api/ConfigCategory$Builder;",
            "dev/isxander/yacl3/api/ConfigCategory",
            "createBuilder",
            "()Ldev/isxander/yacl3/api/ConfigCategory$Builder;"
        );

        transformer.registerMethodRedirect(
            "dev/isxander/yacl/api/Option",
            "createBuilder",
            "()Ldev/isxander/yacl/api/Option$Builder;",
            "dev/isxander/yacl3/api/Option",
            "createBuilder",
            "()Ldev/isxander/yacl3/api/Option$Builder;"
        );
        
        transformer.registerClassRedirect(
            "dev/isxander/yacl/api/Binding",
            "dev/isxander/yacl3/api/Binding"
        );
        
        transformer.registerMethodRedirect(
            "dev/isxander/yacl/api/Binding",
            "generic",
            "(Ljava/lang/Object;Ljava/util/function/Supplier;Ljava/util/function/Consumer;)Ldev/isxander/yacl/api/Binding;",
            "dev/isxander/yacl3/api/Binding",
            "generic",
            "(Ljava/lang/Object;Ljava/util/function/Supplier;Ljava/util/function/Consumer;)Ldev/isxander/yacl3/api/Binding;"
        );
        
        transformer.registerClassRedirect(
            "dev/isxander/yacl/api/OptionDescription",
            "dev/isxander/yacl3/api/OptionDescription"
        );
        
        transformer.registerMethodRedirect(
            "dev/isxander/yacl/api/OptionDescription",
            "of",
            "(Lnet/minecraft/network/chat/Component;)Ldev/isxander/yacl/api/OptionDescription;",
            "dev/isxander/yacl3/api/OptionDescription",
            "of",
            "(Lnet/minecraft/network/chat/Component;)Ldev/isxander/yacl3/api/OptionDescription;"
        );

        transformer.registerClassRedirect(
            "dev/isxander/yacl/gui/YACLScreen",
            "dev/isxander/yacl3/gui/YACLScreen"
        );
    }
    
    @Override
    public String[] getShimClasses() {
        return new String[] {
            "com.retromod.shim.api.common.embedded.YaclShim"
        };
    }
}
