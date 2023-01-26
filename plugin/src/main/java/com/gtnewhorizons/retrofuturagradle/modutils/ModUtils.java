package com.gtnewhorizons.retrofuturagradle.modutils;

import com.gtnewhorizons.retrofuturagradle.MinecraftExtension;
import com.gtnewhorizons.retrofuturagradle.ObfuscationAttribute;
import com.gtnewhorizons.retrofuturagradle.mcp.MCPTasks;
import com.gtnewhorizons.retrofuturagradle.minecraft.MinecraftTasks;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.ModuleIdentifier;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.internal.artifacts.DefaultModuleIdentifier;
import org.gradle.api.model.ObjectFactory;

/**
 * Gradle utilities for developing mods
 */
public class ModUtils {
    private final Project project;
    private final MinecraftExtension mcExt;
    private final MinecraftTasks minecraftTasks;
    private final MCPTasks mcpTasks;

    public ModUtils(Project project, MinecraftExtension mcExt, MinecraftTasks minecraftTasks, MCPTasks mcpTasks) {
        this.project = project;
        this.mcExt = mcExt;
        this.minecraftTasks = minecraftTasks;
        this.mcpTasks = mcpTasks;

        final DependencyHandler deps = project.getDependencies();
        final ObjectFactory objects = project.getObjects();
        final ConfigurationContainer configs = project.getConfigurations();

        // Dependency deobfuscation utilities
        /* see comment on deobfuscate
        deps.registerTransform(DependencyDeobfuscationTransform.class, spec -> {
            spec.getFrom().attribute(ObfuscationAttribute.OBFUSCATION_ATTRIBUTE, ObfuscationAttribute.getSrg(objects));
            spec.getTo().attribute(ObfuscationAttribute.OBFUSCATION_ATTRIBUTE, ObfuscationAttribute.getMcp(objects));
            final DependencyDeobfuscationTransform.Parameters params = spec.getParameters();
            params.getSrgFile()
                    .set(mcpTasks.getTaskGenerateForgeSrgMappings().flatMap(GenSrgMappingsTask::getSrgToMcp));
            params.getFieldsCsv()
                    .set(mcpTasks.getTaskGenerateForgeSrgMappings().flatMap(GenSrgMappingsTask::getFieldsCsv));
            params.getMethodsCsv()
                    .set(mcpTasks.getTaskGenerateForgeSrgMappings().flatMap(GenSrgMappingsTask::getMethodsCsv));
            params.getMinecraftJar()
                    .set(mcpTasks.getTaskDeobfuscateMergedJarToSrg().flatMap(DeobfuscateTask::getOutputJar));
        });*/
    }

    // TODO: Make public once Gradle fixes artifact transforms
    private Dependency deobfuscate(Object depSpec) {
        final DependencyHandler deps = project.getDependencies();
        final Dependency rawDep = deps.create(depSpec);
        if (!(rawDep instanceof ModuleDependency)) {
            throw new IllegalArgumentException(
                    "deobfuscate() needs a ModuleDependency, was given " + rawDep.getClass());
        }
        ModuleDependency dep = (ModuleDependency) rawDep;
        final ModuleIdentifier moduleSpec = DefaultModuleIdentifier.newId(dep.getGroup(), dep.getName());
        deps.getComponents().withModule(moduleSpec, ctx -> {
            ctx.allVariants(variant -> {
                variant.getAttributes()
                        .attribute(
                                ObfuscationAttribute.OBFUSCATION_ATTRIBUTE,
                                ObfuscationAttribute.getSrg(project.getObjects()));
                for (Attribute attrib : variant.getAttributes().keySet()) {
                    project.getLogger()
                            .warn(String.format(
                                    " - %s : %s",
                                    attrib.toString(),
                                    variant.getAttributes().getAttribute(attrib).toString()));
                }
            });
        });
        return rawDep;
    }
}
