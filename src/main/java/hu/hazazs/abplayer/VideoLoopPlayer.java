package hu.hazazs.abplayer;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.ScrollEvent;
import javafx.scene.image.Image;
import javafx.scene.layout.*;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
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

    private MediaPlayer mediaPlayer;
    private final MediaView mediaView = new MediaView();

    private final Slider seekSlider = new Slider(0, 1, 0);
    private final Slider volumeSlider = new Slider(0, 100, 75);
    private final Label currentTimeLabel = new Label("00:00:00.000");
    private final Label totalTimeLabel = new Label("00:00:00.000");
    private final Label fileLabel = new Label("No video loaded");

    private final TextField aField = new TextField("00:00:00.000");
    private final TextField bField = new TextField("00:00:00.000");

    private Duration pointA = Duration.ZERO;
    private Duration pointB = Duration.ZERO;
    private Duration mediaDuration = Duration.ZERO;
    private boolean userSeeking;

    @Override
    public void start(Stage stage) {
        stage.setTitle("A–B Loop Video Player");
        setWindowIcon(stage);

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #17191d;");

        StackPane videoPane = new StackPane(mediaView);
        videoPane.setStyle("-fx-background-color: black;");
        videoPane.setMinHeight(360);

        mediaView.setPreserveRatio(true);
        mediaView.fitWidthProperty().bind(videoPane.widthProperty());
        mediaView.fitHeightProperty().bind(videoPane.heightProperty());

        videoPane.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                togglePlayPause();
            }
        });
        root.setCenter(videoPane);

        Button openButton = new Button("Open");
        openButton.setOnAction(e -> openVideo(stage));

        fileLabel.setStyle("-fx-text-fill: #d6d9df;");
        fileLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(fileLabel, Priority.ALWAYS);

        HBox topBar = new HBox(12, openButton, fileLabel);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setPadding(new Insets(10, 12, 8, 12));
        topBar.setStyle("-fx-background-color: #22252b;");
        root.setTop(topBar);

        seekSlider.setDisable(true);
        seekSlider.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(seekSlider, Priority.ALWAYS);

        HBox timeRow = new HBox(8, currentTimeLabel, seekSlider, totalTimeLabel);
        timeRow.setAlignment(Pos.CENTER);

        volumeSlider.setPrefWidth(120);
        volumeSlider.valueProperty().addListener((obs, oldV, newV) -> {
            if (mediaPlayer != null) {
                mediaPlayer.setVolume(newV.doubleValue() / 100.0);
            }
        });

        Region playbackSpacer = new Region();
        HBox.setHgrow(playbackSpacer, Priority.ALWAYS);

        HBox playbackRow = new HBox(8, playbackSpacer, volumeSlider);
        playbackRow.setAlignment(Pos.CENTER_LEFT);

        aField.setPrefColumnCount(12);
        bField.setPrefColumnCount(12);

        Button setAButton = new Button("Set");
        Button clearAButton = new Button("Clear");
        Button setBButton = new Button("Set");
        Button clearBButton = new Button("Clear");

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

        Scene scene = new Scene(root, 1000, 700);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::handleKeyboard);
        scene.addEventFilter(ScrollEvent.SCROLL, this::handleVolumeScroll);
        stage.setScene(scene);
        stage.setMinWidth(760);
        stage.setMinHeight(560);

        // Maximized fills the usable desktop while keeping the taskbar visible.
        stage.setMaximized(true);
        stage.show();

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

            fileLabel.setText(file.getName());
            seekSlider.setDisable(true);

            mediaPlayer.setOnReady(() -> {
                mediaDuration = mediaPlayer.getTotalDuration();
                pointA = Duration.ZERO;
                pointB = mediaDuration;
                aField.setText(formatDuration(pointA));
                bField.setText(formatDuration(pointB));
                totalTimeLabel.setText(formatDuration(mediaDuration));
                seekSlider.setMin(0);
                seekSlider.setMax(Math.max(1, mediaDuration.toMillis()));
                seekSlider.setValue(0);
                seekSlider.setDisable(false);
                mediaPlayer.play();
            });

            mediaPlayer.currentTimeProperty().addListener((obs, oldTime, newTime) -> {
                if (!userSeeking) {
                    seekSlider.setValue(newTime.toMillis());
                }
                currentTimeLabel.setText(formatDuration(newTime));

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
        Tooltip seekTooltip = new Tooltip("00:00:00.000");
        seekTooltip.setShowDelay(Duration.ZERO);
        seekTooltip.setShowDuration(Duration.INDEFINITE);
        Tooltip.install(seekSlider, seekTooltip);

        seekSlider.setOnMouseMoved(e -> {
            double width = seekSlider.getWidth();
            if (width <= 0) return;

            double ratio = Math.max(0, Math.min(1, e.getX() / width));
            double hoverMillis = seekSlider.getMin()
                    + ratio * (seekSlider.getMax() - seekSlider.getMin());
            seekTooltip.setText(formatDuration(Duration.millis(hoverMillis)));
        });

        seekSlider.setOnMousePressed(e -> userSeeking = true);
        seekSlider.setOnMouseDragged(e -> userSeeking = true);
        seekSlider.setOnMouseReleased(e -> {
            if (mediaPlayer != null) {
                mediaPlayer.seek(Duration.millis(seekSlider.getValue()));
            }
            userSeeking = false;
        });
        seekSlider.valueChangingProperty().addListener((obs, was, changing) -> {
            userSeeking = changing;
            if (!changing && mediaPlayer != null) {
                mediaPlayer.seek(Duration.millis(seekSlider.getValue()));
            }
        });
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
        if (mediaPlayer == null || mediaDuration.isUnknown() || mediaDuration.isIndefinite()) return;

        double targetMillis = mediaPlayer.getCurrentTime().toMillis() + seconds * 1000.0;
        targetMillis = Math.max(0, Math.min(targetMillis, mediaDuration.toMillis()));
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
