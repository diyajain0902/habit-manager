package habitmanager.web;

import com.sun.net.httpserver.HttpServer;
import habitmanager.service.HabitService;

import java.awt.Desktop;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Runs the dashboard on http://localhost:PORT using the JDK's built-in HTTP server.
 * It only listens on the loopback address, so nothing on your network can reach it.
 */
public class WebServer {

    private final HabitService service;
    private final Path webRoot;
    private final int port;
    private final boolean openBrowser;

    public WebServer(HabitService service, Path webRoot, int port, boolean openBrowser) {
        this.service = service;
        this.webRoot = webRoot;
        this.port = port;
        this.openBrowser = openBrowser;
    }

    public void start() throws IOException {
        if (!Files.isRegularFile(webRoot.resolve("index.html"))) {
            throw new IOException("Cannot find " + webRoot.toAbsolutePath().resolve("index.html")
                    + ". Run the app from the project folder.");
        }
        List<String> warnings = service.load();
        warnings.forEach(w -> System.out.println("Warning: " + w));

        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/api/", new ApiHandler(service, warnings));
        server.createContext("/", new StaticFileHandler(webRoot));
        server.start();

        // Save once more on Ctrl+C. Every change is already saved, so this is just a safety net.
        Runtime.getRuntime().addShutdownHook(new Thread(service::save));

        String url = "http://localhost:" + port + "/";
        System.out.println("Habit Manager is running at " + url);
        System.out.println("Press Ctrl+C to stop.");
        if (openBrowser) {
            openBrowser(url);
        }
    }

    private static void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            System.out.println("Open " + url + " in your browser.");
        }
    }
}
