package habitmanager.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Serves the dashboard's HTML, CSS, JS and images.
 *
 * When a web/ folder exists next to the app (running from the project), files are read from
 * disk, so edits show up on refresh. Otherwise (the packaged app) they are read from the
 * /web/ folder inside the jar.
 */
public class StaticFileHandler implements HttpHandler {

    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "svg", "image/svg+xml",
            "png", "image/png",
            "ico", "image/x-icon");

    private final Path root;

    /** @param root the web folder on disk, or null to serve the copy bundled in the jar */
    StaticFileHandler(Path root) {
        this.root = root == null ? null : root.toAbsolutePath().normalize();
    }

    /** True if the dashboard files can be found on disk or in the jar. */
    static boolean dashboardAvailable(Path root) {
        return (root != null && Files.isRegularFile(root.resolve("index.html")))
                || StaticFileHandler.class.getResource("/web/index.html") != null;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            if (!method.equals("GET") && !method.equals("HEAD")) {
                send(exchange, 405, "text/plain; charset=utf-8", "Method not allowed".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String requestPath = exchange.getRequestURI().getPath();
            String relative = requestPath.equals("/") ? "index.html" : requestPath.substring(1);
            Optional<byte[]> body = isSafe(relative) ? read(relative) : Optional.empty();
            if (body.isEmpty()) {
                send(exchange, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            send(exchange, 200, contentTypeOf(relative), body.get());
        } finally {
            exchange.close();
        }
    }

    /** Refuses anything that could escape the web folder, e.g. /../habits.txt. */
    public static boolean isSafe(String relative) {
        if (relative.isEmpty() || relative.contains("\\") || relative.contains(":") || relative.startsWith("/")) {
            return false;
        }
        for (String segment : relative.split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private Optional<byte[]> read(String relative) throws IOException {
        if (root != null && Files.isDirectory(root)) {
            Path file = root.resolve(relative).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                return Optional.empty();
            }
            return Optional.of(Files.readAllBytes(file));
        }
        try (InputStream in = StaticFileHandler.class.getResourceAsStream("/web/" + relative)) {
            return in == null ? Optional.empty() : Optional.of(in.readAllBytes());
        }
    }

    private static String contentTypeOf(String name) {
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return CONTENT_TYPES.getOrDefault(extension, "application/octet-stream");
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.sendResponseHeaders(status, -1); // headers only
            return;
        }
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
