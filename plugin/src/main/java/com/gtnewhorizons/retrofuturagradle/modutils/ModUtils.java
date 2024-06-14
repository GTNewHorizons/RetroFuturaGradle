package com.gtnewhorizons.retrofuturagradle.modutils;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.attributes.AttributeCompatibilityRule;
import org.gradle.api.attributes.CompatibilityCheckDetails;
import org.gradle.api.file.*;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.BasePluginExtension;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.plugins.scala.ScalaPlugin;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.jvm.tasks.Jar;
import org.gradle.language.jvm.tasks.ProcessResources;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.gradle.plugin.KaptExtension;

import com.gtnewhorizons.retrofuturagradle.MinecraftExtension;
import com.gtnewhorizons.retrofuturagradle.ObfuscationAttribute;
import com.gtnewhorizons.retrofuturagradle.mcp.GenSrgMappingsTask;
import com.gtnewhorizons.retrofuturagradle.mcp.MCPTasks;
import com.gtnewhorizons.retrofuturagradle.mcp.ReobfuscatedJar;
import com.gtnewhorizons.retrofuturagradle.mcp.RfgCacheService;
import com.gtnewhorizons.retrofuturagradle.minecraft.MinecraftTasks;
import com.gtnewhorizons.retrofuturagradle.util.Utilities;

import kotlin.Unit;

/**
 * Gradle utilities for developing mods
 */
public class ModUtils {

    protected static final String TASK_GROUP_INTERNAL = "Internal RFG Utilities";
    protected static final String TASK_GROUP_USER = "RFG Utilities";

    private final Project project;
    private final MinecraftExtension mcExt;
    private final MinecraftTasks minecraftTasks;
    private final MCPTasks mcpTasks;
    private final ConfigurableFileCollection depFilesToDeobf;
    private final SetProperty<String> depModulesToDeobf;
    private final Property<String> mixinRefMap;

    /**
     * Attribute that controls running the deobfuscation artifact transform.
     */
    public static final Attribute<Boolean> DEOBFUSCATOR_TRANSFORMED = Attribute
            .of("rfgDeobfuscatorTransformed", Boolean.class);

    public static class DeobfuscatorTransformerCompatRules implements AttributeCompatibilityRule<Boolean> {

        @Override
        public void execute(CompatibilityCheckDetails<Boolean> details) {
            if (details.getProducerValue() == null) {
                details.compatible();
            } else {
                details.incompatible();
            }
        }
    }

