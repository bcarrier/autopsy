/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.sleuthkit.autopsy.timeline.ui.detailview;

import com.google.common.collect.Range;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.stream.Collectors;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.Border;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.BorderStroke;
import javafx.scene.layout.BorderStrokeStyle;
import javafx.scene.layout.BorderWidths;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import static javafx.scene.layout.Region.USE_COMPUTED_SIZE;
import static javafx.scene.layout.Region.USE_PREF_SIZE;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import org.apache.commons.lang3.StringUtils;
import org.joda.time.DateTime;
import org.joda.time.Interval;
import org.openide.util.NbBundle;
import org.sleuthkit.autopsy.coreutils.ColorUtilities;
import org.sleuthkit.autopsy.coreutils.LoggedTask;
import org.sleuthkit.autopsy.coreutils.Logger;
import org.sleuthkit.autopsy.timeline.datamodel.EventBundle;
import org.sleuthkit.autopsy.timeline.datamodel.EventCluster;
import org.sleuthkit.autopsy.timeline.datamodel.EventStripe;
import org.sleuthkit.autopsy.timeline.datamodel.FilteredEventsModel;
import org.sleuthkit.autopsy.timeline.datamodel.eventtype.EventType;
import org.sleuthkit.autopsy.timeline.filters.RootFilter;
import org.sleuthkit.autopsy.timeline.filters.TextFilter;
import org.sleuthkit.autopsy.timeline.filters.TypeFilter;
import org.sleuthkit.autopsy.timeline.zooming.DescriptionLOD;
import org.sleuthkit.autopsy.timeline.zooming.ZoomParams;
import org.sleuthkit.datamodel.SleuthkitCase;

/**
 *
 */
public class EventStripeNode extends StackPane implements DetailViewNode {

    private static final Logger LOGGER = Logger.getLogger(EventClusterNode.class.getName());

    private static final Image HASH_PIN = new Image("/org/sleuthkit/autopsy/images/hashset_hits.png");
    private final static Image PLUS = new Image("/org/sleuthkit/autopsy/timeline/images/plus-button.png"); // NON-NLS
    private final static Image MINUS = new Image("/org/sleuthkit/autopsy/timeline/images/minus-button.png"); // NON-NLS
    private final static Image TAG = new Image("/org/sleuthkit/autopsy/images/green-tag-icon-16.png"); // NON-NLS

    private final Pane subNodePane = new Pane();
    private final EventStripe cluster;
    private final EventStripeNode parentNode;
    private final EventDetailChart chart;
    private SimpleObjectProperty<DescriptionLOD> descLOD = new SimpleObjectProperty<>();
    private final SleuthkitCase sleuthkitCase;
    private final FilteredEventsModel eventsModel;
    /**
     * The label used to display this node's event's description
     */
    private final Label descrLabel = new Label();

    /**
     * The label used to display this node's event count
     */
    private final Label countLabel = new Label();

    private final ImageView hashIV = new ImageView(HASH_PIN);
    private final ImageView tagIV = new ImageView(TAG);
    private final Button plusButton = new Button(null, new ImageView(PLUS)) {
        {
            setMinSize(16, 16);
            setMaxSize(16, 16);
            setPrefSize(16, 16);
        }
    };
    private final Button minusButton = new Button(null, new ImageView(MINUS)) {
        {
            setMinSize(16, 16);
            setMaxSize(16, 16);
            setPrefSize(16, 16);
        }
    };
    private DescriptionVisibility descrVis;
    private final HBox spanRegion = new HBox();
    /**
     * The IamgeView used to show the icon for this node's event's type
     */
    private final ImageView eventTypeImageView = new ImageView();
    private Background spanFill;
    private static final CornerRadii CORNER_RADII = new CornerRadii(3);

