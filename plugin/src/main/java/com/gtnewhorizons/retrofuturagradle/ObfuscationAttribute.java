package com.gtnewhorizons.retrofuturagradle;

import org.gradle.api.Named;
import org.gradle.api.Project;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.attributes.AttributeCompatibilityRule;
import org.gradle.api.attributes.CompatibilityCheckDetails;

public interface ObfuscationAttribute extends Named {
    Attribute<ObfuscationAttribute> OBFUSCATION_ATTRIBUTE =
            Attribute.of("com.gtnewhorizons.retrofuturagradle.obfuscation", ObfuscationAttribute.class);

    /**
     * Obfuscation doesn't matter for artifacts without a Minecraft dependency
     */
    String NO_MINECRAFT = "no-minecraft";

    /**
     * MCP (dev deobfuscated) names
     */
    String MCP = "mcp";

    /**
     * SRG (Searge reobfuscated) names, used in Forge mods
     */
    String SRG = "srg";

    final class CompatRules implements AttributeCompatibilityRule<ObfuscationAttribute> {
        @Override
        public void execute(CompatibilityCheckDetails<ObfuscationAttribute> details) {
            if (details.getProducerValue().equals(NO_MINECRAFT)
                    || details.getConsumerValue().equals(NO_MINECRAFT)
                    || details.getProducerValue().equals(details.getConsumerValue())) {
                details.compatible();
            } else {
                details.incompatible();
            }
        }
    }

    static void configureProject(Project project) {
        project.getDependencies().getAttributesSchema().attribute(OBFUSCATION_ATTRIBUTE, attrib -> {
            attrib.getCompatibilityRules().add(CompatRules.class);
        });
        project.getConfigurations().all(cfg -> cfg.getAttributes()
                .attribute(
                        ObfuscationAttribute.OBFUSCATION_ATTRIBUTE,
                        project.getObjects().named(ObfuscationAttribute.class, ObfuscationAttribute.NO_MINECRAFT)));
    }
}
