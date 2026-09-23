package com.example.abplayer;

import javafx.application.Application;

/**
 * Plain Java entry point.
 *
 * Keeping the JVM entry point separate from the class that extends
 * javafx.application.Application avoids the Java launcher's special JavaFX
 * detection path, which can otherwise report:
 * "JavaFX runtime components are missing, and are required to run this application"
 * when JavaFX is supplied as Maven dependencies.
 */
public final class AppLauncher {

    private AppLauncher() {
    }

    public static void main(String[] args) {
        Application.launch(VideoLoopPlayer.class, args);
    }
}