    public ModUtils(Project project, MinecraftExtension mcExt, MinecraftTasks minecraftTasks, MCPTasks mcpTasks) {
        this.project = project;
        this.mcExt = mcExt;
        this.minecraftTasks = minecraftTasks;
        this.mcpTasks = mcpTasks;

        final DependencyHandler deps = project.getDependencies();
        final ObjectFactory objects = project.getObjects();
        final ConfigurationContainer configs = project.getConfigurations();

        this.depFilesToDeobf = objects.fileCollection();
        this.depModulesToDeobf = objects.setProperty(String.class);
        this.mixinRefMap = objects.property(String.class);

        final boolean disableDependencyDeobfuscation = Boolean.parseBoolean(
                Optional.ofNullable(project.findProperty("rfg.disableDependencyDeobfuscation")).orElse("false")
                        .toString());

        project.getTasks().register("applyDecompilerCleanupToMain", ApplyDecompCleanupTask.class, task -> {
            task.setGroup(TASK_GROUP_USER);
            task.setDescription(
                    "Apply MCP decompiler cleanup to the main source set, doing things like replacing numerical OpenGL constants with their names");
        });

        project.getTasks().register("migrateMappings", MigrateMappingsTask.class, task -> {
            task.setGroup(TASK_GROUP_USER);
            task.setDescription("Migrate main source set to a new set of mappings");
            task.dependsOn("extractMcpData", "extractForgeUserdev", "packagePatchedMc", "injectTags");
            task.getSourceSrg()
                    .set(mcpTasks.getTaskGenerateForgeSrgMappings().flatMap(GenSrgMappingsTask::getInputSrg));
            task.getSourceFieldsCsv()
                    .set(mcpTasks.getTaskGenerateForgeSrgMappings().flatMap(GenSrgMappingsTask::getFieldsCsv));
            task.getSourceMethodsCsv()
                    .set(mcpTasks.getTaskGenerateForgeSrgMappings().flatMap(GenSrgMappingsTask::getMethodsCsv));
            ConfigurableFileCollection cp = task.getCompileClasspath();
            cp.from(project.getConfigurations().getByName("compileClasspath"));
            cp.from(project.getTasks().named("packagePatchedMc", Jar.class));
            cp.from(
                    project.getExtensions().getByType(JavaPluginExtension.class).getSourceSets()
                            .getByName("injectedTags").getOutput());
        });

        if (!disableDependencyDeobfuscation) {
            project.getDependencies().getAttributesSchema().attribute(DEOBFUSCATOR_TRANSFORMED, ams -> {
                ams.getCompatibilityRules().add(DeobfuscatorTransformerCompatRules.class);
                ams.getDisambiguationRules().pickFirst(Comparator.nullsFirst(Comparator.naturalOrder()));
            });

            // Dependency deobfuscation utilities, see comment on deobfuscate
            deps.registerTransform(DependencyDeobfuscationTransform.class, spec -> {
                spec.getFrom().attribute(DEOBFUSCATOR_TRANSFORMED, Boolean.FALSE);
                spec.getTo().attribute(DEOBFUSCATOR_TRANSFORMED, Boolean.TRUE);
                final DependencyDeobfuscationTransform.Parameters params = spec.getParameters();
                params.getFieldsCsv()
                        .set(mcpTasks.getTaskGenerateForgeSrgMappings().flatMap(GenSrgMappingsTask::getFieldsCsv));
                params.getMethodsCsv()
                        .set(mcpTasks.getTaskGenerateForgeSrgMappings().flatMap(GenSrgMappingsTask::getMethodsCsv));
                params.getFilesToDeobf().from(depFilesToDeobf);
                params.getModulesToDeobf().set(depModulesToDeobf);
                // can't use a true build service here due to gradle serialization errors
                params.getMappingService().set(RfgCacheService.lazyAccess(project.getGradle()));
            });

            project.afterEvaluate(_p -> {
                project.getDependencies().getArtifactTypes().getByName("jar").getAttributes()
                        .attribute(DEOBFUSCATOR_TRANSFORMED, Boolean.FALSE);
                project.getConfigurations().configureEach(cfg -> {
                    // Don't add the deobfuscator-transformed attribute to published variants
                    if (cfg.isCanBeConsumed() && !cfg.isCanBeResolved()) {
                        return;
                    }
                    if (cfg.getName().endsWith("Elements") || cfg.getName().endsWith("ElementsForTest")) {
                        return;
                    }
                    ObfuscationAttribute requiredObfuscation = cfg.getAttributes()
                            .getAttribute(ObfuscationAttribute.OBFUSCATION_ATTRIBUTE);
                    if (requiredObfuscation.getName().equals(ObfuscationAttribute.MCP)) {
                        cfg.getAttributes().attribute(DEOBFUSCATOR_TRANSFORMED, Boolean.TRUE);
                    }
                });
            });
        }

        project.afterEvaluate(_p -> {
            if (this.mixinRefMap.isPresent()) {
                File tempMixinDir = FileUtils.getFile(project.getBuildDir(), "tmp", "mixins");
                File mixinSrg = new File(tempMixinDir, "mixins.srg");
                File mixinRefMapFile = new File(tempMixinDir, this.mixinRefMap.get());
                TaskProvider<ReobfuscatedJar> reobfJarTask = project.getTasks()
                        .named("reobfJar", ReobfuscatedJar.class);
                reobfJarTask.configure(task -> task.getExtraSrgFiles().from(mixinSrg));
                project.getTasks().named("compileJava", JavaCompile.class).configure(task -> {
                    task.doFirst("createTempMixinDirectory", _t -> tempMixinDir.mkdirs());
                    ListProperty<String> reobfSrgFile = project.getObjects().listProperty(String.class);
                    reobfSrgFile.add(
                            reobfJarTask.map(ReobfuscatedJar::getSrg).map(RegularFileProperty::get)
                                    .map(RegularFile::getAsFile).map(f -> "-AreobfSrgFile=" + f));
                    task.getOptions().getCompilerArgumentProviders().add(reobfSrgFile::get);
                    List<String> compilerArgs = task.getOptions().getCompilerArgs();
                    compilerArgs.add("-AoutSrgFile=" + mixinSrg);
                    compilerArgs.add("-AoutRefMapFile=" + mixinRefMapFile);
                });
                // Keep as class instead of lambda to ensure it works even if the plugin is not loaded into the
                // classpath
                // noinspection rawtypes
                project.getPlugins().withId("org.jetbrains.kotlin.kapt", new Action<Plugin>() {

                    @Override
                    public void execute(@NotNull Plugin rawPlugin) {
                        KaptExtension kapt = project.getExtensions().getByType(KaptExtension.class);
                        kapt.setCorrectErrorTypes(true);
                        kapt.javacOptions(jco -> {
                            jco.option("-AoutSrgFile=" + mixinSrg);
                            jco.option("-AoutRefMapFile=" + mixinRefMapFile);
                            // This is lazily evaluated by the kapt plugin
                            Provider<String> reobfSrg = reobfJarTask.map(ReobfuscatedJar::getSrg)
                                    .map(RegularFileProperty::get).map(RegularFile::getAsFile)
                                    .map(f -> "-AreobfSrgFile=" + f);
                            if (reobfSrg.isPresent()) {
                                jco.option(reobfSrg.get());
                            }
                            return Unit.INSTANCE;
                        });
                        project.getTasks().configureEach(task -> {
                            if (task.getName().equals("kaptKotlin")) {
                                task.doFirst("createTempMixinDirectory", _t -> tempMixinDir.mkdirs());
                            }
                        });
                    }
                });
                project.getTasks().named("processResources", ProcessResources.class).configure(task -> {
                    task.from(mixinRefMapFile);
                    task.dependsOn("compileJava");
                    project.getPlugins().withType(ScalaPlugin.class, scp -> { task.dependsOn("compileScala"); });
                    project.getPlugins().withId("org.jetbrains.kotlin.jvm", p -> { task.dependsOn("compileKotlin"); });
                });
            }
        });

        deps.getExtensions().add("rfg", new RfgDependencyExtension());
    }

