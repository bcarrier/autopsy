/*
 * Autopsy Forensic Browser
 *
 * Copyright 2014-2021 Basis Technology Corp.
 * Contact: carrier <at> sleuthkit <dot> org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sleuthkit.autopsy.ingest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.annotation.concurrent.GuardedBy;
import javax.swing.JOptionPane;
import org.netbeans.api.progress.ProgressHandle;
import org.openide.util.Cancellable;
import org.openide.util.NbBundle;
import org.openide.windows.WindowManager;
import org.sleuthkit.autopsy.casemodule.Case;
import org.sleuthkit.autopsy.core.RuntimeProperties;
import org.sleuthkit.autopsy.coreutils.Logger;
import org.sleuthkit.autopsy.coreutils.NetworkUtils;
import org.sleuthkit.autopsy.ingest.IngestTasksScheduler.IngestJobTasksSnapshot;
import org.sleuthkit.datamodel.AbstractFile;
import org.sleuthkit.datamodel.Content;
import org.sleuthkit.datamodel.IngestJobInfo;
import org.sleuthkit.datamodel.IngestJobInfo.IngestJobStatusType;
import org.sleuthkit.datamodel.IngestModuleInfo;
import org.sleuthkit.datamodel.IngestModuleInfo.IngestModuleType;
import org.sleuthkit.datamodel.SleuthkitCase;
import org.sleuthkit.datamodel.TskCoreException;
import org.sleuthkit.autopsy.modules.interestingitems.FilesSet;
import org.sleuthkit.autopsy.python.FactoryClassNameNormalizer;
import org.sleuthkit.datamodel.DataArtifact;
import org.sleuthkit.datamodel.DataSource;

/**
 * An ingest-job-level pipeline that works with the ingest tasks scheduler to
 * coordinate the creation, scheduling, and execution of ingest tasks for one of
 * the data sources in an ingest job. An ingest job pipeline is actually
 * composed of multiple ingest task pipelines. Each ingest task pipeline is a
 * sequence of ingest modules of a given type (e.g., data source level, file
 * level, or artifact ingest modules) that have been enabled and configured as
 * part of the ingest job settings.
 */
final class IngestJobPipeline {

    private static final String AUTOPSY_MODULE_PREFIX = "org.sleuthkit.autopsy";

    private static final Logger logger = Logger.getLogger(IngestJobPipeline.class.getName());

    /*
     * A regular expression for identifying the proxy classes Jython generates
     * for ingest module factories classes written using Python. For example:
     * org.python.proxies.GPX_Parser_Module$GPXParserFileIngestModuleFactory$14
     */
    private static final Pattern JYTHON_MODULE_REGEX = Pattern.compile("org\\.python\\.proxies\\.(.+?)\\$(.+?)(\\$[0-9]*)?$");

    /*
     * These fields define an ingest pipeline: the parent ingest job, a pipeline
     * ID, the user's ingest job settings, and the data source to be analyzed.
     * Optionally, there is a set of files to be analyzed, instead of analyzing
     * ALL of the files in the data source.
     *
     * The pipeline ID is used to associate the pipeline with its ingest tasks.
     * The ingest job ID cannot be used for this purpose because the parent
     * ingest job may have more than one data source and each data source gets
     * its own pipeline.
     */
    private final IngestJob job;
    private static final AtomicLong nextPipelineId = new AtomicLong(0L);
    private final long pipelineId;
    private final IngestJobSettings settings;
    private DataSource dataSource;
    private final List<AbstractFile> files;

    /*
     * An ingest pipeline runs its ingest modules in stages.
     */
    private static enum Stages {
        /*
         * The pipeline is instantiating ingest modules and loading them into
         * its ingest task pipelines.
         */
        INITIALIZATION,
        /*
         * The pipeline is running file ingest modules on files streamed to it
         * by a data source processor. The data source has not been added to the
         * pipeline yet.
         */
        FIRST_STAGE_STREAMING,
        /*
         * The pipeline is running one or more of the following three types of
         * ingest modules: higher priority data source level ingest modules,
         * file ingest modules, and artifact ingest modules.
         */
        FIRST_STAGE,
        /**
         * The pipeline is running lower priority, usually long-running, data
         * source level ingest modules and artifact ingest modules.
         */
        SECOND_STAGE,
        /**
         * The pipeline is shutting down its ingest modules.
         */
        FINALIZATION
    };
    @GuardedBy("stageTransitionLock")
    private Stages stage = IngestJobPipeline.Stages.INITIALIZATION;
    private final Object stageTransitionLock = new Object();

    /**
     * An ingest pipeline has separate data source level ingest task pipelines
     * for the first and second stages. Longer running, lower priority modules
     * belong in the second stage pipeline.
     */
    private final Object dataSourceIngestPipelineLock = new Object();
    private DataSourceIngestPipeline firstStageDataSourceIngestPipeline;
    private DataSourceIngestPipeline secondStageDataSourceIngestPipeline;
    private DataSourceIngestPipeline currentDataSourceIngestPipeline;

    /**
     * An ingest pipeline has a collection of identical file ingest task
     * pipelines, one for each file ingest thread in the ingest manager. The
     * ingest threads take ingest task pipelines as they need them and return
     * the pipelines using a blocking queue. Additionally, a fixed list of all
     * of the file pipelines is used to cycle through each of the individual
     * task pipelines to check their status.
     */
    private final LinkedBlockingQueue<FileIngestPipeline> fileIngestPipelinesQueue = new LinkedBlockingQueue<>();
    private final List<FileIngestPipeline> fileIngestPipelines = new ArrayList<>();

    /*
     * An ingest pipeline has a single artifact ingest task pipeline
     */
    private DataArtifactIngestPipeline artifactIngestPipeline;

    /**
     * An ingest pipeline supports cancellation of just its currently running
     * data source level ingest task pipeline or cancellation of ALL of its
     * child ingest task pipelines. Cancellation works by setting flags that are
     * checked by the ingest task pipelines every time they transition from one
     * module to another. Modules are also expected to check these flags (via
     * the ingest job context) and stop processing if they are set. This means
     * that there can be a variable length delay between a cancellation request
     * and its fulfillment.
     */
    private volatile boolean currentDataSourceIngestModuleCancelled;
    private final List<String> cancelledDataSourceIngestModules = new CopyOnWriteArrayList<>();
    private volatile boolean cancelled;
    private volatile IngestJob.CancellationReason cancellationReason = IngestJob.CancellationReason.NOT_CANCELLED;

    /*
     * An ingest pipeline interacts with the ingest task scheduler to create and
     * queue ingest tasks and to determine whether or not there are ingest tasks
     * still to be executed so that the pipeline can transition through its
     * stages. The ingest modules in the pipeline can schedule ingest tasks as
     * well (via the ingest job context). For example, a file carving module can
     * add carved files to the ingest job and most modules will add data
     * artifacts to the ingest job.
     */
    private static final IngestTasksScheduler taskScheduler = IngestTasksScheduler.getInstance();

