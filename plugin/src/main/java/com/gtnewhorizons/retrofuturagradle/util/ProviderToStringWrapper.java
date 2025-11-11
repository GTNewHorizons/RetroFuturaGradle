package com.gtnewhorizons.retrofuturagradle.util;

import org.gradle.api.provider.Provider;

/**
 * A simple Provider wrapper that gets the provider value when toString is called, for use with non-provider-compatible
 * interfaces.
 */
public record ProviderToStringWrapper(Provider<String> provider) {

    @Override
    public String toString() {
        return provider.get();
    }
}
