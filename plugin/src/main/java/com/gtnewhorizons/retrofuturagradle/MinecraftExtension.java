package com.gtnewhorizons.retrofuturagradle;

import org.gradle.api.provider.Property;

/**
 * Parameter block for the `minecraft {...}` Gradle script extension
 */
public abstract class MinecraftExtension {
    public MinecraftExtension() {
        getMcVersion().convention("1.7.10");
        getApplyMcDependencies().convention(Boolean.TRUE);
        getLwjglVersion().convention("2.9.3");
        getJavaVersion().convention(8);

        getMcpMappingChannel().convention("stable");
        getMcpMappingVersion().convention("12");
    }

    // Vanilla configs

    /**
     * MC version to download&use, only 1.7.10 is supported now and it is the default.
     */
    public abstract Property<String> getMcVersion();

    /**
     * Whether to add all of MC's dependencies automatically as dependencies of your project, default is true.
     */
    public abstract Property<Boolean> getApplyMcDependencies();

    /**
     * LWJGL version to use. Default is 2.9.3
     */
    public abstract Property<String> getLwjglVersion();

    /**
     * Java version to use. Default is 8.
     */
    public abstract Property<Integer> getJavaVersion();

    // MCP configs

    /**
     * stable/snapshot
     */
    public abstract Property<String> getMcpMappingChannel();

    /**
     * From <a href="https://maven.minecraftforge.net/de/oceanlabs/mcp/versions.json">the MCP versions.json file</a>
     */
    public abstract Property<String> getMcpMappingVersion();
}
