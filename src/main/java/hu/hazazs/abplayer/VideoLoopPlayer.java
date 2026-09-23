package hu.hazazs.abplayer;

import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.ScrollEvent;
import javafx.scene.image.Image;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.scene.text.Text;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

public class VideoLoopPlayer extends Application {

    private static final double FRAME_STEP_SECONDS = 1.0 / 30.0;
    private static final double MAX_NATURAL_PLAYBACK_STEP_MILLIS = 3000.0;

    private MediaPlayer mediaPlayer;
    private final MediaView mediaView = new MediaView();

    private final Slider seekSlider = new Slider(0, 1, 0);
    private final Pane seekMarkerOverlay = new Pane();
    private final VBox aMarker = createSeekMarker("A");
    private final VBox bMarker = createSeekMarker("B");
    private final Slider volumeSlider = new Slider(0, 100, 75);
    private final Label currentTimeLabel = new Label("00:00:00.000");
    private final Label totalTimeLabel = new Label("00:00:00.000");

    private final TextField aField = new TextField("00:00:00.000");
    private final TextField bField = new TextField("00:00:00.000");
    private final Button setAButton = new Button("Set");
    private final Button setBButton = new Button("Set");

    private Duration pointA = Duration.ZERO;
    private Duration pointB = Duration.ZERO;
    private Duration mediaDuration = Duration.ZERO;
    private boolean userSeeking;
    private boolean bypassLoopUntilEnd;
    private boolean atVideoEnd;
    private boolean restartingFromEnd;
    private int restartZeroConfirmations;
    private Long exactPausedSeekMillis;

    private final PauseTransition singleClickDelay = new PauseTransition(Duration.millis(220));
    private final PauseTransition restartCheckDelay = new PauseTransition(Duration.millis(25));

    @Override
    public void start(Stage stage) {
        stage.setTitle("A-B Loop Player");
        setWindowIcon(stage);

        BorderPane root = new BorderPane();
        root.setStyle(
                "-fx-background-color: #17191d;" +
                "-fx-focus-color: transparent;" +
                "-fx-faint-focus-color: transparent;"
        );

        StackPane videoPane = new StackPane(mediaView);
        videoPane.setStyle("-fx-background-color: black;");
        videoPane.setMinHeight(360);
        videoPane.setFocusTraversable(true);

        mediaView.setPreserveRatio(true);
        mediaView.fitWidthProperty().bind(videoPane.widthProperty());
        mediaView.fitHeightProperty().bind(videoPane.heightProperty());

        singleClickDelay.setOnFinished(e -> togglePlayPause());
        restartCheckDelay.setOnFinished(e -> finishRestartFromEnd());
        videoPane.setOnMouseClicked(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;

            if (e.getClickCount() == 2) {
                singleClickDelay.stop();
                openVideo(stage);
            } else if (e.getClickCount() == 1) {
                singleClickDelay.playFromStart();
            }
        });
        root.setCenter(videoPane);

        seekSlider.setDisable(true);
        seekSlider.setMaxWidth(Double.MAX_VALUE);
        seekSlider.setFocusTraversable(false);

        seekMarkerOverlay.setMouseTransparent(false);
        seekMarkerOverlay.setPickOnBounds(false);
        seekMarkerOverlay.getChildren().addAll(aMarker, bMarker);
        configureSeekMarkerDrag(aMarker, true);
        configureSeekMarkerDrag(bMarker, false);
        aMarker.setVisible(false);
        bMarker.setVisible(false);

        StackPane seekBarPane = new StackPane(seekSlider, seekMarkerOverlay);
        seekBarPane.setMinWidth(0);
        seekBarPane.setMaxWidth(Double.MAX_VALUE);
        seekBarPane.setMinHeight(34);
        seekBarPane.setPrefHeight(34);
        seekBarPane.setMaxHeight(34);
        HBox.setHgrow(seekBarPane, Priority.ALWAYS);
        seekBarPane.widthProperty().addListener((obs, oldWidth, newWidth) -> updateLoopMarkers());
        seekBarPane.heightProperty().addListener((obs, oldHeight, newHeight) -> updateLoopMarkers());

        Text widestTimeLabel = new Text("88:88:88.888");
        widestTimeLabel.setFont(currentTimeLabel.getFont());
        double timestampLabelWidth = Math.ceil(widestTimeLabel.getLayoutBounds().getWidth()) + 8;

