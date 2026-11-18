package habitmanager;

import habitmanager.desktop.StartupManager;
import habitmanager.service.HabitManager;
import habitmanager.service.HabitService;
import habitmanager.settings.SettingsStorage;
import habitmanager.storage.FileStorage;
import habitmanager.ui.ConsoleUI;
import habitmanager.web.WebServer;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
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
        String dataFile = DEFAULT_DATA_FILE;
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
        StartupManager startup = StartupManager.forThisApp(storage.getFile(), port);
        try {
            new WebServer(service, WEB_ROOT, port, openBrowser, useTray, startup).start();
        } catch (IOException e) {
            System.out.println("Could not start the dashboard: " + e.getMessage());
            System.exit(1);
        }
    }
}
