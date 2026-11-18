package habitmanager.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import habitmanager.service.HabitService;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON API used by the dashboard. Every successful call answers with the full,
 * freshly computed state so the page can simply re-render.
 *
 * GET  /api/state
 * POST /api/habits/add      name
 * POST /api/habits/rename   name, newName
 * POST /api/habits/delete   name
 * POST /api/checkin         name
 * POST /api/undo            name
 * POST /api/toggle          name, date (yyyy-MM-dd)
 * POST /api/demo
 *
 * POST bodies are application/x-www-form-urlencoded.
 */
class ApiHandler implements HttpHandler {

    /**
     * Browsers cannot send a custom header cross-origin without a CORS preflight, which
     * this server never approves. Requiring it on POSTs stops other websites from
     * changing your habits through your browser (CSRF).
     */
    static final String CLIENT_HEADER = "X-Habit-Client";

    private final HabitService service;
    private final List<String> loadWarnings;

    ApiHandler(HabitService service, List<String> loadWarnings) {
        this.service = service;
        this.loadWarnings = loadWarnings;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (method.equals("GET") && path.equals("/api/state")) {
                sendState(exchange);
            } else if (method.equals("POST")) {
                handlePost(exchange, path);
            } else {
                sendError(exchange, 404, "Not found.");
            }
        } catch (DateTimeParseException e) {
            sendError(exchange, 400, "Dates must look like yyyy-MM-dd, e.g. 2024-03-15.");
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, e.getMessage());
        } catch (UncheckedIOException e) {
            sendError(exchange, 500, e.getMessage());
        } finally {
            exchange.close();
        }
    }

    private void handlePost(HttpExchange exchange, String path) throws IOException {
        if (exchange.getRequestHeaders().getFirst(CLIENT_HEADER) == null) {
            sendError(exchange, 403, "Missing " + CLIENT_HEADER + " header.");
            return;
        }
        Map<String, String> form = parseForm(exchange);
        switch (path) {
            case "/api/habits/add" -> service.addHabit(form.get("name"));
            case "/api/habits/rename" -> service.renameHabit(required(form, "name"), form.get("newName"));
            case "/api/habits/delete" -> service.deleteHabit(required(form, "name"));
            case "/api/checkin" -> service.checkIn(required(form, "name"));
            case "/api/undo" -> service.undoToday(required(form, "name"));
            case "/api/toggle" -> service.toggleDate(required(form, "name"), LocalDate.parse(required(form, "date")));
            case "/api/demo" -> service.loadDemoData();
            default -> {
                sendError(exchange, 404, "Not found.");
                return;
            }
        }
        sendState(exchange);
    }

    private void sendState(HttpExchange exchange) throws IOException {
        sendJson(exchange, 200, StateSerializer.toJson(service.habits(), service.today(), loadWarnings));
    }

    private static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        sendJson(exchange, status, Json.object(Map.of("error", Json.quote(message))));
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static Map<String, String> parseForm(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> form = new HashMap<>();
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            form.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return form;
    }

    private static String required(Map<String, String> form, String key) {
        String value = form.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing \"" + key + "\".");
        }
        return value;
    }
}
