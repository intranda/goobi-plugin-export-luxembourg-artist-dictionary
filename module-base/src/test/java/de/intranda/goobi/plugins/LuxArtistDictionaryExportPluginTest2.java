package de.intranda.goobi.plugins;

import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.commons.configuration.XMLConfiguration;
import org.easymock.EasyMock;
import org.powermock.api.easymock.PowerMock;

import ugh.dl.DigitalDocument;
import ugh.dl.Fileformat;
import ugh.dl.Prefs;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;
import ugh.fileformats.mets.MetsMods;

//@RunWith(PowerMockRunner.class)
//@PrepareForTest({ MetadatenHelper.class, VariableReplacer.class, ConfigPlugins.class, StepManager.class, ConfigurationHelper.class,
//  ProcessManager.class, CloseStepHelper.class })
//@PowerMockIgnore({ "javax.management.*", "javax.net.ssl.*", "jdk.internal.reflect.*", "com.sun.org.apache.xerces.*",
//  "javax.xml.*", "org.xml.*" })
public class LuxArtistDictionaryExportPluginTest2 {

    Path meta = Paths.get("src/test/resources/848f183d-ea46-48d7-a8f1-b9fab4da5c02.xml");
    Path ruleset = Paths.get("src/test/resources/entity.xml");

    // @Test
    public void test_setRepresentative()
            throws PreferencesException, MetadataTypeNotAllowedException, NotExportableException, ExportException, ReadException {

        Path file = Paths.get(
                "/opt/digiverso/goobi/metadata/17/images/848f183d-ea46-48d7-a8f1-b9fab4da5c02_media/Roger BERTEMES - exposition Convento à Asolo (IT) 1976 - Photo et copyright Paul Bertemes_page-0001(1).jpg");

        XMLConfiguration config = PowerMock.createMock(XMLConfiguration.class);
        EasyMock.expect(config.getBoolean(EasyMock.anyObject(), EasyMock.anyObject())).andReturn(true);

        LuxArtistDictionaryExportPlugin plugin = new LuxArtistDictionaryExportPlugin();
        Prefs prefs = new Prefs();
        prefs.loadPrefs(ruleset.toAbsolutePath().toString());
        Fileformat ff = new MetsMods(prefs);
        ff.read(meta.toAbsolutePath().toString());
        //
        //
        DigitalDocument dd = plugin.enrichFileformat(ff, prefs, config, null);
        assertTrue(dd.getFileSet().getAllFiles().get(5).isRepresentative());
    }

}
