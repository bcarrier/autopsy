/*
 * Autopsy Forensic Browser
 *
 * Copyright 2020 Basis Technology Corp.
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
package org.sleuthkit.autopsy.datasourcesummary.uiutils;

import java.awt.BorderLayout;
import java.awt.Graphics;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.swing.JComponent;
import javax.swing.JLayer;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.plaf.LayerUI;
import javax.swing.table.DefaultTableColumnModel;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import org.sleuthkit.autopsy.coreutils.Logger;
import org.sleuthkit.autopsy.datasourcesummary.uiutils.CellModelTableCellRenderer.CellModel;

/**
 * A table that displays a list of items and also can display messages for
 * loading, load error, and not loaded.
 */
public class JTablePanel<T> extends AbstractLoadableComponent<T> {

    /**
     * JTables don't allow displaying messages. So this LayerUI is used to
     * display the contents of a child JLabel. Inspired by TableWaitLayerTest
     * (Animating a Busy Indicator):
     * https://docs.oracle.com/javase/tutorial/uiswing/misc/jlayer.html.
     */
    private static class Overlay extends LayerUI<JComponent> {

        private static final long serialVersionUID = 1L;
        private BaseMessageOverlay overlay = new BaseMessageOverlay();

        /**
         * Sets this layer visible when painted. In order to be shown in UI,
         * this component needs to be repainted.
         *
         * @param visible Whether or not it is visible.
         */
        void setVisible(boolean visible) {
            overlay.setVisible(visible);
        }

        /**
         * Sets the message to be displayed in the child jlabel.
         *
         * @param message The message to be displayed.
         */
        void setMessage(String message) {
            overlay.setMessage(message);
        }

        @Override
        public void paint(Graphics g, JComponent c) {
            super.paint(g, c);
            overlay.paintOverlay(g, c.getWidth(), c.getHeight());
        }
    }

    /**
     * Describes aspects of a column which can be used with getTableModel or
     * getJTablePanel. 'T' represents the object that will represent rows in the
     * table.
     */
    public static class ColumnModel<T> {

        private final String headerTitle;
        private final Function<T, CellModelTableCellRenderer.CellModel> cellRenderer;
        private final Integer width;

        /**
         * Constructor for a DataResultColumnModel.
         *
         * @param headerTitle  The title for the column.
         * @param cellRenderer The method that generates a CellModel for the
         *                     column based on the data.
         */
        public ColumnModel(String headerTitle, Function<T, CellModelTableCellRenderer.CellModel> cellRenderer) {
            this(headerTitle, cellRenderer, null);
        }

        /**
         * Constructor for a DataResultColumnModel.
         *
         * @param headerTitle  The title for the column.
         * @param cellRenderer The method that generates a CellModel for the
         *                     column based on the data.
         * @param width        The preferred width of the column.
         */
        public ColumnModel(String headerTitle, Function<T, CellModelTableCellRenderer.CellModel> cellRenderer, Integer width) {
            this.headerTitle = headerTitle;
            this.cellRenderer = cellRenderer;
            this.width = width;
        }

        /**
         * @return The title for the column.
         */
        public String getHeaderTitle() {
            return headerTitle;
        }

        /**
         * @return The method that generates a CellModel for the column based on
         *         the data.
         */
        public Function<T, CellModel> getCellRenderer() {
            return cellRenderer;
        }

        /**
         * @return The preferred width of the column (can be null).
         */
        public Integer getWidth() {
            return width;
        }
    }

    private static final long serialVersionUID = 1L;

    private static final Logger logger = Logger.getLogger(JTablePanel.class.getName());
    private static final CellModelTableCellRenderer DEFAULT_CELL_RENDERER = new CellModelTableCellRenderer();

    /**
     * Generates a TableColumnModel based on the column definitions.
     *
     * @param columns The column definitions.
     *
     * @return The corresponding TableColumnModel to be used with a JTable.
     */
    public static <T> TableColumnModel getTableColumnModel(List<ColumnModel<T>> columns) {
        TableColumnModel tableModel = new DefaultTableColumnModel();

        for (int i = 0; i < columns.size(); i++) {
            TableColumn col = new TableColumn(i);
            ColumnModel<T> model = columns.get(i);
            // if a preferred width is specified in the column definition, 
            // set the underlying TableColumn preferred width.
            if (model.getWidth() != null && model.getWidth() >= 0) {
                col.setPreferredWidth(model.getWidth());
            }

            // set the title
            col.setHeaderValue(model.getHeaderTitle());

            // use the cell model renderer in this instance
            col.setCellRenderer(DEFAULT_CELL_RENDERER);

            tableModel.addColumn(col);
        }

        return tableModel;
    }

    /**
     * Generates a ListTableModel based on the column definitions provided where
     * 'T' is the object representing each row.
     *
     * @param columns The column definitions.
     *
     * @return The corresponding ListTableModel.
     */
    public static <T> ListTableModel<T> getTableModel(List<ColumnModel<T>> columns) {
        List<Function<T, ? extends Object>> columnRenderers = columns.stream()
                .map((colModel) -> colModel.getCellRenderer())
                .collect(Collectors.toList());

        return new DefaultListTableModel<T>(columnRenderers);
    }

    /**
     * Generates a JTablePanel corresponding to the provided column definitions
     * where 'T' is the object representing each row.
     *
     * @param columns The column definitions.
     *
     * @return The corresponding JTablePanel.
     */
    public static <T> JTablePanel<T> getJTablePanel(List<ColumnModel<T>> columns) {
        ListTableModel<T> tableModel = getTableModel(columns);
        JTablePanel<T> resultTable = new JTablePanel<>(tableModel);
        return resultTable.setColumnModel(getTableColumnModel(columns));
    }

    private final JScrollPane tableScrollPane;
    private final Overlay overlayLayer;
    private final ListTableModel<T> tableModel;
    private final JTable table;

    /**
     * Main constructor.
     *
     * @param tableModel The model to use for the table.
     */
    public JTablePanel(ListTableModel<T> tableModel) {
        this.tableModel = tableModel;
        this.table = new JTable(tableModel);
        this.table.getTableHeader().setReorderingAllowed(false);

        this.overlayLayer = new Overlay();
        this.tableScrollPane = new JScrollPane(table);
        JLayer<JComponent> dualLayer = new JLayer<JComponent>(tableScrollPane, overlayLayer);
        setLayout(new BorderLayout());
        add(dualLayer, BorderLayout.CENTER);
    }

    /**
     * @return The underlying JTable's column model.
     */
    public TableColumnModel getColumnModel() {
        return this.table.getColumnModel();
    }

    /**
     * Sets the underlying JTable's column model.
     *
     * @param columnModel The table column model to use with the JTable.
     *
     * @return As a utility, returns this.
     */
    public JTablePanel<T> setColumnModel(TableColumnModel columnModel) {
        this.table.setColumnModel(columnModel);
        return this;
    }

    @Override
    protected void setResultList(List<T> data) {
        // set the list of data to be shown as either the data or an empty list 
        // on null.
        List<T> dataToSet = (data == null) ? Collections.emptyList() : data;

        // since the data is being reset, scroll to the top.
        tableScrollPane.getVerticalScrollBar().setValue(0);

        // set the underlying table model's data.
        this.tableModel.setDataRows(dataToSet);
    }

    @Override
    protected void setOverlay(boolean visible, String message) {
        this.overlayLayer.setVisible(visible);
        this.overlayLayer.setMessage(message);
    }
}
