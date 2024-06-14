package com.gtnewhorizons.retrofuturagradle.mcp;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.channels.FileLock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import com.google.common.base.Charsets;
import com.google.common.collect.Maps;
import com.google.common.io.Files;
import com.gtnewhorizons.retrofuturagradle.fg12shadow.net.minecraftforge.srg2source.rangeapplier.MethodData;
import com.gtnewhorizons.retrofuturagradle.fg12shadow.net.minecraftforge.srg2source.rangeapplier.SrgContainer;
import com.gtnewhorizons.retrofuturagradle.util.Utilities;
import com.opencsv.CSVReader;

/**
 * Generates Deobf(Mcp)-Searge(Srg)-Obf(Notch) name mappings
 */
@CacheableTask
public abstract class GenSrgMappingsTask extends DefaultTask {

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getInputSrg();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getInputExc();

    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getExtraExcs();

    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getExtraSrgs();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getMethodsCsv();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getFieldsCsv();

    @OutputFile
    public abstract RegularFileProperty getNotchToSrg();

    @OutputFile
    public abstract RegularFileProperty getNotchToMcp();

    @OutputFile
    public abstract RegularFileProperty getMcpToNotch();

    @OutputFile
    public abstract RegularFileProperty getSrgToMcp();

    @OutputFile
    public abstract RegularFileProperty getMcpToSrg();

    @OutputFile
    public abstract RegularFileProperty getSrgExc();

    @OutputFile
    public abstract RegularFileProperty getMcpExc();

    @Internal
    public abstract Property<RfgCacheService> getCacheService();

    @TaskAction
    public void generateMappings() throws IOException {
        // SRG->MCP from the MCP csv files
        HashMap<String, String> methods = new HashMap<>(5000);
        HashMap<String, String> fields = new HashMap<>(5000);

        try (final FileLock ignored = getCacheService().get().lockCache(false)) {

            try (CSVReader csvReader = Utilities.createCsvReader(getMethodsCsv().get().getAsFile())) {
                for (String[] line : csvReader) {
                    methods.put(line[0], line[1]);
                }
            }
            try (CSVReader csvReader = Utilities.createCsvReader(getFieldsCsv().get().getAsFile())) {
                for (String[] line : csvReader) {
                    fields.put(line[0], line[1]);
                }
            }

            SrgContainer inSrg = new SrgContainer().readSrg(getInputSrg().get().getAsFile());
            Map<String, String> excRemap = Maps.newHashMap(); // Was a bunch of commented out code in ForgeGradle
            // Write outputs
            writeOutSrgs(inSrg, methods, fields);
            writeOutExcs(excRemap, methods);
        }
    }

