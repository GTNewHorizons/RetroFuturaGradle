package com.gtnewhorizons.retrofuturagradle.mcp;

import com.gtnewhorizons.retrofuturagradle.util.Utilities;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

@CacheableTask
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
        final String[] repTo = replacements.values().toArray(new String[0]);
        for (Map.Entry<String, String> inputResource : getInputResources().get().entrySet()) {
            FileUtils.write(
                    new File(outputDir, inputResource.getKey()),
                    StringUtils.replaceEachRepeatedly(inputResource.getValue(), repFrom, repTo),
                    StandardCharsets.UTF_8,
                    false);
        }
    }

    public void addResource(ProviderFactory providers, String resPath) {
        getInputResources().put(resPath, providers.provider(() -> Utilities.readEmbeddedResourceText(resPath)));
    }
}
