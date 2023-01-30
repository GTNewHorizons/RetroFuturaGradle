package com.gtnewhorizons.retrofuturagradle.util;

import java.security.MessageDigest;

import org.gradle.api.Task;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.OutputFile;

public interface IJarOutputTask extends Task {

    @OutputFile
    RegularFileProperty getOutputJar();

    /**
     * @param digest Update this digest with a hash of all the non-jar inputs.
     */
    void hashInputs(MessageDigest digest);

}
