package com.gtnewhorizons.retrofuturagradle.modutils;

import java.io.File;
import java.nio.file.Files;
import java.util.Collections;
import java.util.stream.Collectors;

import javax.inject.Inject;

import net.fabricmc.mappingio.adapter.MappingNsRenamer;
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch;
import net.fabricmc.mappingio.format.srg.SrgFileReader;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import net.fabricmc.tinyremapper.IMappingProvider;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.TinyUtils;

import org.cadixdev.mercury.Mercury;
import org.cadixdev.mercury.remapper.MercuryRemapper;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;

import com.gtnewhorizons.retrofuturagradle.util.Utilities;

public abstract class MigrateMappingsTask extends DefaultTask {

    @Optional
    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    @Option(
            option = "mcpDir",
            description = "The directory containing the mappings to migrate to, using MCP's fields.csv and methods.csv format.")
    public abstract DirectoryProperty getMcpDir();

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    @Option(option = "inputDir", description = "The directory containing the source code to migrate.")
    public abstract DirectoryProperty getInputDir();

    @OutputDirectory
    @Option(option = "outputDir", description = "The directory the migrated source code should be written to.")
    public abstract DirectoryProperty getOutputDir();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getSourceSrg();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getSourceFieldsCsv();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getSourceMethodsCsv();

    @InputFiles
    @Classpath
    public abstract ConfigurableFileCollection getCompileClasspath();

    @Inject
    public MigrateMappingsTask() {
        getInputDir().convention(getProject().getLayout().getProjectDirectory().dir("src/main/java"));
        getOutputDir().convention(getProject().getLayout().getProjectDirectory().dir("src/main/java"));
    }

    @TaskAction
    public void migrateMappings() throws Exception {
        if (!getMcpDir().isPresent()) {
            throw new IllegalArgumentException("A target mapping must be set using --mcpDir.");
        }
        File sourceFields = getSourceFieldsCsv().getAsFile().get();
        File sourceMethods = getSourceMethodsCsv().getAsFile().get();

        File target = getMcpDir().get().getAsFile();
        File srg = getSourceSrg().getAsFile().get();

        MemoryMappingTree notchSrg = new MemoryMappingTree();
        SrgFileReader.read(Files.newBufferedReader(srg.toPath()), "official", "srg", notchSrg);

        MemoryMappingTree sourceSrgMcp = new MemoryMappingTree();
        Utilities.loadSrgMcpMappings(sourceSrgMcp, notchSrg, sourceMethods, sourceFields, null, null);

        MemoryMappingTree targetSrgMcp = new MemoryMappingTree();
        Utilities.loadSrgMcpMappings(
                targetSrgMcp,
                notchSrg,
                new File(target, "methods.csv"),
                new File(target, "fields.csv"),
                null,
                null);

        MemoryMappingTree joinedSrgMcp = new MemoryMappingTree();
        joinedSrgMcp.setSrcNamespace("srg");
        sourceSrgMcp.accept(new MappingNsRenamer(joinedSrgMcp, Collections.singletonMap("mcp", "mcpSource")));
        targetSrgMcp.accept(new MappingNsRenamer(joinedSrgMcp, Collections.singletonMap("mcp", "mcpTarget")));

        MemoryMappingTree diffMcp = new MemoryMappingTree();
        diffMcp.setSrcNamespace("mcpSource");
        joinedSrgMcp.accept(new MappingSourceNsSwitch(diffMcp, "mcpSource"));
        diffMcp.setDstNamespaces(Collections.singletonList("mcpTarget"));

        IMappingProvider tinyMap = TinyUtils.createMappingProvider(diffMcp, "srg", "mcpSource");
        TinyRemapper remapper = TinyRemapper.newRemapper().withMappings(tinyMap).build();

        final Mercury mercury = new Mercury();

        mercury.getClassPath().addAll(
                getCompileClasspath().getFiles().stream().map(File::toPath).filter(Files::exists)
                        .collect(Collectors.toList()));

        // Fixes some issues like broken javadoc in Forge, and JDT not understanding Hodgepodge's obfuscated voxelmap
        // targets.
        mercury.setGracefulClasspathChecks(true);

        mercury.getProcessors().add(MercuryRemapper.create(remapper.getEnvironment()));

        mercury.setSourceCompatibility(
                getProject().getExtensions().getByType(JavaPluginExtension.class).getSourceCompatibility().toString());

        mercury.rewrite(getInputDir().getAsFile().get().toPath(), getOutputDir().getAsFile().get().toPath());
    }

}