    /**
     * If running in a GUI, an ingest pipeline reports progress and allows a
     * user to cancel either an individual data source level ingest module or
     * all of its ingest tasks using progress bars in the lower right hand
     * corner of the main application window. There is also support for taking
     * ingest progress snapshots and for recording ingest job details in the
     * case database.
     */
    private final boolean doUI;
    private final Object dataSourceIngestProgressLock = new Object();
    private ProgressHandle dataSourceIngestProgressBar;
    private final Object fileIngestProgressLock = new Object();
    private final List<String> filesInProgress = new ArrayList<>();
    private long estimatedFilesToProcess;
    private long processedFiles;
    private ProgressHandle fileIngestProgressBar;
    private final Object artifactIngestProgressLock = new Object();
    private ProgressHandle artifactIngestProgressBar;
    private volatile IngestJobInfo ingestJobInfo;

    /**
     * An ingest pipeline uses this field to report its creation time.
     */
    private final long createTime;

    /**
     * Constructs an ingest-job-level pipeline that works with the ingest tasks
     * scheduler to coordinate the creation, scheduling, and execution of ingest
     * tasks for one of the data sources in an ingest job. An ingest job
     * pipeline is actually composed of multiple ingest task pipelines. Each
     * ingest task pipeline is a sequence of ingest modules of a given type
     * (e.g., data source level, file level, or artifact ingest modules) that
     * have been enabled and configured as part of the ingest job settings.
     *
     * @param job        The ingest job.
     * @param dataSource One of the data sources that are the subjects of the
     *                   ingest job.
     * @param settings   The ingest settings for the ingest job.
     *
     * @throws InterruptedException Exception thrown if the thread in which the
     *                              pipeline is being created is interrupted.
     */
    IngestJobPipeline(IngestJob job, Content dataSource, IngestJobSettings settings) throws InterruptedException {
        this(job, dataSource, Collections.emptyList(), settings);
    }

    /**
     * Constructs an ingest-job-level pipeline that works with the ingest tasks
     * scheduler to coordinate the creation, scheduling, and execution of ingest
     * tasks for one of the data sources in an ingest job. An ingest job
     * pipeline is actually composed of multiple ingest task pipelines. Each
     * ingest task pipeline is a sequence of ingest modules of a given type
     * (e.g., data source level, file level, or artifact ingest modules) that
     * have been enabled and configured as part of the ingest job settings.
     *
     * @param job        The ingest job.
     * @param dataSource One of the data sources that are the subjects of the
     *                   ingest job.
     * @param files      A subset of the files from the data source. If the list
     *                   is empty, ALL of the files in the data source are an
     *                   analyzed.
     * @param settings   The ingest settings for the ingest job.
     *
     * @throws InterruptedException Exception thrown if the thread in which the
     *                              pipeline is being created is interrupted.
     */
    IngestJobPipeline(IngestJob job, Content dataSource, List<AbstractFile> files, IngestJobSettings settings) throws InterruptedException {
        if (!(dataSource instanceof DataSource)) {
            throw new IllegalArgumentException("Passed dataSource that does not implement the DataSource interface"); //NON-NLS
        }
        this.job = job;
        pipelineId = IngestJobPipeline.nextPipelineId.getAndIncrement();
        this.dataSource = (DataSource) dataSource;
        this.files = new ArrayList<>();
        this.files.addAll(files);
        this.settings = settings;
        doUI = RuntimeProperties.runningWithGUI();
        createTime = new Date().getTime();
        stage = Stages.INITIALIZATION;
        createIngestTaskPipelines();
    }

    /**
     * Adds ingest module templates to an output list with core Autopsy modules
     * first and third party modules next.
     *
     * @param orderedModules The list to populate.
     * @param javaModules    The input ingest module templates for modules
     *                       implemented using Java.
     * @param jythonModules  The input ingest module templates for modules
     *                       implemented using Jython.
     */
    private static void completePipeline(final List<IngestModuleTemplate> orderedModules, final Map<String, IngestModuleTemplate> javaModules, final Map<String, IngestModuleTemplate> jythonModules) {
        final List<IngestModuleTemplate> autopsyModules = new ArrayList<>();
        final List<IngestModuleTemplate> thirdPartyModules = new ArrayList<>();
        Stream.concat(javaModules.entrySet().stream(), jythonModules.entrySet().stream()).forEach((templateEntry) -> {
            if (templateEntry.getKey().startsWith(AUTOPSY_MODULE_PREFIX)) {
                autopsyModules.add(templateEntry.getValue());
            } else {
                thirdPartyModules.add(templateEntry.getValue());
            }
        });
        orderedModules.addAll(autopsyModules);
        orderedModules.addAll(thirdPartyModules);
    }

    /**
     * Extracts a module class name from a Jython module proxy class name. For
     * example, a Jython class name such
     * "org.python.proxies.GPX_Parser_Module$GPXParserFileIngestModuleFactory$14"
     * will be parsed to return
     * "GPX_Parser_Module.GPXParserFileIngestModuleFactory."
     *
     * @param className The canonical class name.
     *
     * @return The Jython proxu class name or null if the extraction fails.
     */
    private static String getModuleNameFromJythonClassName(String className) {
        Matcher m = JYTHON_MODULE_REGEX.matcher(className);
        if (m.find()) {
            return String.format("%s.%s", m.group(1), m.group(2)); //NON-NLS
        } else {
            return null;
        }
    }

    /**
     * Adds an ingest module template to one of two mappings of ingest module
     * factory class names to module templates. One mapping is for ingest
     * modules imnplemented using Java and the other is for ingest modules
     * implemented using Jython.
     *
     * @param mapping       Mapping for Java ingest module templates.
     * @param jythonMapping Mapping for Jython ingest module templates.
     * @param template      The ingest module template.
     */
    private static void addIngestModuleTemplateToMaps(Map<String, IngestModuleTemplate> mapping, Map<String, IngestModuleTemplate> jythonMapping, IngestModuleTemplate template) {
        String className = template.getModuleFactory().getClass().getCanonicalName();
        String jythonName = getModuleNameFromJythonClassName(className);
        if (jythonName != null) {
            jythonMapping.put(jythonName, template);
        } else {
            mapping.put(className, template);
        }
    }

