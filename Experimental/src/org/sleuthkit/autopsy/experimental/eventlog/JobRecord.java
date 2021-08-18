/*
 * Autopsy Forensic Browser
 *
 * Copyright 2021 Basis Technology Corp.
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
package org.sleuthkit.autopsy.experimental.eventlog;

import java.time.Instant;
import java.util.Optional;

/**
 * The record for the job.
 */
public class JobRecord {
    
    private final long id;
    private final long caseId;
    private final String caseName;
    private final String dataSourceName;
    private final Optional<Instant> startTime;
    private final Optional<Instant> endTime;
    private final JobStatus status;

    /**
     * Main constructor.
     * @param id The id of the job.
     * @param caseId The parent case id in the event log.
     * @param caseName The name of the case.
     * @param dataSourceName The name of the data source.
     * @param startTime The start time of processing.
     * @param endTime The end time of processing.
     * @param status The current status.
     */
    JobRecord(long id, long caseId, String caseName, String dataSourceName, Optional<Instant> startTime, Optional<Instant> endTime, JobStatus status) {
        this.id = id;
        this.caseId = caseId;
        this.caseName = caseName;
        this.dataSourceName = dataSourceName;
        this.startTime = startTime;
        this.endTime = endTime;
        this.status = status;
    }

    /**
     * @return The id of the job.
     */
    public long getId() {
        return id;
    }

    /**
     * @return The parent case id in the event log.
     */
    public long getCaseId() {
        return caseId;
    }

    /**
     * @return The name of the case.
     */
    public String getCaseName() {
        return caseName;
    }

    /**
     * @return The name of the data source.
     */
    public String getDataSourceName() {
        return dataSourceName;
    }

    /**
     * @return The start time of processing.
     */
    public Optional<Instant> getStartTime() {
        return startTime;
    }

    /**
     * @return The end time of processing.
     */
    public Optional<Instant> getEndTime() {
        return endTime;
    }

    /**
     * @return The current status.
     */
    public JobStatus getStatus() {
        return status;
    }

    @Override
    public String toString() {
        return "JobRecord{" + "id=" + id 
                + ", caseId=" + caseId 
                + ", caseName=" + caseName 
                + ", dataSourceName=" + dataSourceName 
                + ", startTime=" + startTime + ", endTime=" 
                + endTime + ", status=" 
                + status + '}';
    }
}
