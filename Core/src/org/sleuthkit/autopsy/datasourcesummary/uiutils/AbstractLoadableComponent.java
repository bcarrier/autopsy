/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.sleuthkit.autopsy.datasourcesummary.uiutils;

import java.util.List;
import java.util.logging.Level;
import javax.swing.JPanel;
import org.openide.util.NbBundle;
import org.sleuthkit.autopsy.coreutils.Logger;

/**
 *
 * @author gregd
 */
@NbBundle.Messages({
    "AbstractLoadableComponent_loadingMessage_defaultText=Loading results...",
    "AbstractLoadableComponent_errorMessage_defaultText=There was an error loading results.",
    "AbstractLoadableComponent_noDataExists_defaultText=No data exists.",
})
public abstract class AbstractLoadableComponent<T> extends JPanel implements LoadableComponent<T> {

    public static final String DEFAULT_LOADING_MESSAGE = Bundle.AbstractLoadableComponent_loadingMessage_defaultText();
    public static final String DEFAULT_ERROR_MESSAGE = Bundle.AbstractLoadableComponent_errorMessage_defaultText();
    public static final String DEFAULT_NO_RESULTS_MESSAGE = Bundle.AbstractLoadableComponent_noDataExists_defaultText();

    private static final Logger logger = Logger.getLogger(AbstractLoadableComponent.class.getName());

    /**
     * @return The default error message.
     */
    public static String getDefaultErrorMessage() {
        return DEFAULT_ERROR_MESSAGE;
    }

    /**
     * @return The default message for no results.
     */
    public static String getDefaultNoResultsMessage() {
        return DEFAULT_NO_RESULTS_MESSAGE;
    }

    /**
     * Clears the results from the underlying JTable and shows the provided
     * message.
     *
     * @param message The message to be shown.
     */
    public synchronized void showMessage(String message) {
        setResultList(null);
        setOverlay(true, message);
        repaint();
    }

    /**
     * Shows a default loading message on the table. This will clear any results
     * in the table.
     */
    public void showDefaultLoadingMessage() {
        showMessage(DEFAULT_LOADING_MESSAGE);
    }

    /**
     * Shows the list as rows of data in the table. If overlay message will be
     * cleared if present.
     *
     * @param data The data to be shown where each item represents a row of
     *             data.
     */
    public synchronized void showResults(List<T> data) {
        setOverlay(false, null);
        setResultList(data);
        repaint();
    }

    /**
     * Shows the data in a DataFetchResult. If there was an error during the
     * operation, the errorMessage will be displayed. If the operation completed
     * successfully and no data is present, noResultsMessage will be shown.
     * Otherwise, the data will be shown as rows in the table.
     *
     * @param result           The DataFetchResult.
     * @param errorMessage     The error message to be shown in the event of an
     *                         error.
     * @param noResultsMessage The message to be shown if there are no results
     *                         but the operation completed successfully.
     */
    public void showDataFetchResult(DataFetchResult<List<T>> result, String errorMessage, String noResultsMessage) {
        if (result == null) {
            logger.log(Level.SEVERE, "Null data fetch result received.");
            return;
        }

        switch (result.getResultType()) {
            case SUCCESS:
                if (result.getData() == null || result.getData().isEmpty()) {
                    showMessage(noResultsMessage);
                } else {
                    showResults(result.getData());
                }
                break;
            case ERROR:
                // if there is an error, log accordingly, set result list to 
                // empty and display error message
                logger.log(Level.WARNING, "An exception was caused while results were loaded.", result.getException());
                showMessage(errorMessage);
                break;
            default:
                // an unknown loading state was specified.  log accordingly.
                logger.log(Level.SEVERE, "No known loading state was found in result.");
                break;
        }
    }

    /**
     * Shows the data in a DataFetchResult. If there was an error during the
     * operation, the DEFAULT_ERROR_MESSAGE will be displayed. If the operation
     * completed successfully and no data is present, DEFAULT_NO_RESULTS_MESSAGE
     * will be shown. Otherwise, the data will be shown as rows in the table.
     *
     * @param result The DataFetchResult.
     */
    public void showDataFetchResult(DataFetchResult<List<T>> result) {
        showDataFetchResult(result, DEFAULT_ERROR_MESSAGE, DEFAULT_NO_RESULTS_MESSAGE);
    }

    /**
     * Sets the message and visibility of the overlay. Repaint does not need to
     * be handled in this method.
     *
     * @param visible The visibility of the overlay.
     * @param message The message in the overlay.
     */
    protected abstract void setOverlay(boolean visible, String message);

    /**
     * Sets the data to be shown in the JTable. Repaint does not need to be
     * handled in this method.
     *
     * @param data The list of data objects to be shown.
     */
    protected abstract void setResultList(List<T> data);
}