    /**
     * Tries to connect to a set of URLs in parallel, returning the first URL that responds with a success HTTP code.
     * 
     * @param timeoutMillis Timeout for connections made
     * @param mirrors       The list of mirror URLs to try
     * @return The live URL
     * @throws RuntimeException If none of the URLs are live
     */
    public String getLiveMirrorURL(int timeoutMillis, String... mirrors) {
        final AtomicReference<String> successUrl = new AtomicReference<>(null);
        final ArrayList<Thread> threads = new ArrayList<>(mirrors.length);
        for (final String rawUrl : mirrors) {
            final String url = rawUrl.replaceFirst("^https", "http");
            try {
                final HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(timeoutMillis);
                connection.setReadTimeout(timeoutMillis);
                connection.setRequestMethod("HEAD");
                final Thread thread = new Thread("rfg-mirror-checker") {

                    @Override
                    public void run() {
                        try {
                            int response = connection.getResponseCode();
                            if (response >= 200 && response <= 399) {
                                synchronized (successUrl) {
                                    successUrl.set(rawUrl);
                                    successUrl.notifyAll();
                                }
                            }
                        } catch (IOException e) {
                            project.getLogger().info("RFG mirror {} failed: {}", rawUrl, e.getMessage());
                        }
                    }
                };
                thread.setDaemon(true);
                threads.add(thread);
                thread.start();
            } catch (IOException e) {
                continue;
            }
        }
        try {
            synchronized (successUrl) {
                successUrl.wait(timeoutMillis);
            }
        } catch (InterruptedException e) {
            // cancel
        }
        final String liveUrl = successUrl.get();
        for (final Thread t : threads) {
            t.interrupt();
        }
        if (liveUrl == null) {
            throw new RuntimeException("None of the given URL mirrors are live: " + StringUtils.join(mirrors));
        } else {
            return liveUrl;
        }
    }

    /**
     * Wrap a dependency specification with a call to this function to make RFG deobfuscate it on resolution in
     * MCP-mapped configurations.
     */
    public Object deobfuscate(Object depSpec) {
        if (depSpec instanceof CharSequence) {
            depModulesToDeobf.add(depSpec.toString());
        } else if (depSpec instanceof Map<?, ?>depMap) {
            final String group = Utilities.getMapStringOrBlank(depMap, "group");
            final String module = Utilities.getMapStringOrBlank(depMap, "name");
            final String version = Utilities.getMapStringOrBlank(depMap, "version");
            final String classifier = Utilities.getMapStringOrBlank(depMap, "classifier");
            String gmv = group + ":" + module + ":" + version;
            if (StringUtils.isNotBlank(classifier)) {
                gmv += ":" + classifier;
            }
            depModulesToDeobf.add(gmv);
        } else if (depSpec instanceof File || depSpec instanceof RegularFile
                || depSpec instanceof Path
                || depSpec instanceof URI
                || depSpec instanceof URL
                || depSpec instanceof FileTree
                || depSpec instanceof FileCollection) {
                    depFilesToDeobf.from(depSpec);
                } else {
                    throw new UnsupportedOperationException(
                            "Unsupported dependency type " + depSpec.getClass() + " for RFG deobfuscation");
                }
        return depSpec;
    }

    /**
     * Wrap a mixin dependency specification with a call to this function to allow RFG to recognise the need for a
     * refmap to be generated. The dependency should be declared as part of the annotationProcessor configuration
     */
    public Object enableMixins(Object mixinSpec, String refMapName) {
        mixinRefMap.set(refMapName);
        return mixinSpec;
    }

    public Object enableMixins(Object mixinSpec) {
        mixinRefMap.set(
                project.getExtensions().getByType(BasePluginExtension.class).getArchivesName()
                        .map(name -> String.format("mixins.%s.refmap.json", name)));
        return mixinSpec;
    }

    public class RfgDependencyExtension {

        /**
         * Wrap a dependency specification with a call to this function to make RFG deobfuscate it on resolution in
         * MCP-mapped configurations.
         */
        public Object deobf(Object depSpec) {
            return ModUtils.this.deobfuscate(depSpec);
        }
    }
}
