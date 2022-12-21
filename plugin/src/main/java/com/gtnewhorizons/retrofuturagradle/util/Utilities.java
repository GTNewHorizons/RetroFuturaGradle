package com.gtnewhorizons.retrofuturagradle.util;

import java.io.File;
import org.apache.commons.io.FileUtils;
import org.gradle.api.Project;

public final class Utilities {
    public static File getCacheRoot(Project project) {
        return FileUtils.getFile(project.getGradle().getGradleUserHomeDir(), "caches", "retro_futura_gradle");
    }

    public static File getCacheDir(Project project, String... paths) {
        return FileUtils.getFile(getCacheRoot(project), paths);
    }
}
