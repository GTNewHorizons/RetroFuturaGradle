package com.gtnewhorizons.retrofuturagradle.util;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;

public interface IJarTransformTask extends IJarOutputTask {

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    RegularFileProperty getInputJar();
}
