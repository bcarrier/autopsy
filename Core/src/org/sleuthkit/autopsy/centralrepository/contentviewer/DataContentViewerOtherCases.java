/*
 * Central Repository
 *
 * Copyright 2017-2021 Basis Technology Corp.
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
package org.sleuthkit.autopsy.centralrepository.contentviewer;

import java.awt.Component;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import org.sleuthkit.autopsy.coreutils.Logger;
import javax.swing.JPanel;
import org.openide.nodes.Node;
import org.openide.util.NbBundle.Messages;
import org.openide.util.lookup.ServiceProvider;
import org.sleuthkit.autopsy.corecomponentinterfaces.DataContentViewer;
import org.sleuthkit.datamodel.AbstractFile;
import org.sleuthkit.autopsy.centralrepository.datamodel.CentralRepository;

/**
 * View correlation results from other cases
 */
@SuppressWarnings("PMD.SingularField") // UI widgets cause lots of false positives
@ServiceProvider(service = DataContentViewer.class, position = 10)
@Messages({"DataContentViewerOtherCases.title=Other Occurrences",
    "DataContentViewerOtherCases.toolTip=Displays instances of the selected file/artifact from other occurrences."})
public final class DataContentViewerOtherCases extends JPanel implements DataContentViewer {

    private static final long serialVersionUID = -1L;
    private static final Logger logger = Logger.getLogger(DataContentViewerOtherCases.class.getName());
    private final OtherOccurrencesPanel otherOccurrencesPanel = new OtherOccurrencesPanel();
    
    private OtherOccurrencesWorker worker = null;

    /**
     * Creates new form DataContentViewerOtherCases
     */
    public DataContentViewerOtherCases() {
        initComponents();
        add(otherOccurrencesPanel);
    }

    @Override
    public String getTitle() {
        return Bundle.DataContentViewerOtherCases_title();
    }

    @Override
    public String getToolTip() {
        return Bundle.DataContentViewerOtherCases_toolTip();
    }

    @Override
    public DataContentViewer createInstance() {
        return new DataContentViewerOtherCases();
    }

    @Override
    public Component getComponent() {
        return this;
    }

    @Override
    public void resetComponent() {
        otherOccurrencesPanel.reset();
    }

    @Override
    public int isPreferred(Node node) {
        return 1;

    }

    @Override
    public boolean isSupported(Node node) {

        // Is supported if one of the following is true:
        // - The central repo is enabled and the node has correlatable content
        //   (either through the MD5 hash of the associated file or through a BlackboardArtifact)
        // - The central repo is disabled and the backing file has a valid MD5 hash
        AbstractFile file = OtherOccurrenceUtilities.getAbstractFileFromNode(node);
        if (CentralRepository.isEnabled()) {
            return !OtherOccurrenceUtilities.getCorrelationAttributesFromNode(node, file).isEmpty();
        } else {
            return file != null
                    && file.getSize() > 0
                    && ((file.getMd5Hash() != null) && (!file.getMd5Hash().isEmpty()));
        }
    }

    @Override
    public void setNode(Node node) {
        otherOccurrencesPanel.reset(); // reset the table to empty.
        if (node == null) {
            return;
        }
        
        if(worker != null) {
            worker.cancel(true);
        }
        worker = new OtherOccurrencesWorker(node) {
            @Override
            public void done() {
                try {
                    if(!isCancelled()) {
                        OtherOccurrencesData data = get();
                        otherOccurrencesPanel.populateTable(data);
                    }
                } catch (InterruptedException | ExecutionException ex) {
                    DataContentViewerOtherCases.logger.log(Level.SEVERE, "Failed to update OtherOccurrencesPanel", ex);
                }
            }
        };
        
        worker.execute();
        
        
//        //could be null
//        AbstractFile file = OtherOccurrenceUtilities.getAbstractFileFromNode(node);
//        String dataSourceName = "";
//        String deviceId = "";
//        try {
//            if (file != null) {
//                Content dataSource = file.getDataSource();
//                dataSourceName = dataSource.getName();
//                deviceId = Case.getCurrentCaseThrows().getSleuthkitCase().getDataSource(dataSource.getId()).getDeviceId();
//            }
//        } catch (TskException | NoCurrentCaseException ex) {
//            // do nothing. 
//            // @@@ Review this behavior
//        }
//        otherOccurrencesPanel.populateTable(OtherOccurrenceUtilities.getCorrelationAttributesFromNode(node, file), dataSourceName, deviceId, file);

    }
    
    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {
        java.awt.GridBagConstraints gridBagConstraints;

        setMinimumSize(new java.awt.Dimension(1000, 10));
        setOpaque(false);
        setPreferredSize(new java.awt.Dimension(1000, 63));
        setLayout(new java.awt.BorderLayout());
    }// </editor-fold>//GEN-END:initComponents

    // Variables declaration - do not modify//GEN-BEGIN:variables
    // End of variables declaration//GEN-END:variables
}
