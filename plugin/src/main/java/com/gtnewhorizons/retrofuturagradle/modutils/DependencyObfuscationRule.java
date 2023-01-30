package com.gtnewhorizons.retrofuturagradle.modutils;

import java.util.Locale;

import javax.inject.Inject;

import org.gradle.api.artifacts.CacheableRule;
import org.gradle.api.artifacts.ComponentMetadataContext;
import org.gradle.api.artifacts.ComponentMetadataRule;
import org.gradle.api.artifacts.VariantMetadata;
import org.gradle.api.attributes.LibraryElements;
import org.gradle.api.model.ObjectFactory;

import com.gtnewhorizons.retrofuturagradle.ObfuscationAttribute;

@CacheableRule
public abstract class DependencyObfuscationRule implements ComponentMetadataRule {

    final String obfuscation;

    @Inject
    public DependencyObfuscationRule(String obfuscation) {
        switch (obfuscation.trim().toLowerCase(Locale.ROOT)) {
            case "mcp":
                this.obfuscation = ObfuscationAttribute.MCP;
                break;
            case "srg":
                this.obfuscation = ObfuscationAttribute.SRG;
                break;
            case "no-minecraft":
                this.obfuscation = ObfuscationAttribute.NO_MINECRAFT;
                break;
            default:
                throw new IllegalArgumentException(
                        "Unsupported obfuscation type " + obfuscation + " - only mcp, srg and no-minecraft are valid.");
        }
    }

    @Inject
    protected abstract ObjectFactory getObjects();

    @Override
    public void execute(ComponentMetadataContext context) {
        context.getDetails().allVariants((VariantMetadata meta) -> {
            meta.getAttributes()
                    .attribute(
                            ObfuscationAttribute.OBFUSCATION_ATTRIBUTE,
                            getObjects().named(ObfuscationAttribute.class, obfuscation))
                    .attribute(
                            LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                            getObjects().named(LibraryElements.class, LibraryElements.JAR));
        });
    }
}