    EventStripeNode(EventStripe cluster, EventStripeNode parentNode, EventDetailChart chart) {
        this.chart = chart;
        sleuthkitCase = chart.getController().getAutopsyCase().getSleuthkitCase();
        eventsModel = chart.getController().getEventsModel();

        this.parentNode = parentNode;
        this.cluster = cluster;
        descLOD.set(cluster.getDescriptionLOD());

        final Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        final HBox hBox = new HBox(descrLabel, countLabel, spacer, hashIV, tagIV, minusButton, plusButton);
        if (cluster.getEventIDsWithHashHits().isEmpty()) {
            hashIV.setManaged(false);
            hashIV.setVisible(false);
        }
        if (cluster.getEventIDsWithTags().isEmpty()) {
            tagIV.setManaged(false);
            tagIV.setVisible(false);
        }
        hBox.setPrefWidth(USE_COMPUTED_SIZE);
        hBox.setMinWidth(USE_PREF_SIZE);
        hBox.setPadding(new Insets(2, 5, 2, 5));
        hBox.setAlignment(Pos.CENTER_LEFT);

        minusButton.setVisible(false);
        plusButton.setVisible(false);
        minusButton.setManaged(false);
        plusButton.setManaged(false);
        final BorderPane borderPane = new BorderPane(subNodePane, hBox, null, null, null);
        BorderPane.setAlignment(subNodePane, Pos.TOP_LEFT);
        borderPane.setPrefWidth(USE_COMPUTED_SIZE);
        final Color evtColor = cluster.getType().getColor();

        spanFill = new Background(new BackgroundFill(evtColor.deriveColor(0, 1, 1, .2), CORNER_RADII, Insets.EMPTY));
        for (Range<Long> r : cluster.getRanges()) {
            Region region = new Region();
            region.setStyle("-fx-border-width:2 1 2 1; -fx-border-radius: 1; -fx-border-color: " + ColorUtilities.getRGBCode(evtColor.deriveColor(0, 1, 1, .3)) + ";"); // NON-NLS
            region.setBackground(spanFill);
            spanRegion.getChildren().addAll(region, new Region());
        }
        spanRegion.getChildren().remove(spanRegion.getChildren().size() - 1);

        getChildren().addAll(spanRegion, borderPane);
        setBackground(new Background(new BackgroundFill(evtColor.deriveColor(0, 1, 1, .1), CORNER_RADII, Insets.EMPTY)));
        setAlignment(Pos.TOP_LEFT);
        setMinHeight(24);
        minWidthProperty().bind(spanRegion.widthProperty());
        setPrefHeight(USE_COMPUTED_SIZE);
        setMaxHeight(USE_PREF_SIZE);

        //set up subnode pane sizing contraints
        subNodePane.setPrefHeight(USE_COMPUTED_SIZE);
        subNodePane.setMinHeight(USE_PREF_SIZE);
        subNodePane.setMinWidth(USE_PREF_SIZE);
        subNodePane.setMaxHeight(USE_PREF_SIZE);
        subNodePane.setMaxWidth(USE_PREF_SIZE);
        subNodePane.setPickOnBounds(false);

        //setup description label
        eventTypeImageView.setImage(cluster.getType().getFXImage());
        descrLabel.setGraphic(eventTypeImageView);
        descrLabel.setPrefWidth(USE_COMPUTED_SIZE);
        descrLabel.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS);

        descrLabel.setMouseTransparent(true);
        setDescriptionVisibility(chart.getDescrVisibility().get());
        setOnMouseClicked(new EventMouseHandler());

        //set up mouse hover effect and tooltip
        setOnMouseEntered((MouseEvent e) -> {
            //defer tooltip creation till needed, this had a surprisingly large impact on speed of loading the chart
//            installTooltip();
            spanRegion.setEffect(new DropShadow(10, evtColor));
            minusButton.setVisible(true);
            plusButton.setVisible(true);
            minusButton.setManaged(true);
            plusButton.setManaged(true);
            toFront();
        });

        setOnMouseExited((MouseEvent e) -> {
            spanRegion.setEffect(null);
            minusButton.setVisible(false);
            plusButton.setVisible(false);
            minusButton.setManaged(false);
            plusButton.setManaged(false);
        });

        plusButton.disableProperty().bind(descLOD.isEqualTo(DescriptionLOD.FULL));
        minusButton.disableProperty().bind(descLOD.isEqualTo(cluster.getDescriptionLOD()));

