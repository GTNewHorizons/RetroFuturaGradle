package com.gtnewhorizons.retrofuturagradle.util;

import java.io.IOException;

import org.apache.commons.io.FileUtils;
import org.gradle.api.Action;
import org.gradle.api.Task;
import org.gradle.api.file.Directory;
import org.gradle.api.provider.Provider;

public class MkdirAction implements Action<Task> {

    private final Provider<Directory> directory;

    public MkdirAction(Provider<Directory> path) {
        this.directory = path;
    }

    @Override
    public void execute(Task task) {
        try {
            FileUtils.forceMkdir(directory.get().getAsFile());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
