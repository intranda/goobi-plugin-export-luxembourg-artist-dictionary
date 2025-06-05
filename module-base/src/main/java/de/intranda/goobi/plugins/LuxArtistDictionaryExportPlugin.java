package de.intranda.goobi.plugins;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.reloading.FileChangedReloadingStrategy;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.goobi.beans.Process;
import org.goobi.beans.ProjectFileGroup;
import org.goobi.beans.Step;
import org.goobi.beans.User;
import org.goobi.production.enums.LogType;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IExportPlugin;
import org.goobi.production.plugin.interfaces.IPlugin;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.helper.FilesystemHelper;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.NIOFileUtils;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.VariableReplacer;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.ExportFileException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.helper.exceptions.UghHelperException;
import de.sub.goobi.persistence.managers.PropertyManager;
import io.goobi.vocabulary.exchange.FieldDefinition;
import io.goobi.vocabulary.exchange.FieldValue;
import io.goobi.vocabulary.exchange.TranslationInstance;
import io.goobi.vocabulary.exchange.Vocabulary;
import io.goobi.vocabulary.exchange.VocabularySchema;
import io.goobi.workflow.api.vocabulary.APIException;
import io.goobi.workflow.api.vocabulary.VocabularyAPIManager;
import io.goobi.workflow.api.vocabulary.helper.ExtendedFieldInstance;
import io.goobi.workflow.api.vocabulary.helper.ExtendedVocabularyRecord;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.ContentFile;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.MetadataGroup;
import ugh.dl.MetadataType;
import ugh.dl.Prefs;
import ugh.dl.Reference;
import ugh.dl.VirtualFileGroup;
import ugh.exceptions.DocStructHasNoTypeException;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;
import ugh.exceptions.TypeNotAllowedAsChildException;
import ugh.exceptions.TypeNotAllowedForParentException;
import ugh.exceptions.UGHException;
import ugh.exceptions.WriteException;
import ugh.fileformats.mets.MetsMods;
import ugh.fileformats.mets.MetsModsImportExport;

@PluginImplementation
@Log4j2
public class LuxArtistDictionaryExportPlugin implements IExportPlugin, IPlugin {

    private static final long serialVersionUID = 4952992593656226170L;

    public static final String DIRECTORY_SUFFIX = "_media";
    private static final String EXPORT_ERROR_PREFIX = "Export cancelled: ";
    private static final String PROCESS_PROPERTY_PROCESS_STATUS = "ProcessStatus";

    private static final List<String> REPRESENTATIVE_IMAGE_SUBJECTS = List.of("Porträt", "Portrait", "Event visual", "Award visual ");

    @Getter
    private String title = "intranda_export_luxArtistDictionary";
    @Getter
    private PluginType type = PluginType.Export;
    @Getter
    @Setter
    private Step step;
    @Getter
    @Setter
    private boolean exportFulltext;
    @Getter
    @Setter
    private boolean exportImages;

    @Getter
    private List<String> problems;

    @Override
    public boolean startExport(Process process) throws IOException, InterruptedException, DocStructHasNoTypeException, PreferencesException,
            WriteException, MetadataTypeNotAllowedException, ExportFileException, UghHelperException, ReadException, SwapException, DAOException,
            TypeNotAllowedForParentException {
        String benutzerHome = process.getProjekt().getDmsImportRootPath();
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
            Fileformat ff = process.readMetadataFile();

            XMLConfiguration config = getConfig();

            cleanUpPagination(process, ff, config);
            List<MetadataConfiguration> additionalMetadata = readMetadataConfigurations(config);
            List<VocabularyRecordConfig> vocabularyConfigs = readVocabularyRecordConfigs(config);

            Optional<String> authorityUriReplacementFrom = Optional.empty();
            Optional<String> authorityUriReplacementTo = Optional.empty();
            try {
                SubnodeConfiguration replacement = config.configurationAt("vocabularyUriReplacement");
                authorityUriReplacementFrom = Optional.ofNullable(replacement.getString("[@from]", null));
                authorityUriReplacementTo = Optional.ofNullable(replacement.getString("[@to]", null));
            } catch (IllegalArgumentException e) {
                // Ignore missing setting
            }

            DigitalDocument dd = enrichFileformat(ff, prefs, config, process.getImagesTifDirectory(true));

            enrichFromVocabulary(prefs, dd, vocabularyConfigs);

            // export data
            VariableReplacer vp = new VariableReplacer(dd, prefs, process, null);
            MetsModsImportExport mm = new MetsModsImportExport(prefs);
            mm.setDigitalDocument(dd);
            // Replace rights and digiprov entries.
            addProjectData(mm, process, vp);
            addAdditionalMetadata(additionalMetadata, dd, prefs, vp);
            writeFileGroups(process, dd, vp, mm);

            if (authorityUriReplacementFrom.isPresent() && authorityUriReplacementTo.isPresent()) {
                log.debug("Replacing authority URIs with \"{}\" replaced by \"{}\"", authorityUriReplacementFrom.get(),
                        authorityUriReplacementTo.get());
                replaceAuthorityLinks(mm, authorityUriReplacementFrom.get(), authorityUriReplacementTo.get());
            }

            mm.write(Paths.get(destination, process.getTitel() + ".xml").toString());

            if (!exportFiles(process, vp, destination)) {
                log.error("Failed to download images or fulltext files");
                problems.add("Failed to download images or fulltext files");
                return false;
            }
            setProcessStatus(process, "Published");
        } catch (ExportException e) {
            log.error(e.getMessage());
            problems.add(e.getMessage());
            return false;
        } catch (NotExportableException e) {
            generateMessage(process, LogType.DEBUG, e.getMessage());
            return true;
        } catch (ReadException | PreferencesException | WriteException | IOException | SwapException e) {
            log.error(e);
            problems.add("Cannot read metadata file.");
            return false;
        } catch (UGHException e) {
            log.error(e);
        }