    /**
     * Creates the child ingest task pipelines for this ingest pipeline.
     *
     * @throws InterruptedException Exception thrown if the thread in which the
     *                              task pipelines are being created is
     *                              interrupted.
     */
    private void createIngestTaskPipelines() throws InterruptedException {
        /*
         * Get the enabled ingest module templates from the ingest job settings.
         * An ingest module template combines an ingest module factory with job
         * level ingest module settings to support the creation of any number of
         * fully configured instances of a given ingest module. An ingest module
         * factory may be able to create multiple types of ingest modules.
         */
        List<IngestModuleTemplate> enabledTemplates = settings.getEnabledIngestModuleTemplates();

        /**
         * Sort the ingest module templates into buckets based on the module
         * types the ingest module factory can create. A template may go into
         * more than one bucket. The buckets are actually maps of ingest module
         * factory class names to ingest module templates. The maps are used to
         * go from an ingest module factory class name read from the pipeline
         * configuration file to the corresponding ingest module template.
         *
         * There are actually two maps for each module type bucket. One map is
         * for Java modules and the other one is for Jython modules. The
         * templates are separated this way so that Java modules that are not in
         * the pipeline config file can be placed before the Jython modules.
         */
        Map<String, IngestModuleTemplate> javaDataSourceModuleTemplates = new LinkedHashMap<>();
        Map<String, IngestModuleTemplate> jythonDataSourceModuleTemplates = new LinkedHashMap<>();
        Map<String, IngestModuleTemplate> javaFileModuleTemplates = new LinkedHashMap<>();
        Map<String, IngestModuleTemplate> jythonFileModuleTemplates = new LinkedHashMap<>();
        Map<String, IngestModuleTemplate> javaArtifactModuleTemplates = new LinkedHashMap<>();
        Map<String, IngestModuleTemplate> jythonArtifactModuleTemplates = new LinkedHashMap<>();
        for (IngestModuleTemplate template : enabledTemplates) {
            if (template.isDataSourceIngestModuleTemplate()) {
                addIngestModuleTemplateToMaps(javaDataSourceModuleTemplates, jythonDataSourceModuleTemplates, template);
            }
            if (template.isFileIngestModuleTemplate()) {
                addIngestModuleTemplateToMaps(javaFileModuleTemplates, jythonFileModuleTemplates, template);
            }
            if (template.isDataArtifactIngestModuleTemplate()) {
                addIngestModuleTemplateToMaps(javaArtifactModuleTemplates, jythonArtifactModuleTemplates, template);
            }
        }

        /**
         * Take the module templates that have pipeline configuration file
         * entries out of the buckets and put them in lists representing ingest
         * task pipelines, in the order prescribed by the file. Note that the
         * pipeline configuration file currently only supports specifying data
         * source level and file ingest module pipeline layouts.
         */
        IngestPipelinesConfiguration pipelineConfig = IngestPipelinesConfiguration.getInstance();
        List<IngestModuleTemplate> firstStageDataSourceModuleTemplates = createPipelineFromConfigFile(javaDataSourceModuleTemplates, jythonDataSourceModuleTemplates, pipelineConfig.getStageOneDataSourceIngestPipelineConfig());
        List<IngestModuleTemplate> secondStageDataSourceModuleTemplates = createPipelineFromConfigFile(javaDataSourceModuleTemplates, jythonDataSourceModuleTemplates, pipelineConfig.getStageTwoDataSourceIngestPipelineConfig());
        List<IngestModuleTemplate> fileIngestModuleTemplates = createPipelineFromConfigFile(javaFileModuleTemplates, jythonFileModuleTemplates, pipelineConfig.getFileIngestPipelineConfig());
        List<IngestModuleTemplate> artifactModuleTemplates = new ArrayList<>();

        /**
         * Add any module templates remaining in the buckets to the appropriate
         * ingest task pipeline. Note that any data source level ingest modules
         * that were not listed in the configuration file are added to the first
         * stage data source pipeline, Java modules are added before Jython
         * modules, and Core Autopsy modules are added before third party
         * modules.
         */
        completePipeline(firstStageDataSourceModuleTemplates, javaDataSourceModuleTemplates, jythonDataSourceModuleTemplates);
        completePipeline(fileIngestModuleTemplates, javaFileModuleTemplates, jythonFileModuleTemplates);
        completePipeline(artifactModuleTemplates, javaArtifactModuleTemplates, jythonArtifactModuleTemplates);

        /**
         * Construct the actual ingest task pipelines from the ordered lists.
         */
        firstStageDataSourceIngestPipeline = new DataSourceIngestPipeline(this, firstStageDataSourceModuleTemplates);
        secondStageDataSourceIngestPipeline = new DataSourceIngestPipeline(this, secondStageDataSourceModuleTemplates);
        int numberOfFileIngestThreads = IngestManager.getInstance().getNumberOfFileIngestThreads();
        for (int i = 0; i < numberOfFileIngestThreads; ++i) {
            FileIngestPipeline pipeline = new FileIngestPipeline(this, fileIngestModuleTemplates);
            fileIngestPipelinesQueue.put(pipeline);
            fileIngestPipelines.add(pipeline);
        }
        artifactIngestPipeline = new DataArtifactIngestPipeline(this, artifactModuleTemplates);
    }

    /**
     * Uses an input collection of ingest module templates and a pipeline
     * configuration, i.e., an ordered list of ingest module factory class
     * names, to create an ordered output list of ingest module templates for an
     * ingest task pipeline. The ingest module templates are removed from the
     * input collection as they are added to the output collection.
     *
     * @param javaIngestModuleTemplates   A mapping of Java ingest module
     *                                    factory class names to ingest module
     *                                    templates.
     * @param jythonIngestModuleTemplates A mapping of Jython ingest module
     *                                    factory proxy class names to ingest
     *                                    module templates.
     * @param pipelineConfig              An ordered list of ingest module
     *                                    factory class names representing an
     *                                    ingest pipeline, read form the
     *                                    pipeline configuration file.
     *
     * @return An ordered list of ingest module templates, i.e., an
     *         uninstantiated pipeline.
     */
    private static List<IngestModuleTemplate> createPipelineFromConfigFile(Map<String, IngestModuleTemplate> javaIngestModuleTemplates, Map<String, IngestModuleTemplate> jythonIngestModuleTemplates, List<String> pipelineConfig) {
        List<IngestModuleTemplate> templates = new ArrayList<>();
        for (String moduleClassName : pipelineConfig) {
            if (javaIngestModuleTemplates.containsKey(moduleClassName)) {
                templates.add(javaIngestModuleTemplates.remove(moduleClassName));
            } else if (jythonIngestModuleTemplates.containsKey(moduleClassName)) {
                templates.add(jythonIngestModuleTemplates.remove(moduleClassName));
            }
        }
        return templates;
    }

    /**
     * Gets the ID of this ingest pipeline.
     *
     * @return The ID.
     */
    long getId() {
        return pipelineId;
    }

    /**
     * Gets the parent ingest job of this ingest pipeline.
     *
     * @return The ingest job.
     */
    IngestJob getIngestJob() {
        return job;
    }

    /**
     * Gets the ingest execution context name.
     *
     * @return The context name.
     */
    String getExecutionContext() {
        return settings.getExecutionContext();
    }

    /**
     * Gets the data source to be analyzed by this ingest pipeline.
     *
     * @return The data source.
     */
    DataSource getDataSource() {
        return dataSource;
    }

    /**
     * Gets the subset of the files from the data source to be analyzed by this
     * ingest pipeline.
     *
     * @return The files.
     */
    List<AbstractFile> getFiles() {
        return Collections.unmodifiableList(files);
    }

    /**
     * Queries whether or not unallocated space should be processed by this
     * ingest pipeline.
     *
     * @return True or false.
     */
    boolean shouldProcessUnallocatedSpace() {
        return settings.getProcessUnallocatedSpace();
    }

    /**
     * Gets the file ingest filter for this ingest pipeline.
     *
     * @return The filter.
     */
    FilesSet getFileIngestFilter() {
        return settings.getFileFilter();
    }

    /**
     * Checks to see if this ingest pipeline has at least one ingest module to
     * run.
     *
     * @return True or false.
     */
    boolean hasIngestModules() {
        return hasFileIngestModules()
                || hasFirstStageDataSourceIngestModules()
                || hasSecondStageDataSourceIngestModules()
                || hasArtifactIngestModules();
    }

    /**
     * Checks to see if this ingest pipeline has at least one first stage data
     * source level ingest modules.
     *
     * @return True or false.
     */
    boolean hasFirstStageDataSourceIngestModules() {
        return (firstStageDataSourceIngestPipeline.isEmpty() == false);
    }

    /**
     * Checks to see if this ingest pipeline has at least one second stage data
     * source level ingest module.
     *
     * @return True or false.
     */
    boolean hasSecondStageDataSourceIngestModules() {
        return (secondStageDataSourceIngestPipeline.isEmpty() == false);
    }

