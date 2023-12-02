package com.gtnewhorizons.retrofuturagradle;

import java.util.Objects;

import javax.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.gradle.api.Project;
import org.gradle.api.file.ArchiveOperations;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.invocation.Gradle;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.jvm.toolchain.JavaCompiler;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.jvm.toolchain.JavaToolchainService;
import org.gradle.process.ExecOperations;

import com.google.common.collect.Lists;
import com.gtnewhorizons.retrofuturagradle.util.Utilities;

public interface IMinecraftyExtension {
    // Vanilla configs

    /**
     * MC version to download and use, only 1.7.10 is supported now and it is the default.
     */
    Property<String> getMcVersion();

    Property<String> getUsername();

    Property<String> getUserUUID();

    /**
     * Whether to add all of MC's dependencies automatically as dependencies of your project, default is true.
     */
    Property<Boolean> getApplyMcDependencies();

    @Deprecated
    default Property<String> getLwjglVersion() {
        return getLwjgl2Version();
    }

    /**
     * LWJGL 2 version to use. Default is 2.9.4-nightly-20150209
     */
    Property<String> getLwjgl2Version();

    /**
     * LWJGL 3 version to use. Default is 3.3.1
     */
    Property<String> getLwjgl3Version();

    /**
     * Java version to provide source/target compatibility for. Default is 8.
     */
    Property<Integer> getJavaCompatibilityVersion();

    /**
     * @return The JVM language version to use for Minecraft (de)compilation tasks. Default is 8.
     */
    Property<JavaLanguageVersion> getJvmLanguageVersion();

    // MCP configs

    /**
     * stable/snapshot
     */
    Property<String> getMcpMappingChannel();

    /**
     * From <a href="https://maven.minecraftforge.net/de/oceanlabs/mcp/versions.json">the MCP versions.json file</a>
     */
    Property<String> getMcpMappingVersion();

    /**
     * Whether to use the mappings embedded in Forge for methods and fields (params are taken from MCP because Forge
     * doesn't have any) Default: true.
     */
    Property<Boolean> getUseForgeEmbeddedMappings();

    /**
     * A list of additional params.csv-style mappings for method parameter renaming.
     */
    ConfigurableFileCollection getExtraParamsCsvs();

    /**
     * Whether to use the generics map to add missing generic parameters to non-private types in the decompiled source
     * code. Default: false. (This is new in RFG compared to FG)
     */
    Property<Boolean> getInjectMissingGenerics();

    /**
     * Fernflower args, default is "-din=1","-rbr=0","-dgs=1","-asc=1","-log=ERROR"
     */
    ListProperty<String> getFernflowerArguments();

    /**
     * @return The major version of LWJGL (2 or 3) used by the main and test source sets. Default: 2
     */
    Property<Integer> getMainLwjglVersion();

    /**
     * @return An auto-injected gradle service handle.
     */
    @Inject
    FileSystemOperations getFileSystemOperations();

    /**
     * @return An auto-injected gradle service handle.
     */
    @Inject
    ArchiveOperations getArchiveOperations();

    /**
     * @return An auto-injected gradle service handle.
     */
    @Inject
    ProviderFactory getProviderFactory();

    /**
     * @return An auto-injected gradle service handle.
     */
    @Inject
    ExecOperations getExecOperations();

    /**
     * @return An auto-injected gradle service handle.
     */
    @Inject
    ProjectLayout getProjectLayout();

    /**
     * @return An auto-injected gradle service handle.
     */
    @Inject
    ObjectFactory getObjectFactory();

