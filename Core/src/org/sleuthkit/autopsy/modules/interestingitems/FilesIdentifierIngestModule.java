/*
 * Autopsy Forensic Browser
 *
 * Copyright 2014-2018 Basis Technology Corp.
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
package org.sleuthkit.autopsy.modules.interestingitems;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.openide.util.NbBundle;
import org.openide.util.NbBundle.Messages;
import org.sleuthkit.autopsy.casemodule.Case;
import org.sleuthkit.autopsy.casemodule.NoCurrentCaseException;
import org.sleuthkit.autopsy.coreutils.Logger;
import org.sleuthkit.autopsy.coreutils.MessageNotifyUtil;
import org.sleuthkit.autopsy.ingest.FileIngestModule;
import org.sleuthkit.autopsy.ingest.IngestJobContext;
import org.sleuthkit.autopsy.ingest.IngestMessage;
import org.sleuthkit.autopsy.ingest.IngestModuleReferenceCounter;
import org.sleuthkit.autopsy.ingest.IngestServices;
import org.sleuthkit.datamodel.AbstractFile;
import org.sleuthkit.datamodel.Blackboard;
import org.sleuthkit.datamodel.BlackboardArtifact;
import static org.sleuthkit.datamodel.BlackboardArtifact.ARTIFACT_TYPE.TSK_INTERESTING_FILE_HIT;
import org.sleuthkit.datamodel.BlackboardAttribute;
import static org.sleuthkit.datamodel.BlackboardAttribute.ATTRIBUTE_TYPE.TSK_CATEGORY;
import static org.sleuthkit.datamodel.BlackboardAttribute.ATTRIBUTE_TYPE.TSK_SET_NAME;
import org.sleuthkit.datamodel.Score;
import org.sleuthkit.datamodel.TskCoreException;
import org.sleuthkit.datamodel.TskData;

/**
 * A file ingest module that generates interesting files set hit artifacts for
 * files that match interesting files set definitions.
 */
@NbBundle.Messages({"FilesIdentifierIngestModule.getFilesError=Error getting interesting files sets from file."})
final class FilesIdentifierIngestModule implements FileIngestModule {
    
    private static final Object sharedResourcesLock = new Object();
    private static final Logger logger = Logger.getLogger(FilesIdentifierIngestModule.class.getName());
    private static final IngestModuleReferenceCounter refCounter = new IngestModuleReferenceCounter();
    private static final Map<Long, List<FilesSet>> interestingFileSetsByJob = new ConcurrentHashMap<>();
    private static final String MODULE_NAME = InterestingItemsIngestModuleFactory.getModuleName();

    private final FilesIdentifierIngestJobSettings settings;
    private final IngestServices services = IngestServices.getInstance();
    private IngestJobContext context;
    private Blackboard blackboard;

    /**
     * Construct an interesting files identifier ingest module for an ingest
     * job.
     *
     * @param settings An ingest job settings object for the module.
     */
    FilesIdentifierIngestModule(FilesIdentifierIngestJobSettings settings) {
        this.settings = settings;
    }

    @Override
    public void startUp(IngestJobContext context) throws IngestModuleException {
        this.context = context;
        synchronized (FilesIdentifierIngestModule.sharedResourcesLock) {
            if (FilesIdentifierIngestModule.refCounter.incrementAndGet(context.getJobId()) == 1) {
                // Starting up the first instance of this module for this ingest 
                // job, so get the interesting file sets definitions snapshot 
                // for the job. Note that getting this snapshot atomically via a 
                // synchronized definitions manager method eliminates the need 
                // to disable the interesting files set definition UI during ingest.
                List<FilesSet> filesSets = new ArrayList<>();
                try {
                    for (FilesSet set : FilesSetsManager.getInstance().getInterestingFilesSets().values()) {
                        if (settings.interestingFilesSetIsEnabled(set.getName())) {
                            filesSets.add(set);
                        }
                    }
                } catch (FilesSetsManager.FilesSetsManagerException ex) {
                    throw new IngestModuleException(Bundle.FilesIdentifierIngestModule_getFilesError(), ex);
                }
                FilesIdentifierIngestModule.interestingFileSetsByJob.put(context.getJobId(), filesSets);
            }
        }
    }

