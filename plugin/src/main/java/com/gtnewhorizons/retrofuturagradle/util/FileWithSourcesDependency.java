package com.gtnewhorizons.retrofuturagradle.util;

import java.util.Objects;

import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.artifacts.dependencies.DefaultSelfResolvingDependency;
import org.gradle.api.internal.file.FileCollectionInternal;

/**
 * A simple wrapper around the Gradle file-based dependency, that can provide a custom group/name/version.
 */
public class FileWithSourcesDependency extends DefaultSelfResolvingDependency {

    private final String group;
    private final String name;
    private final String version;

    public FileWithSourcesDependency(FileCollection source, String group, String name, String version) {
        super((FileCollectionInternal) source);
        this.group = group;
        this.name = name;
        this.version = version;
    }

    public FileWithSourcesDependency(ComponentIdentifier targetComponentId, FileCollection source, String group,
            String name, String version) {
        super(targetComponentId, (FileCollectionInternal) source);
        this.group = group;
        this.name = name;
        this.version = version;
    }

    @Override
    public boolean contentEquals(Dependency dependency) {
        if (dependency instanceof FileWithSourcesDependency fdep) {
            return super.contentEquals(dependency) && Objects.equals(group, fdep.group)
                    && Objects.equals(name, fdep.name)
                    && Objects.equals(version, fdep.version);
        } else {
            return false;
        }
    }

    @Override
    public FileWithSourcesDependency copy() {
        return new FileWithSourcesDependency(getTargetComponentId(), getFiles(), group, name, version);
    }

    @Override
    public String getGroup() {
        return group;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getVersion() {
        return version;
    }
}