        plusButton.setOnMouseClicked(e -> {
            final DescriptionLOD next = descLOD.get().next();
            if (next != null) {
                loadSubClusters(next);
                descLOD.set(next);
            }
        });
        minusButton.setOnMouseClicked(e -> {
            final DescriptionLOD previous = descLOD.get().previous();
            if (previous != null) {
                loadSubClusters(previous);
                descLOD.set(previous);
            }
        });
    }

    @Override
    public long getStartMillis() {
        return cluster.getStartMillis();
    }

    @Override
    public void setSpanWidths(List<Double> spanWidths) {
        for (int i = 0; i < spanWidths.size(); i++) {
            Region get = (Region) spanRegion.getChildren().get(i);
            Double w = spanWidths.get(i);
            get.setPrefWidth(w);
            get.setMaxWidth(w);
            get.setMinWidth(Math.max(2, w));
        }
    }

    public void setDescriptionVisibility(DescriptionVisibility descrVis) {
        this.descrVis = descrVis;
        final int size = cluster.getEventIDs().size();

        switch (descrVis) {
            case COUNT_ONLY:
                descrLabel.setText("");
                countLabel.setText(String.valueOf(size));
                break;
            case HIDDEN:
                countLabel.setText("");
                descrLabel.setText("");
                break;
            default:
            case SHOWN:
                String description = cluster.getDescription();
                description = parentNode != null
                        ? "    ..." + StringUtils.substringAfter(description, parentNode.getDescription())
                        : description;
                descrLabel.setText(description);
                countLabel.setText(((size == 1) ? "" : " (" + size + ")")); // NON-NLS
                break;
        }
    }

    EventStripe getCluster() {
        return cluster;
    }

    @Override
    public void setDescriptionWidth(double w) {
        descrLabel.setMaxWidth(w);
    }

    @Override
    public long getEndMillis() {
        return cluster.getEndMillis();
    }

    @Override
    public Pane getSubNodePane() {
        return subNodePane;
    }

    /**
     * event handler used for mouse events on {@link AggregateEventNode}s
     */
    private class EventMouseHandler implements EventHandler<MouseEvent> {

        @Override
        public void handle(MouseEvent t) {
            if (t.getButton() == MouseButton.PRIMARY) {
                t.consume();
                if (t.isShiftDown()) {
                    if (chart.selectedNodes.contains(EventStripeNode.this) == false) {
                        chart.selectedNodes.add(EventStripeNode.this);
                    }
                } else if (t.isShortcutDown()) {
                    chart.selectedNodes.removeAll(EventStripeNode.this);
                } else if (t.getClickCount() > 1) {
                    final DescriptionLOD next = descLOD.get().next();
                    if (next != null) {
                        loadSubClusters(next);
                        descLOD.set(next);
                    }
                } else {
                    chart.selectedNodes.setAll(EventStripeNode.this);
                }
            }
        }
    }

    @Override
    public EventType getType() {
        return cluster.getType();
    }

    @Override
    public Set<Long> getEventIDs() {
        return cluster.getEventIDs();
    }
    private static final Border selectionBorder = new Border(new BorderStroke(Color.BLACK, BorderStrokeStyle.SOLID, CORNER_RADII, new BorderWidths(2)));

    /**
     * apply the 'effect' to visually indicate selection
     *
     * @param applied true to apply the selection 'effect', false to remove it
     */
    @Override
    public void applySelectionEffect(boolean applied) {
        Platform.runLater(() -> {
            if (applied) {
                setBorder(selectionBorder);
            } else {
                setBorder(null);
            }
        });
    }

    @Override
    public String getDescription() {
        return cluster.getDescription();
    }

    @Override
    public EventBundle getBundleDescriptor() {
        return getCluster();
    }

    /**
     * loads sub-clusters at the given Description LOD
     *
     * @param newDescriptionLOD
     */
    synchronized private void loadSubClusters(DescriptionLOD newDescriptionLOD) {
        getSubNodePane().getChildren().clear();
        if (newDescriptionLOD == cluster.getDescriptionLOD()) {
            chart.setRequiresLayout(true);
            chart.requestChartLayout();
        } else {
            RootFilter combinedFilter = eventsModel.filterProperty().get().copyOf();
            //make a new filter intersecting the global filter with text(description) and type filters to restrict sub-clusters
            combinedFilter.getSubFilters().addAll(new TextFilter(cluster.getDescription()),
                    new TypeFilter(cluster.getType()));

            //make a new end inclusive span (to 'filter' with)
            final Interval span = new Interval(cluster.getStartMillis(), cluster.getEndMillis() + 1000);

            //make a task to load the subnodes
            LoggedTask<List<EventStripeNode>> loggedTask = new LoggedTask<List<EventStripeNode>>(
                    NbBundle.getMessage(this.getClass(), "AggregateEventNode.loggedTask.name"), true) {

                        @Override
                        protected List<EventStripeNode> call() throws Exception {
                            //query for the sub-clusters
                            List<EventCluster> aggregatedEvents = eventsModel.getAggregatedEvents(new ZoomParams(span,
                                            eventsModel.eventTypeZoomProperty().get(),
                                            combinedFilter,
                                            newDescriptionLOD));
                            //for each sub cluster make an AggregateEventNode to visually represent it, and set x-position
                            HashMap<String, EventStripe> stripeDescMap = new HashMap<>();
                            for (EventCluster subCluster : aggregatedEvents) {
                                stripeDescMap.merge(subCluster.getDescription(),
                                        new EventStripe(subCluster),
                                        (EventStripe u, EventStripe v) -> {
                                            return EventStripe.merge(u, v);
                                        }
                                );
                            }

                            return stripeDescMap.values().stream().map(subStripe -> {
                                EventStripeNode subNode = new EventStripeNode(subStripe, EventStripeNode.this, chart);
                                subNode.setLayoutX(chart.getXAxis().getDisplayPosition(new DateTime(subStripe.getStartMillis())) - getLayoutXCompensation());
                                return subNode;
                            }).collect(Collectors.toList()); // return list of AggregateEventNodes representing subclusters
                        }

                        @Override
                        protected void succeeded() {
                            try {
                                chart.setCursor(Cursor.WAIT);
                                //assign subNodes and request chart layout
                                getSubNodePane().getChildren().setAll(get());
                                setDescriptionVisibility(descrVis);
                                chart.setRequiresLayout(true);
                                chart.requestChartLayout();
                                chart.setCursor(null);
                            } catch (InterruptedException | ExecutionException ex) {
                                LOGGER.log(Level.SEVERE, "Error loading subnodes", ex);
                            }
                        }
                    };

            //start task
            chart.getController().monitorTask(loggedTask);
        }
    }

    double getLayoutXCompensation() {
        return (parentNode != null ? parentNode.getLayoutXCompensation() : 0)
                + getBoundsInParent().getMinX();
    }
}