    /**
     * Checks to see if this ingest pipeline has at least one file ingest
     * module.
     *
     * @return True or false.
     */
    boolean hasFileIngestModules() {
        if (!fileIngestPipelines.isEmpty()) {
            /*
             * Note that the file ingest task pipelines are identical.
             */
            return !fileIngestPipelines.get(0).isEmpty();
        }
        return false;
    }

    /**
     * Checks to see if this ingest pipeline has at least one artifact ingest
     * module.
     *
     * @return True or false.
     */
    boolean hasArtifactIngestModules() {
        return (artifactIngestPipeline.isEmpty() == false);
    }

    /**
     * Starts up this ingest pipeline.
     *
     * @return A collection of ingest module startup errors, empty on success.
     */
    List<IngestModuleError> startUp() {
        List<IngestModuleError> errors = startUpIngestTaskPipelines();
        if (errors.isEmpty()) {
            recordIngestJobStartUpInfo();
            if (hasFirstStageDataSourceIngestModules() || hasFileIngestModules() || hasArtifactIngestModules()) {
                if (job.getIngestMode() == IngestJob.Mode.STREAMING) {
                    startFirstStageInStreamingMode();
                } else {
                    startFirstStage();
                }
            } else if (hasSecondStageDataSourceIngestModules()) {
                startSecondStage();
            }
        }
        return errors;
    }

    /**
     * Writes start up data about the ingest job into the case database. The
     * case database returns an object that is retained to allow the additon of
     * a completion time when the ingest job is finished.
     */
    void recordIngestJobStartUpInfo() {
        try {
            SleuthkitCase caseDb = Case.getCurrentCase().getSleuthkitCase();
            List<IngestModuleInfo> ingestModuleInfoList = new ArrayList<>();
            for (IngestModuleTemplate module : settings.getEnabledIngestModuleTemplates()) {
                IngestModuleType moduleType = getIngestModuleTemplateType(module);
                IngestModuleInfo moduleInfo = caseDb.addIngestModule(module.getModuleName(), FactoryClassNameNormalizer.normalize(module.getModuleFactory().getClass().getCanonicalName()), moduleType, module.getModuleFactory().getModuleVersionNumber());
                ingestModuleInfoList.add(moduleInfo);
            }
            ingestJobInfo = caseDb.addIngestJob(dataSource, NetworkUtils.getLocalHostName(), ingestModuleInfoList, new Date(this.createTime), new Date(0), IngestJobStatusType.STARTED, "");
        } catch (TskCoreException ex) {
            logErrorMessage(Level.SEVERE, "Failed to add ingest job info to case database", ex); //NON-NLS
        }
    }

    /**
     * Determines the type of ingest modules a given ingest module template
     * supports.
     *
     * @param moduleTemplate The ingest module template.
     *
     * @return The ingest module type, may be IngestModuleType.MULTIPLE.
     */
    private IngestModuleType getIngestModuleTemplateType(IngestModuleTemplate moduleTemplate) {
        IngestModuleType type = null;
        if (moduleTemplate.isDataSourceIngestModuleTemplate()) {
            type = IngestModuleType.DATA_SOURCE_LEVEL;
        }
        if (moduleTemplate.isFileIngestModuleTemplate()) {
            if (type == null) {
                type = IngestModuleType.FILE_LEVEL;
            } else {
                type = IngestModuleType.MULTIPLE;
            }
        }
        if (moduleTemplate.isDataArtifactIngestModuleTemplate()) {
            if (type == null) {
                type = IngestModuleType.DATA_ARTIFACT;
            } else {
                type = IngestModuleType.MULTIPLE;
            }
        }
        return type;
    }

    /**
     * Starts up each of the child ingest task pipelines in this ingest
     * pipeline.
     *
     * Note that all of the child pipelines are started so that any and all
     * start up errors can be returned to the caller. It is important to capture
     * all of the errors, because the ingest job will be automatically cancelled
     * and the errors will be reported to the user so either the issues can be
     * addressed or the modules that can't start up can be disabled before the
     * ingest job is attempted again.
     *
     * @return A list of ingest module startup errors, empty on success.
     */
    private List<IngestModuleError> startUpIngestTaskPipelines() {
        List<IngestModuleError> errors = new ArrayList<>();
        errors.addAll(startUpIngestTaskPipeline(firstStageDataSourceIngestPipeline));
        errors.addAll(startUpIngestTaskPipeline(secondStageDataSourceIngestPipeline));
        for (FileIngestPipeline pipeline : fileIngestPipelines) {
            List<IngestModuleError> filePipelineErrors = startUpIngestTaskPipeline(pipeline);
            if (!filePipelineErrors.isEmpty()) {
                /*
                 * If one file pipeline copy can't start up, assume that none of
                 * them will be able to start up for the same reason.
                 */
                errors.addAll(filePipelineErrors);
                break;
            }
        }
        errors.addAll(startUpIngestTaskPipeline(artifactIngestPipeline));
        return errors;
    }

    /**
     * Starts up an ingest task pipeline. If there are any start up errors, the
     * pipeline is immediately shut down.
     *
     * @param pipeline The ingest task pipeline to start up.
     *
     * @return A list of ingest module startup errors, empty on success.
     */
    private List<IngestModuleError> startUpIngestTaskPipeline(IngestTaskPipeline<?> pipeline) {
        List<IngestModuleError> startUpErrors = pipeline.startUp();
        if (!startUpErrors.isEmpty()) {
            List<IngestModuleError> shutDownErrors = pipeline.shutDown();
            if (!shutDownErrors.isEmpty()) {
                logIngestModuleErrors(shutDownErrors);
            }
        }
        return startUpErrors;
    }

    /**
     * Starts the first stage of this pipeline in batch mode. In batch mode, all
     * of the files in the data source (excepting carved and derived files) have
     * already been added to the case database by the data source processor.
     */
    private void startFirstStage() {
        if (hasFileIngestModules()) {
            /*
             * Do a count of the files the data source processor has added to
             * the case database. This estimate will be used for ingest progress
             * snapshots and for the file ingest progress bar if running with a
             * GUI.
             */
            long filesToProcess = dataSource.accept(new GetFilesCountVisitor());;
            synchronized (fileIngestProgressLock) {
                estimatedFilesToProcess = filesToProcess;
            }
        }

        /*
         * If running with a GUI, start ingest progress bars in the lower right
         * hand corner of the main application window.
         */
        if (doUI) {
            if (hasFirstStageDataSourceIngestModules()) {
                startDataSourceIngestProgressBar();
            }
            if (hasFileIngestModules()) {
                startFileIngestProgressBar();
            }
            if (hasArtifactIngestModules()) {
                startArtifactIngestProgressBar();
            }
        }

        /*
         * Make the first stage data source level ingest pipeline the current
         * data source level pipeline.
         */
        synchronized (dataSourceIngestPipelineLock) {
            currentDataSourceIngestPipeline = firstStageDataSourceIngestPipeline;
        }

        synchronized (stageTransitionLock) {
            logInfoMessage("Starting first stage analysis in batch mode"); //NON-NLS        
            stage = Stages.FIRST_STAGE;

            /**
             * Schedule the first stage ingest tasks and then immediately check
             * for stage completion. This is necessary because it is possible
             * that zero tasks will actually make it to task execution due to
             * the file filter or other ingest job settings. In that case, there
             * will never be a stage completion check in an ingest thread
             * executing an ingest task, so such a job would run forever without
             * the check here.
             */
            taskScheduler.scheduleIngestTasks(this);
            checkForStageCompleted();
        }
    }

