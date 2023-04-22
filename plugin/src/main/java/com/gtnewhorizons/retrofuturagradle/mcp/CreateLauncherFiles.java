package com.gtnewhorizons.retrofuturagradle.mcp;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.RegExUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

import com.gtnewhorizons.retrofuturagradle.util.Utilities;

public abstract class CreateLauncherFiles extends DefaultTask {

    @Input
    public abstract MapProperty<String, String> getInputResources();

    @Input
    public abstract MapProperty<String, String> getReplacementTokens();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    @TaskAction
    public void createLauncherFiles() throws IOException {
        final File outputDir = getOutputDir().get().getAsFile();
        if (!outputDir.exists()) {
            FileUtils.forceMkdir(outputDir);
        }
        final Map<String, String> replacements = getReplacementTokens().get();
        final String[] repFrom = replacements.keySet().toArray(new String[0]);
        final String[] repTo = replacements.values().stream().map(StringEscapeUtils::escapeJava).toArray(String[]::new);
        for (Map.Entry<String, String> inputResource : getInputResources().get().entrySet()) {
            FileUtils.write(
                    new File(outputDir, inputResource.getKey()),
                    StringUtils.replaceEachRepeatedly(inputResource.getValue(), repFrom, repTo),
                    StandardCharsets.UTF_8,
                    false);
        }
    }

    public void addResources(String stripPattern, Provider<List<String>> resPathList) {
        getInputResources().putAll(
                resPathList.map(
                        resPaths -> resPaths.stream().collect(
                                Collectors.toMap(
                                        p -> RegExUtils.removePattern(p, stripPattern),
                                        Utilities::readEmbeddedResourceText))));
    }
}