        return true;
    }

    private void replaceAuthorityLinks(MetsModsImportExport mm, String from, String to) {
        Optional.ofNullable(mm.getDigitalDocument().getLogicalDocStruct().getAllMetadata())
                .ifPresent(list -> list.forEach(m -> replaceAuthorityLinksInMetadata(m, from, to)));
        Optional.ofNullable(mm.getDigitalDocument().getLogicalDocStruct().getAllMetadataGroups())
                .ifPresent(groups -> groups.forEach(g -> replaceAuthorityLinksInMetadataGroup(g, from, to)));
    }

    private void replaceAuthorityLinksInMetadataGroup(MetadataGroup mg, String from, String to) {
        Optional.ofNullable(mg.getMetadataList()).ifPresent(list -> list.forEach(m -> replaceAuthorityLinksInMetadata(m, from, to)));
        Optional.ofNullable(mg.getAllMetadataGroups()).ifPresent(groups -> groups.forEach(g -> replaceAuthorityLinksInMetadataGroup(g, from, to)));
    }

    private void replaceAuthorityLinksInMetadata(Metadata m, String from, String to) {
        Optional.ofNullable(m.getAuthorityURI()).ifPresent(uri -> m.setAuthorityValue(uri.replace(from, to)));
        Optional.ofNullable(m.getAuthorityValue()).ifPresent(value -> m.setAuthorityValue(value.replace(from, to)));
    }

    private void cleanUpPagination(Process process, Fileformat ff, XMLConfiguration config) throws UGHException, IOException, SwapException {

        // if media folder is used, remove all pages from master folder

        String mediaFolder = process.getImagesTifDirectory(false);
        List<Path> imagesInMediaFolder = StorageProvider.getInstance().listFiles(mediaFolder);
        if (config.getBoolean("cleanupPagination", false)) {
            DigitalDocument dd = ff.getDigitalDocument();
            DocStruct pyhsical = dd.getPhysicalDocStruct();
            DocStruct logical = dd.getLogicalDocStruct();
            if (pyhsical != null && pyhsical.getAllChildren() != null) {
                List<DocStruct> pagesToDelete = new ArrayList<>(pyhsical.getAllChildren());
                List<ContentFile> contentFilesToDelete = new ArrayList<>(dd.getFileSet().getAllFiles());
                for (DocStruct pageToRemove : pagesToDelete) {
                    pyhsical.removeChild(pageToRemove);
                    List<Reference> refs = new ArrayList<>(pageToRemove.getAllFromReferences());
                    for (ugh.dl.Reference ref : refs) {
                        DocStruct source = ref.getSource();
                        for (Reference reference : source.getAllToReferences()) {
                            if (reference.getTarget().equals(pageToRemove)) {
                                source.getAllToReferences().remove(reference);
                                break;
                            }
                        }
                    }
                }
                for (ContentFile cf : contentFilesToDelete) {
                    dd.getFileSet().removeFile(cf);
                }
                Prefs prefs = process.getRegelsatz().getPreferences();
                int currentPhysicalOrder = 0;
                MetadataType physPageNumber = prefs.getMetadataTypeByName("physPageNumber");
                MetadataType logicalPageNumber = prefs.getMetadataTypeByName("logicalPageNumber");

                for (Path image : imagesInMediaFolder) {
                    DocStruct dsPage = dd.createDocStruct(prefs.getDocStrctTypeByName("page"));
                    String mimetype = NIOFileUtils.getMimeTypeFromFile(image);
                    try {
                        // physical page no
                        pyhsical.addChild(dsPage);
                        Metadata mdTemp = new Metadata(physPageNumber);
                        mdTemp.setValue(String.valueOf(++currentPhysicalOrder));
                        dsPage.addMetadata(mdTemp);

                        // logical page no
                        mdTemp = new Metadata(logicalPageNumber);

                        mdTemp.setValue("uncounted");

                        dsPage.addMetadata(mdTemp);
                        logical.addReferenceTo(dsPage, "logical_physical");

                        // image name
                        ContentFile cf = new ContentFile();
                        cf.setMimetype(mimetype);
                        cf.setLocation("file://" + mediaFolder + image.getFileName().toString());

                        dsPage.addContentFile(cf);

                    } catch (TypeNotAllowedAsChildException | MetadataTypeNotAllowedException e) {
                        log.error(e);
                    }

                }

                if (!pagesToDelete.isEmpty()) {
                    // update process
                    process.writeMetadataFile(ff);
                }
            }
        }
    }

    private void setProcessStatus(Process process, String value) {
        process.getEigenschaften()
                .stream()
                .filter(prop -> PROCESS_PROPERTY_PROCESS_STATUS.equals(prop.getPropertyName()))
                .findAny()
                .ifPresent(prop -> {
                    log.info("Settting property {} of process {} to {}", PROCESS_PROPERTY_PROCESS_STATUS, process.getId(), value);
                    prop.setPropertyValue(value);
                    PropertyManager.saveProperty(prop);
                    log.info("Successfully set process property");
                });
    }

    private void addAdditionalMetadata(List<MetadataConfiguration> additionalMetadata, DigitalDocument dd, Prefs prefs, VariableReplacer vp) {
        for (MetadataConfiguration config : additionalMetadata) {
            MetadataType mdt = prefs.getMetadataTypeByName(config.getMetadataType());
            if (mdt != null) {
                List<? extends Metadata> existingMetadata = dd.getLogicalDocStruct().getAllMetadataByType(mdt);
                if (config.isForceCreation() || existingMetadata == null || existingMetadata.isEmpty()) {
                    try {
                        Metadata md = new Metadata(mdt);
                        md.setValue(config.getRule().generate(vp));
                        if (StringUtils.isNotBlank(md.getValue())) {
                            dd.getLogicalDocStruct().addMetadata(md);
                        }
                    } catch (MetadataTypeNotAllowedException e) {
                        log.error(e);
                        problems.add("Error adding metadata of type " + config.getMetadataType());
                    }
                }
            }
        }
    }

    private List<VocabularyRecordConfig> readVocabularyRecordConfigs(XMLConfiguration configuration) {
        List<HierarchicalConfiguration> configs = configuration.configurationsAt("vocabulary");
        if (configs != null) {
            return configs.stream().map(config -> {
                String groupType = config.getString("metadataGroupType", null);
                Integer vocabularyId = config.getInteger("vocabularyId", null);
                String identifierMetadata = config.getString("recordIdentifierMetadata", null);
                if (StringUtils.isNotBlank(groupType) && StringUtils.isNotBlank(identifierMetadata) && vocabularyId != null) {
                    List<HierarchicalConfiguration> enrichConfigs = config.configurationsAt("enrich");
                    List<VocabularyEnrichment> enrichments = Optional.ofNullable(enrichConfigs).map(ecs -> {
                        return ecs.stream().map(ec -> {
                            String md = ec.getString("metadataType", "");
                            String field = ec.getString("vocabularyField", "");
                            return new VocabularyEnrichment(field, md);
                        }).collect(Collectors.toList());
                    }).orElse(Collections.emptyList());
                    return new VocabularyRecordConfig(groupType, vocabularyId, identifierMetadata, enrichments);
                } else {
                    return null;
                }
            })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } else {
            return Collections.emptyList();
        }
    }

    private List<MetadataConfiguration> readMetadataConfigurations(XMLConfiguration configuration) {
        List<HierarchicalConfiguration> metadataConfigs = configuration.configurationsAt("metadata");
        if (metadataConfigs != null) {
            return metadataConfigs.stream().map(config -> {
                String type = config.getString("[@type]");
                boolean force = config.getBoolean("[@force]", false);
                GenerationRule rule = new GenerationRule(config.getString("rule"), config.getString("rule[@numberFormat]"));
                if (StringUtils.isNotBlank(type) && StringUtils.isNotBlank(rule.getValue())) {
                    return new MetadataConfiguration(type, force, rule);
                } else {
                    return null;
                }
            })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } else {
            return Collections.emptyList();
        }
    }

    private void enrichFromVocabulary(Prefs prefs, DigitalDocument dd, List<VocabularyRecordConfig> vocabConfigs)
            throws MetadataTypeNotAllowedException {
        DocStruct logical = dd.getLogicalDocStruct();
        for (Metadata metadata : new ArrayList<>(logical.getAllMetadata())) {
            vocabularyEnrichment(prefs, metadata);
        }

        for (MetadataGroup group : logical.getAllMetadataGroups()) {
            vocabularyEnrichment(group, vocabConfigs);
            for (Metadata metadata : new ArrayList<>(group.getMetadataList())) {
                vocabularyEnrichment(prefs, metadata);
            }
            for (MetadataGroup subgroup : group.getAllMetadataGroups()) {
                vocabularyEnrichment(subgroup, vocabConfigs);
                for (Metadata metadata : new ArrayList<>(subgroup.getMetadataList())) {
                    vocabularyEnrichment(prefs, metadata);
                }
            }

        }
    }

    private void addProjectData(MetsModsImportExport mm, Process process, VariableReplacer vp) {
        mm.setRightsOwner(vp.replace(process.getProjekt().getMetsRightsOwner()));
        mm.setRightsOwnerLogo(vp.replace(process.getProjekt().getMetsRightsOwnerLogo()));
        mm.setRightsOwnerSiteURL(vp.replace(process.getProjekt().getMetsRightsOwnerSite()));
        mm.setRightsOwnerContact(vp.replace(process.getProjekt().getMetsRightsOwnerMail()));
        mm.setDigiprovPresentation(vp.replace(process.getProjekt().getMetsDigiprovPresentation()));
        mm.setDigiprovReference(vp.replace(process.getProjekt().getMetsDigiprovReference()));
        mm.setDigiprovPresentationAnchor(vp.replace(process.getProjekt().getMetsDigiprovPresentationAnchor()));
        mm.setDigiprovReferenceAnchor(vp.replace(process.getProjekt().getMetsDigiprovReferenceAnchor()));

        mm.setMetsRightsLicense(vp.replace(process.getProjekt().getMetsRightsLicense()));
        mm.setMetsRightsSponsor(vp.replace(process.getProjekt().getMetsRightsSponsor()));
        mm.setMetsRightsSponsorLogo(vp.replace(process.getProjekt().getMetsRightsSponsorLogo()));
        mm.setMetsRightsSponsorSiteURL(vp.replace(process.getProjekt().getMetsRightsSponsorSiteURL()));

        mm.setPurlUrl(vp.replace(process.getProjekt().getMetsPurl()));
        mm.setContentIDs(vp.replace(process.getProjekt().getMetsContentIDs()));

        String pointer = process.getProjekt().getMetsPointerPath();
        pointer = vp.replace(pointer);
        mm.setMptrUrl(pointer);

        String anchor = process.getProjekt().getMetsPointerPathAnchor();
        pointer = vp.replace(anchor);
        mm.setMptrAnchorUrl(pointer);

        mm.setGoobiID(String.valueOf(process.getId()));

        mm.setIIIFUrl(vp.replace(process.getProjekt().getMetsIIIFUrl()));
        mm.setSruUrl(vp.replace(process.getProjekt().getMetsSruUrl()));
    }

    protected DigitalDocument enrichFileformat(Fileformat ff, Prefs prefs, XMLConfiguration config, String imageFolder)
            throws PreferencesException, MetadataTypeNotAllowedException, NotExportableException, ExportException {
        MetadataType published = prefs.getMetadataTypeByName("Published");
        DigitalDocument dd = ff.getDigitalDocument();

        // check if record should be exported
        DocStruct logical = dd.getLogicalDocStruct();
        boolean exportAll = config.getBoolean("exportUnpublishedRecords", false);
        if (!exportAll) {
            List<? extends Metadata> md = logical.getAllMetadataByType(published);
            if (md.isEmpty()) {
                throw new NotExportableException("Record is not marked as exportable, skip export");

            }
            if (md.stream().noneMatch(m -> m.getValue() != null && m.getValue().matches("[YyJj]"))) {
                throw new NotExportableException("Record is not marked as exportable, skip export");
            }
        }
        // otherwise record can be exported

        // run through all groups, check if group should be exported
        List<MetadataGroup> allSources = new ArrayList<>();
        List<MetadataGroup> bibliographyList = new ArrayList<>();
        if (logical == null) {
            throw new ExportException("No logical structure defined");

        }
        for (MetadataGroup grp : new ArrayList<>(logical.getAllMetadataGroups())) {
            boolean removed = false;
            for (Metadata metadata : grp.getMetadataList()) {
                if ("Published".equals(metadata.getType().getName()) && "N".equalsIgnoreCase(metadata.getValue())) {
                    // remove group, if its marked as not exportable
                    logical.removeMetadataGroup(grp);
                    removed = true;
                }
            }
            if (!removed) {
                // collect all sources
                List<MetadataGroup> sources = grp.getAllMetadataGroupsByName("Source");
                allSources.addAll(sources);
                if ("Bibliography".equals(grp.getType().getName())) {
                    bibliographyList.add(grp);
                }
            }
        }

        if (ff.getDigitalDocument().getFileSet().getAllFiles() != null && !ff.getDigitalDocument().getFileSet().getAllFiles().isEmpty()
                && ff.getDigitalDocument().getFileSet().getAllFiles().stream().noneMatch(ContentFile::isRepresentative)) {
            setRepresentative(prefs, ff);
        }

        boolean addEventLocationFromAgent = config.getBoolean("addEventLocationFromAgent", false);
        if (addEventLocationFromAgent && logical.getAllMetadataGroupsByType(prefs.getMetadataGroupTypeByName("LocationGroup")).isEmpty()) {
            try {
                addLocationFromRelatedAgent(logical, prefs);
            } catch (PreferencesException | ReadException | MetadataTypeNotAllowedException | DocStructHasNoTypeException e) {
                log.error("Unable to add location metadata group to event from agent: {}", e.toString());
            }
        }

        if (StringUtils.isNotBlank(imageFolder)) {
            DocStruct physical = dd.getPhysicalDocStruct();
            if (physical != null && physical.getAllChildren() != null) {
                List<String> imageNamesInFolder = StorageProvider.getInstance().list(imageFolder);
                List<String> imageNamesInFile = new ArrayList<>();
                List<DocStruct> pages = physical.getAllChildren();
                List<DocStruct> pagesToDelete = new ArrayList<>();
                for (DocStruct page : pages) {
                    String currentImage = Paths.get(page.getImageName()).getFileName().toString();
                    if (imageNamesInFile.contains(currentImage)) {
                        // duplicate entry, remove page
                        pagesToDelete.add(page);
                    } else {
                        imageNamesInFile.add(currentImage);
                    }
                    if (!imageNamesInFolder.contains(currentImage)) {
                        // image does not longer exist, remove page
                        pagesToDelete.add(page);
                    }
                }

                for (DocStruct page : pagesToDelete) {

                    List<Reference> refs = new ArrayList<>(page.getAllFromReferences());
                    for (ugh.dl.Reference ref : refs) {
                        ref.getSource().removeReferenceTo(page);
                    }

                    physical.removeChild(page);
                }
                // finally generate new phys order
            }

            if (physical != null && physical.getAllChildren() != null) {
                int order = 1;
                for (DocStruct page : physical.getAllChildren()) {
                    for (Metadata md : page.getAllMetadata()) {
                        if ("physPageNumber".equals(md.getType().getName())) {
                            md.setValue(String.valueOf(order));
                            order++;
                            break;
                        }
                    }
                }
            }
        }
        return dd;
    }

    private void addLocationFromRelatedAgent(DocStruct logical, Prefs prefs)
            throws PreferencesException, ReadException, MetadataTypeNotAllowedException, DocStructHasNoTypeException {
        List<MetadataGroup> relationships = logical.getAllMetadataGroupsByType(prefs.getMetadataGroupTypeByName("Relationship"));
        for (MetadataGroup rel : relationships) {
            String entityType = rel.getMetadataByType("RelationEntityType").stream().findFirst().map(md -> md.getValue()).orElse(null);
            String relationshipType = rel.getMetadataByType("Type").stream().findFirst().map(md -> md.getValue()).orElse(null);
            if ("Agent".equals(entityType) && ("was organized by".equals(relationshipType) || "organized".equals(relationshipType))) {
                String agentIdentifier = rel.getMetadataByType("RelationProcessID").stream().findFirst().map(md -> md.getValue()).orElse(null);
                Path agentMetsPath = Paths.get(ConfigurationHelper.getInstance().getMetadataFolder(), agentIdentifier, "meta.xml");
                Fileformat agentFormat = new MetsMods(prefs);
                agentFormat.read(agentMetsPath.toAbsolutePath().toString());
                MetadataGroup locationGroup = agentFormat.getDigitalDocument()
                        .getLogicalDocStruct()
                        .getAllMetadataGroupsByType(prefs.getMetadataGroupTypeByName("LocationGroup"))
                        .stream()
                        .findFirst()
                        .orElse(null);
                if (locationGroup != null) {
                    logical.addMetadataGroup(locationGroup);
                }
            }
        }
    }

    private void setRepresentative(Prefs prefs, Fileformat ff) throws PreferencesException {
        List<MetadataGroup> mediaGroups =
                ff.getDigitalDocument().getLogicalDocStruct().getAllMetadataGroupsByType(prefs.getMetadataGroupTypeByName("Media"));
        Optional<MetadataGroup> firstPortrait = mediaGroups.stream()
                .filter(gr -> gr.getMetadataByType("Subject").stream().anyMatch(md -> REPRESENTATIVE_IMAGE_SUBJECTS.contains(md.getValue())))
                .findFirst();
        firstPortrait.ifPresent(gr -> {
            gr.getMetadataByType("File").stream().findAny().map(Metadata::getValue).ifPresent(filepath -> {
                String filename = Paths.get(filepath).toAbsolutePath().getFileName().toString().replaceAll("\\s+", " ");
                try {
                    List<ContentFile> allFiles = ff.getDigitalDocument().getFileSet().getAllFiles();
                    for (ContentFile contentFile : allFiles) {
                        String location = contentFile.getLocation();
                        if (location != null && location.replaceAll("\\s+", " ").endsWith(filename)) {
                            contentFile.setRepresentative(true);
                        }
                    }

                } catch (PreferencesException e) {
                    log.error("Error reading fileset", e);
                }
            });
        });
    }

    private XMLConfiguration getConfig() {
        XMLConfiguration xmlConfig = ConfigPlugins.getPluginConfig(title);
        xmlConfig.setReloadingStrategy(new FileChangedReloadingStrategy());
        return xmlConfig;
    }

    private void writeFileGroups(Process process, DigitalDocument dd, VariableReplacer vp, MetsModsImportExport mm)
            throws IOException, SwapException {

        List<ProjectFileGroup> myFilegroups = process.getProjekt().getFilegroups();
        boolean useOriginalFiles = false;
        if (myFilegroups != null && !myFilegroups.isEmpty()) {
            for (ProjectFileGroup pfg : myFilegroups) {
                if (pfg.isUseOriginalFiles()) {
                    useOriginalFiles = true;
                }
                // check if source files exists
                if (pfg.getFolder() != null && pfg.getFolder().length() > 0) {
                    String foldername = process.getMethodFromName(pfg.getFolder());
                    if (foldername != null) {
                        Path folder = Paths.get(process.getMethodFromName(pfg.getFolder()));
                        if (folder != null && StorageProvider.getInstance().isFileExists(folder)
                                && !StorageProvider.getInstance().list(folder.toString()).isEmpty()) {
                            VirtualFileGroup v = createFilegroup(vp, pfg);
                            mm.getDigitalDocument().getFileSet().addVirtualFileGroup(v);
                        }
                    }
                } else {
                    VirtualFileGroup v = createFilegroup(vp, pfg);
                    mm.getDigitalDocument().getFileSet().addVirtualFileGroup(v);
                }
            }
        }

        if (useOriginalFiles) {
            // check if media folder contains images
            List<Path> filesInFolder = StorageProvider.getInstance().listFiles(process.getImagesTifDirectory(false));
            filesInFolder.sort((file1, file2) -> {
                String name1 = file1.getFileName().toString();
                String name2 = file2.getFileName().toString();
                String base1 = FilenameUtils.getBaseName(name1);
                String base2 = FilenameUtils.getBaseName(name2);
                String extension1 = FilenameUtils.getExtension(name1);
                String extension2 = FilenameUtils.getExtension(name2);
                if (StringUtils.equalsIgnoreCase(base1, base2)) {
                    if (StringUtils.isBlank(extension1)) {
                        return 1;
                    } else if (StringUtils.isBlank(extension2)) {
                        return -1;
                    }
                    return 0;
                } else {
                    return name1.compareTo(name2);
                }
            });
            if (!filesInFolder.isEmpty()) {
                // compare image names with files in mets file
                List<DocStruct> pages = dd.getPhysicalDocStruct().getAllChildren();
                if (pages != null && !pages.isEmpty()) {
                    for (DocStruct page : pages) {
                        Path completeNameInMets = Paths.get(page.getImageName());
                        String filenameInMets = completeNameInMets.getFileName().toString();
                        int dotIndex = filenameInMets.lastIndexOf('.');
                        if (dotIndex != -1) {
                            filenameInMets = filenameInMets.substring(0, dotIndex);
                        }
                        for (Path imageNameInFolder : filesInFolder) {
                            String imageName = imageNameInFolder.getFileName().toString();
                            dotIndex = imageName.lastIndexOf('.');
                            if (dotIndex != -1) {
                                imageName = imageName.substring(0, dotIndex);
                            }

                            if (filenameInMets.equalsIgnoreCase(imageName)) {
                                // found matching filename
                                page.setImageName(imageNameInFolder.toString());
                                break;
                            }
                        }
                    }
                    // replace filename in mets file
                }
            }
        }
    }

    private VirtualFileGroup createFilegroup(VariableReplacer variableRplacer, ProjectFileGroup projectFileGroup) {
        VirtualFileGroup v = new VirtualFileGroup();
        v.setName(projectFileGroup.getName());
        v.setPathToFiles(variableRplacer.replace(projectFileGroup.getPath()));
        v.setMimetype(projectFileGroup.getMimetype());
        //check for null to stop NullPointerException
        String projectFileSuffix = projectFileGroup.getSuffix();
        if (projectFileSuffix != null) {
            v.setFileSuffix(projectFileSuffix.trim());
        } else {
            v.setFileSuffix(projectFileSuffix);
        }
        v.setFileExtensionsToIgnore(projectFileGroup.getIgnoreMimetypes());
        v.setIgnoreConfiguredMimetypeAndSuffix(projectFileGroup.isUseOriginalFiles());
        if ("PRESENTATION".equals(projectFileGroup.getName())) {
            v.setMainGroup(true);
        }
        return v;
    }

    private boolean exportFiles(Process myProzess, VariableReplacer replacer, String inZielVerzeichnis) {
        String errorMessageTitle = EXPORT_ERROR_PREFIX + "Process: " + myProzess.getTitel();
        String atsPpnBand = myProzess.getTitel();

        String zielVerzeichnis;
        Path benutzerHome;
        if (myProzess.getProjekt().isUseDmsImport()) {
            zielVerzeichnis = myProzess.getProjekt().getDmsImportImagesPath();
            zielVerzeichnis = replacer.replace(zielVerzeichnis);
            benutzerHome = Paths.get(zielVerzeichnis);

            /* ggf. noch einen Vorgangsordner anlegen */
            if (myProzess.getProjekt().isDmsImportCreateProcessFolder()) {
                benutzerHome = Paths.get(benutzerHome.toString(), myProzess.getTitel());
                zielVerzeichnis = benutzerHome.toString();

                /* alte Import-Ordner löschen */
                if (!StorageProvider.getInstance().deleteDir(benutzerHome)) {
                    String errorDetails = "Import folder could not be cleared.";
                    Helper.setFehlerMeldung(errorMessageTitle, errorDetails);
                    problems.add(EXPORT_ERROR_PREFIX + errorDetails);
                    return false;
                }

                if (!StorageProvider.getInstance().isFileExists(benutzerHome)) {
                    try {
                        StorageProvider.getInstance().createDirectories(benutzerHome);
                    } catch (IOException e) {
                        log.error("Error creating directory {}", benutzerHome);
                        return false;
                    }
                }
            }

        } else {
            zielVerzeichnis = inZielVerzeichnis + atsPpnBand;
            zielVerzeichnis = replacer.replace(zielVerzeichnis) + FileSystems.getDefault().getSeparator();
            // wenn das Home existiert, erst löschen und dann neu anlegen
            benutzerHome = Paths.get(zielVerzeichnis);
            if (!StorageProvider.getInstance().deleteDir(benutzerHome)) {
                String errorDetails = "Could not delete home directory.";
                Helper.setFehlerMeldung(errorMessageTitle, errorDetails);
                problems.add(EXPORT_ERROR_PREFIX + errorDetails);
                return false;
            }
            prepareUserDirectory(zielVerzeichnis);
        }

        try {
            if (this.exportImages) {
                imageDownload(myProzess, benutzerHome, atsPpnBand, DIRECTORY_SUFFIX);
            }
            if (this.exportFulltext) {
                fulltextDownload(myProzess, benutzerHome, atsPpnBand);
            }

            String ed = myProzess.getExportDirectory();
            Path exportFolder = Paths.get(ed);
            if (StorageProvider.getInstance().isFileExists(exportFolder) && StorageProvider.getInstance().isDirectory(exportFolder)) {
                List<Path> filesInExportFolder = StorageProvider.getInstance().listFiles(ed);

                for (Path exportFile : filesInExportFolder) {
                    if (StorageProvider.getInstance().isDirectory(exportFile)
                            && !StorageProvider.getInstance().list(exportFile.toString()).isEmpty()) {
                        if (!exportFile.getFileName().toString().matches(".+\\.\\d+")) {
                            String suffix = exportFile.getFileName().toString().substring(exportFile.getFileName().toString().lastIndexOf("_"));
                            Path destination = Paths.get(benutzerHome.toString(), atsPpnBand + suffix);
                            if (!StorageProvider.getInstance().isFileExists(destination)) {
                                StorageProvider.getInstance().createDirectories(destination);
                            }
                            List<Path> files = StorageProvider.getInstance().listFiles(exportFile.toString());
                            for (Path file : files) {
                                Path target = Paths.get(destination.toString(), file.getFileName().toString());
                                StorageProvider.getInstance().copyFile(file, target);
                            }
                        }
                    } else {
                        // if it is a regular file, export it to source folder
                        Path destination = Paths.get(benutzerHome.toString(), atsPpnBand + "_src");
                        if (!StorageProvider.getInstance().isFileExists(destination)) {
                            StorageProvider.getInstance().createDirectories(destination);
                        }
                        Path target = Paths.get(destination.toString(), exportFile.getFileName().toString());
                        StorageProvider.getInstance().copyFile(exportFile, target);
                    }

                }
            }
        } catch (AccessDeniedException exception) {
            String errorDetails = "Access to " + exception.getMessage() + " was denied.";
            Helper.setFehlerMeldung(errorMessageTitle, errorDetails);
            problems.add(EXPORT_ERROR_PREFIX + errorDetails);
            return false;
        } catch (Exception exception) { //NOSONAR InterruptedException must not be re-thrown as it is not running in a separate thread
            Helper.setFehlerMeldung(errorMessageTitle, exception);
            problems.add(EXPORT_ERROR_PREFIX + exception.getMessage());
            return false;
        }
        return true;
    }

    protected String prepareUserDirectory(String inTargetFolder) {
        String target = inTargetFolder;
        User myBenutzer = Helper.getCurrentUser();
        if (myBenutzer != null) {
            try {
                FilesystemHelper.createDirectoryForUser(target, myBenutzer.getLogin());
            } catch (Exception e) { //NOSONAR InterruptedException must not be re-thrown as it is not running in a separate thread
                Helper.setFehlerMeldung("Export canceled, could not create destination directory: " + inTargetFolder, e);
            }
        }
        return target;
    }

    public void fulltextDownload(Process myProzess, Path benutzerHome, String atsPpnBand)
            throws IOException, SwapException {

        // download sources
        Path sources = Paths.get(myProzess.getSourceDirectory());
        if (StorageProvider.getInstance().isFileExists(sources) && !StorageProvider.getInstance().list(sources.toString()).isEmpty()) {
            Path destination = Paths.get(benutzerHome.toString(), atsPpnBand + "_src");
            if (!StorageProvider.getInstance().isFileExists(destination)) {
                StorageProvider.getInstance().createDirectories(destination);
            }
            List<Path> dateien = StorageProvider.getInstance().listFiles(myProzess.getSourceDirectory());
            for (Path dir : dateien) {
                Path meinZiel = Paths.get(destination.toString(), dir.getFileName().toString());
                StorageProvider.getInstance().copyFile(dir, meinZiel);
            }
        }

        Path ocr = Paths.get(myProzess.getOcrDirectory());
        if (StorageProvider.getInstance().isFileExists(ocr)) {
            List<Path> folder = StorageProvider.getInstance().listFiles(myProzess.getOcrDirectory());
            for (Path dir : folder) {
                if (StorageProvider.getInstance().isDirectory(dir) && !StorageProvider.getInstance().list(dir.toString()).isEmpty()) {
                    String suffix = dir.getFileName().toString().substring(dir.getFileName().toString().lastIndexOf("_"));
                    Path destination = Paths.get(benutzerHome.toString(), atsPpnBand + suffix);
                    if (!StorageProvider.getInstance().isFileExists(destination)) {
                        StorageProvider.getInstance().createDirectories(destination);
                    }
                    List<Path> files = StorageProvider.getInstance().listFiles(dir.toString());
                    for (Path file : files) {
                        Path target = Paths.get(destination.toString(), file.getFileName().toString());
                        StorageProvider.getInstance().copyFile(file, target);
                    }
                }
            }
        }
    }

    public void imageDownload(Process myProzess, Path benutzerHome, String atsPpnBand, final String ordnerEndung)
            throws IOException, InterruptedException, SwapException, DAOException {

        /*
         * -------------------------------- dann den Ausgangspfad ermitteln --------------------------------
         */
        Path tifOrdner = Paths.get(myProzess.getImagesTifDirectory(false));

        /*
         * -------------------------------- jetzt die Ausgangsordner in die Zielordner kopieren --------------------------------
         */
        Path zielTif = Paths.get(benutzerHome.toString(), atsPpnBand + ordnerEndung);
        if (StorageProvider.getInstance().isFileExists(tifOrdner) && !StorageProvider.getInstance().list(tifOrdner.toString()).isEmpty()) {

            /* bei Agora-Import einfach den Ordner anlegen */
            if (myProzess.getProjekt().isUseDmsImport()) {
                if (!StorageProvider.getInstance().isFileExists(zielTif)) {
                    StorageProvider.getInstance().createDirectories(zielTif);
                }
            } else {
                /*
                 * wenn kein Agora-Import, dann den Ordner mit Benutzerberechtigung neu anlegen
                 */
                User myBenutzer = Helper.getCurrentUser();
                try {
                    if (myBenutzer == null) {
                        StorageProvider.getInstance().createDirectories(zielTif);
                    } else {
                        FilesystemHelper.createDirectoryForUser(zielTif.toString(), myBenutzer.getLogin());
                    }
                } catch (Exception exception) { //NOSONAR InterruptedException must not be re-thrown as it is not running in a separate thread
                    String errorDetails = "Could not create destination directory.";
                    Helper.setFehlerMeldung(EXPORT_ERROR_PREFIX + "Error", errorDetails);
                    log.error(errorDetails, exception);
                }
            }

            /* jetzt den eigentlichen Kopiervorgang */
            List<Path> files = StorageProvider.getInstance().listFiles(myProzess.getImagesTifDirectory(false), NIOFileUtils.DATA_FILTER);
            for (Path file : files) {
                Path target = Paths.get(zielTif.toString(), file.getFileName().toString());
                StorageProvider.getInstance().copyFile(file, target);

                //for 3d object files look for "helper files" with the same base name and copy them as well
                if (NIOFileUtils.objectNameFilter.accept(file)) {
                    copy3DObjectHelperFiles(myProzess, zielTif, file);
                }
            }
        }

        if (ConfigurationHelper.getInstance().isExportFilesFromOptionalMetsFileGroups()) {

            List<ProjectFileGroup> myFilegroups = myProzess.getProjekt().getFilegroups();
            if (myFilegroups != null && !myFilegroups.isEmpty()) {
                for (ProjectFileGroup pfg : myFilegroups) {
                    // check if source files exists
                    if (pfg.getFolder() != null && pfg.getFolder().length() > 0) {
                        Path folder = Paths.get(myProzess.getMethodFromName(pfg.getFolder()));
                        if (folder != null && StorageProvider.getInstance().isFileExists(folder)
                                && !StorageProvider.getInstance().list(folder.toString()).isEmpty()) {
                            List<Path> files = StorageProvider.getInstance().listFiles(folder.toString());
                            for (Path file : files) {
                                Path target = Paths.get(zielTif.toString(), file.getFileName().toString());
                                StorageProvider.getInstance().copyFile(file, target);
                            }
                        }
                    }
                }
            }
        }
    }

    public void copy3DObjectHelperFiles(Process myProzess, Path zielTif, Path file)
            throws IOException, SwapException {
        Path tiffDirectory = Paths.get(myProzess.getImagesTifDirectory(true));
        String baseName = FilenameUtils.getBaseName(file.getFileName().toString());
        List<Path> helperFiles = StorageProvider.getInstance()
                .listDirNames(tiffDirectory.toString())
                .stream()
                .filter(dirName -> dirName.equals(baseName))
                .map(tiffDirectory::resolve)
                .collect(Collectors.toList());
        for (Path helperFile : helperFiles) {
            Path helperTarget = Paths.get(zielTif.toString(), helperFile.getFileName().toString());
            if (StorageProvider.getInstance().isDirectory(helperFile)) {
                StorageProvider.getInstance().copyDirectory(helperFile, helperTarget);
            } else {
                StorageProvider.getInstance().copyFile(helperFile, helperTarget);
            }
        }
    }

    private void vocabularyEnrichment(MetadataGroup group, List<VocabularyRecordConfig> vocabConfigs) {

        for (VocabularyRecordConfig config : vocabConfigs) {
            if (Objects.equals(config.getGroupType(), group.getType().getName())) {
                List<Metadata> vocabIds = group.getMetadataByType(config.getRecordIdentifierMetadata());
                if (vocabIds != null) {
                    for (Metadata vocabIdMetadata : vocabIds) {
                        try {
                            Integer vocabularyRecordId = Optional.ofNullable(vocabIdMetadata)
                                    .map(Metadata::getValue)
                                    .filter(StringUtils::isNotBlank)
                                    .filter(StringUtils::isNumeric)
                                    .map(Long::parseLong)
                                    .map(Long::intValue)
                                    .orElse(-1);
                            ExtendedVocabularyRecord r = VocabularyAPIManager.getInstance().vocabularyRecords().get(vocabularyRecordId);
                            Vocabulary vocabulary = VocabularyAPIManager.getInstance().vocabularies().get(r.getVocabularyId());
                            VocabularySchema schema = VocabularyAPIManager.getInstance().vocabularySchemas().get(vocabulary.getSchemaId());

                            for (VocabularyEnrichment enrichment : config.getEnrichments()) {
                                Optional<FieldDefinition> enrichmentField = schema.getDefinitions()
                                        .stream()
                                        .filter(d -> d.getName().equals(enrichment.getVocabularyField()))
                                        .findFirst();

                                if (enrichmentField.isEmpty()) {
                                    continue;
                                }

                                Optional<String> fieldValue = r.getFieldValueForDefinition(enrichmentField.get());

                                if (fieldValue.isEmpty()) {
                                    continue;
                                }

                                List<Metadata> metadataList =
                                        Optional.ofNullable(group.getMetadataByType(enrichment.getMetadataType())).orElse(Collections.emptyList());
                                for (Metadata metadata : metadataList) {
                                    if (StringUtils.isBlank(metadata.getValue()) || "null".equalsIgnoreCase(metadata.getValue())) {
                                        metadata.setValue(fieldValue.get());
                                    }
                                }
                            }
                        } catch (APIException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }
    }

    private void vocabularyEnrichment(Prefs prefs, Metadata metadata) throws MetadataTypeNotAllowedException {
        if (!hasVocabularyReference(metadata)) {
            return;
        }
        if (isExternalReference(metadata)) {
            return;
        }
        if (!isValidVocabularyReference(metadata)) {
            log.warn("Metadata has invalid vocabulary references!\n\tAuthority: {}\n\tAuthority URI: {}\n\tValue URI: {}", metadata.getAuthorityID(),
                    metadata.getAuthorityURI(), metadata.getAuthorityValue());
            return;
        }

        String vocabularyName = metadata.getAuthorityID();
        String vocabRecordUrl = metadata.getAuthorityValue();

        ExtendedVocabularyRecord matchingRecord = VocabularyAPIManager.getInstance().vocabularyRecords().get(vocabRecordUrl);

        switch (vocabularyName) {
            case "Location":
                String value = matchingRecord.getFieldValueForDefinitionName("Location").orElseThrow();
                String authority = matchingRecord.getFieldValueForDefinitionName("Authority Value").orElseThrow();
                metadata.setValue(value);
                metadata.setAuthorityFile("geonames", "http://www.geonames.org/", "http://www.geonames.org/" + authority);
                break;
            case "R01 Relationship Person - Person":
            case "R02 Relationship Collective agent - Collective agent":
            case "R03 Relationship Person - Collective agent":
            case "R04 Relationship Person - Event":
            case "R05 Relationship Collective agent - Event":
            case "R06 Relationship Person - Work":
            case "R07 Relationship Collective agent - Work":
            case "R08 Relationship Event - Work":
            case "R09 Relationship Person - Award":
            case "R10 Relationship Collective agent - Award":
            case "R11 Relationship Work - Award":
            case "R12 Relationship Event - Award":
            case "R13 Relationship Award - Award":
            case "R13 Relationship Work - Work":
            case "R14 Relationship Event - Event":
                String eng = null;
                String fre = null;
                String ger = null;
                // check if relation or reverse relation is used
                boolean useReverseRelationship = false;
                for (ExtendedFieldInstance f : matchingRecord.getExtendedFields()) {
                    if (f.getFieldValue().equals(metadata.getValue())) {
                        if (f.getDefinition().getName().startsWith("Reverse")) {
                            useReverseRelationship = true;
                        }
                        break;
                    }
                }
                // get normed values
                for (ExtendedFieldInstance f : matchingRecord.getExtendedFields()) {
                    if ((f.getDefinition().getName().startsWith("Reverse") && useReverseRelationship)
                            || (f.getDefinition().getName().startsWith("Relationship") && !useReverseRelationship)) {
                        ger = f.getFieldValue("ger");
                        eng = f.getFieldValue("eng");
                        fre = f.getFieldValue("fre");
                    }
                }
                // write normed metadata
                try {
                    Metadata md = new Metadata(prefs.getMetadataTypeByName("_relationship_type_ger"));
                    md.setValue(ger);
                    metadata.getParent().addMetadata(md);
                } catch (MetadataTypeNotAllowedException e) {
                    log.trace(e);
                }
                try {
                    Metadata md = new Metadata(prefs.getMetadataTypeByName("_relationship_type_eng"));
                    md.setValue(eng);
                    metadata.getParent().addMetadata(md);
                } catch (MetadataTypeNotAllowedException e) {
                    log.trace(e);
                }
                try {
                    Metadata md = new Metadata(prefs.getMetadataTypeByName("_relationship_type_fre"));
                    md.setValue(fre);
                    metadata.getParent().addMetadata(md);
                } catch (MetadataTypeNotAllowedException e) {
                    log.trace(e);
                }
                break;

            default:
                for (ExtendedFieldInstance f : matchingRecord.getExtendedFields()) {
                    FieldDefinition def = f.getDefinition();
                    Map<String, List<String>> languageValues = new HashMap<>();
                    for (FieldValue v : f.getValues()) {
                        for (TranslationInstance t : v.getTranslations()) {
                            String language = "";
                            if (t.getLanguage() != null) {
                                language = t.getLanguage();
                            }
                            languageValues.computeIfAbsent(language, l -> new LinkedList<>());
                            languageValues.get(language).add(t.getValue());
                        }
                    }
                    for (Map.Entry<String, List<String>> e : languageValues.entrySet()) {
                        // find metadata name
                        String metadataName = "_" + def.getName() + (e.getKey().isBlank() ? "" : "_" + e.getKey());
                        metadataName = metadataName.replace(" ", "").toLowerCase();

                        // create metadata
                        MetadataType mdt = prefs.getMetadataTypeByName(metadataName);
                        if (mdt != null) {
                            // set value
                            Metadata vocabMetadata = new Metadata(mdt);
                            vocabMetadata.setValue(String.join("|", e.getValue()));
                            try {
                                metadata.getParent().addMetadata(vocabMetadata);
                            } catch (MetadataTypeNotAllowedException ex) {
                                log.trace(e);
                            }
                        }
                    }
                }
                break;
        }

        String value = metadata.getValue();
        matchingRecord.writeReferenceMetadata(metadata);
        metadata.setValue(value);
    }

    private boolean hasVocabularyReference(Metadata metadata) {
        return StringUtils.isNotBlank(metadata.getAuthorityURI())
                && StringUtils.isNotBlank(metadata.getAuthorityValue());
    }

    private boolean isExternalReference(Metadata metadata) {
        return hasVocabularyReference(metadata)
                && metadata.getAuthorityURI().contains("http://www.geonames.org");
    }

    private boolean isValidVocabularyReference(Metadata metadata) {
        return hasVocabularyReference(metadata)
                && metadata.getAuthorityURI().contains("/api/v1/vocabularies/")
                && metadata.getAuthorityValue().contains("/api/v1/records/");
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