package com.gtnewhorizons.retrofuturagradle;

import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaToolchainSpec;
import org.gradle.jvm.toolchain.JvmVendorSpec;
import org.gradle.jvm.toolchain.internal.DefaultToolchainSpec;

import com.google.common.collect.Lists;

public interface IMinecraftyExtension {
    // Vanilla configs

    /**
     * MC version to download and use, only 1.7.10 is supported now and it is the default.
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
     * Whether to use the mappings embedded in Forge for methods and fields (params are taken from MCP because Forge
     * doesn't have any) Default: true.
     */
    public abstract Property<Boolean> getUseForgeEmbeddedMappings();

    /**
     * Whether to use the generics map to add missing generic parameters to non-private types in the decompiled source
     * code. Default: false. (This is new in RFG compared to FG)
     */
    public abstract Property<Boolean> getInjectMissingGenerics();

    /**
     * Fernflower args, default is "-din=1","-rbr=0","-dgs=1","-asc=1","-log=ERROR"
     */
    public abstract ListProperty<String> getFernflowerArguments();

    default void applyMinecraftyConventions(ObjectFactory objects) {
        getMcVersion().convention("1.7.10");
        getApplyMcDependencies().convention(Boolean.TRUE);
        getLwjglVersion().convention("2.9.4-nightly-20150209");
        getJavaCompatibilityVersion().convention(8);
        {
            final JavaToolchainSpec defaultToolchain = new DefaultToolchainSpec(objects);
            defaultToolchain.getLanguageVersion().set(JavaLanguageVersion.of(8));
            defaultToolchain.getVendor().set(JvmVendorSpec.ADOPTIUM);
            getJavaToolchain().convention(defaultToolchain);
        }

        getMcpMappingChannel().convention("stable");
        getMcpMappingVersion().convention("12");
        getUseForgeEmbeddedMappings().convention(true);
        getFernflowerArguments().convention(Lists.newArrayList("-din=1", "-rbr=0", "-dgs=1", "-asc=1", "-log=ERROR"));
    }
}
