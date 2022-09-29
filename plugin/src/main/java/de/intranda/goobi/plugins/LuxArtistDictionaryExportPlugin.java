package de.intranda.goobi.plugins;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.goobi.beans.Process;
import org.goobi.beans.Step;
import org.goobi.production.enums.LogType;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IExportPlugin;
import org.goobi.production.plugin.interfaces.IPlugin;
import org.goobi.vocabulary.Definition;
import org.goobi.vocabulary.Field;
import org.goobi.vocabulary.VocabRecord;

import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.ExportFileException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.helper.exceptions.UghHelperException;
import de.sub.goobi.persistence.managers.VocabularyManager;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.MetadataGroup;
import ugh.dl.MetadataType;
import ugh.dl.Prefs;
import ugh.exceptions.DocStructHasNoTypeException;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;
import ugh.exceptions.TypeNotAllowedForParentException;
import ugh.exceptions.WriteException;
import ugh.fileformats.mets.MetsModsImportExport;

@PluginImplementation
@Log4j2
public class LuxArtistDictionaryExportPlugin implements IExportPlugin, IPlugin {

    @Getter
    private String title = "intranda_export_luxArtistDictionary";
    @Getter
    private PluginType type = PluginType.Export;
    @Getter
    @Setter
    private Step step;

    @Getter
    private List<String> problems;

    @Override
    public void setExportFulltext(boolean arg0) {
    }

    @Override
    public void setExportImages(boolean arg0) {
    }

    @Override
    public boolean startExport(Process process) throws IOException, InterruptedException, DocStructHasNoTypeException, PreferencesException,
    WriteException, MetadataTypeNotAllowedException, ExportFileException, UghHelperException, ReadException, SwapException, DAOException,
    TypeNotAllowedForParentException {
        String benutzerHome = process.getProjekt().getDmsImportImagesPath();
        return startExport(process, benutzerHome);
    }

    @Override
    public boolean startExport(Process process, String destination) throws IOException, InterruptedException, DocStructHasNoTypeException,
    PreferencesException, WriteException, MetadataTypeNotAllowedException, ExportFileException, UghHelperException, ReadException,
    SwapException, DAOException, TypeNotAllowedForParentException {
        problems = new ArrayList<>();

        try {
            // read mets file
            Prefs prefs = process.getRegelsatz().getPreferences();
            MetadataType published = prefs.getMetadataTypeByName("Published");

            Fileformat ff = null;
            ff = process.readMetadataFile();
            DigitalDocument dd = ff.getDigitalDocument();

            // check if record should be exported
            DocStruct logical = dd.getLogicalDocStruct();
            List<? extends Metadata> md = logical.getAllMetadataByType(published);
            if (md.isEmpty()) {
                generateMessage(process, LogType.DEBUG, "Record is not marked as exportable, skip export");
                return true;
            }
            if ("N".equalsIgnoreCase(md.get(0).getValue())) {
                generateMessage(process, LogType.DEBUG, "Record is not marked as exportable, skip export");
                return true;
            }
            // otherwise record can be exported

            // run through all groups, check if group should be exported
            List<MetadataGroup> allSources = new ArrayList<>();
            List<MetadataGroup> bibliographyList = new ArrayList<>();
            for (MetadataGroup grp : new ArrayList<>(logical.getAllMetadataGroups())) {
                boolean removed = false;
                for (Metadata metadata : grp.getMetadataList()) {
                    if (metadata.getType().getName().equals("Published") && metadata.getValue().equalsIgnoreCase("N")) {
                        // remove group, if its marked as not exportable
                        logical.removeMetadataGroup(grp);
                        removed = true;
                    }
                }
                if (!removed) {
                    // collect all sources
                    List<MetadataGroup> sources = grp.getAllMetadataGroupsByName("Source");
                    allSources.addAll(sources);
                    if (grp.getType().getName().equals("Bibliography")) {
                        bibliographyList.add(grp);
                    }
                }
            }

            for (Metadata metadata : new ArrayList<>(logical.getAllMetadata())) {
                vocabularyEnrichment(prefs, metadata);
            }

            for (MetadataGroup group : logical.getAllMetadataGroups()) {
                for (Metadata metadata : new ArrayList<>(group.getMetadataList())) {
                    vocabularyEnrichment(prefs, metadata);
                }
                for (MetadataGroup subgroup : group.getAllMetadataGroups()) {
                    for (Metadata metadata : new ArrayList<>(subgroup.getMetadataList())) {
                        vocabularyEnrichment(prefs, metadata);
                    }
                }

            }

            // create bibliography from exported sources
            //            for (MetadataGroup currentSource : allSources) {
            //                boolean sourceMatched = false;
            //                String sourceId = currentSource.getMetadataByType("SourceID").get(0).getValue();
            //                for (MetadataGroup bibliography : bibliographyList) {
            //                    if (bibliography.getMetadataByType("SourceID").get(0).getValue().equals(sourceId)) {
            //                        sourceMatched = true;
            //                        break;
            //                    }
            //                }
            //                if (!sourceMatched) {
            //                    MetadataGroup bib = new MetadataGroup(prefs.getMetadataGroupTypeByName("Bibliography"));
            //
            //                    for (Metadata oldMd : currentSource.getMetadataList())  {
            //                        try {
            //                            Metadata newMd = new Metadata(oldMd.getType());
            //                            newMd.setValue(oldMd.getValue());
            //                            newMd.setAuthorityValue(oldMd.getAuthorityValue());
            //                            bib.addMetadata(newMd);
            //                        } catch (MetadataTypeNotAllowedException e) {
            //                        }
            //
            //                    }
            //                    logical.addMetadataGroup(bib);
            //                    bibliographyList.add(bib);
            //                }
            //            }

            // export data
            MetsModsImportExport mm = new MetsModsImportExport(prefs);
            mm.setDigitalDocument(dd);
            mm.write(destination + process.getTitel() + ".xml");
        } catch (ReadException | PreferencesException | WriteException | IOException | SwapException e) {
            log.error(e);
            problems.add("Cannot read metadata file.");
            return false;
        }

        return true;
    }

