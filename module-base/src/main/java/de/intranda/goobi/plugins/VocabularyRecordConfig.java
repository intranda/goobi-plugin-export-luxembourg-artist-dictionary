package de.intranda.goobi.plugins;

import java.util.List;

import lombok.Data;

@Data
public class VocabularyRecordConfig {

    private final String groupType;
    private final Integer vocabularyId;
    private final String recordIdentifierMetadata;
    private final List<VocabularyEnrichment> enrichments;

}