    /**
     * Starts the first stage of this pipeline in streaming mode. In streaming
     * mode, the data source processor streams files into the pipeline as it
     * adds them to the case database and only adds the data source to the
     * pipeline after all of the files have been streamed in. See
     * addStreamingIngestFiles() and addStreamingIngestDataSource().
     */
    private void startFirstStageInStreamingMode() {
        if (hasFileIngestModules()) {
            synchronized (fileIngestProgressLock) {
                /*
                 * Start with zero to signal an unknown value. This estimate
                 * will be used for ingest progress snapshots and for the file
                 * ingest progress bar if running with a GUI.
                 */
                estimatedFilesToProcess = 0;
            }
        }

        /*
         * If running with a GUI, start ingest progress bars in the lower right
         * hand corner of the main application window.
         */
        if (doUI) {
            if (hasFileIngestModules()) {
                /*
                 * Note that because estimated files remaining to process has
                 * been set to zero, the progress bar will start in the
                 * "indeterminate" state.
                 */
                startFileIngestProgressBar();
            }
            if (hasArtifactIngestModules()) {
                startArtifactIngestProgressBar();
            }
        }

        synchronized (stageTransitionLock) {
            logInfoMessage("Starting first stage analysis in streaming mode"); //NON-NLS
            stage = Stages.FIRST_STAGE_STREAMING;
            if (hasArtifactIngestModules()) {
                /*
                 * Schedule artifact ingest tasks for any artifacts currently in
                 * the case database. This needs to be done before any files or
                 * the data source are streamed in to avoid analyzing data
                 * artifacts added to the case database by the data source level
                 * or file level ingest tasks.
                 */
                taskScheduler.scheduleDataArtifactIngestTasks(this);
            }
        }
    }

    /**
     * Start data source ingest. Used for streaming ingest when the data source
     * is not ready when ingest starts.
     */
    void addStreamingIngestDataSource() {
        /*
         * Do a count of the files the data source processor has added to the
         * case database. This estimate will be used for ingest progress
         * snapshots and for the file ingest progress bar if running with a GUI.
         * The count will be off by any streamed files that have already been
         * analyzed.
         */
        long filesToProcess = dataSource.accept(new GetFilesCountVisitor());;
        synchronized (fileIngestProgressLock) {
            estimatedFilesToProcess = filesToProcess;
        }

        /*
         * If running with a GUI, start ingest progress bars in the lower right
         * hand corner of the main application window.
         */
        if (doUI) {
            if (hasFirstStageDataSourceIngestModules()) {
                startDataSourceIngestProgressBar();
            }
        }

        /**
         * Make the first stage data source level ingest pipeline the current
         * data source level pipeline.
         */
        synchronized (this.dataSourceIngestPipelineLock) {
            this.currentDataSourceIngestPipeline = this.firstStageDataSourceIngestPipeline;
        }

        synchronized (stageTransitionLock) {
            logInfoMessage("Adding the data source in streaming mode"); //NON-NLS
            stage = IngestJobPipeline.Stages.FIRST_STAGE;
            if (hasFirstStageDataSourceIngestModules()) {
                IngestJobPipeline.taskScheduler.scheduleDataSourceIngestTask(this);
            } else {
                /*
                 * If no data source level ingest task is scheduled at this time
                 * and all of the file level and artifact ingest tasks scheduled
                 * when streaming began have already executed, there will never
                 * be a stage completion check in an ingest thread executing an
                 * ingest task, so such a job would run forever without the
                 * check here.
                 */
                checkForStageCompleted();
            }
        }
    }

    /**
     * Starts the second stage ingest task pipelines.
     */
    private void startSecondStage() {
        if (doUI) {
            startDataSourceIngestProgressBar();
        }
        synchronized (dataSourceIngestPipelineLock) {
            currentDataSourceIngestPipeline = secondStageDataSourceIngestPipeline;
        }
        synchronized (stageTransitionLock) {
            logInfoMessage(String.format("Starting second stage ingest task pipelines for %s (objID=%d, jobID=%d)", dataSource.getName(), dataSource.getId(), job.getId())); //NON-NLS
            stage = IngestJobPipeline.Stages.SECOND_STAGE;
            taskScheduler.scheduleDataSourceIngestTask(this);
        }
    }

    /**
     * Starts a progress bar for the results ingest tasks for the ingest job.
     */
    private void startArtifactIngestProgressBar() {
        if (doUI) {
            synchronized (artifactIngestProgressLock) {
                String displayName = NbBundle.getMessage(this.getClass(), "IngestJob.progress.dataArtifactIngest.displayName", this.dataSource.getName());
                artifactIngestProgressBar = ProgressHandle.createHandle(displayName, new Cancellable() {
                    @Override
                    public boolean cancel() {
                        IngestJobPipeline.this.cancel(IngestJob.CancellationReason.USER_CANCELLED);
                        return true;
                    }
                });
                artifactIngestProgressBar.start();
                artifactIngestProgressBar.switchToIndeterminate();
            }
        }
    }

    /**
     * Starts a data source level ingest progress bar for this job.
     */
    private void startDataSourceIngestProgressBar() {
        if (this.doUI) {
            synchronized (this.dataSourceIngestProgressLock) {
                String displayName = NbBundle.getMessage(this.getClass(),
                        "IngestJob.progress.dataSourceIngest.initialDisplayName",
                        this.dataSource.getName());
                this.dataSourceIngestProgressBar = ProgressHandle.createHandle(displayName, new Cancellable() {
                    @Override
                    public boolean cancel() {
                        // If this method is called, the user has already pressed 
                        // the cancel button on the progress bar and the OK button
                        // of a cancelation confirmation dialog supplied by 
                        // NetBeans. What remains to be done is to find out whether
                        // the user wants to cancel only the currently executing
                        // data source ingest module or the entire ingest job.
                        DataSourceIngestCancellationPanel panel = new DataSourceIngestCancellationPanel();
                        String dialogTitle = NbBundle.getMessage(IngestJobPipeline.this.getClass(), "IngestJob.cancellationDialog.title");
                        JOptionPane.showConfirmDialog(WindowManager.getDefault().getMainWindow(), panel, dialogTitle, JOptionPane.OK_OPTION, JOptionPane.PLAIN_MESSAGE);
                        if (panel.cancelAllDataSourceIngestModules()) {
                            IngestJobPipeline.this.cancel(IngestJob.CancellationReason.USER_CANCELLED);
                        } else {
                            IngestJobPipeline.this.cancelCurrentDataSourceIngestModule();
                        }
                        return true;
                    }
                });
                this.dataSourceIngestProgressBar.start();
                this.dataSourceIngestProgressBar.switchToIndeterminate();
            }
        }
    }

