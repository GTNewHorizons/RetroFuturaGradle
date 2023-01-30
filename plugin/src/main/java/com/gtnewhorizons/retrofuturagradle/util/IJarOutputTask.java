package com.gtnewhorizons.retrofuturagradle.util;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.OutputFile;

public interface IJarOutputTask {

    @OutputFile
    RegularFileProperty getOutputJar();
}
