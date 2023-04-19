package com.gtnewhorizons.retrofuturagradle;

import org.apache.commons.lang3.StringUtils;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.invocation.Gradle;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaToolchainSpec;
import org.gradle.jvm.toolchain.JvmVendorSpec;
import org.gradle.jvm.toolchain.internal.DefaultToolchainSpec;

import com.google.common.collect.Lists;
import com.gtnewhorizons.retrofuturagradle.util.Utilities;

public interface IMinecraftyExtension {
    // Vanilla configs

    /**
     * MC version to download and use, only 1.7.10 is supported now and it is the default.
     */
    public abstract Property<String> getMcVersion();

    public abstract Property<String> getUsername();

    public abstract Property<String> getUserUUID();

    /**
     * Whether to add all of MC's dependencies automatically as dependencies of your project, default is true.
     */
    public abstract Property<Boolean> getApplyMcDependencies();

    @Deprecated
    default Property<String> getLwjglVersion() {
        return getLwjgl2Version();
    }

    /**
     * LWJGL 2 version to use. Default is 2.9.4-nightly-20150209
     */
    public abstract Property<String> getLwjgl2Version();

    /**
     * LWJGL 3 version to use. Default is 3.3.1
     */
    public abstract Property<String> getLwjgl3Version();

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
     * Whether to use the mappings embedded in Forge for methods and fields (params are taken from MCP because Forge
     * doesn't have any) Default: true.
     */
    public abstract Property<Boolean> getUseForgeEmbeddedMappings();

    /**
     * A list of additional params.csv-style mappings for method parameter renaming.
     */
    public abstract ConfigurableFileCollection getExtraParamsCsvs();

    /**
     * Whether to use the generics map to add missing generic parameters to non-private types in the decompiled source
     * code. Default: false. (This is new in RFG compared to FG)
     */
    public abstract Property<Boolean> getInjectMissingGenerics();

    /**
     * Fernflower args, default is "-din=1","-rbr=0","-dgs=1","-asc=1","-log=ERROR"
     */
    public abstract ListProperty<String> getFernflowerArguments();

    /**
     * @return The major version of LWJGL (2 or 3) used by the main and test source sets. Default: 2
     */
    public abstract Property<Integer> getMainLwjglVersion();

    default void applyMinecraftyConventions(ObjectFactory objects, Gradle gradle) {
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
        {
            final JavaToolchainSpec defaultToolchain = new DefaultToolchainSpec(objects);
            defaultToolchain.getLanguageVersion().set(JavaLanguageVersion.of(8));
            defaultToolchain.getVendor().set(JvmVendorSpec.AZUL);
            getJavaToolchain().convention(defaultToolchain);
            getJavaToolchain().finalizeValueOnRead();
        }

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
}
