//package de.intranda.goobi.plugins;
//
//import static org.junit.Assert.assertNotNull;
//
//import java.io.File;
//import java.nio.file.Files;
//import java.nio.file.Path;
//import java.nio.file.Paths;
//
//import org.apache.commons.configuration.ConfigurationException;
//import org.apache.commons.configuration.XMLConfiguration;
//import org.apache.commons.configuration.reloading.FileChangedReloadingStrategy;
//import org.easymock.EasyMock;
//import org.goobi.beans.Process;
//import org.goobi.beans.Project;
//import org.goobi.beans.Ruleset;
//import org.junit.Before;
//import org.junit.Rule;
//import org.junit.Test;
//import org.junit.rules.TemporaryFolder;
//import org.junit.runner.RunWith;
//import org.powermock.api.easymock.PowerMock;
//import org.powermock.core.classloader.annotations.PowerMockIgnore;
//import org.powermock.core.classloader.annotations.PrepareForTest;
//import org.powermock.modules.junit4.PowerMockRunner;
//
//import de.sub.goobi.config.ConfigPlugins;
//import de.sub.goobi.config.ConfigurationHelper;
//import de.sub.goobi.helper.CloseStepHelper;
//import de.sub.goobi.helper.VariableReplacer;
//import de.sub.goobi.metadaten.MetadatenHelper;
//import de.sub.goobi.persistence.managers.ProcessManager;
//import de.sub.goobi.persistence.managers.StepManager;
//import ugh.dl.Fileformat;
//import ugh.dl.Prefs;
//import ugh.fileformats.mets.MetsMods;
//
//@RunWith(PowerMockRunner.class)
//@PrepareForTest({ MetadatenHelper.class, VariableReplacer.class, ConfigPlugins.class, StepManager.class, ConfigurationHelper.class,
//    ProcessManager.class, CloseStepHelper.class })
//@PowerMockIgnore({ "javax.management.*", "javax.net.ssl.*", "jdk.internal.reflect.*", "com.sun.org.apache.xerces.*",
//    "javax.xml.*", "org.xml.*" })
//public class LuxArtistDictionaryExportPluginTest {
//
//    @Rule
//    public TemporaryFolder folder = new TemporaryFolder();
//    private File processDirectory;
//    private File metadataDirectory;
//    private Process process;
//
//    private String resourcesFolder;
//
//    @Before
//    public void setUp() throws Exception {
//
//        resourcesFolder = "src/test/resources/"; // for junit tests in eclipse
//        if (!Files.exists(Paths.get(resourcesFolder))) {
//            resourcesFolder = "target/test-classes/"; // to run mvn test from cli or in jenkins
//        }
//
//        metadataDirectory = folder.newFolder("metadata");
//
//        processDirectory = new File(metadataDirectory + File.separator + "1");
//        processDirectory.mkdirs();
//        String metadataDirectoryName = metadataDirectory.getAbsolutePath() + File.separator;
//
//        // copy meta.xml
//        Path metaSource = Paths.get(resourcesFolder + "meta.xml");
//        Path metaTarget = Paths.get(processDirectory.getAbsolutePath(), "meta.xml");
//        Files.copy(metaSource, metaTarget);
//
//        // copy ruleset
//        XMLConfiguration config = getConfig();
//        PowerMock.mockStatic(ConfigPlugins.class);
//        EasyMock.expect(ConfigPlugins.getPluginConfig("intranda_workflow_projectexport")).andReturn(config).anyTimes();
//
//        PowerMock.mockStatic(ConfigurationHelper.class);
//        ConfigurationHelper configurationHelper = EasyMock.createMock(ConfigurationHelper.class);
//        EasyMock.expect(ConfigurationHelper.getInstance()).andReturn(configurationHelper).anyTimes();
//        EasyMock.expect(configurationHelper.getMetsEditorLockingTime()).andReturn(1800000l).anyTimes();
//        EasyMock.expect(configurationHelper.isAllowWhitespacesInFolder()).andReturn(false).anyTimes();
//        EasyMock.expect(configurationHelper.useS3()).andReturn(false).anyTimes();
//        EasyMock.expect(configurationHelper.isUseMasterDirectory()).andReturn(true).anyTimes();
//        EasyMock.expect(configurationHelper.getRulesetFolder()).andReturn(resourcesFolder).anyTimes();
//
//        EasyMock.expect(configurationHelper.isCreateMasterDirectory()).andReturn(false).anyTimes();
//        EasyMock.expect(configurationHelper.isCreateSourceFolder()).andReturn(false).anyTimes();
//        EasyMock.expect(configurationHelper.getProcessImagesMainDirectoryName()).andReturn("RM0166F05-0000001_media").anyTimes();
//        EasyMock.expect(configurationHelper.getFolderForInternalProcesslogFiles()).andReturn("intern").anyTimes();
//        EasyMock.expect(configurationHelper.getMetadataFolder()).andReturn(metadataDirectoryName).anyTimes();
//
//        EasyMock.expect(configurationHelper.getScriptCreateDirMeta()).andReturn("").anyTimes();
//        EasyMock.replay(configurationHelper);
//        PowerMock.mockStatic(VariableReplacer.class);
//        EasyMock.expect(VariableReplacer.simpleReplace(EasyMock.anyString(), EasyMock.anyObject())).andReturn("RM0166F05-0000001_media").anyTimes();
//        PowerMock.replay(VariableReplacer.class);
//
//        PowerMock.mockStatic(CloseStepHelper.class);
//        //        EasyMock.expect(CloseStepHelper.closeStep(EasyMock.anyObject(), EasyMock.anyObject())).andAnswer(new IAnswer<Boolean>() {
//        //
//        //            @Override
//        //            public Boolean answer() throws Throwable {
//        //                for (Step step : process.getSchritte()) {
//        //                    if (step.getTitel().equals("test step to close")) {
//        //                        step.setBearbeitungsstatusEnum(StepStatus.DONE);
//        //                    } else if (step.getTitel().equals("locked step that should open")) {
//        //                        step.setBearbeitungsstatusEnum(StepStatus.OPEN);
//        //                    }
//        //                }
//        //
//        //                return true;
//        //            }
//        //        }).anyTimes();
//        //
//        //        PowerMock.replay(CloseStepHelper.class);
//
//        Prefs prefs = new Prefs();
//        prefs.loadPrefs(resourcesFolder + "ruleset.xml");
//        Fileformat ff = new MetsMods(prefs);
//        ff.read(metaTarget.toString());
//
//        PowerMock.mockStatic(MetadatenHelper.class);
//        EasyMock.expect(MetadatenHelper.getMetaFileType(EasyMock.anyString())).andReturn("mets").anyTimes();
//        EasyMock.expect(MetadatenHelper.getFileformatByName(EasyMock.anyString(), EasyMock.anyObject())).andReturn(ff).anyTimes();
//
//        PowerMock.mockStatic(StepManager.class);
//        PowerMock.mockStatic(ProcessManager.class);
//        PowerMock.replay(ConfigPlugins.class);
//        PowerMock.replay(ConfigurationHelper.class);
//        PowerMock.replay(MetadatenHelper.class);
//        process = getProcess();
//        EasyMock.expect(ProcessManager.getProcessById(EasyMock.anyInt())).andReturn(process).anyTimes();
//        PowerMock.replay(ProcessManager.class);
//    }
//
//    @Test
//    public void testConstructor() throws Exception {
//        LuxArtistDictionaryExportPlugin plugin = new LuxArtistDictionaryExportPlugin();
//        assertNotNull(plugin);
//        plugin.startExport(process);
//    }
//
//    private XMLConfiguration getConfig() {
//        String file = "plugin_intranda_export_sample.xml";
//        XMLConfiguration config = new XMLConfiguration();
//        config.setDelimiterParsingDisabled(true);
//        try {
//            config.load(resourcesFolder + file);
//        } catch (ConfigurationException e) {
//        }
//        config.setReloadingStrategy(new FileChangedReloadingStrategy());
//        return config;
//    }
//
//    public Process getProcess() {
//        Project project = createProject();
//
//        Process process = new Process();
//        process.setTitel("RM0166F01-0000001");
//        process.setProjekt(project);
//        process.setId(1);
//
//        Ruleset ruleset = new Ruleset();
//        process.setRegelsatz(ruleset);
//        ruleset.setDatei("ruleset.xml");
//
//        return process;
//    }
//
//    private Project createProject() {
//        Project project = new Project();
//        project.setTitel("SampleProject");
//        project.setMetsRightsOwner("Centro Bibliografico");
//        project.setMetsRightsOwnerSite("http://ucei.it/centro-bibliografico/");
//        return project;
//    }
//
//}
