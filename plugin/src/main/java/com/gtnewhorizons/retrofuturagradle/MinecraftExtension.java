package com.gtnewhorizons.retrofuturagradle;

import java.util.Objects;

import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.SetProperty;
import org.gradle.jvm.toolchain.JavaCompiler;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.jvm.toolchain.JavaToolchainService;
import org.gradle.jvm.toolchain.JavaToolchainSpec;

import com.google.common.collect.Lists;

/**
 * Parameter block for the `minecraft {...}` Gradle script extension
 */
public abstract class MinecraftExtension implements IMinecraftyExtension {

    private final Project project;

    public MinecraftExtension(Project project) {
        this.project = project;
        getSkipSlowTasks().convention(false);
        applyMinecraftyConventions(project.getObjects(), project.getGradle());

        getGroupsToExcludeFromAutoReobfMapping().set(Lists.newArrayList());

        getUsesFml().convention(true);
        getUsesForge().convention(true);

        getUseDependencyAccessTransformers().convention(false);
        getDependenciesForAccessTransformerScan().from(project.getConfigurations().getByName("compileClasspath"));
    }

    // Internal configs

    /**
     * Skips slow-running tasks (decompilation, jar merging, etc.) if the artifacts already exist, useful for
     * development of the plugin.
     */
    public abstract Property<Boolean> getSkipSlowTasks();

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

    // Forge configs

    /**
     * Whether FML is included in the decompiled environment, default is true.
     */
    public abstract Property<Boolean> getUsesFml();

    /**
     * Whether Forge is included in the decompiled environment, default is true.
     */
    public abstract Property<Boolean> getUsesForge();

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

    /**
     * Glob patterns on which to run tag replacement, deprecated as the implementation is very hacky, implemented for
     * compat with FG buildscripts
     */
    public abstract ListProperty<String> getTagReplacementFiles();

    /**
     * Dependency groups to exclude from automatic remapping from dev to reobf jars. Add reobfed dependencies manually
     * to reobfJarConfiguration as needed
     */
    public abstract SetProperty<String> getGroupsToExcludeFromAutoReobfMapping();

    /**
     * Set to true to scan dependencies for access transformers (based on FMLAT entries in MANIFEST.MFs). False by
     * default.
     */
    public abstract Property<Boolean> getUseDependencyAccessTransformers();

    /**
     * If {@link MinecraftExtension#getUseDependencyAccessTransformers()} is true, this is the list of files to scan for
     * AT entries. It's the compileClasspath configuration by default, use
     * {@link ConfigurableFileCollection#setFrom(Object...)} if you want to replace it with something else.
     */
    public abstract ConfigurableFileCollection getDependenciesForAccessTransformerScan();

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

    // FG compatibility shims for changes that can cause confusing behaviour
    /** @deprecated Use {@link MinecraftExtension#getMcVersion()} instead */
    @Deprecated
    public void setVersion(String version) {
        getMcVersion().set(version);
    }

    /** @deprecated Use {@link MinecraftExtension#getMcVersion()} instead */
    @Deprecated
    public String getVersion() {
        return getMcVersion().get();
    }

    /**
     * @deprecated Use {@link MinecraftExtension#getMcpMappingVersion()} and
     *             {@link MinecraftExtension#getMcpMappingChannel()} instead
     */
    @Deprecated
    public String getMappings() {
        return getMcpMappingChannel().get() + "-" + getMcpMappingVersion().get();
    }
}
