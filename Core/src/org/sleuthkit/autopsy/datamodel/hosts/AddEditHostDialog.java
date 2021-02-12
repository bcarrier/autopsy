/*
 * Central Repository
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
package org.sleuthkit.autopsy.datamodel.hosts;

import java.awt.Color;
import org.sleuthkit.datamodel.Host;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.openide.util.NbBundle.Messages;

/**
 *
 * Dialog for adding or editing a host.
 */
class AddEditHostDialog extends javax.swing.JDialog {

    private static final long serialVersionUID = 1L;

    private boolean changed = false;

    private final Set<String> hostNamesUpper;
    private final Host initialHost;

    /**
     * Main constructor.
     * @param parent The parent frame for this dialog.
     * @param currentHosts The current set of hosts in the case.
     */
    AddEditHostDialog(java.awt.Frame parent, Collection<Host> currentHosts) {
        this(parent, currentHosts, null);
    }

    /**
     * Main constructor.
     *
     * @param parent The parent frame for this dialog.
     * @param currentHosts The current set of hosts (used for determining if
     * name is unique).
     * @param initialHost If adding a new host, this will be a null value.
     * Otherwise, if editing, this will be the host being edited.
     */
    @Messages({
        "AddEditHostDialog_addHost_title=Add Host",
        "AddEditHostDialog_editHost_title=Edit Host"
    })
    AddEditHostDialog(java.awt.Frame parent, Collection<Host> currentHosts, Host initialHost) {
        super(parent, true);
        this.initialHost = initialHost;
        setTitle(initialHost == null ? Bundle.AddEditHostDialog_addHost_title() : Bundle.AddEditHostDialog_editHost_title());

        Stream<Host> curHostStream = (currentHosts == null) ? Stream.empty() : currentHosts.stream();
        hostNamesUpper = curHostStream
                .filter(h -> h != null && h.getName() != null)
                .map(h -> h.getName().toUpperCase())
                .collect(Collectors.toSet());

        initComponents();
        onNameUpdate(initialHost == null ? null : initialHost.getName());

        // initially, don't show validation message (for empty strings or repeat),
        // but do disable ok button if not valid.
        validationLabel.setText("");

        inputTextField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void changedUpdate(DocumentEvent e) {
                onNameUpdate(inputTextField.getText());
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                onNameUpdate(inputTextField.getText());
            }

            @Override
            public void insertUpdate(DocumentEvent e) {
                onNameUpdate(inputTextField.getText());
            }
        });
    }

    /**
     * @return The string value for the name in the input field if Ok pressed or
     * null if not.
     */
    String getValue() {
        return inputTextField.getText();
    }

    /**
     * @return Whether or not the value has been changed and the user pressed
     * okay to save the new value.
     */
    boolean isChanged() {
        return changed;
    }

    /**
     * When the text field is updated, this method is called.
     *
     * @param newNameValue
     */
    private void onNameUpdate(String newNameValue) {
        String newNameValueOrEmpty = newNameValue == null ? "" : newNameValue;
        // update input text field if it is not the same.
        if (!newNameValueOrEmpty.equals(this.inputTextField.getText())) {
            inputTextField.setText(newNameValue);
        }

        // validate text input against invariants setting validation 
        // message and whether or not okay button is enabled accordingly.
        String validationMessage = getValidationMessage(newNameValue);
        okButton.setEnabled(validationMessage == null);
        validationLabel.setText(validationMessage == null ? "" : validationMessage);
    }

    /**
     * Gets the validation message based on the current text checked against the
     * host names.
     *
     * @param name The current name in the text field.
     * @return The validation message if the name is not valid or null.
     */
    @Messages({
        "AddEditHostDialog_getValidationMessage_onEmpty=Please provide some text for the host name.",
        "AddEditHostDialog_getValidationMessage_sameAsOriginal=Please provide a new name for this host.",
        "AddEditHostDialog_getValidationMessage_onDuplicate=Another host already has the same name.  Please choose a different name.",})
    private String getValidationMessage(String name) {
        if (name == null || name.isEmpty()) {
            return Bundle.AddEditHostDialog_getValidationMessage_onEmpty();
        } else if (initialHost != null && name.equalsIgnoreCase(initialHost.getName())) {
            return Bundle.AddEditHostDialog_getValidationMessage_sameAsOriginal();
        } else if (hostNamesUpper.contains(name.toUpperCase())) {
            return Bundle.AddEditHostDialog_getValidationMessage_onDuplicate();
        } else {
            return null;
        }
    }

    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        inputTextField = new javax.swing.JTextField();
        javax.swing.JLabel nameLabel = new javax.swing.JLabel();
        validationLabel = new javax.swing.JLabel();
        okButton = new javax.swing.JButton();
        javax.swing.JButton cancelButton = new javax.swing.JButton();

        setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);

        inputTextField.setText(org.openide.util.NbBundle.getMessage(AddEditHostDialog.class, "AddEditHostDialog.inputTextField.text")); // NOI18N

        nameLabel.setText(org.openide.util.NbBundle.getMessage(AddEditHostDialog.class, "AddEditHostDialog.nameLabel.text")); // NOI18N

        validationLabel.setForeground(Color.RED);
        validationLabel.setVerticalAlignment(javax.swing.SwingConstants.TOP);

        okButton.setText(org.openide.util.NbBundle.getMessage(AddEditHostDialog.class, "AddEditHostDialog.okButton.text")); // NOI18N
        okButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                okButtonActionPerformed(evt);
            }
        });

        cancelButton.setText(org.openide.util.NbBundle.getMessage(AddEditHostDialog.class, "AddEditHostDialog.cancelButton.text")); // NOI18N
        cancelButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                cancelButtonActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(validationLabel, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(inputTextField)
                    .addGroup(layout.createSequentialGroup()
                        .addComponent(nameLabel)
                        .addGap(0, 0, Short.MAX_VALUE))
                    .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, layout.createSequentialGroup()
                        .addGap(0, 282, Short.MAX_VALUE)
                        .addComponent(cancelButton)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addComponent(okButton)))
                .addContainerGap())
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(nameLabel)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(inputTextField, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(validationLabel, javax.swing.GroupLayout.PREFERRED_SIZE, 18, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(okButton)
                    .addComponent(cancelButton))
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );

        pack();
    }// </editor-fold>//GEN-END:initComponents

    private void okButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_okButtonActionPerformed
        this.changed = true;
        dispose();
    }//GEN-LAST:event_okButtonActionPerformed


    private void cancelButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_cancelButtonActionPerformed
        this.changed = false;
        dispose();
    }//GEN-LAST:event_cancelButtonActionPerformed


    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JTextField inputTextField;
    private javax.swing.JButton okButton;
    private javax.swing.JLabel validationLabel;
    // End of variables declaration//GEN-END:variables
}