    /**
     * Starts the file level ingest progress bar for this job.
     */
    private void startFileIngestProgressBar() {
        if (this.doUI) {
            synchronized (this.fileIngestProgressLock) {
                String displayName = NbBundle.getMessage(this.getClass(),
                        "IngestJob.progress.fileIngest.displayName",
                        this.dataSource.getName());
                this.fileIngestProgressBar = ProgressHandle.createHandle(displayName, new Cancellable() {
                    @Override
                    public boolean cancel() {
                        // If this method is called, the user has already pressed 
                        // the cancel button on the progress bar and the OK button
                        // of a cancelation confirmation dialog supplied by 
                        // NetBeans. 
                        IngestJobPipeline.this.cancel(IngestJob.CancellationReason.USER_CANCELLED);
                        return true;
                    }
                });
                this.fileIngestProgressBar.start();
                this.fileIngestProgressBar.switchToDeterminate((int) this.estimatedFilesToProcess);
            }
        }
    }

    /**
     * Checks to see if the ingest tasks for the current stage of this job are
     * completed and does a stage transition if they are.
     */
    private void checkForStageCompleted() {
        synchronized (stageTransitionLock) {
            if (stage == Stages.FIRST_STAGE_STREAMING) {
                return;
            }
            if (taskScheduler.currentTasksAreCompleted(this)) {
                switch (stage) {
                    case FIRST_STAGE:
                        finishFirstStage();
                        break;
                    case SECOND_STAGE:
                        shutDown();
                        break;
                }
            }
        }
    }

    /**
     * Shuts down the first stage ingest pipelines and progress bars for this
     * job and starts the second stage, if appropriate.
     */
    private void finishFirstStage() {
        logInfoMessage("Finished first stage analysis"); //NON-NLS        

        shutDownIngestTaskPipeline(currentDataSourceIngestPipeline);
        while (!fileIngestPipelinesQueue.isEmpty()) {
            FileIngestPipeline pipeline = fileIngestPipelinesQueue.poll();
            shutDownIngestTaskPipeline(pipeline);
        }

        finishProgressBar(dataSourceIngestProgressBar, dataSourceIngestProgressLock);
        finishProgressBar(fileIngestProgressBar, fileIngestProgressLock);

        if (!cancelled && hasSecondStageDataSourceIngestModules()) {
            startSecondStage();
        } else {
            shutDown();
        }
    }

    /**
     * Shuts down the ingest pipelines and progress bars for this job.
     */
    private void shutDown() {
        synchronized (stageTransitionLock) {
            logInfoMessage("Finished all tasks"); //NON-NLS        
            stage = IngestJobPipeline.Stages.FINALIZATION;

            shutDownIngestTaskPipeline(currentDataSourceIngestPipeline);
            shutDownIngestTaskPipeline(artifactIngestPipeline);

            finishProgressBar(dataSourceIngestProgressBar, dataSourceIngestProgressLock);
            finishProgressBar(fileIngestProgressBar, fileIngestProgressLock);
            finishProgressBar(artifactIngestProgressBar, artifactIngestProgressLock);

            if (ingestJobInfo != null) {
                if (cancelled) {
                    try {
                        ingestJobInfo.setIngestJobStatus(IngestJobStatusType.CANCELLED);
                    } catch (TskCoreException ex) {
                        logErrorMessage(Level.WARNING, "Failed to update ingest job status in case database", ex);
                    }
                } else {
                    try {
                        ingestJobInfo.setIngestJobStatus(IngestJobStatusType.COMPLETED);
                    } catch (TskCoreException ex) {
                        logErrorMessage(Level.WARNING, "Failed to update ingest job status in case database", ex);
                    }
                }
                try {
                    ingestJobInfo.setEndDateTime(new Date());
                } catch (TskCoreException ex) {
                    logErrorMessage(Level.WARNING, "Failed to set job end date in case database", ex);
                }
            }

            job.notifyIngestPipelineShutDown(this);
        }
    }

    /**
     * Shuts down an ingest task pipeline.
     *
     * @param pipeline The pipeline.
     */
    private <T extends IngestTask> void shutDownIngestTaskPipeline(IngestTaskPipeline<T> pipeline) {
        if (pipeline.isRunning()) {
            List<IngestModuleError> errors = new ArrayList<>();
            errors.addAll(pipeline.shutDown());
            if (!errors.isEmpty()) {
                logIngestModuleErrors(errors);
            }
        }
    }

    /**
     * Finishes a progress bar.
     *
     * @param progress The progress bar.
     * @param lock     The lock that guards the progress bar.
     */
    private void finishProgressBar(ProgressHandle progress, Object lock) {
        if (doUI) {
            synchronized (lock) {
                if (progress != null) {
                    progress.finish();
                    progress = null;
                }
            }
        }
    }

    /**
     * Passes the data source for the ingest job through the currently active
     * data source level ingest task pipeline (first stage or second stage data
     * source ingest modules).
     *
     * @param task A data source ingest task wrapping the data source.
     */
    void execute(DataSourceIngestTask task) {
        try {
            synchronized (dataSourceIngestPipelineLock) {
                if (!isCancelled() && !currentDataSourceIngestPipeline.isEmpty()) {
                    List<IngestModuleError> errors = new ArrayList<>();
                    errors.addAll(currentDataSourceIngestPipeline.executeTask(task));
                    if (!errors.isEmpty()) {
                        logIngestModuleErrors(errors);
                    }
                }
            }

            if (doUI) {
                /**
                 * Shut down the data source ingest progress bar right away.
                 * Data source-level processing is finished for this stage.
                 */
                synchronized (dataSourceIngestProgressLock) {
                    if (dataSourceIngestProgressBar != null) {
                        dataSourceIngestProgressBar.finish();
                        dataSourceIngestProgressBar = null;
                    }
                }
            }

        } finally {
            taskScheduler.notifyTaskCompleted(task);
            checkForStageCompleted();
        }
    }

    /**
     * Passes a file from the data source for the ingest job through the file
     * ingest task pipeline (file ingest modules).
     *
     * @param task A file ingest task wrapping the file.
     */
    void execute(FileIngestTask task) {
        try {
            if (!isCancelled()) {
                FileIngestPipeline pipeline = fileIngestPipelinesQueue.take();
                if (!pipeline.isEmpty()) {
                    /*
                     * Get the file from the task. If the file was "streamed,"
                     * the task may only have the file object ID and a trip to
                     * the case database will be required.
                     */
                    AbstractFile file;
                    try {
                        file = task.getFile();
                    } catch (TskCoreException ex) {
                        List<IngestModuleError> errors = new ArrayList<>();
                        errors.add(new IngestModuleError("Ingest Pipeline", ex));
                        logIngestModuleErrors(errors);
                        fileIngestPipelinesQueue.put(pipeline);
                        return;
                    }

                    synchronized (fileIngestProgressLock) {
                        ++processedFiles;
                        if (doUI) {
                            /**
                             * Update the file ingest progress bar in the lower
                             * right hand corner of the main application window.
                             */
                            if (processedFiles <= estimatedFilesToProcess) {
                                fileIngestProgressBar.progress(file.getName(), (int) processedFiles);
                            } else {
                                fileIngestProgressBar.progress(file.getName(), (int) estimatedFilesToProcess);
                            }
                            filesInProgress.add(file.getName());
                        }
                    }

                    /**
                     * Run the file through the modules in the pipeline.
                     */
                    List<IngestModuleError> errors = new ArrayList<>();
                    errors.addAll(pipeline.executeTask(task));
                    if (!errors.isEmpty()) {
                        logIngestModuleErrors(errors, file);
                    }

                    if (doUI && !cancelled) {
                        synchronized (fileIngestProgressLock) {
                            /**
                             * Update the file ingest progress bar again, in
                             * case the file was being displayed.
                             */
                            filesInProgress.remove(file.getName());
                            if (filesInProgress.size() > 0) {
                                fileIngestProgressBar.progress(filesInProgress.get(0));
                            } else {
                                fileIngestProgressBar.progress("");
                            }
                        }
                    }
                }
                fileIngestPipelinesQueue.put(pipeline);
            }
        } catch (InterruptedException ex) {
            // RJCTODO This probablly should be logged, interrupt during wait for pipeline copy
            // Also need to reset the flag...
        } finally {
            taskScheduler.notifyTaskCompleted(task);
            checkForStageCompleted();
        }
    }

