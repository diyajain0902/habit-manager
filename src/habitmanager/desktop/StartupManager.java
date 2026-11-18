package habitmanager.desktop;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * "Start with Windows": puts a small HabitManager.cmd in the user's Startup folder that
 * launches the app in the background (javaw, no console window, no browser), and removes
 * it again when switched off. Nothing else on the system is changed.
 */
public final class StartupManager {

    static final String SCRIPT_NAME = "HabitManager.cmd";

    private final Path startupFolder;
    private final Path workingDirectory;
    private final String launchCommand;

    /** @param startupFolder the Startup folder, or null when the platform is not supported */
    public StartupManager(Path startupFolder, Path workingDirectory, String launchCommand) {
        this.startupFolder = startupFolder;
        this.workingDirectory = workingDirectory;
        this.launchCommand = launchCommand;
    }

    /** Uses the current user's Windows Startup folder and the way this app was started. */
    public static StartupManager forThisApp(Path dataFile, int port) {
        Path folder = null;
        String appData = System.getenv("APPDATA");
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") && appData != null) {
            folder = Path.of(appData, "Microsoft", "Windows", "Start Menu", "Programs", "Startup");
        }
        return new StartupManager(folder, Path.of("").toAbsolutePath(), buildLaunchCommand(dataFile, port));
    }

    public boolean isSupported() {
        return startupFolder != null && Files.isDirectory(startupFolder);
    }

    public boolean isEnabled() {
        return isSupported() && Files.exists(script());
    }

    public void setEnabled(boolean enabled) throws IOException {
        if (!isSupported()) {
            throw new IllegalArgumentException("Start with Windows is only available on Windows.");
        }
        if (enabled) {
            List<String> lines = List.of(
                    "@echo off",
                    "rem Starts Habit Manager in the background. Created by the Habit Manager settings page;",
                    "rem switch \"Start with Windows\" off there to remove it.",
                    "cd /d \"" + workingDirectory + "\"",
                    "start \"\" " + launchCommand);
            Files.write(script(), lines, StandardCharsets.UTF_8);
        } else {
            Files.deleteIfExists(script());
        }
    }

    Path script() {
        return startupFolder.resolve(SCRIPT_NAME);
    }

    /** javaw (no console window) with the same classpath and data file as this run. */
    private static String buildLaunchCommand(Path dataFile, int port) {
        String javaw = ProcessHandle.current().info().command()
                .map(Path::of)
                .map(java -> java.resolveSibling("javaw.exe"))
                .filter(Files::exists)
                .map(Path::toString)
                .orElse("javaw");
        String classpath = Arrays.stream(System.getProperty("java.class.path", "out").split(File.pathSeparator))
                .map(entry -> Path.of(entry).toAbsolutePath().toString())
                .collect(Collectors.joining(File.pathSeparator));
        return "\"" + javaw + "\" -cp \"" + classpath + "\" habitmanager.Main --no-browser --port " + port
                + " --data \"" + dataFile.toAbsolutePath() + "\"";
    }
}
