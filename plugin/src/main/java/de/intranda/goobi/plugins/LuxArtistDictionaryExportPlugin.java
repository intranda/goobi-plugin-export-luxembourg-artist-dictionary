package de.intranda.goobi.plugins;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.goobi.beans.Process;
import org.goobi.beans.Step;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IExportPlugin;
import org.goobi.production.plugin.interfaces.IPlugin;

import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.ExportFileException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.helper.exceptions.UghHelperException;
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
            List<? extends Metadata> md= logical.getAllMetadataByType(published);
            if (md.isEmpty()) {
                // TODO set message to skip
                return true;
            }
            if ("N".equalsIgnoreCase(md.get(0).getValue())) {
                // TODO set message to skip
                return true;
            }
            // otherwise record is marked as exportable

            List<MetadataGroup> allSources = new ArrayList<>();
            List<MetadataGroup> bibliographyList = new ArrayList<>();
            // run through all groups, check if group should be exported
            for (MetadataGroup grp : logical.getAllMetadataGroups()) {
                for (Metadata metadata : grp.getMetadataList()) {
                    if (metadata.getType().getName().equals("Published") && metadata.getValue().equalsIgnoreCase("N")) {
                        // TODO remove group, if its marked as not exportable
                    }
                }
                // collect all sources
                List<MetadataGroup> sources = grp.getAllMetadataGroupsByName("Source");
                allSources.addAll(sources);
                if (grp.getType().getName().equals("Bibliography")) {
                    bibliographyList.add(grp);
                }
            }



            // create bibliography from exported sources
            for (MetadataGroup currentSource : allSources) {
                boolean sourceMatched = false;
                String sourceId = currentSource.getMetadataByType("SourceID").get(0).getValue();
                for (MetadataGroup bibliography : bibliographyList) {
                    // TODO extent bibliography to save vocab id (SourceID)
                    if (bibliography.getMetadataByType("Link").get(0).getValue().equals(sourceId)) {
                        sourceMatched = true;
                        break;
                    }
                }
                if (!sourceMatched) {
                    MetadataGroup bib = new MetadataGroup(prefs.getMetadataGroupTypeByName("Bibliography"));
                    Metadata type = new Metadata(prefs.getMetadataTypeByName("Type"));
                    type.setValue(currentSource.getMetadataByType("SourceType").get(0).getValue());
                    bib.addMetadata(type);


                    Metadata citation = new Metadata(prefs.getMetadataTypeByName("Citation"));
                    citation.setValue(currentSource.getMetadataByType("SourceName").get(0).getValue());
                    bib.addMetadata(citation);

                    Metadata link = new Metadata(prefs.getMetadataTypeByName("Link"));
                    link.setValue(currentSource.getMetadataByType("SourceLink").get(0).getValue());
                    bib.addMetadata(link);



                    logical.addMetadataGroup(bib);
                    bibliographyList.add(bib);
                }
            }

            //TODO extend metadata with vocabulary information ?

            // export data
            MetsModsImportExport mm = new MetsModsImportExport(prefs);
            mm.setDigitalDocument(dd);
            mm.write(destination + process.getTitel()+ ".xml");
        } catch (ReadException | PreferencesException | WriteException | IOException | InterruptedException | SwapException | DAOException e) {
            log.error(e);
            problems.add("Cannot read metadata file.");
            return false;
        }


        return true;
    }
}