    private void vocabularyEnrichment(Prefs prefs, Metadata metadata) throws MetadataTypeNotAllowedException {
        if (StringUtils.isNotBlank(metadata.getAuthorityValue()) && metadata.getAuthorityURI().contains("vocabulary")) {
            String vocabularyName = metadata.getAuthorityID();
            String vocabRecordUrl = metadata.getAuthorityValue();
            String vocabRecordID = vocabRecordUrl.substring(vocabRecordUrl.lastIndexOf("/") + 1);
            vocabRecordUrl = vocabRecordUrl.substring(0, vocabRecordUrl.lastIndexOf("/"));
            String vocabularyID = vocabRecordUrl.substring(vocabRecordUrl.lastIndexOf("/") + 1);
            VocabRecord vr = VocabularyManager.getRecord(Integer.parseInt(vocabularyID), Integer.parseInt(vocabRecordID));
            // TODO export authority data at group level

            if (vr != null) {
                switch (vocabularyName) {
                    case "Location":
                        String value = null;
                        String authority = null;
                        for (Field f : vr.getFields()) {
                            if (f.getDefinition().getLabel().equals("Location")) {
                                value = f.getValue();
                            } else if (f.getDefinition().getLabel().equals("Authority Value")) {
                                authority = f.getValue();
                            }
                        }
                        metadata.setValue(value);
                        metadata.setAutorityFile("geonames", "http://www.geonames.org/", "http://www.geonames.org/" + authority);
                        break;
                    case "R01 Relationship Person - Person":
                    case "R02 Relationship Collective agent - Collective agent":
                    case "R03a Relationship Person - Collective agent":
                    case "R03b Relationship Collective agent - Person":
                    case "R04 Relationship Person - Event":
                    case "R05 Relationship Collective agent - Event":
                    case "R06 Relationship Person - Work":
                    case "R07 Relationship Collective agent - Work":
                    case "R08 Relationship Event - Work":
                    case "R09 Relationship Person - Award":
                    case "R10 Relationship Collective agent - Award":
                    case "R11 Relationship Work - Award":
                        String eng = null;
                        String fre = null;
                        String ger = null;
                        // check if relation or reverse relation is used
                        boolean useReverseRelationship = false;
                        for (Field f : vr.getFields()) {
                            if (f.getValue().equals(metadata.getValue())) {
                                if (f.getDefinition().getLabel().startsWith("Reverse")) {
                                    useReverseRelationship = true;
                                } else {
                                    useReverseRelationship = false;
                                }
                                break;
                            }
                        }
                        // get normed values
                        for (Field f : vr.getFields()) {
                            if ((f.getDefinition().getLabel().startsWith("Reverse") && useReverseRelationship)
                                    || (f.getDefinition().getLabel().startsWith("Relationship") && !useReverseRelationship)) {
                                if (StringUtils.isNotBlank(f.getDefinition().getLanguage())) {
                                    switch (f.getDefinition().getLanguage()) {
                                        case "ger":
                                            ger = f.getValue();
                                            break;
                                        case "eng":
                                            eng = f.getValue();
                                            break;
                                        case "fre":
                                            fre = f.getValue();
                                            break;
                                        default:
                                    }
                                }
                            }
                        }
                        // write normed metadata
                        try {
                            Metadata md = new Metadata(prefs.getMetadataTypeByName("_relationship_type_ger"));
                            md.setValue(ger);
                            metadata.getParent().addMetadata(md);
                        } catch (MetadataTypeNotAllowedException e) {
                        }
                        try {
                            Metadata md = new Metadata(prefs.getMetadataTypeByName("_relationship_type_eng"));
                            md.setValue(eng);
                            metadata.getParent().addMetadata(md);
                        } catch (MetadataTypeNotAllowedException e) {
                        }
                        try {
                            Metadata md = new Metadata(prefs.getMetadataTypeByName("_relationship_type_fre"));
                            md.setValue(fre);
                            metadata.getParent().addMetadata(md);
                        } catch (MetadataTypeNotAllowedException e) {
                        }
                        break;

                    default:
                        for (Field f : vr.getFields()) {
                            if (StringUtils.isNotBlank(f.getValue())) {
                                String metadataName = null;
                                Definition def = f.getDefinition();
                                // find metadata name
                                if (StringUtils.isNotBlank(def.getLanguage())) {
                                    metadataName = "_" + def.getLabel() + "_" + def.getLanguage();
                                } else {
                                    metadataName = "_" + def.getLabel();
                                }
                                metadataName = metadataName.replace(" ", "").toLowerCase();
                                // create metadata
                                MetadataType mdt = prefs.getMetadataTypeByName(metadataName);
                                if (mdt != null) {
                                    // set value
                                    Metadata vocabMetadata = new Metadata(mdt);
                                    vocabMetadata.setValue(f.getValue());
                                    try {
                                        metadata.getParent().addMetadata(vocabMetadata);
                                    } catch (MetadataTypeNotAllowedException e) {

                                    }
                                }
                            }
                        }
                        break;
                }
            }
        }
    }

    private void generateMessage(Process process, LogType logtype, String message) {
        if (logtype == LogType.ERROR) {
            Helper.setFehlerMeldung(message);
        } else {
            Helper.setMeldung(message);
        }
        Helper.addMessageToProcessJournal(process.getId(), logtype, message);
    }
}