        configureTimestampLabel(currentTimeLabel, timestampLabelWidth, Pos.CENTER_LEFT);
        configureTimestampLabel(totalTimeLabel, timestampLabelWidth, Pos.CENTER_RIGHT);

        HBox timeRow = new HBox(8, currentTimeLabel, seekBarPane, totalTimeLabel);
        timeRow.setAlignment(Pos.CENTER);

        volumeSlider.setPrefWidth(120);
        volumeSlider.setFocusTraversable(false);
        volumeSlider.valueProperty().addListener((obs, oldV, newV) -> {
            if (mediaPlayer != null) {
                mediaPlayer.setVolume(newV.doubleValue() / 100.0);
            }
        });

        Text widestTimestamp = new Text("88:88:88.888");
        widestTimestamp.setFont(aField.getFont());
        double timestampFieldWidth = Math.ceil(widestTimestamp.getLayoutBounds().getWidth()) + 20;

        configureTimestampField(aField, timestampFieldWidth);
        configureTimestampField(bField, timestampFieldWidth);

        configureStaticButton(setAButton);
        configureStaticButton(setBButton);

        setAButton.setOnAction(e -> setPointAFromCurrent());
        setBButton.setOnAction(e -> setPointBFromCurrent());

        setAButton.setDisable(true);
        setBButton.setDisable(true);
        seekSlider.valueProperty().addListener((obs, oldValue, newValue) ->
                updateSetButtonAvailability(newValue.doubleValue())
        );

        aField.setOnAction(e -> applyTypedPoints());
        bField.setOnAction(e -> applyTypedPoints());
        aField.focusedProperty().addListener((obs, was, is) -> {
            if (was && !is) applyTypedPoints();
        });
        bField.focusedProperty().addListener((obs, was, is) -> {
            if (was && !is) applyTypedPoints();
        });

        Region aRowSpacer = new Region();
        HBox.setHgrow(aRowSpacer, Priority.ALWAYS);

        HBox aRow = new HBox(8, new Label("A"), aField, setAButton, aRowSpacer, volumeSlider);
        aRow.setAlignment(Pos.CENTER_LEFT);

        HBox bRow = new HBox(8, new Label("B"), bField, setBButton);
        bRow.setAlignment(Pos.CENTER_LEFT);

        VBox controls = new VBox(8, timeRow, aRow, bRow);
        VBox.setMargin(aRow, new Insets(5, 0, 0, 0));
        controls.setPadding(new Insets(10, 12, 12, 12));
        controls.setStyle(
                "-fx-background-color: #22252b;" +
                "-fx-text-fill: white;" +
                "-fx-border-color: black;" +
                "-fx-border-width: 1;"
        );
        styleLabels(controls);
        root.setBottom(controls);

        installSeekBehavior();

