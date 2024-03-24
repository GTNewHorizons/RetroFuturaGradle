package com.gtnewhorizons.retrofuturagradle.minecraft;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;

import org.apache.commons.lang3.SystemUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileTree;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

public abstract class ExtractNativesTask extends DefaultTask {

    @InputFiles
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract ConfigurableFileCollection getNatives();

    @OutputDirectory
    public abstract DirectoryProperty getDestinationFolder();

    public void configureMe(Project project, File targetDirectory, Configuration lwjglConfiguration,
            Configuration vanillaMcConfiguration) {
        this.getNatives().from(project.provider(() -> {
            final String twitchNatives;
            final String lwjglNatives = (String) project.getExtensions().getByName("lwjglNatives");
            if (SystemUtils.IS_OS_WINDOWS) {
                twitchNatives = "natives-windows-64";
            } else if (SystemUtils.IS_OS_MAC) {
                twitchNatives = "natives-osx";
            } else {
                twitchNatives = "natives-linux"; // don't actually exist
            }
            final FileCollection lwjglZips = lwjglConfiguration.filter(
                    f -> f.getName().contains("lwjgl-platform")
                            || (f.getName().contains("lwjgl") && f.getName().contains(lwjglNatives)));
            final FileCollection twitchZips = vanillaMcConfiguration
                    .filter(f -> f.getName().contains("twitch") && f.getName().contains(twitchNatives));
            final FileCollection ttsZips = vanillaMcConfiguration
                    .filter(f -> f.getName().contains("text2speech") && f.getName().contains("natives"));
            final FileCollection jinputZips = vanillaMcConfiguration
                    .filter(f -> f.getName().contains("jinput-platform"));
            final FileCollection zips = lwjglZips.plus(twitchZips).plus(ttsZips).plus(jinputZips);
            final ArrayList<FileTree> trees = new ArrayList<>();
            for (File zip : zips) {
                trees.add(project.zipTree(zip));
            }
            return trees;
        }));
        // this.exclude("META-INF/**", "META-INF");
        this.getDestinationFolder().set(targetDirectory);
    }

    @TaskAction
    public void extract() throws IOException {
        final Path destFolder = getDestinationFolder().get().getAsFile().toPath();
        for (final File sourceFile : getNatives()) {
            final Path source = sourceFile.toPath();
            String path = sourceFile.getPath();
            // If there's someone out there who has a username of META-INF, this will have to be changed
            if (path.contains("META-INF")) {
                continue;
            }
            final Path destination = destFolder.resolve(source.getFileName());
            boolean update = true;
            if (Files.exists(destination)) {
                final byte[] srcBytes = Files.readAllBytes(source);
                final byte[] dstBytes = Files.readAllBytes(destination);
                if (Arrays.equals(srcBytes, dstBytes)) {
                    update = false;
                }
            }
            if (update) {
                Files.copy(
                        source,
                        destination,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }
}
