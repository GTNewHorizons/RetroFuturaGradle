package com.gtnewhorizons.retrofuturagradle.util;

import org.gradle.api.Task;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.OutputFile;

public interface IJarOutputTask extends Task {

    @OutputFile
    RegularFileProperty getOutputJar();

    /**
     * @return A function that updates this digest with a hash of all the non-jar inputs.
     */
    MessageDigestConsumer hashInputs();

}
