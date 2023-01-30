package com.gtnewhorizons.retrofuturagradle.util;

import java.util.ArrayList;
import java.util.List;

import org.gradle.api.provider.Provider;

public class JarChain {

    private final List<Provider<IJarTransformTask>> taskChain = new ArrayList<>();

}
