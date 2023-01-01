package com.gtnewhorizons.retrofuturagradle;

import com.google.common.collect.Lists;
import java.util.Objects;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.jvm.toolchain.JavaCompiler;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.jvm.toolchain.JavaToolchainService;
import org.gradle.jvm.toolchain.JavaToolchainSpec;
import org.gradle.jvm.toolchain.JvmVendorSpec;
import org.gradle.jvm.toolchain.internal.DefaultToolchainSpec;

/**
 * Parameter block for the `minecraft {...}` Gradle script extension
 */
public abstract class MinecraftExtension {
    private final Project project;

    public MinecraftExtension(Project project) {
        this.project = project;
        getSkipSlowTasks().convention(false);
        getMcVersion().convention("1.7.10");
        getApplyMcDependencies().convention(Boolean.TRUE);
        getLwjglVersion().convention("2.9.4-nightly-20150209");
        getJavaCompatibilityVersion().convention(8);
        {
            final JavaToolchainSpec defaultToolchain = new DefaultToolchainSpec(project.getObjects());
            defaultToolchain.getLanguageVersion().set(JavaLanguageVersion.of(8));
            defaultToolchain.getVendor().set(JvmVendorSpec.ADOPTIUM);
            getJavaToolchain().convention(defaultToolchain);
        }

        getMcpMappingChannel().convention("stable");
        getMcpMappingVersion().convention("12");
        getFernflowerArguments().convention(Lists.newArrayList("-din=1", "-rbr=0", "-dgs=1", "-asc=1", "-log=ERROR"));

        getUsesFml().convention(true);
        getUsesForge().convention(true);
    }

    // Internal configs

    /**
     * Skips slow-running tasks (decompilation, jar merging, etc.) if the artifacts already exist, useful for development of the plugin.
     */
    public abstract Property<Boolean> getSkipSlowTasks();

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
     * LWJGL version to use. Default is 2.9.4-nightly-20150209
     */
    public abstract Property<String> getLwjglVersion();

    /**
     * Java version to provide source/target compatibility for. Default is 8.
     */
    public abstract Property<Integer> getJavaCompatibilityVersion();

    /**
     * The JDK to use for compiling and running the mod
     */
    public abstract Property<JavaToolchainSpec> getJavaToolchain();

    // MCP configs

    /**
     * stable/snapshot
     */
    public abstract Property<String> getMcpMappingChannel();

    /**
     * From <a href="https://maven.minecraftforge.net/de/oceanlabs/mcp/versions.json">the MCP versions.json file</a>
     */
    public abstract Property<String> getMcpMappingVersion();

    /**
     * Fernflower args, default is "-din=1","-rbr=0","-dgs=1","-asc=1","-log=ERROR"
     */
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
     * A key-value map of tags to inject into the project either by way of token substitution (hacky, deprecated) or generating a small Java file with the tag values.
     */
    public abstract MapProperty<String, Object> getInjectedTags();

    /**
     * Deprecated, source files to replace the tokens in
     */
    public abstract ConfigurableFileCollection getTokenSubstituteSources();

    /**
     * Class package and name (e.g. org.mymod.Tags) to fill with the provided tags.
     */
    public abstract Property<String> getInjectedTagClass();

    public Provider<JavaLauncher> getToolchainLauncher() {
        JavaToolchainService jts =
                Objects.requireNonNull(project.getExtensions().findByType(JavaToolchainService.class));
        return getJavaToolchain().flatMap(jts::launcherFor);
    }

    public Provider<JavaCompiler> getToolchainCompiler() {
        JavaToolchainService jts =
                Objects.requireNonNull(project.getExtensions().findByType(JavaToolchainService.class));
        return getJavaToolchain().flatMap(jts::compilerFor);
    }
}
