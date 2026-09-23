package hu.hazazs.abplayer;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.javafx.videosurface.ImageViewVideoSurface;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;

import java.io.File;
import java.util.Locale;

public class VideoLoopPlayer extends Application {

    private static final double FALLBACK_FRAME_STEP_SECONDS = 1.0 / 30.0;

    private MediaPlayerFactory mediaPlayerFactory;
    private EmbeddedMediaPlayer mediaPlayer;
    private final ImageView mediaView = new ImageView();

    private final Slider seekSlider = new Slider(0, 1, 0);
    private final Slider volumeSlider = new Slider(0, 100, 75);
    private final Label currentTimeLabel = new Label("00:00:00");
    private final Label totalTimeLabel = new Label("00:00:00");
    private final Label fileLabel = new Label("No video loaded");
    private final Label statusLabel = new Label("Open a video to begin.");

    private final Button playPauseButton = new Button("▶");
    private final Button stopButton = new Button("■");

    private final TextField aField = new TextField("00:00:00");
    private final TextField bField = new TextField("00:00:00");
    private final CheckBox loopCheckBox = new CheckBox("Loop A–B");

    private Duration pointA = Duration.ZERO;
    private Duration pointB = Duration.ZERO;
    private Duration mediaDuration = Duration.ZERO;
    private boolean userSeeking;
    private volatile boolean closing;

    @Override
    public void start(Stage stage) {
        stage.setTitle("A–B Loop Video Player");

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
        openButton.setDisable(true);
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

        playPauseButton.setDisable(true);
        stopButton.setDisable(true);

        playPauseButton.setMinSize(42, 36);
        playPauseButton.setPrefSize(42, 36);
        playPauseButton.setMaxSize(42, 36);

        stopButton.setMinSize(42, 36);
        stopButton.setPrefSize(42, 36);
        stopButton.setMaxSize(42, 36);

        playPauseButton.setOnAction(e -> togglePlayPause());
        stopButton.setOnAction(e -> stopPlayback());

        volumeSlider.setPrefWidth(120);
        volumeSlider.valueProperty().addListener((obs, oldV, newV) -> {
            if (mediaPlayer != null) {
                mediaPlayer.audio().setVolume(newV.intValue());
            }
        });

        Region playbackSpacer = new Region();
        HBox.setHgrow(playbackSpacer, Priority.ALWAYS);

        HBox playbackRow = new HBox(8,
                playPauseButton, stopButton, playbackSpacer, volumeSlider);
        playbackRow.setAlignment(Pos.CENTER_LEFT);

        aField.setPrefColumnCount(10);
        bField.setPrefColumnCount(10);

        Button setAButton = new Button("Set A = current");
        Button setBButton = new Button("Set B = current");
        Button goAButton = new Button("Go to A");
        Button clearLoopButton = new Button("Clear A/B");

        setAButton.setOnAction(e -> setPointAFromCurrent());
        setBButton.setOnAction(e -> setPointBFromCurrent());
        goAButton.setOnAction(e -> seekTo(pointA));
        clearLoopButton.setOnAction(e -> clearLoop());

        aField.setOnAction(e -> applyTypedPoints());
        bField.setOnAction(e -> applyTypedPoints());
        aField.focusedProperty().addListener((obs, was, is) -> {
            if (was && !is) applyTypedPoints();
        });
        bField.focusedProperty().addListener((obs, was, is) -> {
            if (was && !is) applyTypedPoints();
        });
        loopCheckBox.setOnAction(e -> validateLoopState());

        GridPane loopGrid = new GridPane();
        loopGrid.setHgap(8);
        loopGrid.setVgap(8);
        loopGrid.add(new Label("A"), 0, 0);
        loopGrid.add(aField, 1, 0);
        loopGrid.add(setAButton, 2, 0);
        loopGrid.add(new Label("B"), 0, 1);
        loopGrid.add(bField, 1, 1);
        loopGrid.add(setBButton, 2, 1);
        loopGrid.add(loopCheckBox, 3, 0);
        loopGrid.add(goAButton, 3, 1);
        loopGrid.add(clearLoopButton, 4, 1);

        Label loopHint = new Label("Example: A = 00:01:00, B = 00:06:00");
        loopHint.setStyle("-fx-text-fill: #9ea4ae;");
        statusLabel.setStyle("-fx-text-fill: #c6cad1;");

        statusLabel.setText("Initializing VLC…");

        VBox controls = new VBox(10, timeRow, playbackRow, new Separator(), loopGrid, loopHint, statusLabel);
        controls.setPadding(new Insets(10, 12, 12, 12));
        controls.setStyle("-fx-background-color: #22252b; -fx-text-fill: white;");
        styleLabels(controls);
        root.setBottom(controls);

        installSeekBehavior();

        Scene scene = new Scene(root, 1000, 700);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::handleKeyboard);
        stage.setScene(scene);
        stage.setMinWidth(760);
        stage.setMinHeight(560);