    /**
     * Passes a data artifact from the data source for the ingest job through
     * the data artifact ingest task pipeline (data artifact ingest modules).
     *
     * @param task A data artifact ingest task wrapping the file.
     */
    void execute(DataArtifactIngestTask task) {
        try {
            if (!isCancelled() && !artifactIngestPipeline.isEmpty()) {
                List<IngestModuleError> errors = new ArrayList<>();
                errors.addAll(artifactIngestPipeline.executeTask(task));
                if (!errors.isEmpty()) {
                    logIngestModuleErrors(errors);
                }
            }
        } finally {
            taskScheduler.notifyTaskCompleted(task);
            checkForStageCompleted();
        }
    }

    /**
     * Adds some subset of the "streamed" files for a streaming ingest job to
     * this pipeline after startUp() has been called.
     *
     * @param fileObjIds The object IDs of the files.
     */
    void addStreamingIngestFiles(List<Long> fileObjIds) {
        synchronized (stageTransitionLock) {
            if (hasFileIngestModules()) {
                if (stage.equals(Stages.FIRST_STAGE_STREAMING)) {
                    IngestJobPipeline.taskScheduler.scheduleStreamedFileIngestTasks(this, fileObjIds);
                } else {
                    logErrorMessage(Level.SEVERE, "Adding streaming files to job during stage " + stage.toString() + " not supported");
                }
            }
        }
    }

    /**
     * Adds additional files (e.g., extracted or carved files) for any type of
     * ingest job to this pipeline after startUp() has been called. Not
     * currently supported for second stage of the job.
     *
     * @param files A list of the files to add.
     */
    void addFiles(List<AbstractFile> files) {
        synchronized (stageTransitionLock) {
            if (stage.equals(Stages.FIRST_STAGE_STREAMING)
                    || stage.equals(Stages.FIRST_STAGE)) {
                taskScheduler.fastTrackFileIngestTasks(this, files);
            } else {
                logErrorMessage(Level.SEVERE, "Adding streaming files to job during stage " + stage.toString() + " not supported");
            }

            /**
             * The intended clients of this method are ingest modules running
             * code in an ingest thread that is holding a reference to a
             * "primary" ingest task that was the source of the files, in which
             * case a completion check would not be necessary, so this is a bit
             * of defensive programming.
             */
            checkForStageCompleted();
        }
    }

    /**
     * Adds data artifacts for any type of ingest job to this pipeline after
     * startUp() has been called.
     *
     * @param artifacts
     */
    void addDataArtifacts(List<DataArtifact> artifacts) {
        synchronized (stageTransitionLock) {
            if (stage.equals(Stages.FIRST_STAGE_STREAMING)
                    || stage.equals(Stages.FIRST_STAGE)
                    || stage.equals(Stages.SECOND_STAGE)) {
                taskScheduler.scheduleDataArtifactIngestTasks(this, artifacts);
            } else {
                logErrorMessage(Level.SEVERE, "Adding streaming files to job during stage " + stage.toString() + " not supported");
            }

            /**
             * The intended clients of this method are ingest modules running
             * code in an ingest thread that is holding a reference to a
             * "primary" ingest task that was the source of the files, in which
             * case a completion check would not be necessary, so this is a bit
             * of defensive programming.
             */
            checkForStageCompleted();
        }
    }

    /**
     * Updates the display name shown on the current data source level ingest
     * progress bar for this job.
     *
     * @param displayName The new display name.
     */
    void updateDataSourceIngestProgressBarDisplayName(String displayName) {
        if (this.doUI && !this.cancelled) {
            synchronized (this.dataSourceIngestProgressLock) {
                this.dataSourceIngestProgressBar.setDisplayName(displayName);
            }
        }
    }

    /**
     * Switches the data source level ingest progress bar for this job to
     * determinate mode. This should be called if the total work units to
     * process the data source is known.
     *
     * @param workUnits Total number of work units for the processing of the
     *                  data source.
     */
    void switchDataSourceIngestProgressBarToDeterminate(int workUnits) {
        if (this.doUI && !this.cancelled) {
            synchronized (this.dataSourceIngestProgressLock) {
                if (null != this.dataSourceIngestProgressBar) {
                    this.dataSourceIngestProgressBar.switchToDeterminate(workUnits);
                }
            }
        }
    }

    /**
     * Switches the data source level ingest progress bar for this job to
     * indeterminate mode. This should be called if the total work units to
     * process the data source is unknown.
     */
    void switchDataSourceIngestProgressBarToIndeterminate() {
        if (this.doUI && !this.cancelled) {
            synchronized (this.dataSourceIngestProgressLock) {
                if (null != this.dataSourceIngestProgressBar) {
                    this.dataSourceIngestProgressBar.switchToIndeterminate();
                }
            }
        }
    }

    /**
     * Updates the data source level ingest progress bar for this job with the
     * number of work units performed, if in the determinate mode.
     *
     * @param workUnits Number of work units performed.
     */
    void advanceDataSourceIngestProgressBar(int workUnits) {
        if (doUI && !cancelled) {
            synchronized (dataSourceIngestProgressLock) {
                if (null != dataSourceIngestProgressBar) {
                    dataSourceIngestProgressBar.progress("", workUnits);
                }
            }
        }
    }

    /**
     * Updates the data source level ingest progress for this job with a new
     * task name, where the task name is the "subtitle" under the display name.
     *
     * @param currentTask The task name.
     */
    void advanceDataSourceIngestProgressBar(String currentTask) {
        if (doUI && !cancelled) {
            synchronized (dataSourceIngestProgressLock) {
                if (null != dataSourceIngestProgressBar) {
                    dataSourceIngestProgressBar.progress(currentTask);
                }
            }
        }
    }

    /**
     * Updates the data source level ingest progress bar for this with a new
     * task name and the number of work units performed, if in the determinate
     * mode. The task name is the "subtitle" under the display name.
     *
     * @param currentTask The task name.
     * @param workUnits   Number of work units performed.
     */
    void advanceDataSourceIngestProgressBar(String currentTask, int workUnits) {
        if (this.doUI && !this.cancelled) {
            synchronized (this.fileIngestProgressLock) {
                this.dataSourceIngestProgressBar.progress(currentTask, workUnits);
            }
        }
    }

    /**
     * Queries whether or not a temporary cancellation of data source level
     * ingest in order to stop the currently executing data source level ingest
     * module is in effect for this job.
     *
     * @return True or false.
     */
    boolean currentDataSourceIngestModuleIsCancelled() {
        return this.currentDataSourceIngestModuleCancelled;
    }

