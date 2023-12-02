package com.gtnewhorizons.retrofuturagradle;

import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;

/**
 * Parameter block for the `rfgPatchDev {...}` Gradle script extension
 */
public abstract class RfgPatchdevExtension implements IMinecraftyExtension {

    private final Project project;

    public RfgPatchdevExtension(Project project) {
        this.project = project;
        this.applyMinecraftyConventions(project);
    }

    /**
     * ATs to apply to the deobfuscated jar before decompilation
     */
    public abstract ConfigurableFileCollection getAccessTransformers();

    // Launching & end product
    /**
     * Extra LaunchWrapper tweak classes to use when running Minecraft
     */
    public abstract ListProperty<String> getExtraTweakClasses();

    /**
     * Extra JVM arguments to use when running Minecraft
     */
    public abstract ListProperty<String> getExtraRunJvmArguments();

    /**
     * A key-value map of tags to inject into the project either by way of token substitution (hacky, deprecated) or
     * generating a small Java file with the tag values.
     */
    public abstract MapProperty<String, Object> getInjectedTags();
}
