package com.gtnewhorizons.retrofuturagradle.modutils;

import com.gtnewhorizons.retrofuturagradle.mcp.GenSrgMappingsTask;
import com.gtnewhorizons.retrofuturagradle.mcp.InjectTagsTask;
import com.gtnewhorizons.retrofuturagradle.mcp.SharedMCPTasks;
import com.gtnewhorizons.retrofuturagradle.util.Utilities;
import com.opencsv.CSVReader;
import org.apache.commons.io.FileUtils;
import org.cadixdev.lorenz.MappingSet;
import org.cadixdev.lorenz.io.MappingFormats;
import org.cadixdev.lorenz.io.MappingsWriter;
import org.cadixdev.lorenz.model.FieldMapping;
import org.cadixdev.lorenz.model.MethodMapping;
import org.cadixdev.lorenz.model.TopLevelClassMapping;
import org.cadixdev.mercury.Mercury;
import org.cadixdev.mercury.mixin.MixinRemapper;
import org.cadixdev.mercury.remapper.MercuryRemapper;
import org.gradle.api.DefaultTask;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.TaskAction;
import org.gradle.jvm.tasks.Jar;
import org.gradle.api.tasks.options.Option;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class MigrateMappingsTask extends DefaultTask {

    private static final boolean DEBUG_WRITE_DIFF = Boolean.parseBoolean(System.getenv("RFG_DEBUG_WRITE_MAPPING_DIFF"));

    private File mcpDir;
    private File inputDir;
    private File outputDir;

    @Option(option = "mcpDir", description = "The directory containing the mappings to migrate to, using MCP's fields.csv and methods.csv format.")
    public void setMcpDir(String mcpDir) {
        this.mcpDir = new File(mcpDir);
    }

    @Option(option = "inputDir", description = "The directory containing the source code to migrate.")
    public void setInputDir(String inputDir) {
        this.inputDir = new File(inputDir);
    }

    @Option(option = "outputDir", description = "The directory the migrated source code should be written to.")
    public void setOutputDir(String outputDir) {
        this.outputDir = new File(outputDir);
    }
    
    @TaskAction
    public void migrateMappings() throws Exception {
        if(mcpDir == null) {
            throw new IllegalArgumentException("--mcpDir must be provided.");
        }
        if(inputDir == null) {
            inputDir = new File(getProject().getProjectDir(), "src/main/java");
        }
        if(outputDir == null) {
            outputDir = new File(getProject().getProjectDir(), "src/main/java");
        }
        GenSrgMappingsTask genSrgMappings = (GenSrgMappingsTask)getProject().getTasks().getByName("generateForgeSrgMappings");
        File currentFields = genSrgMappings.getFieldsCsv().getAsFile().get();
        File currentMethods = genSrgMappings.getMethodsCsv().getAsFile().get();

        File target = mcpDir;
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
        mercury.getClassPath().add(((Jar)getProject().getTasks().getByName("packagePatchedMc")).getArchiveFile().get().getAsFile().toPath());
        mercury.getClassPath().add(((InjectTagsTask)getProject().getTasks().getByName("injectTags")).getOutputDir().getAsFile().get().toPath());
        for (File file : getProject().getConfigurations().getByName("compileClasspath").getFiles()) {
            mercury.getClassPath().add(file.toPath());
        }
        // Fixes some issues like broken javadoc in Forge, and JDT not understanding Hodgepodge's obfuscated voxelmap targets.
        mercury.setGracefulClasspathChecks(true);

        mercury.getProcessors().add(MixinRemapper.create(diffMcp));
        mercury.getProcessors().add(MercuryRemapper.create(diffMcp));

        mercury.setSourceCompatibility(getProject().getExtensions().getByType(JavaPluginExtension.class).getSourceCompatibility().toString());

        mercury.rewrite(inputDir.toPath(), outputDir.toPath());
    }

    /** Returns the difference between two mappings of the same root.
     * 
     * @param mappingsA Mappings from O to A.
     * @param mappingsB Mappings from O to B.
     * @return Mappings from A to B.
     */
    private MappingSet diff(MappingSet mappingsA, MappingSet mappingsB) {
        MappingSet diff = MappingSet.create();
        
        for(TopLevelClassMapping topLevelClassA : mappingsA.getTopLevelClassMappings()) {
            TopLevelClassMapping topLevelClassB = mappingsB.getTopLevelClassMapping(topLevelClassA.getFullObfuscatedName()).get();
            TopLevelClassMapping topLevelClassDiff = diff.createTopLevelClassMapping(
                    topLevelClassA.getFullDeobfuscatedName(),
                    topLevelClassB.getFullDeobfuscatedName());
            
            for(FieldMapping fieldA : topLevelClassA.getFieldMappings()) {
                FieldMapping fieldB = topLevelClassB.getFieldMapping(fieldA.getObfuscatedName()).get();
                
                topLevelClassDiff.createFieldMapping(
                        fieldA.getDeobfuscatedName())
                .setDeobfuscatedName(
                        fieldB.getDeobfuscatedName());
            }
            
            for(MethodMapping methodA : topLevelClassA.getMethodMappings()) {
                MethodMapping methodB = topLevelClassB.getMethodMapping(methodA.getObfuscatedName(), methodA.getObfuscatedDescriptor()).get();
                
                topLevelClassDiff.createMethodMapping(
                        methodA.getDeobfuscatedName(),
                        methodA.getDeobfuscatedDescriptor())
                .setDeobfuscatedName(
                        methodB.getDeobfuscatedName());
            }
        }
        
        return diff;
    }

    private MappingSet createSrgMcpMappingSet(MappingSet notchSrg, Map<String, String> fields, Map<String, String> methods) {
        MappingSet mcpSrg = MappingSet.create();

        for(TopLevelClassMapping notchSrgTopLevelClass : notchSrg.getTopLevelClassMappings()) {
            TopLevelClassMapping srgMcpTopLevelClass = mcpSrg.createTopLevelClassMapping(
                    notchSrgTopLevelClass.getFullDeobfuscatedName(),
                    notchSrgTopLevelClass.getFullDeobfuscatedName());
            
            for(FieldMapping notchSrgField : notchSrgTopLevelClass.getFieldMappings()) {
                srgMcpTopLevelClass.createFieldMapping(
                        notchSrgField.getDeobfuscatedName())
                .setDeobfuscatedName(fields.getOrDefault(
                        notchSrgField.getDeobfuscatedName(),
                        notchSrgField.getDeobfuscatedName()));
            }
            
            for(MethodMapping notchSrgMethod : notchSrgTopLevelClass.getMethodMappings()) {
                srgMcpTopLevelClass.createMethodMapping(
                        notchSrgMethod.getDeobfuscatedName(),
                        notchSrgMethod.getDeobfuscatedDescriptor())
                .setDeobfuscatedName(methods.getOrDefault(
                        notchSrgMethod.getDeobfuscatedName(),
                        notchSrgMethod.getDeobfuscatedName()));
            }
        }

        return mcpSrg;
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
