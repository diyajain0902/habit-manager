package habitmanager;

import habitmanager.desktop.StartupManager;
import habitmanager.service.HabitManager;
import habitmanager.service.HabitService;
import habitmanager.settings.SettingsStorage;
import habitmanager.storage.FileStorage;
import habitmanager.ui.ConsoleUI;
import habitmanager.web.WebServer;

import javax.swing.JOptionPane;
import java.awt.GraphicsEnvironment;
import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;

/**
 * Entry point. Starts the web dashboard (with reminders) by default.
 *
 * Options:
 *   --console      use the text menu instead of the web dashboard
 *   --no-browser   do not open the browser automatically
 *   --no-tray      no system tray icon or Windows notifications (reminders show in the browser only)
 *   --port N       port for the web dashboard (default 8080)
 *   --data FILE    habit data file (default habits.txt); settings.properties is kept next to it
 */
public class Main {

    private static final String DEFAULT_DATA_FILE = "habits.txt";
    private static final int DEFAULT_PORT = 8080;
    private static final Path WEB_ROOT = Path.of("web");

    public static void main(String[] args) {
        boolean console = false;
        boolean openBrowser = true;
        boolean useTray = true;
        int port = DEFAULT_PORT;
        String dataFile = null;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--console" -> console = true;
                    case "--no-browser" -> openBrowser = false;
                    case "--no-tray" -> useTray = false;
                    case "--port" -> port = Integer.parseInt(args[++i]);
                    case "--data" -> dataFile = args[++i];
                    default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
                }
            }
        } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
            System.out.println("Usage: java -cp out habitmanager.Main [--console] [--no-browser] [--no-tray] [--port N] [--data FILE]");
            System.exit(2);
        }

        if (dataFile == null) {
            dataFile = isPackaged() ? packagedDataFile().toString() : DEFAULT_DATA_FILE;
        }
        FileStorage storage = new FileStorage(Path.of(dataFile));
        if (console) {
            runConsole(storage);
        } else {
            runWeb(storage, port, openBrowser, useTray);
        }
    }

    private static void runConsole(FileStorage storage) {
        // UTF-8 output so the check / cross symbols print correctly (on Windows run "chcp 65001" first).
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        new ConsoleUI(new HabitManager(), storage, Clock.systemDefaultZone(), in, out).run();
    }

    private static void runWeb(FileStorage storage, int port, boolean openBrowser, boolean useTray) {
        Path settingsFile = storage.getFile().toAbsolutePath().resolveSibling("settings.properties");
        HabitService service = new HabitService(new HabitManager(), storage, new SettingsStorage(settingsFile),
                Clock.systemDefaultZone());
        service.enableAutoBackup();
        StartupManager startup = StartupManager.forThisApp(storage.getFile(), port);
        try {
            boolean started = new WebServer(service, WEB_ROOT, port, openBrowser, useTray, startup).start();
            if (!started) {
                System.exit(0); // another copy is already running; its dashboard was opened
            }
        } catch (IOException | RuntimeException e) {
            fail("Could not start Habit Manager: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- packaged app (HabitManager.exe)

    /** jpackage sets this property in the app it builds. */
    static boolean isPackaged() {
        return System.getProperty("jpackage.app-path") != null;
    }

    /**
     * The installed app keeps its data in the user's own folder rather than next to the
     * program: %APPDATA%\HabitManager on Windows, ~/.habitmanager elsewhere.
     */
    private static Path packagedDataFile() {
        String appData = System.getenv("APPDATA");
        Path folder = appData != null
                ? Path.of(appData, "HabitManager")
                : Path.of(System.getProperty("user.home"), ".habitmanager");
        try {
            Files.createDirectories(folder);
        } catch (IOException e) {
            fail("Could not create " + folder + ": " + e.getMessage());
        }
        return folder.resolve(DEFAULT_DATA_FILE);
    }

    /** Prints the problem, and also shows it in a dialog for the packaged app, which has no console. */
    private static void fail(String message) {
        System.out.println(message);
        if (isPackaged() && !GraphicsEnvironment.isHeadless()) {
            JOptionPane.showMessageDialog(null, message, "Habit Manager", JOptionPane.ERROR_MESSAGE);
        }
        System.exit(1);
    }
}
