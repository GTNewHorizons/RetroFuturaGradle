package com.gtnewhorizons.retrofuturagradle;

import java.util.Objects;

import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.jvm.toolchain.JavaCompiler;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.jvm.toolchain.JavaToolchainService;
import org.gradle.jvm.toolchain.JavaToolchainSpec;

/**
 * Parameter block for the `rfgPatchDev {...}` Gradle script extension
 */
public abstract class RfgPatchdevExtension implements IMinecraftyExtension {

    private final Project project;

    public RfgPatchdevExtension(Project project) {
        this.project = project;
        this.applyMinecraftyConventions(project.getObjects(), project.getGradle());
    }

    // Vanilla configs

    /** {@inheritDoc} */
    @Override
    public abstract Property<String> getMcVersion();

    /** {@inheritDoc} */
    @Override
    public abstract Property<Boolean> getApplyMcDependencies();

    /** {@inheritDoc} */
    @Override
    public abstract Property<String> getLwjgl2Version();

    /** {@inheritDoc} */
    @Override
    public abstract Property<Integer> getJavaCompatibilityVersion();

    /** {@inheritDoc} */
    @Override
    public abstract Property<JavaToolchainSpec> getJavaToolchain();

    // MCP configs

    /** {@inheritDoc} */
    @Override
    public abstract Property<String> getMcpMappingChannel();

    /** {@inheritDoc} */
    @Override
    public abstract Property<String> getMcpMappingVersion();

    /** {@inheritDoc} */
    @Override
    public abstract Property<Boolean> getUseForgeEmbeddedMappings();

    /** {@inheritDoc} */
    @Override
    public abstract ListProperty<String> getFernflowerArguments();

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

    public Provider<JavaLauncher> getToolchainLauncher() {
        JavaToolchainService jts = Objects
                .requireNonNull(project.getExtensions().findByType(JavaToolchainService.class));
        return getJavaToolchain().flatMap(jts::launcherFor);
    }

    public Provider<JavaCompiler> getToolchainCompiler() {
        JavaToolchainService jts = Objects
                .requireNonNull(project.getExtensions().findByType(JavaToolchainService.class));
        return getJavaToolchain().flatMap(jts::compilerFor);
    }
}
