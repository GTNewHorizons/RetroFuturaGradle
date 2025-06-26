package com.gtnewhorizons.retrofuturagradle.modutils;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

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
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.jvm.tasks.Jar;
import org.gradle.language.jvm.tasks.ProcessResources;
import org.gradle.process.CommandLineArgumentProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.gradle.internal.KaptTask;
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
    /** The source set to enable mixin processing on, defaults to main. */
    public final Property<SourceSet> mixinSourceSet;

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
        this.mixinSourceSet = project.getObjects().property(SourceSet.class);
        final SourceSetContainer sourceSets = project.getExtensions().getByType(JavaPluginExtension.class)
                .getSourceSets();
        this.mixinSourceSet.convention(sourceSets.named("main"));

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
            Provider<String> mixinRefMap = this.mixinRefMap;
            if (mixinRefMap.isPresent()) {
                Provider<Directory> tempMixinDir = project.getLayout().getBuildDirectory().dir("tmp/mixins");
                Provider<RegularFile> mixinSrg = tempMixinDir.map(it -> it.file("mixins.srg"));
                Provider<RegularFile> mixinRefMapFile = tempMixinDir.map(it -> it.file(mixinRefMap.get()));
                TaskProvider<ReobfuscatedJar> reobfJarTask = project.getTasks()
                        .named("reobfJar", ReobfuscatedJar.class);
                reobfJarTask.configure(task -> task.getExtraSrgFiles().from(mixinSrg));
                Provider<RegularFile> reobfSrg = reobfJarTask.flatMap(ReobfuscatedJar::getSrg);
                // getLocationOnly is needed because JavaCompile compile options are read by intellij on project reload,
                // and it causes an error because the srg task hasn't run yet
                // Adding the file above to the task inputs fixes the task dependency chain breakage caused by this.
                Provider<RegularFile> reobfSrgLocation = reobfJarTask.flatMap(task -> task.getSrg().getLocationOnly());
                SrgCommandLineArgs srgArgs = project.getObjects().newInstance(SrgCommandLineArgs.class);
                srgArgs.getReobfSrgFile().set(reobfSrgLocation);
                srgArgs.getOutSrgFile().set(mixinSrg);
                srgArgs.getOutRefMapFile().set(mixinRefMapFile);
                final SourceSet mixinSourceSet = this.mixinSourceSet.get();
                project.getTasks().named(mixinSourceSet.getCompileJavaTaskName(), JavaCompile.class).configure(task -> {
                    task.getInputs().file(reobfSrg);
                    task.getOutputs().files(mixinSrg, mixinRefMapFile);
                    task.doFirst("createTempMixinDirectory", _t -> tempMixinDir.get().getAsFile().mkdirs());
                    task.getOptions().getCompilerArgumentProviders().add(srgArgs);
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
                            srgArgs.asArguments().forEach(jco::option);
                            return Unit.INSTANCE;
                        });
                        project.getTasks().withType(KaptTask.class).configureEach(task -> {
                            task.getInputs().file(reobfSrg);
                            task.getOutputs().files(mixinSrg, mixinRefMapFile);
                            task.doFirst("createTempMixinDirectory", _t -> tempMixinDir.get().getAsFile().mkdirs());
                        });
                    }
                });
                project.getTasks().named(mixinSourceSet.getProcessResourcesTaskName(), ProcessResources.class)
                        .configure(task -> {
                            task.from(mixinRefMapFile);
                            final String compileJava = mixinSourceSet.getCompileJavaTaskName();
                            task.dependsOn(compileJava);
                            final String compileScala = StringUtils.removeEnd(compileJava, "Java") + "Scala";
                            final String compileKotlin = StringUtils.removeEnd(compileJava, "Java") + "Kotlin";
                            project.getPlugins().withType(ScalaPlugin.class, scp -> { task.dependsOn(compileScala); });
                            project.getPlugins()
                                    .withId("org.jetbrains.kotlin.jvm", p -> { task.dependsOn(compileKotlin); });
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

    public abstract static class SrgCommandLineArgs implements CommandLineArgumentProvider {

        @InputFile
        @PathSensitive(PathSensitivity.RELATIVE)
        public abstract RegularFileProperty getReobfSrgFile();

        @OutputFile
        public abstract RegularFileProperty getOutSrgFile();

        @OutputFile
        public abstract RegularFileProperty getOutRefMapFile();

        @Override
        public Iterable<String> asArguments() {
            return Arrays.asList(
                    "-AreobfSrgFile=" + getReobfSrgFile().get().getAsFile(),
                    "-AoutSrgFile=" + getOutSrgFile().get().getAsFile(),
                    "-AoutRefMapFile=" + getOutRefMapFile().get().getAsFile());
        }
    }
}
