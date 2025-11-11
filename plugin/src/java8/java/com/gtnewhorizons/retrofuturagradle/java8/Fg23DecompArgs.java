package com.gtnewhorizons.retrofuturagradle.java8;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.workers.WorkParameters;

public interface Fg23DecompArgs extends WorkParameters {

    DirectoryProperty getTempDir();

    RegularFileProperty getLogFile();

    RegularFileProperty getInputJar();

    RegularFileProperty getOutputDir();

    ConfigurableFileCollection getClasspath();
}