    @Override
    @Messages({"FilesIdentifierIngestModule.indexError.message=Failed to index interesting file hit artifact for keyword search."})
    public ProcessResult process(AbstractFile file) {
        try {
            blackboard = Case.getCurrentCaseThrows().getSleuthkitCase().getBlackboard();
        } catch (NoCurrentCaseException ex) {
            logger.log(Level.SEVERE, "Exception while getting open case.", ex); //NON-NLS
            return ProcessResult.ERROR;
        }

        // Skip slack space files.
        if (file.getType().equals(TskData.TSK_DB_FILES_TYPE_ENUM.SLACK)) {
            return ProcessResult.OK;
        }

        // See if the file belongs to any defined interesting files set.
        List<FilesSet> filesSets = FilesIdentifierIngestModule.interestingFileSetsByJob.get(this.context.getJobId());
        for (FilesSet filesSet : filesSets) {
            String ruleSatisfied = filesSet.fileIsMemberOf(file);
            if (ruleSatisfied != null) {
                try {

                    Collection<BlackboardAttribute> attributes = Arrays.asList(
                            /*
                             * Add a set name attribute to the artifact. This
                             * adds a fair amount of redundant data to the
                             * attributes table (i.e., rows that differ only in
                             * artifact id), but doing otherwise would requires
                             * reworking the interesting files set hit artifact. */
                            new BlackboardAttribute(
                                    TSK_SET_NAME, MODULE_NAME,
                                    filesSet.getName()),
                            /*
                             * Add a category attribute to the artifact to
                             * record the interesting files set membership rule
                             * that was satisfied. */
                            new BlackboardAttribute(
                                    TSK_CATEGORY, MODULE_NAME,
                                    ruleSatisfied)
                    );

                    // Create artifact if it doesn't already exist.
                    if (!blackboard.artifactExists(file, TSK_INTERESTING_FILE_HIT, attributes)) {
                        BlackboardArtifact artifact = file.newAnalysisResult(
                                BlackboardArtifact.Type.TSK_INTERESTING_FILE_HIT, Score.SCORE_LIKELY_NOTABLE, 
                                null, filesSet.getName(), null, 
                                attributes)
                                .getAnalysisResult();
                        try {

                            // Post thet artifact to the blackboard.
                            blackboard.postArtifact(artifact, MODULE_NAME);
                        } catch (Blackboard.BlackboardException ex) {
                            logger.log(Level.SEVERE, "Unable to index blackboard artifact " + artifact.getArtifactID(), ex); //NON-NLS
                            MessageNotifyUtil.Notify.error(Bundle.FilesIdentifierIngestModule_indexError_message(), artifact.getDisplayName());
                        }

                        // make an ingest inbox message
                        StringBuilder detailsSb = new StringBuilder();
                        detailsSb.append("File: ").append(file.getParentPath()).append(file.getName()).append("<br/>\n");
                        detailsSb.append("Rule Set: ").append(filesSet.getName());

                        services.postMessage(IngestMessage.createDataMessage(InterestingItemsIngestModuleFactory.getModuleName(),
                                "Interesting File Match: " + filesSet.getName() + "(" + file.getName() + ")",
                                detailsSb.toString(),
                                file.getName(),
                                artifact));
                    }
                } catch (TskCoreException ex) {
                    FilesIdentifierIngestModule.logger.log(Level.SEVERE, "Error posting to the blackboard", ex); //NOI18N NON-NLS
                }
            }
        }
        return ProcessResult.OK;
    }

    @Override
    public void shutDown() {
        if (context != null) {
            if (refCounter.decrementAndGet(this.context.getJobId()) == 0) {
                // Shutting down the last instance of this module for this ingest 
                // job, so discard the interesting file sets definitions snapshot 
                // for the job.
                FilesIdentifierIngestModule.interestingFileSetsByJob.remove(this.context.getJobId());
            }
        }
    }
}