    /**
     * Rescind a temporary cancellation of data source level ingest that was
     * used to stop a single data source level ingest module for this job.
     *
     * @param moduleDisplayName The display name of the module that was stopped.
     */
    void currentDataSourceIngestModuleCancellationCompleted(String moduleDisplayName) {
        this.currentDataSourceIngestModuleCancelled = false;
        this.cancelledDataSourceIngestModules.add(moduleDisplayName);

        if (this.doUI) {
            /**
             * A new progress bar must be created because the cancel button of
             * the previously constructed component is disabled by NetBeans when
             * the user selects the "OK" button of the cancellation confirmation
             * dialog popped up by NetBeans when the progress bar cancel button
             * is pressed.
             */
            synchronized (this.dataSourceIngestProgressLock) {
                this.dataSourceIngestProgressBar.finish();
                this.dataSourceIngestProgressBar = null;
                this.startDataSourceIngestProgressBar();
            }
        }
    }

    /**
     * Gets the currently running data source level ingest module for this job.
     *
     * @return The currently running module, may be null.
     */
    DataSourceIngestPipeline.DataSourcePipelineModule getCurrentDataSourceIngestModule() {
        if (null != currentDataSourceIngestPipeline) {
            return (DataSourceIngestPipeline.DataSourcePipelineModule) currentDataSourceIngestPipeline.getCurrentlyRunningModule();
        } else {
            return null;
        }
    }

    /**
     * Requests a temporary cancellation of data source level ingest for this
     * job in order to stop the currently executing data source ingest module.
     */
    void cancelCurrentDataSourceIngestModule() {
        this.currentDataSourceIngestModuleCancelled = true;
    }

    /**
     * Requests cancellation of ingest, i.e., a shutdown of the data source
     * level and file level ingest pipelines.
     *
     * @param reason The cancellation reason.
     */
    void cancel(IngestJob.CancellationReason reason) {
        this.cancelled = true;
        this.cancellationReason = reason;
        IngestJobPipeline.taskScheduler.cancelPendingFileTasksForIngestJob(this);

        if (this.doUI) {
            synchronized (this.dataSourceIngestProgressLock) {
                if (null != dataSourceIngestProgressBar) {
                    dataSourceIngestProgressBar.setDisplayName(NbBundle.getMessage(this.getClass(), "IngestJob.progress.dataSourceIngest.initialDisplayName", this.dataSource.getName()));
                    dataSourceIngestProgressBar.progress(NbBundle.getMessage(this.getClass(), "IngestJob.progress.cancelling"));
                }
            }

            synchronized (this.fileIngestProgressLock) {
                if (null != this.fileIngestProgressBar) {
                    this.fileIngestProgressBar.setDisplayName(NbBundle.getMessage(this.getClass(), "IngestJob.progress.fileIngest.displayName", this.dataSource.getName()));
                    this.fileIngestProgressBar.progress(NbBundle.getMessage(this.getClass(), "IngestJob.progress.cancelling"));
                }
            }
        }

        // If a data source had no tasks in progress it may now be complete.
        checkForStageCompleted();
    }

    /**
     * Queries whether or not cancellation, i.e., a shutdown of the data source
     * level and file level ingest pipelines for this job, has been requested.
     *
     * @return True or false.
     */
    boolean isCancelled() {
        return this.cancelled;
    }

    /**
     * Gets the reason this job was cancelled.
     *
     * @return The cancellation reason, may be not cancelled.
     */
    IngestJob.CancellationReason getCancellationReason() {
        return this.cancellationReason;
    }

    /**
     * Writes an info message to the application log that includes the data
     * source name, data source object id, and the job id.
     *
     * @param message The message.
     */
    private void logInfoMessage(String message) {
        logger.log(Level.INFO, String.format("%s (data source = %s, objId = %d, pipeline id = %d, ingest job id = %d)", message, this.dataSource.getName(), this.dataSource.getId(), pipelineId, ingestJobInfo.getIngestJobId())); //NON-NLS        
    }

    /**
     * Writes an error message to the application log that includes the data
     * source name, data source object id, and the job id.
     *
     * @param level     The logging level for the message.
     * @param message   The message.
     * @param throwable The throwable associated with the error.
     */
    private void logErrorMessage(Level level, String message, Throwable throwable) {
        logger.log(level, String.format("%s (data source = %s, objId = %d, pipeline id = %d, ingest job id = %d)", message, this.dataSource.getName(), this.dataSource.getId(), pipelineId, ingestJobInfo.getIngestJobId()), throwable); //NON-NLS
    }

    /**
     * Writes an error message to the application log that includes the data
     * source name, data source object id, and the job id.
     *
     * @param level   The logging level for the message.
     * @param message The message.
     */
    private void logErrorMessage(Level level, String message) {
        logger.log(level, String.format("%s (data source = %s, objId = %d, pipeline id = %d, ingest job id %d)", message, this.dataSource.getName(), this.dataSource.getId(), pipelineId, ingestJobInfo.getIngestJobId())); //NON-NLS
    }

    /**
     * Write ingest module errors to the log.
     *
     * @param errors The errors.
     */
    private void logIngestModuleErrors(List<IngestModuleError> errors) {
        for (IngestModuleError error : errors) {
            logErrorMessage(Level.SEVERE, String.format("%s experienced an error during analysis", error.getModuleDisplayName()), error.getThrowable()); //NON-NLS
        }
    }

    /**
     * Write ingest module errors to the log.
     *
     * @param errors The errors.
     * @param file   AbstractFile that caused the errors.
     */
    private void logIngestModuleErrors(List<IngestModuleError> errors, AbstractFile file) {
        for (IngestModuleError error : errors) {
            logErrorMessage(Level.SEVERE, String.format("%s experienced an error during analysis while processing file %s, object ID %d", error.getModuleDisplayName(), file.getName(), file.getId()), error.getThrowable()); //NON-NLS
        }
    }

    /**
     * Gets a snapshot of this ingest pipelines current state.
     *
     * @return An ingest job statistics object.
     */
    Snapshot getSnapshot(boolean getIngestTasksSnapshot) {
        /**
         * Determine whether file ingest is running at the time of this snapshot
         * and determine the earliest file ingest level pipeline start time, if
         * file ingest was started at all.
         */
        boolean fileIngestRunning = false;
        Date fileIngestStartTime = null;
        for (FileIngestPipeline pipeline : this.fileIngestPipelines) {
            if (pipeline.isRunning()) {
                fileIngestRunning = true;
            }
            Date pipelineStartTime = pipeline.getStartTime();
            if (null != pipelineStartTime && (null == fileIngestStartTime || pipelineStartTime.before(fileIngestStartTime))) {
                fileIngestStartTime = pipelineStartTime;
            }
        }

        long processedFilesCount = 0;
        long estimatedFilesToProcessCount = 0;
        long snapShotTime = new Date().getTime();
        IngestJobTasksSnapshot tasksSnapshot = null;
        if (getIngestTasksSnapshot) {
            synchronized (fileIngestProgressLock) {
                processedFilesCount = this.processedFiles;
                estimatedFilesToProcessCount = this.estimatedFilesToProcess;
                snapShotTime = new Date().getTime();
            }
            tasksSnapshot = taskScheduler.getTasksSnapshotForJob(pipelineId);

        }

        return new Snapshot(dataSource.getName(),
                pipelineId, createTime,
                getCurrentDataSourceIngestModule(),
                fileIngestRunning, fileIngestStartTime,
                cancelled, cancellationReason, cancelledDataSourceIngestModules,
                processedFilesCount, estimatedFilesToProcessCount, snapShotTime, tasksSnapshot);
    }

}
