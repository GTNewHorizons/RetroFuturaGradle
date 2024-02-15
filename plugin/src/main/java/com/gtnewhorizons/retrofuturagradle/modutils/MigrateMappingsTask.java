package com.gtnewhorizons.retrofuturagradle.modutils;

import com.gtnewhorizons.retrofuturagradle.mcp.GenSrgMappingsTask;
import com.gtnewhorizons.retrofuturagradle.mcp.InjectTagsTask;
import com.gtnewhorizons.retrofuturagradle.util.Utilities;
import com.opencsv.CSVReader;
import org.cadixdev.lorenz.MappingSet;
import org.cadixdev.lorenz.io.MappingFormats;
import org.cadixdev.lorenz.io.MappingsWriter;
import org.cadixdev.lorenz.model.ClassMapping;
import org.cadixdev.lorenz.model.FieldMapping;
import org.cadixdev.lorenz.model.InnerClassMapping;
import org.cadixdev.lorenz.model.MethodMapping;
import org.cadixdev.lorenz.model.TopLevelClassMapping;
import org.cadixdev.mercury.Mercury;
import org.cadixdev.mercury.mixin.MixinRemapper;
import org.cadixdev.mercury.remapper.MercuryRemapper;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.jvm.tasks.Jar;
import org.gradle.api.tasks.options.Option;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public abstract class MigrateMappingsTask extends DefaultTask {

    private static final boolean DEBUG_WRITE_DIFF = Boolean.parseBoolean(System.getenv("RFG_DEBUG_WRITE_MAPPING_DIFF"));

    @Optional
    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    @Option(option = "mcpDir", description = "The directory containing the mappings to migrate to, using MCP's fields.csv and methods.csv format.")
    public abstract DirectoryProperty getMcpDir();

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    @Option(option = "inputDir", description = "The directory containing the source code to migrate.")
    public abstract DirectoryProperty getInputDir();

    @OutputDirectory
    @Option(option = "outputDir", description = "The directory the migrated source code should be written to.")
    public abstract DirectoryProperty getOutputDir();

    @Inject
    public MigrateMappingsTask() {
        getInputDir().convention(getProject().getLayout().getProjectDirectory().dir("src/main/java"));
        getOutputDir().convention(getProject().getLayout().getProjectDirectory().dir("src/main/java"));
    }
    
    @TaskAction
    public void migrateMappings() throws Exception {
        if(!getMcpDir().isPresent()) {
            throw new IllegalArgumentException("A target mapping must be set using --mcpDir.");
        }
        GenSrgMappingsTask genSrgMappings = getProject().getTasks().named("generateForgeSrgMappings", GenSrgMappingsTask.class).get();
        File currentFields = genSrgMappings.getFieldsCsv().getAsFile().get();
        File currentMethods = genSrgMappings.getMethodsCsv().getAsFile().get();

        File target = getMcpDir().get().getAsFile();
        File srg = genSrgMappings.getInputSrg().getAsFile().get();

        MappingSet notchSrg = MappingFormats.SRG.read(srg.toPath());
        MappingSet currentSrgMcp = createSrgMcpMappingSet(notchSrg, readCsv(currentFields), readCsv(currentMethods));
        MappingSet targetSrgMcp = createSrgMcpMappingSet(notchSrg, readCsv(new File(target, "fields.csv")), readCsv(new File(target, "methods.csv")));
        MappingSet diffMcp = diff(currentSrgMcp, targetSrgMcp);

        if(DEBUG_WRITE_DIFF) {
            try (MappingsWriter w = MappingFormats.SRG.createWriter(new File(getProject().getRootDir(), "diff.srg").toPath())) {
                w.write(diffMcp);
            }
        }

        final Mercury mercury = new Mercury();

        // There's probably a better way to do this
        mercury.getClassPath().add(getProject().getTasks().named("packagePatchedMc", Jar.class).get().getArchiveFile().get().getAsFile().toPath());
        mercury.getClassPath().add(getProject().getTasks().named("injectTags", InjectTagsTask.class).get().getOutputDir().getAsFile().get().toPath());
        for (File file : getProject().getConfigurations().getByName("compileClasspath").getFiles()) {
            mercury.getClassPath().add(file.toPath());
        }
        // Fixes some issues like broken javadoc in Forge, and JDT not understanding Hodgepodge's obfuscated voxelmap targets.
        mercury.setGracefulClasspathChecks(true);

        mercury.getProcessors().add(MixinRemapper.create(diffMcp));
        mercury.getProcessors().add(MercuryRemapper.create(diffMcp));

        mercury.setSourceCompatibility(getProject().getExtensions().getByType(JavaPluginExtension.class).getSourceCompatibility().toString());

        mercury.rewrite(getInputDir().getAsFile().get().toPath(), getOutputDir().getAsFile().get().toPath());
    }

    /** Returns the difference between two mappings of the same root.
     *
     * @param mappingsA Mappings from O to A.
     * @param mappingsB Mappings from O to B.
     * @return Mappings from A to B.
     */
    private MappingSet diff(MappingSet mappingsA, MappingSet mappingsB) {
        MappingSet diff = MappingSet.create();

        forEachClassMapping(mappingsA, classA -> {
            ClassMapping<?, ?> classB = mappingsB.getClassMapping(classA.getFullObfuscatedName()).get();
            ClassMapping<?, ?> classDiff = diff.getOrCreateClassMapping(
                    classA.getFullDeobfuscatedName());

            for(FieldMapping fieldA : classA.getFieldMappings()) {
                FieldMapping fieldB = classB.getFieldMapping(fieldA.getObfuscatedName()).get();

                classDiff.createFieldMapping(
                                fieldA.getDeobfuscatedName())
                        .setDeobfuscatedName(
                                fieldB.getDeobfuscatedName());
            }

            for(MethodMapping methodA : classA.getMethodMappings()) {
                MethodMapping methodB = classB.getMethodMapping(methodA.getObfuscatedName(), methodA.getObfuscatedDescriptor()).get();

                classDiff.createMethodMapping(
                                methodA.getDeobfuscatedName(),
                                methodA.getDeobfuscatedDescriptor())
                        .setDeobfuscatedName(
                                methodB.getDeobfuscatedName());
            }
        });

        return diff;
    }

    private void forEachClassMapping(MappingSet mappings, Consumer<ClassMapping<?, ?>> callback) {
        for(TopLevelClassMapping topLevelClass : mappings.getTopLevelClassMappings()) {
            callback.accept(topLevelClass);
            for(InnerClassMapping inner : topLevelClass.getInnerClassMappings()) {
                forEachClassMappingInner(inner, callback);
            }
        }
    }

    private void forEachClassMappingInner(InnerClassMapping inner, Consumer<ClassMapping<?, ?>> callback) {
        callback.accept(inner);
        for(InnerClassMapping innerer : inner.getInnerClassMappings()) {
            forEachClassMappingInner(innerer, callback);
        }
    }

    private MappingSet createSrgMcpMappingSet(MappingSet notchSrg, Map<String, String> fields, Map<String, String> methods) {
        MappingSet srgMcp = MappingSet.create();

        // In 1.7 everything is top level because proguard strips inner class info
        for(TopLevelClassMapping notchSrgTopLevelClass : notchSrg.getTopLevelClassMappings()) {
            ClassMapping<?, ?> srgMcpClass = srgMcp.getOrCreateClassMapping(notchSrgTopLevelClass.getFullDeobfuscatedName());

            for(FieldMapping notchSrgField : notchSrgTopLevelClass.getFieldMappings()) {
                srgMcpClass.createFieldMapping(
                                notchSrgField.getDeobfuscatedName())
                        .setDeobfuscatedName(fields.getOrDefault(
                                notchSrgField.getDeobfuscatedName(),
                                notchSrgField.getDeobfuscatedName()));
            }

            for(MethodMapping notchSrgMethod : notchSrgTopLevelClass.getMethodMappings()) {
                srgMcpClass.createMethodMapping(
                                notchSrgMethod.getDeobfuscatedName(),
                                notchSrgMethod.getDeobfuscatedDescriptor())
                        .setDeobfuscatedName(methods.getOrDefault(
                                notchSrgMethod.getDeobfuscatedName(),
                                notchSrgMethod.getDeobfuscatedName()));
            }
        }

        return srgMcp;
    }

    private static Map<String, String> readCsv(File csv) throws IOException {
        Map<String, String> names = new HashMap<>(5000);
        try (CSVReader csvReader = Utilities.createCsvReader(csv)) {
            for (String[] line : csvReader) {
                names.put(line[0], line[1]);
            }
        }
        return names;
    }

}
