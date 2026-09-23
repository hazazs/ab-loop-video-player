# A–B Loop Video Player

Small Java 21 / JavaFX desktop MP4 player with repeat-between-two-times support.

## Features

- Open MP4 / M4V video
- Play / pause / stop
- Seek slider
- Jump backward / forward
- Volume control
- Set loop start **A** from the current playback position
- Set loop end **B** from the current playback position
- Type A/B manually using `HH:MM:SS` or `HH:MM:SS.mmm`
- Enable/disable continuous **A–B loop**
- Jump directly to A
- Keyboard shortcuts: `Space` = Play/Pause, `Left` / `Right` = ±5 seconds

## Example

For a one-hour video, to repeat minutes 1 through 6:

- A: `00:01:00`
- B: `00:06:00`
- Tick **Loop A–B**
- Press **Play**

## Requirements

- JDK 21
- Maven 3.9+

You do **not** need to install JavaFX separately. Maven downloads the correct JavaFX libraries.

## Run on Windows

Double-click:

    run.bat

or from a terminal in the project directory:

    mvn javafx:run

## IntelliJ IDEA / other IDEs

Import the project as a **Maven project**, wait for Maven dependencies to finish loading, and run:

    com.example.abplayer.AppLauncher

Do **not** run `VideoLoopPlayer` directly. `VideoLoopPlayer` extends JavaFX `Application`; `AppLauncher` is the normal JVM entry point and avoids the common "JavaFX runtime components are missing" launcher error.

## macOS / Linux

    ./run.sh

or:

    mvn javafx:run

## MP4 compatibility

JavaFX Media supports common MP4 files containing H.264/AVC video and AAC audio. An MP4 using a different codec may not play even though its extension is `.mp4`.
