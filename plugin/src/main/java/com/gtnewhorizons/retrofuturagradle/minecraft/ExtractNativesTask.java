package com.gtnewhorizons.retrofuturagradle.minecraft;

import java.io.File;
import java.util.ArrayList;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileTree;
import org.gradle.api.tasks.Copy;
import org.gradle.internal.os.OperatingSystem;

public class ExtractNativesTask extends Copy {

    public void configureMe(Project project, File targetDirectory, Configuration lwjglConfiguration,
            Configuration vanillaMcConfiguration) {
        this.from(project.provider(() -> {
            final OperatingSystem os = OperatingSystem.current();
            final String twitchNatives;
            final String lwjglNatives = (String) project.getExtensions().getByName("lwjglNatives");
            if (os.isWindows()) {
                twitchNatives = "natives-windows-64";
            } else if (os.isMacOsX()) {
                twitchNatives = "natives-osx";
            } else {
                twitchNatives = "natives-linux"; // don't actually exist
            }
            final FileCollection lwjglZips = lwjglConfiguration
                    .filter(f -> f.getName().contains("lwjgl") && f.getName().contains(lwjglNatives));
            final FileCollection twitchZips = vanillaMcConfiguration
                    .filter(f -> f.getName().contains("twitch") && f.getName().contains(twitchNatives));
            final FileCollection ttsZips = vanillaMcConfiguration
                    .filter(f -> f.getName().contains("text2speech") && f.getName().contains("natives"));
            final FileCollection zips = lwjglZips.plus(twitchZips).plus(ttsZips);
            final ArrayList<FileTree> trees = new ArrayList<>();
            for (File zip : zips) {
                trees.add(project.zipTree(zip));
            }
            return trees;
        }));
        this.exclude("META-INF/**", "META-INF");
        this.into(targetDirectory);
    }
}