        // Maximized fills the usable desktop while keeping the Windows taskbar visible.
        stage.setMaximized(true);
        stage.show();

        initialiseVlcAsync(openButton);

        stage.setOnCloseRequest(e -> {
            closing = true;
            disposePlayer();
        });
    }

    private void initialiseVlcAsync(Button openButton) {
        Thread initThread = new Thread(() -> {
            MediaPlayerFactory factory = null;
            EmbeddedMediaPlayer player = null;

            try {
                // MediaPlayerFactory performs VLC native discovery automatically.
                factory = new MediaPlayerFactory();
                player = factory.mediaPlayers().newEmbeddedMediaPlayer();

                MediaPlayerFactory readyFactory = factory;
                EmbeddedMediaPlayer readyPlayer = player;

                Platform.runLater(() -> {
                    if (closing) {
                        releasePlayer(readyPlayer, readyFactory);
                        return;
                    }

                    mediaPlayerFactory = readyFactory;
                    mediaPlayer = readyPlayer;
                    mediaPlayer.videoSurface().set(new ImageViewVideoSurface(mediaView));
                    mediaPlayer.audio().setVolume((int) volumeSlider.getValue());
                    attachMediaPlayerEvents();

                    openButton.setDisable(false);
                    statusLabel.setText("Open a video to begin.");
                });
            } catch (Throwable ex) {
                releasePlayer(player, factory);

                String detail = ex.getMessage();
                if (detail == null || detail.isBlank()) {
                    detail = ex.getClass().getSimpleName();
                }
                String message = detail;

                Platform.runLater(() -> {
                    if (!closing) {
                        openButton.setDisable(true);
                        statusLabel.setText("VLC initialization failed: " + message);
                    }
                });
            }
        }, "vlc-init");

        initThread.setDaemon(true);
        initThread.start();
    }

    private void attachMediaPlayerEvents() {
        mediaPlayer.events().addMediaPlayerEventListener(new MediaPlayerEventAdapter() {
            @Override
            public void lengthChanged(MediaPlayer mediaPlayer, long newLength) {
                Platform.runLater(() -> onLengthChanged(newLength));
            }

            @Override
            public void timeChanged(MediaPlayer mediaPlayer, long newTime) {
                Platform.runLater(() -> onTimeChanged(newTime));
            }

            @Override
            public void playing(MediaPlayer mediaPlayer) {
                Platform.runLater(VideoLoopPlayer.this::updatePlayButton);
            }

            @Override
            public void paused(MediaPlayer mediaPlayer) {
                Platform.runLater(VideoLoopPlayer.this::updatePlayButton);
            }

            @Override
            public void stopped(MediaPlayer mediaPlayer) {
                Platform.runLater(VideoLoopPlayer.this::updatePlayButton);
            }

            @Override
            public void finished(MediaPlayer mediaPlayer) {
                Platform.runLater(VideoLoopPlayer.this::onFinished);
            }

            @Override
            public void error(MediaPlayer mediaPlayer) {
                Platform.runLater(() ->
                        statusLabel.setText("VLC could not play this video."));
            }
        });
    }

    private void releasePlayer(EmbeddedMediaPlayer player, MediaPlayerFactory factory) {
        if (player != null) {
            try {
                player.release();
            } catch (Throwable ignored) {
            }
        }

        if (factory != null) {
            try {
                factory.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private void styleLabels(Pane pane) {
        pane.lookupAll(".label").forEach(n -> n.setStyle("-fx-text-fill: #d6d9df;"));
        statusLabel.setStyle("-fx-text-fill: #c6cad1;");
    }

    private void openVideo(Stage stage) {
        if (mediaPlayer == null) return;

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open Video");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Video files", "*.mp4", "*.m4v", "*.avi"),
                new FileChooser.ExtensionFilter("MP4 / M4V", "*.mp4", "*.m4v"),
                new FileChooser.ExtensionFilter("AVI", "*.avi"),
                new FileChooser.ExtensionFilter("All files", "*.*")
        );

        File file = chooser.showOpenDialog(stage);
        if (file == null) return;

        resetPlayerUi();
        fileLabel.setText(file.getName());
        statusLabel.setText("Loading video…");

        String path = file.getAbsolutePath();
        Thread loader = new Thread(() -> {
            boolean started = mediaPlayer.media().startPaused(path);
            if (!started) {
                Platform.runLater(() -> statusLabel.setText("Could not open video."));
            }
        }, "video-loader");
        loader.setDaemon(true);
        loader.start();
    }

    private void resetPlayerUi() {
        playPauseButton.setDisable(true);
        stopButton.setDisable(true);
        seekSlider.setDisable(true);
        seekSlider.setValue(0);
        currentTimeLabel.setText("00:00:00");
        totalTimeLabel.setText("00:00:00");
        mediaDuration = Duration.ZERO;
        pointA = Duration.ZERO;
        pointB = Duration.ZERO;
        aField.setText("00:00:00");
        bField.setText("00:00:00");
        loopCheckBox.setSelected(false);
    }

    private void onLengthChanged(long newLength) {
        if (newLength <= 0) return;

        mediaDuration = Duration.millis(newLength);
        pointA = Duration.ZERO;
        pointB = mediaDuration;

        aField.setText(formatDuration(pointA));
        bField.setText(formatDuration(pointB));
        totalTimeLabel.setText(formatDuration(mediaDuration));

        seekSlider.setMin(0);
        seekSlider.setMax(Math.max(1, newLength));
        seekSlider.setValue(Math.max(0, mediaPlayer.status().time()));
        seekSlider.setDisable(false);

        playPauseButton.setDisable(false);
        stopButton.setDisable(false);

        statusLabel.setText("Ready. Set A and B, then enable Loop A–B.");
        updatePlayButton();
    }

    private void onTimeChanged(long newTime) {
        if (!userSeeking) {
            seekSlider.setValue(newTime);
        }
        currentTimeLabel.setText(formatDuration(Duration.millis(newTime)));

        if (loopCheckBox.isSelected()
                && pointB.greaterThan(pointA)
                && newTime >= Math.round(pointB.toMillis())) {
            mediaPlayer.controls().setTime(Math.round(pointA.toMillis()));
            if (!mediaPlayer.status().isPlaying()) {
                mediaPlayer.controls().play();
            }
        }
    }

    private void onFinished() {
        if (loopCheckBox.isSelected() && pointB.greaterThan(pointA)) {
            mediaPlayer.controls().setTime(Math.round(pointA.toMillis()));
            mediaPlayer.controls().play();
        } else {
            updatePlayButton();
        }
    }

    private void installSeekBehavior() {
        seekSlider.setOnMousePressed(e -> userSeeking = true);
        seekSlider.setOnMouseDragged(e -> userSeeking = true);
        seekSlider.setOnMouseReleased(e -> {
            if (mediaPlayer != null) {
                mediaPlayer.controls().setTime(Math.round(seekSlider.getValue()));
            }
            userSeeking = false;
        });
        seekSlider.valueChangingProperty().addListener((obs, was, changing) -> {
            userSeeking = changing;
            if (!changing && mediaPlayer != null) {
                mediaPlayer.controls().setTime(Math.round(seekSlider.getValue()));
            }
        });
    }

    private void togglePlayPause() {
        if (mediaPlayer == null || mediaDuration.lessThanOrEqualTo(Duration.ZERO)) return;

        if (mediaPlayer.status().isPlaying()) {
            mediaPlayer.controls().pause();
        } else {
            long currentTime = mediaPlayer.status().time();
            if (loopCheckBox.isSelected()
                    && pointB.greaterThan(pointA)
                    && currentTime >= Math.round(pointB.toMillis())) {
                mediaPlayer.controls().setTime(Math.round(pointA.toMillis()));
            }
            mediaPlayer.controls().play();
        }

        updatePlayButton();
    }

    private void stopPlayback() {
        if (mediaPlayer == null) return;

        mediaPlayer.controls().setPause(true);
        mediaPlayer.controls().setTime(
                Math.round((loopCheckBox.isSelected() ? pointA : Duration.ZERO).toMillis())
        );
        updatePlayButton();
    }

    private void seekBySeconds(double seconds) {
        if (mediaPlayer == null || mediaDuration.lessThanOrEqualTo(Duration.ZERO)) return;

        long target = mediaPlayer.status().time() + Math.round(seconds * 1000.0);
        target = Math.max(0, Math.min(target, Math.round(mediaDuration.toMillis())));
        mediaPlayer.controls().setTime(target);
    }

    private void stepFrame(int direction) {
        if (mediaPlayer == null) return;

        mediaPlayer.controls().setPause(true);

        if (direction > 0) {
            mediaPlayer.controls().nextFrame();
        } else {
            seekBySeconds(-FALLBACK_FRAME_STEP_SECONDS);
        }

        updatePlayButton();
    }

    private void setPointAFromCurrent() {
        if (mediaPlayer == null) return;
        pointA = Duration.millis(Math.max(0, mediaPlayer.status().time()));
        aField.setText(formatDuration(pointA));
        validateLoopState();
    }

    private void setPointBFromCurrent() {
        if (mediaPlayer == null) return;
        pointB = Duration.millis(Math.max(0, mediaPlayer.status().time()));
        bField.setText(formatDuration(pointB));
        validateLoopState();
    }

    private void applyTypedPoints() {
        if (mediaPlayer == null || mediaDuration.lessThanOrEqualTo(Duration.ZERO)) return;

        try {
            Duration a = parseDuration(aField.getText());
            Duration b = parseDuration(bField.getText());
            a = clamp(a, Duration.ZERO, mediaDuration);
            b = clamp(b, Duration.ZERO, mediaDuration);
            pointA = a;
            pointB = b;
            aField.setText(formatDuration(pointA));
            bField.setText(formatDuration(pointB));
            validateLoopState();
        } catch (IllegalArgumentException ex) {
            statusLabel.setText("Invalid time. Use HH:MM:SS or HH:MM:SS.mmm");
        }
    }

    private void validateLoopState() {
        if (mediaPlayer == null) {
            loopCheckBox.setSelected(false);
            return;
        }

        if (!pointB.greaterThan(pointA)) {
            loopCheckBox.setSelected(false);
            statusLabel.setText("B must be later than A.");
            return;
        }

        if (loopCheckBox.isSelected()) {
            statusLabel.setText("A–B loop active: " + formatDuration(pointA) + " → " + formatDuration(pointB));
            long currentTime = mediaPlayer.status().time();
            if (currentTime < Math.round(pointA.toMillis())
                    || currentTime >= Math.round(pointB.toMillis())) {
                mediaPlayer.controls().setTime(Math.round(pointA.toMillis()));
            }
        } else {
            statusLabel.setText("A–B loop is off.");
        }
    }

    private void clearLoop() {
        if (mediaPlayer == null) return;
        pointA = Duration.ZERO;
        pointB = mediaDuration;
        aField.setText(formatDuration(pointA));
        bField.setText(formatDuration(pointB));
        loopCheckBox.setSelected(false);
        statusLabel.setText("A/B reset to the whole video.");
    }

    private void seekTo(Duration target) {
        if (mediaPlayer != null) {
            mediaPlayer.controls().setTime(Math.round(target.toMillis()));
        }
    }

    private void updatePlayButton() {
        playPauseButton.setText(
                mediaPlayer != null && mediaPlayer.status().isPlaying() ? "⏸" : "▶"
        );
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
        if (duration == null || duration.isUnknown() || duration.isIndefinite()) return "00:00:00";

        long totalMillis = Math.max(0, Math.round(duration.toMillis()));
        long totalSeconds = totalMillis / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        long millis = totalMillis % 1000;

        if (millis == 0) {
            return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds);
        }

        return String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", hours, minutes, seconds, millis);
    }

    private void disposePlayer() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.controls().stop();
                mediaPlayer.release();
            } catch (RuntimeException ignored) {
            }
            mediaPlayer = null;
        }

        if (mediaPlayerFactory != null) {
            try {
                mediaPlayerFactory.release();
            } catch (RuntimeException ignored) {
            }
            mediaPlayerFactory = null;
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