    default void applyMinecraftyConventions(Project project) {
        final ObjectFactory objects = project.getObjects();
        final Gradle gradle = project.getGradle();
        getMcVersion().convention("1.7.10");
        getMcVersion().finalizeValueOnRead();
        getUsername().convention("Developer");
        getUserUUID().convention(getUsername().map(name -> Utilities.resolveUUID(name, gradle).toString()));
        getApplyMcDependencies().convention(Boolean.TRUE);
        getApplyMcDependencies().finalizeValueOnRead();
        getLwjgl2Version().convention("2.9.4-nightly-20150209");
        getLwjgl2Version().finalizeValueOnRead();
        getLwjgl3Version().convention("3.3.2");
        getLwjgl2Version().finalizeValueOnRead();
        getJavaCompatibilityVersion().convention(8);
        getJavaCompatibilityVersion().finalizeValueOnRead();
        getJvmLanguageVersion().convention(JavaLanguageVersion.of(8));

        getMcpMappingChannel().convention("stable");
        getMcpMappingChannel().finalizeValueOnRead();
        getMcpMappingVersion().convention(getMcVersion().map(ver -> switch (ver) {
            case "1.7.10" -> "12";
            case "1.12.2" -> "39";
            default -> throw new UnsupportedOperationException("Unsupported MC version " + ver);
        }));
        getMcpMappingVersion().finalizeValueOnRead();
        getUseForgeEmbeddedMappings().convention(getMinorMcVersion().map(ver -> ver <= 8));
        getUseForgeEmbeddedMappings().finalizeValueOnRead();
        getFernflowerArguments().convention(Lists.newArrayList("-din=1", "-rbr=0", "-dgs=1", "-asc=1", "-log=ERROR"));
        getFernflowerArguments().finalizeValueOnRead();
        getMainLwjglVersion().convention(2);
        getMainLwjglVersion().finalizeValueOnRead();
    }

    @FunctionalInterface
    interface IMcVersionFunction<R> {

        R apply(String mcVersion, String mcpChannel, String mcpVersion);
    }

    default <R> Provider<R> mapMcpVersions(IMcVersionFunction<R> mapper) {
        return getMcVersion().flatMap(
                mcVer -> getMcpMappingChannel()
                        .zip(getMcpMappingVersion(), (mcpChan, mcpVer) -> mapper.apply(mcVer, mcpChan, mcpVer)));
    }

    default Provider<String> getForgeVersion() {
        return getMcVersion().map(mcVer -> switch (mcVer) {
            case "1.7.10" -> "1.7.10-10.13.4.1614-1.7.10";
            case "1.12.2" -> "1.12.2-14.23.5.2847";
            default -> throw new UnsupportedOperationException();
        });
    }

    default Provider<Integer> getMinorMcVersion() {
        return getMcVersion().map(
                minecraftVersion -> Integer
                        .parseInt(StringUtils.removeStart(minecraftVersion, "1.").replaceAll("\\..+$", ""), 10));
    }

    default Provider<JavaLauncher> getToolchainLauncher(Project project) {
        final JavaToolchainService jts = Objects
                .requireNonNull(project.getExtensions().findByType(JavaToolchainService.class));
        final JavaPluginExtension jext = Objects
                .requireNonNull(project.getExtensions().findByType(JavaPluginExtension.class));
        final Property<JavaLanguageVersion> jvmLanguageVersion = getJvmLanguageVersion();
        return jts.launcherFor(spec -> {
            spec.getLanguageVersion().set(jvmLanguageVersion);
            spec.getVendor().set(jext.getToolchain().getVendor());
            spec.getImplementation().set(jext.getToolchain().getImplementation());
        });
    }

    default Provider<JavaLauncher> getToolchainLauncher(Project project, int languageVersion) {
        final JavaToolchainService jts = Objects
                .requireNonNull(project.getExtensions().findByType(JavaToolchainService.class));
        final JavaPluginExtension jext = Objects
                .requireNonNull(project.getExtensions().findByType(JavaPluginExtension.class));
        return jts.launcherFor(spec -> {
            spec.getLanguageVersion().set(JavaLanguageVersion.of(languageVersion));
            spec.getVendor().set(jext.getToolchain().getVendor());
            spec.getImplementation().set(jext.getToolchain().getImplementation());
        });
    }

    default Provider<JavaCompiler> getToolchainCompiler(Project project) {
        final JavaToolchainService jts = Objects
                .requireNonNull(project.getExtensions().findByType(JavaToolchainService.class));
        final JavaPluginExtension jext = Objects
                .requireNonNull(project.getExtensions().findByType(JavaPluginExtension.class));
        final Property<JavaLanguageVersion> jvmLanguageVersion = getJvmLanguageVersion();
        return jts.compilerFor(spec -> {
            spec.getLanguageVersion().set(jvmLanguageVersion);
            spec.getVendor().set(jext.getToolchain().getVendor());
            spec.getImplementation().set(jext.getToolchain().getImplementation());
        });
    }

}
