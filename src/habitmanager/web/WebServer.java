package habitmanager.web;

import com.sun.net.httpserver.HttpServer;
import habitmanager.desktop.StartupManager;
import habitmanager.desktop.TrayController;
import habitmanager.reminders.NotificationCenter;
import habitmanager.reminders.ReminderScheduler;
import habitmanager.service.HabitService;

import java.awt.Desktop;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
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

    public void start() throws IOException {
        if (!Files.isRegularFile(webRoot.resolve("index.html"))) {
            throw new IOException("Cannot find " + webRoot.toAbsolutePath().resolve("index.html")
                    + ". Run the app from the project folder.");
        }
        List<String> warnings = service.load();
        warnings.forEach(w -> System.out.println("Warning: " + w));

        String url = "http://localhost:" + port + "/";
        NotificationCenter notifications = new NotificationCenter();
        Optional<TrayController> tray = useTray
                ? TrayController.install(service, () -> openBrowser(url))
                : Optional.empty();
        tray.ifPresent(notifications::addNotifier);

        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/api/", new ApiHandler(service, warnings, notifications, startup, tray.isPresent()));
        server.createContext("/", new StaticFileHandler(webRoot));
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
    }

    private static void openBrowser(String url) {
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
