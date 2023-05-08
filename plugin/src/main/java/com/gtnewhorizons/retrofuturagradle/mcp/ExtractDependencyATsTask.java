package com.gtnewhorizons.retrofuturagradle.mcp;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

public abstract class ExtractDependencyATsTask extends DefaultTask {

    @InputFiles
    @Classpath
    public abstract ConfigurableFileCollection getDependencies();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @TaskAction
    public void extract() throws IOException {
        final Set<File> deps = getDependencies().getFiles();
        final ArrayList<String> output = new ArrayList<>();

        final Attributes.Name fmlAtName = new Attributes.Name("FMLAT");

        for (File dep : deps) {
            if (dep.isDirectory()) {
                final File metaInf = new File(dep, "META-INF");
                final File manifestFile = new File(metaInf, "MANIFEST.MF");
                if (!manifestFile.isFile()) {
                    continue;
                }
                try (final FileInputStream fis = FileUtils.openInputStream(manifestFile);
                        final BufferedInputStream bis = new BufferedInputStream(fis)) {
                    final Manifest mf = new Manifest(bis);
                    final String atNames = mf.getMainAttributes().getValue(fmlAtName);
                    if (atNames == null || atNames.isEmpty()) {
                        continue;
                    }
                    for (String atName : atNames.split(" ")) {
                        atName = atName.trim();
                        if (atName.isEmpty()) {
                            continue;
                        }
                        final File atFile = new File(metaInf, atName);
                        final String atContent = FileUtils.readFileToString(atFile, StandardCharsets.UTF_8)
                                .replaceAll("\r\n", "\n");
                        output.add("# RFG-Merged: " + StringEscapeUtils.escapeJava(dep.getName() + "/" + atName));
                        output.addAll(Arrays.asList(atContent.split("\\R")));
                    }
                }
            } else if (dep.isFile() && dep.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                try (JarFile jar = new JarFile(dep, false)) {
                    final Manifest mf = jar.getManifest();
                    if (mf == null) {
                        continue;
                    }
                    final String atNames = mf.getMainAttributes().getValue(fmlAtName);
                    if (atNames == null || atNames.isEmpty()) {
                        continue;
                    }
                    for (String atName : atNames.split(" ")) {
                        atName = atName.trim();
                        if (atName.isEmpty()) {
                            continue;
                        }
                        final String atJarPath = "META-INF/" + atName;
                        final ZipEntry entry = jar.getEntry(atJarPath);
                        if (entry == null) {
                            getLogger().warn("Dependency AT '{}'!'{}' not found, skipping.", dep.getName(), atJarPath);
                            continue;
                        }
                        final String atContent;
                        try (final InputStream is = jar.getInputStream(entry)) {
                            atContent = IOUtils.toString(is, StandardCharsets.UTF_8);
                        }
                        output.add("# RFG-Merged: " + StringEscapeUtils.escapeJava(dep.getName() + "/" + atName));
                        output.addAll(Arrays.asList(atContent.split("\\R")));
                    }
                }
            }
        }

        final File outputFile = getOutputFile().getAsFile().get();
        FileUtils.writeStringToFile(
                outputFile,
                StringUtils.join(output, System.lineSeparator()),
                StandardCharsets.UTF_8,
                false);
    }
}
