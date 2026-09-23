package hu.hazazs.abplayer;

import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.*;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.scene.paint.Color;
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

    private static final double SEEK_BAR_HEIGHT = 34;
    private static final double SEEK_TRACK_CENTER_Y = 11;
    private static final double SEEK_TRACK_HEIGHT = 4;
    private static final double SEEK_THUMB_SIZE = 12;
    private static final double SEEK_MARKER_LINE_HEIGHT = 22;

    private MediaPlayer mediaPlayer;
    private final MediaView mediaView = new MediaView();

    private final Pane seekBarPane = new Pane();
    private final Region seekInactiveTrack = new Region();
    private final Region seekActiveTrack = new Region();
    private final Region seekThumb = new Region();
    private final Region aMarkerLine = createMarkerLine();
    private final Region bMarkerLine = createMarkerLine();
    private final Label aMarkerLabel = createMarkerLabel("A");
    private final Label bMarkerLabel = createMarkerLabel("B");

    private final Slider volumeSlider = new Slider(0, 100, 75);
    private final Label currentTimeLabel = new Label("00:00:00.000");
    private final Label totalTimeLabel = new Label("00:00:00.000");
    private final Label fileLabel = new Label("No video loaded");

    private final TextField aField = new TextField("00:00:00.000");
    private final TextField bField = new TextField("00:00:00.000");

    private final PauseTransition singleClickDelay = new PauseTransition(Duration.millis(220));

    private Duration pointA = Duration.ZERO;
    private Duration pointB = Duration.ZERO;
    private Duration mediaDuration = Duration.ZERO;

    private boolean userSeeking;
    private double pendingSeekMillis;
    private Tooltip seekTooltip;

    @Override
    public void start(Stage stage) {
        stage.setTitle("A–B Loop Video Player");
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

        mediaView.setPreserveRatio(true);
        mediaView.fitWidthProperty().bind(videoPane.widthProperty());
        mediaView.fitHeightProperty().bind(videoPane.heightProperty());

        singleClickDelay.setOnFinished(e -> togglePlayPause());
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

        Button openButton = new Button("Open");
        configureStaticButton(openButton);
        openButton.setOnAction(e -> openVideo(stage));

        fileLabel.setStyle("-fx-text-fill: #d6d9df;");
        fileLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(fileLabel, Priority.ALWAYS);

        HBox topBar = new HBox(12, openButton, fileLabel);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setPadding(new Insets(10, 12, 8, 12));
        topBar.setStyle("-fx-background-color: #22252b;");
        root.setTop(topBar);

        configureSeekBar();

        HBox timeRow = new HBox(8, currentTimeLabel, seekBarPane, totalTimeLabel);
        timeRow.setAlignment(Pos.CENTER);
        HBox.setHgrow(seekBarPane, Priority.ALWAYS);

        volumeSlider.setPrefWidth(120);
        volumeSlider.setFocusTraversable(false);
        volumeSlider.valueProperty().addListener((obs, oldV, newV) -> {
            if (mediaPlayer != null) {
                mediaPlayer.setVolume(newV.doubleValue() / 100.0);
            }
        });

        Region playbackSpacer = new Region();
        HBox.setHgrow(playbackSpacer, Priority.ALWAYS);

        HBox playbackRow = new HBox(8, playbackSpacer, volumeSlider);
        playbackRow.setAlignment(Pos.CENTER_LEFT);

        Text widestTimestamp = new Text("88:88:88.888");
        widestTimestamp.setFont(aField.getFont());
        double timestampFieldWidth = Math.ceil(widestTimestamp.getLayoutBounds().getWidth()) + 20;

        configureTimestampField(aField, timestampFieldWidth);
        configureTimestampField(bField, timestampFieldWidth);

        Button setAButton = new Button("Set");
        Button clearAButton = new Button("Clear");
        Button setBButton = new Button("Set");
        Button clearBButton = new Button("Clear");

        configureStaticButton(setAButton);
        configureStaticButton(clearAButton);
        configureStaticButton(setBButton);
        configureStaticButton(clearBButton);

        setAButton.setOnAction(e -> setPointAFromCurrent());
        clearAButton.setOnAction(e -> clearPointA());
        setBButton.setOnAction(e -> setPointBFromCurrent());
        clearBButton.setOnAction(e -> clearPointB());

        aField.setOnAction(e -> applyTypedPoints());
        bField.setOnAction(e -> applyTypedPoints());
        aField.focusedProperty().addListener((obs, was, is) -> {
            if (was && !is) applyTypedPoints();
        });
        bField.focusedProperty().addListener((obs, was, is) -> {
            if (was && !is) applyTypedPoints();
        });

        GridPane loopGrid = new GridPane();
        loopGrid.setHgap(8);
        loopGrid.setVgap(8);
        loopGrid.add(new Label("A"), 0, 0);
        loopGrid.add(aField, 1, 0);
        loopGrid.add(setAButton, 2, 0);
        loopGrid.add(clearAButton, 3, 0);
        loopGrid.add(new Label("B"), 0, 1);
        loopGrid.add(bField, 1, 1);
        loopGrid.add(setBButton, 2, 1);
        loopGrid.add(clearBButton, 3, 1);

        VBox controls = new VBox(10, timeRow, playbackRow, loopGrid);
        controls.setPadding(new Insets(10, 12, 12, 12));
        controls.setStyle("-fx-background-color: #22252b; -fx-text-fill: white;");
        styleLabels(controls);
        root.setBottom(controls);

        installSeekBehavior();

        Scene scene = new Scene(root, 1000, 700, Color.BLACK);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::handleKeyboard);
        scene.addEventFilter(ScrollEvent.SCROLL, this::handleVolumeScroll);

        stage.setScene(scene);
        stage.setMinWidth(760);
        stage.setMinHeight(560);

        stage.setOpacity(0);
        stage.setMaximized(true);
        stage.show();

        root.applyCss();
        root.layout();

        configureVolumeSliderAppearance();
        layoutSeekBar();

        Platform.runLater(() -> stage.setOpacity(1));

        stage.setOnCloseRequest(e -> disposePlayer());
    }

    private void configureSeekBar() {
        seekBarPane.setMinWidth(0);
        seekBarPane.setMaxWidth(Double.MAX_VALUE);
        seekBarPane.setMinHeight(SEEK_BAR_HEIGHT);
        seekBarPane.setPrefHeight(SEEK_BAR_HEIGHT);
        seekBarPane.setMaxHeight(SEEK_BAR_HEIGHT);

        seekInactiveTrack.setMouseTransparent(true);
        seekInactiveTrack.setStyle(
                "-fx-background-color: #777777;" +
                "-fx-border-color: black;" +
                "-fx-border-width: 1;" +
                "-fx-background-radius: 2;" +
                "-fx-border-radius: 2;"
        );

        seekActiveTrack.setMouseTransparent(true);
        seekActiveTrack.setStyle(
                "-fx-background-color: #d0d0d0;" +
                "-fx-border-color: black;" +
                "-fx-border-width: 1;" +
                "-fx-background-radius: 2;" +
                "-fx-border-radius: 2;"
        );

        seekThumb.setMouseTransparent(true);
        seekThumb.setMinSize(SEEK_THUMB_SIZE, SEEK_THUMB_SIZE);
        seekThumb.setPrefSize(SEEK_THUMB_SIZE, SEEK_THUMB_SIZE);
        seekThumb.setMaxSize(SEEK_THUMB_SIZE, SEEK_THUMB_SIZE);
        seekThumb.setStyle(
                "-fx-background-color: #f4f4f4;" +
                "-fx-border-color: black;" +
                "-fx-border-width: 1;" +
                "-fx-background-radius: 20;" +
                "-fx-border-radius: 20;"
        );

        aMarkerLine.setMouseTransparent(true);
        bMarkerLine.setMouseTransparent(true);
        aMarkerLabel.setMouseTransparent(true);
        bMarkerLabel.setMouseTransparent(true);

        seekBarPane.getChildren().addAll(
                seekInactiveTrack,
                seekActiveTrack,
                seekThumb,
                aMarkerLine,
                bMarkerLine,
                aMarkerLabel,
                bMarkerLabel
        );

        seekBarPane.widthProperty().addListener((obs, oldWidth, newWidth) -> layoutSeekBar());
        seekBarPane.heightProperty().addListener((obs, oldHeight, newHeight) -> layoutSeekBar());

        setSeekVisualsVisible(false);
    }

    private void styleLabels(Pane pane) {
        pane.lookupAll(".label").forEach(n -> {
            if (n != aMarkerLabel && n != bMarkerLabel) {
                n.setStyle("-fx-text-fill: #d6d9df;");
            }
        });
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

        mediaDuration = Duration.ZERO;
        pointA = Duration.ZERO;
        pointB = Duration.ZERO;
        pendingSeekMillis = 0;
        currentTimeLabel.setText("00:00:00.000");
        totalTimeLabel.setText("00:00:00.000");
        aField.setText("00:00:00.000");
        bField.setText("00:00:00.000");
        setSeekVisualsVisible(false);

        try {
            Media media = new Media(file.toURI().toString());
            mediaPlayer = new MediaPlayer(media);
            mediaView.setMediaPlayer(mediaPlayer);
            mediaPlayer.setVolume(volumeSlider.getValue() / 100.0);

            fileLabel.setText(file.getName());

            mediaPlayer.setOnReady(() -> {
                mediaDuration = mediaPlayer.getTotalDuration();
                pointA = Duration.ZERO;
                pointB = mediaDuration;
                pendingSeekMillis = pointA.toMillis();

                aField.setText(formatDuration(pointA));
                bField.setText(formatDuration(pointB));
                totalTimeLabel.setText(formatDuration(mediaDuration));

                setSeekVisualsVisible(true);
                layoutSeekBar();
                mediaPlayer.play();
            });

            mediaPlayer.currentTimeProperty().addListener((obs, oldTime, newTime) -> {
                currentTimeLabel.setText(formatDuration(newTime));

                if (!userSeeking) {
                    layoutSeekBar();
                }

                if (pointB.greaterThan(pointA)
                        && newTime.greaterThanOrEqualTo(pointB)) {
                    mediaPlayer.seek(pointA);
                    if (mediaPlayer.getStatus() != MediaPlayer.Status.PLAYING) {
                        mediaPlayer.play();
                    }
                }
            });

            mediaPlayer.setOnEndOfMedia(() -> {
                if (pointB.greaterThan(pointA)) {
                    mediaPlayer.seek(pointA);
                    mediaPlayer.play();
                }
            });

            mediaPlayer.setOnError(() -> showMediaError(mediaPlayer.getError()));
            media.setOnError(() -> showMediaError(media.getError()));

        } catch (Exception ex) {
            System.err.println("Could not open video: " + ex.getMessage());
        }
    }

    private void installSeekBehavior() {
        seekTooltip = new Tooltip("00:00:00.000");
        seekTooltip.setAutoHide(false);

        seekBarPane.setOnMouseEntered(e -> {
            if (!userSeeking && isMediaReady()) {
                showSeekTooltip(e.getScreenX(), e.getScreenY(), fullTimelineTimeAt(e.getX()));
            }
        });

        seekBarPane.setOnMouseMoved(e -> {
            if (!userSeeking && isMediaReady()) {
                showSeekTooltip(e.getScreenX(), e.getScreenY(), fullTimelineTimeAt(e.getX()));
            }
        });

        seekBarPane.setOnMouseExited(e -> {
            if (!userSeeking) {
                seekTooltip.hide();
            }
        });

        seekBarPane.setOnMousePressed(e -> {
            if (e.getButton() != MouseButton.PRIMARY || !isMediaReady()) return;

            double aX = timeToX(pointA);
            double bX = timeToX(pointB);
            if (e.getX() < aX || e.getX() > bX) {
                e.consume();
                return;
            }

            userSeeking = true;
            pendingSeekMillis = segmentTimeAt(e.getX()).toMillis();
            layoutSeekBar();
            showSeekTooltip(e.getScreenX(), e.getScreenY(), Duration.millis(pendingSeekMillis));
            e.consume();
        });

        seekBarPane.setOnMouseDragged(e -> {
            if (!userSeeking || !isMediaReady()) return;

            pendingSeekMillis = segmentTimeAt(e.getX()).toMillis();
            layoutSeekBar();
            showSeekTooltip(e.getScreenX(), e.getScreenY(), Duration.millis(pendingSeekMillis));
            e.consume();
        });

        seekBarPane.setOnMouseReleased(e -> {
            if (!userSeeking) return;

            if (mediaPlayer != null) {
                mediaPlayer.seek(Duration.millis(pendingSeekMillis));
            }

            userSeeking = false;
            layoutSeekBar();

            if (seekBarPane.isHover()) {
                showSeekTooltip(e.getScreenX(), e.getScreenY(), fullTimelineTimeAt(e.getX()));
            } else {
                seekTooltip.hide();
            }
            e.consume();
        });
    }

    private Duration fullTimelineTimeAt(double mouseX) {
        if (!isMediaReady() || seekBarPane.getWidth() <= 0) return Duration.ZERO;

        double ratio = clamp01(mouseX / seekBarPane.getWidth());
        return Duration.millis(ratio * mediaDuration.toMillis());
    }

    private Duration segmentTimeAt(double mouseX) {
        if (!isMediaReady()) return pointA;

        double aX = timeToX(pointA);
        double bX = timeToX(pointB);
        double clampedX = Math.max(aX, Math.min(mouseX, bX));

        if (bX <= aX) return pointA;

        double ratio = (clampedX - aX) / (bX - aX);
        double millis = pointA.toMillis()
                + ratio * (pointB.toMillis() - pointA.toMillis());
        return Duration.millis(millis);
    }

    private void showSeekTooltip(double screenX, double screenY, Duration time) {
        seekTooltip.setText(formatDuration(time));
        seekTooltip.show(seekBarPane.getScene().getWindow(), screenX + 10, screenY - 35);
    }

    private void layoutSeekBar() {
        double width = seekBarPane.getWidth();
        if (width <= 0) return;

        double trackY = SEEK_TRACK_CENTER_Y - SEEK_TRACK_HEIGHT / 2.0;
        seekInactiveTrack.resizeRelocate(0, trackY, width, SEEK_TRACK_HEIGHT);

        if (!isMediaReady()) {
            setSeekVisualsVisible(false);
            return;
        }

        setSeekVisualsVisible(true);

        double aX = timeToX(pointA);
        double bX = timeToX(pointB);

        seekActiveTrack.resizeRelocate(
                aX,
                trackY,
                Math.max(0, bX - aX),
                SEEK_TRACK_HEIGHT
        );

        double currentMillis = userSeeking
                ? pendingSeekMillis
                : mediaPlayer.getCurrentTime().toMillis();
        currentMillis = Math.max(pointA.toMillis(), Math.min(currentMillis, pointB.toMillis()));

        double thumbX = timeToX(Duration.millis(currentMillis));
        seekThumb.resizeRelocate(
                thumbX - SEEK_THUMB_SIZE / 2.0,
                SEEK_TRACK_CENTER_Y - SEEK_THUMB_SIZE / 2.0,
                SEEK_THUMB_SIZE,
                SEEK_THUMB_SIZE
        );

        positionMarker(aMarkerLine, aMarkerLabel, aX);
        positionMarker(bMarkerLine, bMarkerLabel, bX);
    }

    private void positionMarker(Region line, Label label, double x) {
        line.resizeRelocate(
                x - 1,
                SEEK_TRACK_CENTER_Y - SEEK_MARKER_LINE_HEIGHT / 2.0,
                2,
                SEEK_MARKER_LINE_HEIGHT
        );

        label.applyCss();
        label.autosize();

        double labelWidth = label.prefWidth(-1);
        double labelX = x - labelWidth / 2.0;
        labelX = Math.max(0, Math.min(labelX, seekBarPane.getWidth() - labelWidth));

        label.relocate(labelX, SEEK_TRACK_CENTER_Y + SEEK_MARKER_LINE_HEIGHT / 2.0);
    }

    private double timeToX(Duration time) {
        if (!isMediaReady() || seekBarPane.getWidth() <= 0) return 0;

        double ratio = clamp01(time.toMillis() / mediaDuration.toMillis());
        return ratio * seekBarPane.getWidth();
    }

    private void setSeekVisualsVisible(boolean visible) {
        seekActiveTrack.setVisible(visible);
        seekThumb.setVisible(visible);
        aMarkerLine.setVisible(visible);
        bMarkerLine.setVisible(visible);
        aMarkerLabel.setVisible(visible);
        bMarkerLabel.setVisible(visible);
    }

    private boolean isMediaReady() {
        return mediaPlayer != null
                && mediaDuration != null
                && !mediaDuration.isUnknown()
                && !mediaDuration.isIndefinite()
                && mediaDuration.greaterThan(Duration.ZERO);
    }

    private double clamp01(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private void configureTimestampField(TextField field, double width) {
        field.setMinWidth(width);
        field.setPrefWidth(width);
        field.setMaxWidth(width);
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

    private Region createMarkerLine() {
        Region line = new Region();
        line.setStyle("-fx-background-color: #00c853;");
        return line;
    }

    private Label createMarkerLabel(String text) {
        Label label = new Label(text);
        label.setStyle(
                "-fx-text-fill: #00c853;" +
                "-fx-font-size: 9px;" +
                "-fx-font-weight: bold;" +
                "-fx-background-color: rgba(0, 0, 0, 0.75);" +
                "-fx-padding: 0 2 0 2;"
        );
        return label;
    }

    private void configureVolumeSliderAppearance() {
        applyVolumeSliderOutline();
        volumeSlider.focusedProperty().addListener((obs, oldValue, newValue) ->
                Platform.runLater(this::applyVolumeSliderOutline)
        );
        volumeSlider.skinProperty().addListener((obs, oldSkin, newSkin) ->
                Platform.runLater(this::applyVolumeSliderOutline)
        );
    }

    private void applyVolumeSliderOutline() {
        Region track = (Region) volumeSlider.lookup(".track");
        if (track != null) {
            track.setStyle(
                    "-fx-background-color: #b8b8b8;" +
                    "-fx-border-color: black;" +
                    "-fx-border-width: 1;" +
                    "-fx-background-radius: 2;" +
                    "-fx-border-radius: 2;"
            );
        }

        Region thumb = (Region) volumeSlider.lookup(".thumb");
        if (thumb != null) {
            thumb.setStyle(
                    "-fx-background-color: #f4f4f4;" +
                    "-fx-border-color: black;" +
                    "-fx-border-width: 1;" +
                    "-fx-background-radius: 20;" +
                    "-fx-border-radius: 20;"
            );
        }
    }

    private void togglePlayPause() {
        if (mediaPlayer == null) return;

        MediaPlayer.Status status = mediaPlayer.getStatus();
        if (status == MediaPlayer.Status.PLAYING) {
            mediaPlayer.pause();
        } else {
            if (pointB.greaterThan(pointA)
                    && mediaPlayer.getCurrentTime().greaterThanOrEqualTo(pointB)) {
                mediaPlayer.seek(pointA);
            }
            mediaPlayer.play();
        }
    }

    private void seekBySeconds(double seconds) {
        if (!isMediaReady()) return;

        double targetMillis = mediaPlayer.getCurrentTime().toMillis() + seconds * 1000.0;
        targetMillis = Math.max(
                pointA.toMillis(),
                Math.min(targetMillis, pointB.toMillis())
        );
        mediaPlayer.seek(Duration.millis(targetMillis));
    }

    private void stepFrame(int direction) {
        if (mediaPlayer == null) return;

        mediaPlayer.pause();
        seekBySeconds(direction * FRAME_STEP_SECONDS);
    }

    private void setPointAFromCurrent() {
        if (mediaPlayer == null) return;

        Duration candidate = mediaPlayer.getCurrentTime();
        if (pointB.greaterThan(candidate)) {
            pointA = candidate;
            aField.setText(formatDuration(pointA));
            validateLoopRange();
        }
    }

    private void setPointBFromCurrent() {
        if (mediaPlayer == null) return;

        Duration candidate = mediaPlayer.getCurrentTime();
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

    private void validateLoopRange() {
        if (mediaPlayer == null) return;

        layoutSeekBar();

        if (!pointB.greaterThan(pointA)) return;

        Duration current = mediaPlayer.getCurrentTime();
        if (current.lessThan(pointA) || current.greaterThanOrEqualTo(pointB)) {
            mediaPlayer.seek(pointA);
        }
    }

    private void clearPointA() {
        if (mediaPlayer == null) return;

        pointA = Duration.ZERO;
        aField.setText(formatDuration(pointA));
        validateLoopRange();
    }

    private void clearPointB() {
        if (mediaPlayer == null) return;

        pointB = mediaDuration;
        bField.setText(formatDuration(pointB));
        validateLoopRange();
    }

    private void showMediaError(Throwable error) {
        String message = error == null ? "Unknown media error" : error.getMessage();
        System.err.println("Media error: " + message);
    }

    private void handleVolumeScroll(ScrollEvent e) {
        if (e.getDeltaY() == 0) return;

        double step = e.getDeltaY() > 0 ? 5 : -5;
        volumeSlider.setValue(Math.max(0, Math.min(100, volumeSlider.getValue() + step)));
        e.consume();
    }

    private void handleKeyboard(KeyEvent e) {
        if (e.getTarget() instanceof TextInputControl) return;

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
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Empty time");
        }

        String normalized = text.trim().replace(',', '.');
        String[] parts = normalized.split(":");
        if (parts.length < 1 || parts.length > 3) {
            throw new IllegalArgumentException("Bad format");
        }

        double seconds;
        try {
            if (parts.length == 3) {
                seconds = Integer.parseInt(parts[0]) * 3600.0
                        + Integer.parseInt(parts[1]) * 60.0
                        + Double.parseDouble(parts[2]);
            } else if (parts.length == 2) {
                seconds = Integer.parseInt(parts[0]) * 60.0
                        + Double.parseDouble(parts[1]);
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
        if (duration == null || duration.isUnknown() || duration.isIndefinite()) {
            return "00:00:00.000";
        }

        long totalMillis = Math.max(0, Math.round(duration.toMillis()));
        long totalSeconds = totalMillis / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        long millis = totalMillis % 1000;

        return String.format(
                Locale.ROOT,
                "%02d:%02d:%02d.%03d",
                hours,
                minutes,
                seconds,
                millis
        );
    }

    private void setWindowIcon(Stage stage) {
        try (InputStream resource = getClass().getResourceAsStream("/app-icon.b64")) {
            if (resource == null) return;

            String encoded = new String(
                    resource.readAllBytes(),
                    StandardCharsets.US_ASCII
            ).trim();

            byte[] iconBytes = Base64.getDecoder().decode(encoded);
            stage.getIcons().add(new Image(new ByteArrayInputStream(iconBytes)));

        } catch (Exception ex) {
            System.err.println("Could not load application icon: " + ex.getMessage());
        }
    }

    private void disposePlayer() {
        if (seekTooltip != null) {
            seekTooltip.hide();
        }

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