    // Copied straight from ForgeGradle
    private void writeOutSrgs(SrgContainer inSrg, Map<String, String> methods, Map<String, String> fields)
            throws IOException {
        // ensure folders exist
        com.google.common.io.Files.createParentDirs(getNotchToSrg().get().getAsFile());
        com.google.common.io.Files.createParentDirs(getNotchToMcp().get().getAsFile());
        com.google.common.io.Files.createParentDirs(getSrgToMcp().get().getAsFile());
        com.google.common.io.Files.createParentDirs(getMcpToSrg().get().getAsFile());
        com.google.common.io.Files.createParentDirs(getMcpToNotch().get().getAsFile());

        // create streams

        try (BufferedWriter notchToSrg = Files.newWriter(getNotchToSrg().get().getAsFile(), Charsets.UTF_8);
                BufferedWriter notchToMcp = Files.newWriter(getNotchToMcp().get().getAsFile(), Charsets.UTF_8);
                BufferedWriter srgToMcp = Files.newWriter(getSrgToMcp().get().getAsFile(), Charsets.UTF_8);
                BufferedWriter mcpToSrg = Files.newWriter(getMcpToSrg().get().getAsFile(), Charsets.UTF_8);
                BufferedWriter mcpToNotch = Files.newWriter(getMcpToNotch().get().getAsFile(), Charsets.UTF_8)) {
            String line, temp, mcpName;
            // packages
            for (Map.Entry<String, String> e : inSrg.packageMap.entrySet()) {
                line = "PK: " + e.getKey() + " " + e.getValue();

                // nobody cares about the packages.
                notchToSrg.write(line);
                notchToSrg.newLine();

                notchToMcp.write(line);
                notchToMcp.newLine();

                // No package changes from MCP to SRG names
                // srgToMcp.write(line);
                // srgToMcp.newLine();

                // No package changes from MCP to SRG names
                // mcpToSrg.write(line);
                // mcpToSrg.newLine();

                // reverse!
                mcpToNotch.write(String.format("PK: %s %s", e.getValue(), e.getKey()));
                mcpToNotch.newLine();
            }

            // classes
            for (Map.Entry<String, String> e : inSrg.classMap.entrySet()) {
                line = String.format("CL: %s %s", e.getKey(), e.getValue());

                // same...
                notchToSrg.write(line);
                notchToSrg.newLine();

                // SRG and MCP have the same class names
                notchToMcp.write(line);
                notchToMcp.newLine();

                line = String.format("CL: %s %s", e.getValue(), e.getValue());

                // deobf: same classes on both sides.
                srgToMcp.write(line);
                srgToMcp.newLine();

                // reobf: same classes on both sides.
                mcpToSrg.write(line);
                mcpToSrg.newLine();

                // output is notch
                mcpToNotch.write(line);
                mcpToNotch.newLine();
            }

            // fields
            for (Map.Entry<String, String> e : inSrg.fieldMap.entrySet()) {
                line = String.format("FD: %s %s", e.getKey(), e.getValue());

                // same...
                notchToSrg.write(line);
                notchToSrg.newLine();

                temp = e.getValue().substring(e.getValue().lastIndexOf('/') + 1);
                mcpName = e.getValue();
                if (fields.containsKey(temp)) mcpName = mcpName.replace(temp, fields.get(temp));

                // SRG and MCP have the same class names
                notchToMcp.write(String.format("FD: %s %s", e.getKey(), mcpName));
                notchToMcp.newLine();

                // srg name -> mcp name
                srgToMcp.write(String.format("FD: %s %s", e.getValue(), mcpName));
                srgToMcp.newLine();

                // mcp name -> srg name
                mcpToSrg.write(String.format("FD: %s %s", mcpName, e.getValue()));
                mcpToSrg.newLine();

                // output is notch
                mcpToNotch.write(String.format("FD: %s %s", mcpName, e.getKey()));
                mcpToNotch.newLine();
            }

            // methods
            for (Map.Entry<MethodData, MethodData> e : inSrg.methodMap.entrySet()) {
                line = String.format("MD: %s %s", e.getKey(), e.getValue());

                // same...
                notchToSrg.write(line);
                notchToSrg.newLine();

                temp = e.getValue().name.substring(e.getValue().name.lastIndexOf('/') + 1);
                mcpName = e.getValue().toString();
                if (methods.containsKey(temp)) mcpName = mcpName.replace(temp, methods.get(temp));

                // SRG and MCP have the same class names
                notchToMcp.write(String.format("MD: %s %s", e.getKey(), mcpName));
                notchToMcp.newLine();

                // srg name -> mcp name
                srgToMcp.write(String.format("MD: %s %s", e.getValue(), mcpName));
                srgToMcp.newLine();

                // mcp name -> srg name
                mcpToSrg.write(String.format("MD: %s %s", mcpName, e.getValue()));
                mcpToSrg.newLine();

                // output is notch
                mcpToNotch.write(String.format("MD: %s %s", mcpName, e.getKey()));
                mcpToNotch.newLine();
            }
        }
    }

    private void writeOutExcs(Map<String, String> excRemap, Map<String, String> methods) throws IOException {
        // ensure folders exist
        com.google.common.io.Files.createParentDirs(getSrgExc().get().getAsFile());
        com.google.common.io.Files.createParentDirs(getMcpExc().get().getAsFile());

        // create streams
        try (BufferedWriter srgOut = com.google.common.io.Files
                .newWriter(getSrgExc().get().getAsFile(), Charsets.UTF_8);
                BufferedWriter mcpOut = com.google.common.io.Files
                        .newWriter(getMcpExc().get().getAsFile(), Charsets.UTF_8)) {

            // read and write existing lines
            Set<File> excFiles = new HashSet<>(getExtraExcs().getFiles());
            excFiles.add(getInputExc().get().getAsFile());
            for (File f : excFiles) {
                List<String> lines = Files.readLines(f, Charsets.UTF_8);

                for (String line : lines) {
                    // these are in MCP names
                    srgOut.write(line);
                    srgOut.newLine();

                    // remap SRG

                    // split line up
                    String[] split = line.split("=");
                    int sigIndex = split[0].indexOf('(');
                    int dotIndex = split[0].indexOf('.');

                    // not a method? wut?
                    if (sigIndex == -1 || dotIndex == -1) {
                        mcpOut.write(line);
                        mcpOut.newLine();
                        continue;
                    }

                    // get new name
                    String name = split[0].substring(dotIndex + 1, sigIndex);
                    if (excRemap.containsKey(name)) name = excRemap.get(name);

                    // write remapped line
                    mcpOut.write(
                            split[0].substring(0, dotIndex) + name + split[0].substring(sigIndex) + "=" + split[1]);
                    mcpOut.newLine();
                }
            }
        }
    }
}
