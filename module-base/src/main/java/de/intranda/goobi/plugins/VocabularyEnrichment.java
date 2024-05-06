package de.intranda.goobi.plugins;

import lombok.Data;

@Data
public class VocabularyEnrichment {
    private final String vocabularyField;
    private final String metadataType;
}
