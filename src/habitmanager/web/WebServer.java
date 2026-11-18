package habitmanager.web;

import com.sun.net.httpserver.HttpServer;
import habitmanager.desktop.StartupManager;
import habitmanager.desktop.TrayController;
import habitmanager.reminders.NotificationCenter;
import habitmanager.reminders.ReminderScheduler;
import habitmanager.service.HabitService;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.net.BindException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Runs the dashboard on http://localhost:PORT using the JDK's built-in HTTP server, plus
 * the reminder scheduler and (if available) the system tray icon. The server only listens
 * on the loopback address, so nothing on your network can reach it.
 */
public class WebServer {

    private final HabitService service;
    private final Path webRoot;
    private final int port;
    private final boolean openBrowser;
    private final boolean useTray;
    private final StartupManager startup;

    public WebServer(HabitService service, Path webRoot, int port, boolean openBrowser,
                     boolean useTray, StartupManager startup) {
        this.service = service;
        this.webRoot = webRoot;
        this.port = port;
        this.openBrowser = openBrowser;
        this.useTray = useTray;
        this.startup = startup;
    }

    /**
     * Starts everything. Returns false (after opening the dashboard) if Habit Manager is
     * already running on this port, e.g. when the app is double-clicked a second time.
     */
    public boolean start() throws IOException {
        Path diskRoot = Files.isRegularFile(webRoot.resolve("index.html")) ? webRoot : null;
        if (!StaticFileHandler.dashboardAvailable(diskRoot)) {
            throw new IOException("Cannot find the dashboard files (" + webRoot.toAbsolutePath().resolve("index.html")
                    + "). Run the app from the project folder.");
        }
        String url = "http://localhost:" + port + "/";

        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        } catch (BindException e) {
            if (isHabitManagerAt(url)) {
                System.out.println("Habit Manager is already running at " + url + ". Opening it.");
                openBrowser(url);
                return false;
            }
            throw new IOException("Port " + port + " is used by another program. Start with --port 9000 (or another number).");
        }

        List<String> warnings = service.load();
        warnings.forEach(w -> System.out.println("Warning: " + w));

        NotificationCenter notifications = new NotificationCenter();
        Optional<TrayController> tray = useTray
                ? TrayController.install(service, () -> openBrowser(url))
                : Optional.empty();
        tray.ifPresent(notifications::addNotifier);

        server.createContext("/api/", new ApiHandler(service, warnings, notifications, startup, tray.isPresent()));
        server.createContext("/", new StaticFileHandler(diskRoot));
        server.start();

        ReminderScheduler scheduler = new ReminderScheduler(service, notifications);
        scheduler.start();

        // Save once more on Ctrl+C or Quit. Every change is already saved, so this is just a safety net.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            scheduler.stop();
            service.save();
        }));

        System.out.println("Habit Manager is running at " + url);
        System.out.println(tray.isPresent()
                ? "Reminders will pop up from the bubble icon in your system tray. Close this window or use Quit in the tray menu to stop."
                : "Press Ctrl+C to stop.");
        if (openBrowser) {
            openBrowser(url);
        }
        return true;
    }

    /** Asks whatever is listening on the port whether it is a Habit Manager. */
    private static boolean isHabitManagerAt(String url) {
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(url + "api/settings").toURL().openConnection();
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);
            try (InputStream in = connection.getInputStream()) {
                return connection.getResponseCode() == 200
                        && new String(in.readAllBytes(), StandardCharsets.UTF_8).contains("\"remindersEnabled\"");
            } finally {
                connection.disconnect();
            }
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    public static void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            // fall through to the message below
        }
        System.out.println("Open " + url + " in your browser.");
    }
}
