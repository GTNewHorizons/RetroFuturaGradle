package com.gtnewhorizons.retrofuturagradle.util;

import org.gradle.api.artifacts.DependencyArtifact;

public class ReclassifiedDependencyArtifact implements DependencyArtifact {

    public final DependencyArtifact delegate;
    public String newClassifier;

    public ReclassifiedDependencyArtifact(DependencyArtifact delegate, String newClassifier) {
        this.delegate = delegate;
        this.newClassifier = newClassifier;
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public void setName(String name) {
        delegate.setName(name);
    }

    @Override
    public String getType() {
        return delegate.getType();
    }

    @Override
    public void setType(String type) {
        delegate.setType(type);
    }

    @Override
    public String getExtension() {
        return delegate.getExtension();
    }

    @Override
    public void setExtension(String extension) {
        delegate.setExtension(extension);
    }

    @Override
    public String getClassifier() {
        return newClassifier;
    }

    @Override
    public void setClassifier(String classifier) {
        newClassifier = classifier;
    }

    @Override
    public String getUrl() {
        return delegate.getUrl();
    }

    @Override
    public void setUrl(String url) {
        delegate.setUrl(url);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        ReclassifiedDependencyArtifact that = (ReclassifiedDependencyArtifact) o;

        if (getClassifier() != null ? !getClassifier().equals(that.getClassifier()) : that.getClassifier() != null) {
            return false;
        }
        if (getExtension() != null ? !getExtension().equals(that.getExtension()) : that.getExtension() != null) {
            return false;
        }
        if (!getName().equals(that.getName())) {
            return false;
        }
        if (getType() != null ? !getType().equals(that.getType()) : that.getType() != null) {
            return false;
        }
        if (getUrl() != null ? !getUrl().equals(that.getUrl()) : that.getUrl() != null) {
            return false;
        }

        return true;
    }

    @Override
    public int hashCode() {
        int result = getName().hashCode();
        result = 31 * result + (getType() != null ? getType().hashCode() : 0);
        result = 31 * result + (getExtension() != null ? getExtension().hashCode() : 0);
        result = 31 * result + (getClassifier() != null ? getClassifier().hashCode() : 0);
        result = 31 * result + (getUrl() != null ? getUrl().hashCode() : 0);
        return result;
    }
}