        Scene scene = new Scene(root, 1000, 700, Color.BLACK);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::handleKeyboard);
        scene.addEventFilter(ScrollEvent.SCROLL, this::handleVolumeScroll);
        stage.setScene(scene);
        stage.setMinWidth(760);
        stage.setMinHeight(560);

        // Show the already-maximized, already-black scene without a white startup flash.
        stage.setOpacity(0);
        stage.setMaximized(true);
        stage.show();
        root.applyCss();
        root.layout();
        configureStaticSliderAppearance(seekSlider);
        configureStaticSliderAppearance(volumeSlider);
        Platform.runLater(() -> {
            stage.setOpacity(1);
            videoPane.requestFocus();
        });

        stage.setOnCloseRequest(e -> disposePlayer());
    }

    private void styleLabels(Pane pane) {
        pane.lookupAll(".label").forEach(n -> n.setStyle("-fx-text-fill: #d6d9df;"));
    }

    private void openVideo(Stage stage) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open Video");

        File defaultFolder = new File("D:\\stuffz");
        if (defaultFolder.isDirectory()) {
            chooser.setInitialDirectory(defaultFolder);
        }

        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("MP4 / M4V", "*.mp4", "*.m4v"),
                new FileChooser.ExtensionFilter("All files", "*.*")
        );

        File file = chooser.showOpenDialog(stage);
        if (file == null) return;

        disposePlayer();

        try {
            Media media = new Media(file.toURI().toString());
            mediaPlayer = new MediaPlayer(media);
            mediaView.setMediaPlayer(mediaPlayer);
            mediaPlayer.setVolume(volumeSlider.getValue() / 100.0);

            seekSlider.setDisable(true);
            aMarker.setVisible(false);
            bMarker.setVisible(false);

            mediaPlayer.setOnReady(() -> {
                mediaDuration = mediaPlayer.getTotalDuration();
                pointA = Duration.ZERO;
                pointB = mediaDuration;
                bypassLoopUntilEnd = false;
                atVideoEnd = false;
                restartingFromEnd = false;
                restartZeroConfirmations = 0;
                restartCheckDelay.stop();
                exactPausedSeekMillis = null;
                aField.setText(formatDuration(pointA));
                bField.setText(formatDuration(pointB));
                totalTimeLabel.setText(formatDuration(mediaDuration));
                seekSlider.setMin(0);
                seekSlider.setMax(Math.max(1, mediaDuration.toMillis()));
                seekSlider.setValue(pointA.toMillis());
                seekSlider.setDisable(false);
                updateSetButtonAvailability(seekSlider.getValue());
                seekSlider.applyCss();
                seekSlider.layout();
                updateLoopMarkers();
                mediaPlayer.play();
            });

            mediaPlayer.currentTimeProperty().addListener((obs, oldTime, newTime) -> {
                if (restartingFromEnd) {
                    seekSlider.setValue(0);
                    currentTimeLabel.setText(formatDuration(Duration.ZERO));
                    return;
                }

                if (!bypassLoopUntilEnd
                        && pointB.greaterThan(pointA)
                        && oldTime.lessThanOrEqualTo(pointB)
                        && newTime.greaterThan(pointB)
                        && isNaturalPlaybackStep(oldTime, newTime)) {
                    exactPausedSeekMillis = null;
                    atVideoEnd = false;

                    if (!userSeeking) {
                        seekSlider.setValue(pointB.toMillis());
                    }

                    mediaPlayer.seek(pointA);

                    if (!userSeeking) {
                        seekSlider.setValue(pointA.toMillis());
                    }
                    currentTimeLabel.setText(formatDuration(pointA));

                    if (mediaPlayer.getStatus() != MediaPlayer.Status.PLAYING) {
                        mediaPlayer.play();
                    }
                    return;
                }

                if (exactPausedSeekMillis != null
                        && mediaPlayer.getStatus() != MediaPlayer.Status.PLAYING
                        && !userSeeking) {
                    Duration exactTime = Duration.millis(exactPausedSeekMillis);
                    seekSlider.setValue(exactPausedSeekMillis);
                    currentTimeLabel.setText(formatDuration(exactTime));
                    return;
                }

                if (!userSeeking) {
                    seekSlider.setValue(newTime.toMillis());
                }
                currentTimeLabel.setText(formatDuration(newTime));
            });

            mediaPlayer.setOnEndOfMedia(() -> {
                if (restartingFromEnd) {
                    return;
                }

                exactPausedSeekMillis = null;

                if (!bypassLoopUntilEnd && pointB.greaterThan(pointA)) {
                    atVideoEnd = false;
                    mediaPlayer.seek(pointA);
                    seekSlider.setValue(pointA.toMillis());
                    currentTimeLabel.setText(formatDuration(pointA));
                    mediaPlayer.play();
                } else {
                    atVideoEnd = true;
                    seekSlider.setValue(mediaDuration.toMillis());
                    currentTimeLabel.setText(formatDuration(mediaDuration));
                }
            });

            mediaPlayer.setOnError(() -> showMediaError(mediaPlayer.getError()));
            media.setOnError(() -> showMediaError(media.getError()));

        } catch (Exception ex) {
            System.err.println("Could not open video: " + ex.getMessage());
        }
    }

    private void installSeekBehavior() {
        Tooltip seekTooltip = new Tooltip("00:00:00.000");
        seekTooltip.setAutoHide(false);

        seekSlider.setOnMouseEntered(e -> {
            if (!userSeeking) {
                showSeekTooltip(seekTooltip, e.getScreenX(), e.getScreenY(), hoverTimeAt(e.getX()));
            }
        });

        seekSlider.setOnMouseMoved(e -> {
            if (!userSeeking) {
                showSeekTooltip(seekTooltip, e.getScreenX(), e.getScreenY(), hoverTimeAt(e.getX()));
            }
        });

        seekSlider.setOnMouseExited(e -> {
            if (!userSeeking) {
                seekTooltip.hide();
            }
        });

        seekSlider.setOnMousePressed(e -> {
            userSeeking = true;
            showSeekTooltip(seekTooltip, e.getScreenX(), e.getScreenY(), hoverTimeAt(e.getX()));
        });

        seekSlider.setOnMouseDragged(e -> {
            userSeeking = true;
            showSeekTooltip(
                    seekTooltip,
                    e.getScreenX(),
                    e.getScreenY(),
                    Duration.millis(seekSlider.getValue())
            );
        });

        seekSlider.setOnMouseReleased(e -> {
            if (mediaPlayer != null) {
                seekFromProgressBar(Duration.millis(seekSlider.getValue()));
            }
            userSeeking = false;

            if (seekSlider.isHover()) {
                showSeekTooltip(seekTooltip, e.getScreenX(), e.getScreenY(), hoverTimeAt(e.getX()));
            } else {
                seekTooltip.hide();
            }
        });

        seekSlider.valueChangingProperty().addListener((obs, was, changing) -> {
            userSeeking = changing;
            if (!changing && mediaPlayer != null) {
                seekFromProgressBar(Duration.millis(seekSlider.getValue()));
            }
        });
    }

    private void seekFromProgressBar(Duration target) {
        restartCheckDelay.stop();
        restartingFromEnd = false;
        restartZeroConfirmations = 0;
        exactPausedSeekMillis = null;
        atVideoEnd = false;
        bypassLoopUntilEnd = target.greaterThan(pointB);
        mediaPlayer.seek(target);
    }

    private Duration hoverTimeAt(double mouseX) {
        Node track = seekSlider.lookup(".track");
        if (track == null || mediaDuration == null || mediaDuration.lessThanOrEqualTo(Duration.ZERO)) {
            return Duration.ZERO;
        }

        Bounds trackBounds = track.getBoundsInParent();
        double startX = trackBounds.getMinX();
        double endX = trackBounds.getMaxX();
        double trackWidth = endX - startX;
        if (trackWidth <= 0) return Duration.ZERO;

        double ratio = Math.max(0, Math.min(1, (mouseX - startX) / trackWidth));
        return Duration.millis(ratio * mediaDuration.toMillis());
    }

    private void showSeekTooltip(Tooltip tooltip, double screenX, double screenY, Duration time) {
        tooltip.setText(formatDuration(time));
        tooltip.show(seekSlider.getScene().getWindow(), screenX + 10, screenY - 35);
    }

    private void configureTimestampField(TextField field, double width) {
        field.setMinWidth(width);
        field.setPrefWidth(width);
        field.setMaxWidth(width);
    }

    private void configureTimestampLabel(Label label, double width, Pos alignment) {
        label.setMinWidth(width);
        label.setPrefWidth(width);
        label.setMaxWidth(width);
        label.setAlignment(alignment);
    }

    private void configureStaticSliderAppearance(Slider slider) {
        applyStaticSliderAppearance(slider);

        slider.skinProperty().addListener((obs, oldSkin, newSkin) ->
                Platform.runLater(() -> applyStaticSliderAppearance(slider))
        );
    }

    private void applyStaticSliderAppearance(Slider slider) {
        slider.setStyle(
                "-fx-focus-color: transparent;" +
                "-fx-faint-focus-color: transparent;" +
                "-fx-effect: null;"
        );

        Region track = (Region) slider.lookup(".track");
        if (track != null) {
            track.setStyle(
                    "-fx-background-color: #b8b8b8;" +
                    "-fx-border-color: black;" +
                    "-fx-border-width: 1;" +
                    "-fx-background-insets: 0;" +
                    "-fx-background-radius: 2;" +
                    "-fx-border-radius: 2;" +
                    "-fx-effect: null;"
            );
        }

        Region thumb = (Region) slider.lookup(".thumb");
        if (thumb != null) {
            thumb.setStyle(
                    "-fx-background-color: #f4f4f4;" +
                    "-fx-border-color: black;" +
                    "-fx-border-width: 1;" +
                    "-fx-background-insets: 0;" +
                    "-fx-background-radius: 20;" +
                    "-fx-border-radius: 20;" +
                    "-fx-effect: null;"
            );
        }
    }

    private void configureStaticButton(Button button) {
        button.setFocusTraversable(false);
        button.setStyle(
                "-fx-background-color: #f4f4f4;" +
                "-fx-text-fill: #202020;" +
                "-fx-background-insets: 0;" +
                "-fx-background-radius: 3;" +
                "-fx-border-color: #b8b8b8;" +
                "-fx-border-width: 1;" +
                "-fx-border-radius: 3;" +
                "-fx-effect: null;" +
                "-fx-focus-color: transparent;" +
                "-fx-faint-focus-color: transparent;"
        );
    }

    private VBox createSeekMarker(String text) {
        Label label = new Label(text);
        label.setStyle(
                "-fx-text-fill: #00c853;" +
                "-fx-font-size: 9px;" +
                "-fx-font-weight: bold;" +
                "-fx-background-color: rgba(0, 0, 0, 0.75);" +
                "-fx-padding: 0 2 0 2;"
        );

        Region line = new Region();
        line.setMinSize(2, 22);
        line.setPrefSize(2, 22);
        line.setMaxSize(2, 22);
        line.setStyle("-fx-background-color: #00c853;");

        VBox marker = new VBox(0, line, label);
        marker.setAlignment(Pos.TOP_CENTER);
        marker.setManaged(false);
        marker.setMinWidth(14);
        marker.setPickOnBounds(true);
        marker.setMouseTransparent(false);
        marker.setCursor(Cursor.H_RESIZE);
        return marker;
    }

    private void configureSeekMarkerDrag(VBox marker, boolean isPointA) {
        marker.setOnMousePressed(e -> {
            if (e.getButton() != MouseButton.PRIMARY || mediaPlayer == null) return;

            marker.setCursor(Cursor.CLOSED_HAND);
            e.consume();
        });

        marker.setOnMouseDragged(e -> {
            if (!e.isPrimaryButtonDown() || mediaPlayer == null) return;

            Duration candidate = timeAtSceneX(e.getSceneX());
            if (candidate == null) return;

            long candidateMillis = Math.round(candidate.toMillis());
            long maxMillis = Math.round(mediaDuration.toMillis());

            if (isPointA) {
                long latestA = Math.max(0, Math.round(pointB.toMillis()) - 1);
                candidateMillis = Math.max(0, Math.min(candidateMillis, latestA));
                pointA = Duration.millis(candidateMillis);
                aField.setText(formatDuration(pointA));
            } else {
                long earliestB = Math.min(maxMillis, Math.round(pointA.toMillis()) + 1);
                candidateMillis = Math.max(earliestB, Math.min(candidateMillis, maxMillis));
                pointB = Duration.millis(candidateMillis);
                bField.setText(formatDuration(pointB));
            }

            bypassLoopUntilEnd = false;
            atVideoEnd = false;
            updateSetButtonAvailability(seekSlider.getValue());
            updateLoopMarkers();
            e.consume();
        });

        marker.setOnMouseReleased(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;

            marker.setCursor(Cursor.H_RESIZE);
            if (mediaPlayer != null) {
                validateLoopRange();
            }
            e.consume();
        });
    }

    private Duration timeAtSceneX(double sceneX) {
        if (mediaDuration == null
                || mediaDuration.isUnknown()
                || mediaDuration.isIndefinite()
                || mediaDuration.lessThanOrEqualTo(Duration.ZERO)) {
            return null;
        }

        Node track = seekSlider.lookup(".track");
        if (track == null) return null;

        Bounds trackSceneBounds = track.localToScene(track.getBoundsInLocal());
        if (trackSceneBounds == null || trackSceneBounds.getWidth() <= 0) return null;

        double ratio = (sceneX - trackSceneBounds.getMinX()) / trackSceneBounds.getWidth();
        ratio = Math.max(0, Math.min(1, ratio));
        return Duration.millis(ratio * mediaDuration.toMillis());
    }

    private void updateLoopMarkers() {
        if (mediaDuration == null
                || mediaDuration.isUnknown()
                || mediaDuration.isIndefinite()
                || mediaDuration.lessThanOrEqualTo(Duration.ZERO)
                || seekMarkerOverlay.getWidth() <= 0) {
            aMarker.setVisible(false);
            bMarker.setVisible(false);
            return;
        }

        positionSeekMarker(aMarker, pointA);
        positionSeekMarker(bMarker, pointB);
    }

    private void positionSeekMarker(VBox marker, Duration time) {
        marker.applyCss();
        marker.autosize();

        Point2D thumbCenter = thumbCenterForTime(time);
        if (thumbCenter == null) {
            marker.setVisible(false);
            return;
        }

        double markerWidth = Math.max(1, marker.prefWidth(-1));
        double markerLineHeight = 22.0;

        // The marker line is the first VBox child and is centered horizontally.
        // Position the VBox so the line's exact center pixel matches the slider
        // thumb's exact center for the same timestamp.
        double x = thumbCenter.getX() - markerWidth / 2.0;
        double y = thumbCenter.getY() - markerLineHeight / 2.0;

        marker.relocate(x, y);
        marker.setVisible(true);
    }

    private Point2D thumbCenterForTime(Duration time) {
        if (mediaDuration == null
                || mediaDuration.isUnknown()
                || mediaDuration.isIndefinite()
                || mediaDuration.lessThanOrEqualTo(Duration.ZERO)) {
            return null;
        }

        Node track = seekSlider.lookup(".track");
        Node thumb = seekSlider.lookup(".thumb");
        if (track == null || thumb == null || seekSlider.getScene() == null) {
            return null;
        }

        Bounds trackSceneBounds = track.localToScene(track.getBoundsInLocal());
        Bounds thumbSceneBounds = thumb.localToScene(thumb.getBoundsInLocal());
        if (trackSceneBounds == null || thumbSceneBounds == null) {
            return null;
        }

        Point2D trackStart = seekMarkerOverlay.sceneToLocal(
                trackSceneBounds.getMinX(),
                trackSceneBounds.getCenterY()
        );
        Point2D trackEnd = seekMarkerOverlay.sceneToLocal(
                trackSceneBounds.getMaxX(),
                trackSceneBounds.getCenterY()
        );
        Point2D currentThumbCenter = seekMarkerOverlay.sceneToLocal(
                thumbSceneBounds.getCenterX(),
                thumbSceneBounds.getCenterY()
        );

        double ratio = Math.max(0, Math.min(1, time.toMillis() / mediaDuration.toMillis()));
        double x = trackStart.getX() + ratio * (trackEnd.getX() - trackStart.getX());

        return new Point2D(x, currentThumbCenter.getY());
    }

    private void togglePlayPause() {
        if (mediaPlayer == null) return;

        // Consume the real end-of-media state exactly once. Do not infer end
        // from currentTime, because JavaFX can briefly expose the old end
        // timestamp after playback has already restarted.
        if (atVideoEnd) {
            restartFromRealEnd();
            return;
        }

        MediaPlayer.Status status = mediaPlayer.getStatus();
        if (status == MediaPlayer.Status.PLAYING) {
            mediaPlayer.pause();
            exactPausedSeekMillis = Math.round(mediaPlayer.getCurrentTime().toMillis());
            return;
        }

        // The player is already at the paused/keyboard-seeked position.
        // Re-seeking here is unnecessary and can emit a stale asynchronous
        // timestamp from the previous playback state, which may falsely
        // trigger the B -> A loop.
        exactPausedSeekMillis = null;

        // If playback resumes exactly at B, it must first advance beyond B;
        // the current-time listener performs the B -> A jump on the first
        // update strictly after B.
        mediaPlayer.play();
    }

    private void restartFromRealEnd() {
        if (mediaPlayer == null) return;

        bypassLoopUntilEnd = false;
        atVideoEnd = false;
        restartingFromEnd = true;
        restartZeroConfirmations = 0;
        exactPausedSeekMillis = 0L;

        mediaPlayer.stop();
        mediaPlayer.seek(Duration.ZERO);
        seekSlider.setValue(0);
        currentTimeLabel.setText(formatDuration(Duration.ZERO));
        restartCheckDelay.playFromStart();
    }

    private void finishRestartFromEnd() {
        if (!restartingFromEnd || mediaPlayer == null) return;

        if (mediaPlayer.getCurrentTime().toMillis() <= 1.0) {
            restartZeroConfirmations++;
            if (restartZeroConfirmations >= 2) {
                restartingFromEnd = false;
                restartZeroConfirmations = 0;
                exactPausedSeekMillis = null;
                mediaPlayer.play();
                return;
            }

            // Confirm zero again on a later pulse before resuming. This gives
            // any stale asynchronous seek-to-end one more chance to surface.
            mediaPlayer.seek(Duration.ZERO);
            restartCheckDelay.playFromStart();
            return;
        }

        restartZeroConfirmations = 0;

        // A stale asynchronous seek to the previous end may have completed
        // after the restart request. Force zero again and wait until JavaFX
        // confirms the new position before starting playback.
        mediaPlayer.seek(Duration.ZERO);
        seekSlider.setValue(0);
        currentTimeLabel.setText(formatDuration(Duration.ZERO));
        restartCheckDelay.playFromStart();
    }

    private boolean isNaturalPlaybackStep(Duration oldTime, Duration newTime) {
        if (mediaPlayer == null || mediaPlayer.getStatus() != MediaPlayer.Status.PLAYING) {
            return false;
        }

        double deltaMillis = newTime.toMillis() - oldTime.toMillis();
        return deltaMillis > 0 && deltaMillis <= MAX_NATURAL_PLAYBACK_STEP_MILLIS;
    }

    private void seekBySeconds(double seconds) {
        if (mediaPlayer == null || mediaDuration.isUnknown() || mediaDuration.isIndefinite()) return;

        restartCheckDelay.stop();
        restartingFromEnd = false;

        // Use the slider thumb as the logical seek position. Immediately after
        // restarting from the real end, JavaFX may still expose the old end
        // timestamp through MediaPlayer#getCurrentTime(), while the thumb is
        // already correctly reset to 00:00:00.000.
        long baseMillis = Math.round(seekSlider.getValue());

        long deltaMillis = Math.round(seconds * 1000.0);
        long maxMillis = Math.round(mediaDuration.toMillis());
        long targetMillis = Math.max(0, Math.min(baseMillis + deltaMillis, maxMillis));

        atVideoEnd = targetMillis >= maxMillis;
        bypassLoopUntilEnd = targetMillis > pointB.toMillis();
        Duration target = Duration.millis(targetMillis);

        if (mediaPlayer.getStatus() == MediaPlayer.Status.PLAYING) {
            exactPausedSeekMillis = null;
        } else {
            exactPausedSeekMillis = targetMillis;
        }

        mediaPlayer.seek(target);
        seekSlider.setValue(targetMillis);
        currentTimeLabel.setText(formatDuration(target));
    }

    private void stepFrame(int direction) {
        if (mediaPlayer == null) return;

        mediaPlayer.pause();
        seekBySeconds(direction * FRAME_STEP_SECONDS);
    }

    private void setPointAFromCurrent() {
        if (mediaPlayer == null) return;

        Duration candidate = logicalCurrentTime();
        if (pointB.greaterThan(candidate)) {
            pointA = candidate;
            aField.setText(formatDuration(pointA));
            validateLoopRange();
        }
    }

    private void setPointBFromCurrent() {
        if (mediaPlayer == null) return;

        Duration candidate = logicalCurrentTime();
        if (candidate.greaterThan(pointA)) {
            pointB = candidate;
            bField.setText(formatDuration(pointB));
            validateLoopRange();
        }
    }

    private void applyTypedPoints() {
        if (mediaPlayer == null) return;

        try {
            Duration a = parseDuration(aField.getText());
            Duration b = parseDuration(bField.getText());
            a = clamp(a, Duration.ZERO, mediaDuration);
            b = clamp(b, Duration.ZERO, mediaDuration);
            if (!b.greaterThan(a)) {
                aField.setText(formatDuration(pointA));
                bField.setText(formatDuration(pointB));
                return;
            }

            pointA = a;
            pointB = b;
            aField.setText(formatDuration(pointA));
            bField.setText(formatDuration(pointB));
            validateLoopRange();
        } catch (IllegalArgumentException ex) {
            aField.setText(formatDuration(pointA));
            bField.setText(formatDuration(pointB));
        }
    }

    private Duration logicalCurrentTime() {
        return exactPausedSeekMillis == null
                ? mediaPlayer.getCurrentTime()
                : Duration.millis(exactPausedSeekMillis);
    }

    private void validateLoopRange() {
        if (mediaPlayer == null) return;

        bypassLoopUntilEnd = false;
        atVideoEnd = false;
        seekSlider.setValue(logicalCurrentTime().toMillis());
        updateSetButtonAvailability(seekSlider.getValue());
        updateLoopMarkers();
        if (!pointB.greaterThan(pointA)) return;
        Duration current = logicalCurrentTime();
        if (current.greaterThan(pointB)) {
            mediaPlayer.seek(pointA);

            if (mediaPlayer.getStatus() != MediaPlayer.Status.PLAYING) {
                exactPausedSeekMillis = Math.round(pointA.toMillis());
            }

            seekSlider.setValue(pointA.toMillis());
            currentTimeLabel.setText(formatDuration(pointA));
        }
    }

    private void showMediaError(Throwable error) {
        String message = error == null ? "Unknown media error" : error.getMessage();
        System.err.println("Media error: " + message);
    }

    private void updateSetButtonAvailability(double selectedMillis) {
        if (mediaPlayer == null
                || mediaDuration == null
                || mediaDuration.isUnknown()
                || mediaDuration.isIndefinite()
                || mediaDuration.lessThanOrEqualTo(Duration.ZERO)) {
            setAButton.setDisable(true);
            setBButton.setDisable(true);
            return;
        }

        setBButton.setDisable(selectedMillis <= pointA.toMillis());
        setAButton.setDisable(selectedMillis >= pointB.toMillis());
    }

    private void changeVolume(double delta) {
        volumeSlider.setValue(Math.max(0, Math.min(100, volumeSlider.getValue() + delta)));
    }

    private void handleVolumeScroll(ScrollEvent e) {
        if (e.getDeltaY() == 0) return;

        changeVolume(e.getDeltaY() > 0 ? 5 : -5);
        e.consume();
    }

    private void handleKeyboard(KeyEvent e) {
        if (e.getTarget() instanceof TextInputControl) return;

        if (e.getCode() == KeyCode.UP) {
            changeVolume(5);
            e.consume();
            return;
        }

        if (e.getCode() == KeyCode.DOWN) {
            changeVolume(-5);
            e.consume();
            return;
        }

        if (e.getCode() == KeyCode.SPACE) {
            togglePlayPause();
            e.consume();
            return;
        }

        if (e.getCode() != KeyCode.LEFT && e.getCode() != KeyCode.RIGHT) return;

        int direction = e.getCode() == KeyCode.LEFT ? -1 : 1;

        if (e.isControlDown()) {
            stepFrame(direction);
        } else if (e.isShiftDown()) {
            seekBySeconds(direction * 30);
        } else {
            seekBySeconds(direction * 5);
        }

        e.consume();
    }

    private Duration parseDuration(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Empty time");

        String normalized = text.trim().replace(',', '.');
        String[] parts = normalized.split(":");
        if (parts.length < 1 || parts.length > 3) throw new IllegalArgumentException("Bad format");

        double seconds;
        try {
            if (parts.length == 3) {
                seconds = Integer.parseInt(parts[0]) * 3600.0
                        + Integer.parseInt(parts[1]) * 60.0
                        + Double.parseDouble(parts[2]);
            } else if (parts.length == 2) {
                seconds = Integer.parseInt(parts[0]) * 60.0 + Double.parseDouble(parts[1]);
            } else {
                seconds = Double.parseDouble(parts[0]);
            }
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Bad number", ex);
        }

        if (!Double.isFinite(seconds) || seconds < 0) {
            throw new IllegalArgumentException("Bad time");
        }

        return Duration.seconds(seconds);
    }

    private Duration clamp(Duration value, Duration min, Duration max) {
        if (value.lessThan(min)) return min;
        if (value.greaterThan(max)) return max;
        return value;
    }

    private String formatDuration(Duration duration) {
        if (duration == null || duration.isUnknown() || duration.isIndefinite()) return "00:00:00.000";

        long totalMillis = Math.max(0, Math.round(duration.toMillis()));
        long totalSeconds = totalMillis / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        long millis = totalMillis % 1000;

        return String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", hours, minutes, seconds, millis);
    }

    private void setWindowIcon(Stage stage) {
        try (InputStream resource = getClass().getResourceAsStream("/app-icon.b64")) {
            if (resource == null) return;

            String encoded = new String(resource.readAllBytes(), StandardCharsets.US_ASCII).trim();
            byte[] iconBytes = Base64.getDecoder().decode(encoded);
            stage.getIcons().add(new Image(new ByteArrayInputStream(iconBytes)));
        } catch (Exception ex) {
            System.err.println("Could not load application icon: " + ex.getMessage());
        }
    }

    private void disposePlayer() {
        restartCheckDelay.stop();
        restartingFromEnd = false;
        restartZeroConfirmations = 0;
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
                mediaPlayer.dispose();
            } catch (Exception ignored) {
            }
            mediaPlayer = null;
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
