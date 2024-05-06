package de.intranda.goobi.plugins;

import lombok.Data;

@Data
public class MetadataConfiguration {

    public MetadataConfiguration(String metadataType, boolean forceCreation, GenerationRule rule) {
        this.forceCreation = forceCreation;
        this.metadataType = metadataType;
        this.rule = rule;
    }

    private final String metadataType;
    private final boolean forceCreation;
    private GenerationRule rule;
}
