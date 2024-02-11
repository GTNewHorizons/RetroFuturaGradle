package com.gtnewhorizons.retrofuturagradle.modutils;

import com.gtnewhorizons.retrofuturagradle.mcp.GenSrgMappingsTask;
import com.gtnewhorizons.retrofuturagradle.util.Utilities;
import com.opencsv.CSVReader;
import org.cadixdev.lorenz.MappingSet;
import org.cadixdev.lorenz.io.MappingFormats;
import org.cadixdev.lorenz.io.MappingsWriter;
import org.cadixdev.lorenz.model.FieldMapping;
import org.cadixdev.lorenz.model.MethodMapping;
import org.cadixdev.lorenz.model.TopLevelClassMapping;
import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class MigrateMappingsTask extends DefaultTask {

    private static final boolean DEBUG_WRITE_DIFF = Boolean.parseBoolean(System.getenv("RFG_DEBUG_WRITE_MAPPING_DIFF"));

    private String mcpDir;

    @Option(option = "mcpDir", description = "A directory containing the mappings to migrate to, using MCP's fields.csv and methods.csv format.")
    public void setMcpDir(String mcpDir) {
        this.mcpDir = mcpDir;
    }
    
    @TaskAction
    public void migrateMappings() throws IOException {
        if(mcpDir == null) {
            throw new IllegalArgumentException("--mcpDir must be provided.");
        }
        GenSrgMappingsTask genSrgMappings = (GenSrgMappingsTask)getProject().getTasks().getByName("generateForgeSrgMappings");
        File currentFields = genSrgMappings.getFieldsCsv().getAsFile().get();
        File currentMethods = genSrgMappings.getMethodsCsv().getAsFile().get();

        File target = new File(mcpDir);
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
