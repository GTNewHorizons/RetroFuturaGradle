package com.gtnewhorizons.retrofuturagradle.modutils;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileTree;
import org.gradle.api.file.RegularFile;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.SetProperty;

import com.gtnewhorizons.retrofuturagradle.MinecraftExtension;
import com.gtnewhorizons.retrofuturagradle.ObfuscationAttribute;
import com.gtnewhorizons.retrofuturagradle.mcp.GenSrgMappingsTask;
import com.gtnewhorizons.retrofuturagradle.mcp.MCPTasks;
import com.gtnewhorizons.retrofuturagradle.minecraft.MinecraftTasks;
import com.gtnewhorizons.retrofuturagradle.util.Utilities;

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

    /**
     * Attribute that controls running the deobfuscation artifact transform.
     */
    public static final Attribute<Boolean> DEOBFUSCATOR_TRANSFORMED = Attribute
            .of("rfgDeobfuscatorTransformed", Boolean.class);

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

        project.getTasks().register("applyDecompilerCleanupToMain", ApplyDecompCleanupTask.class, task -> {
            task.setGroup(TASK_GROUP_USER);
            task.setDescription(
                    "Apply MCP decompiler cleanup to the main source set, doing things like replacing numerical OpenGL constants with their names");
        });

        project.getTasks().register("updateDependencies", UpdateDependenciesTask.class, task -> {
            task.setGroup(TASK_GROUP_USER);
            task.setDescription(
                    "Updates dependencies described at dependencies.gradle. Currently only supports GTNH repositories.");
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
        });

        project.afterEvaluate(_p -> {
            project.getDependencies().getArtifactTypes().getByName("jar").getAttributes()
                    .attribute(DEOBFUSCATOR_TRANSFORMED, Boolean.FALSE);
            project.getConfigurations().configureEach(cfg -> {
                ObfuscationAttribute requiredObfuscation = cfg.getAttributes()
                        .getAttribute(ObfuscationAttribute.OBFUSCATION_ATTRIBUTE);
                if (requiredObfuscation.getName().equals(ObfuscationAttribute.MCP)) {
                    cfg.getAttributes().attribute(DEOBFUSCATOR_TRANSFORMED, Boolean.TRUE);
                }
            });
        });

        deps.getExtensions().add("rfg", new RfgDependencyExtension());
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
