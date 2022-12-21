package com.gtnewhorizons.retrofuturagradle;

import org.gradle.api.provider.Property;

/**
 * Parameter block for the `minecraft {...}` Gradle script extension
 */
public abstract class MinecraftExtension {
    public MinecraftExtension() {
        getMcVersion().convention("1.7.10");
    }

    public abstract Property<String> getMcVersion();
}
