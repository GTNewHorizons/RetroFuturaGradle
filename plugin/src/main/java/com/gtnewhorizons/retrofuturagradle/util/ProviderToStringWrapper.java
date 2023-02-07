package com.gtnewhorizons.retrofuturagradle.util;

import org.gradle.api.provider.Provider;

/**
 * A simple Provider wrapper that gets the provider value when toString is called, for use with non-provider-compatible
 * interfaces.
 */
public final class ProviderToStringWrapper {

    public final Provider<String> provider;

    public ProviderToStringWrapper(Provider<String> provider) {
        this.provider = provider;
    }

    @Override
    public String toString() {
        return provider.get();
    }
}
