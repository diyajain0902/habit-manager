package habitmanager.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import habitmanager.desktop.StartupManager;
import habitmanager.model.Schedule;
import habitmanager.reminders.NotificationCenter;
import habitmanager.reminders.ReminderEngine;
import habitmanager.service.HabitManager.HabitDetails;
import habitmanager.service.HabitService;
import habitmanager.settings.Settings;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * JSON API used by the dashboard. Changes answer with the full, freshly computed state
 * so the page can simply re-render.
 *
 * <pre>
 * GET  /api/state
 * GET  /api/settings
 * GET  /api/notifications?after=ID
 * POST /api/habits/add       name [+ details]
 * POST /api/habits/update    name, newName [+ details]
 * POST /api/habits/rename    name, newName
 * POST /api/habits/delete    name
 * POST /api/habits/archive   name, archived (true/false)
 * POST /api/habits/move      name, index
 * POST /api/checkin          name
 * POST /api/undo             name
 * POST /api/toggle           name, date
 * POST /api/progress         name, delta [, date]
 * POST /api/note             name, note [, date]
 * POST /api/demo
 * POST /api/settings         remindersEnabled, nudgeEnabled, nudgeTime, streakAlertEnabled,
 *                            streakAlertTime, dayEndsAt, startAtLogin
 * POST /api/notifications/snooze   name, minutes
 * POST /api/notifications/test
 * POST /api/history/undo            undo the last change (any kind)
 * GET  /api/export/csv              all check ins as a CSV download
 * GET  /api/export/backup           a complete backup (habits.txt contents) as a download
 * POST /api/import?mode=merge|replace   body: the backup file as text/plain
 *
 * details: schedule (DAILY | WEEKDAYS | WEEKLY), days (MON,WED,...), timesPerWeek,
 *          target, reminder (HH:mm or empty), color (0-5 or empty), emoji
 * </pre>
 * POST bodies are application/x-www-form-urlencoded. Dates are yyyy-MM-dd, times HH:mm.
 */
class ApiHandler implements HttpHandler {

    /**
     * Browsers cannot send a custom header cross-origin without a CORS preflight, which
     * this server never approves. Requiring it on POSTs stops other websites from
     * changing your habits through your browser (CSRF).
     */
    static final String CLIENT_HEADER = "X-Habit-Client";

    /** Backups are tiny; anything bigger than this is not one. */
    static final int MAX_IMPORT_BYTES = 5 * 1024 * 1024;

    private final HabitService service;
    private final List<String> loadWarnings;
    private final NotificationCenter notifications;
    private final StartupManager startup;
    private final boolean trayAvailable;

    ApiHandler(HabitService service, List<String> loadWarnings, NotificationCenter notifications,
               StartupManager startup, boolean trayAvailable) {
        this.service = service;
        this.loadWarnings = loadWarnings;
        this.notifications = notifications;
        this.startup = startup;
        this.trayAvailable = trayAvailable;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (method.equals("GET")) {
                handleGet(exchange, path);
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

    private void handleGet(HttpExchange exchange, String path) throws IOException {
        switch (path) {
            case "/api/state" -> sendState(exchange);
            case "/api/settings" -> sendSettings(exchange);
            case "/api/notifications" -> {
                Map<String, String> query = parseUrlEncoded(exchange.getRequestURI().getRawQuery());
                long after = query.containsKey("after") ? parseLong(query.get("after"), "after") : 0;
                sendJson(exchange, 200, StateSerializer.noticesJson(notifications.after(after), notifications.latestId()));
            }
            case "/api/export/csv" -> sendDownload(exchange, "text/csv; charset=utf-8",
                    "habits-" + service.today() + ".csv", service.exportCsv());
            case "/api/export/backup" -> sendDownload(exchange, "text/plain; charset=utf-8",
                    "habit-manager-backup-" + service.today() + ".txt", service.exportBackup());
            default -> sendError(exchange, 404, "Not found.");
        }
    }

    /**
     * Imports a backup file sent as the raw request body (text/plain), merging or replacing
     * depending on ?mode=merge|replace. Answers with the new state plus an "imported" summary.
     */
    private void handleImport(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_IMPORT_BYTES + 1);
        if (body.length > MAX_IMPORT_BYTES) {
            throw new IllegalArgumentException("That file is too big to be a Habit Manager backup.");
        }
        Map<String, String> query = parseUrlEncoded(exchange.getRequestURI().getRawQuery());
        boolean replace = "replace".equals(query.get("mode"));
        HabitService.ImportReport report = service.importBackup(new String(body, StandardCharsets.UTF_8), replace);
        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("added", Json.number(report.result().added()));
        summary.put("merged", Json.number(report.result().merged()));
        summary.put("replaced", Json.number(report.result().replaced()));
        summary.put("skippedLines", Json.number(report.warnings().size()));
        sendState(exchange, Map.of("imported", Json.object(summary)));
    }

    private void handlePost(HttpExchange exchange, String path) throws IOException {
        if (exchange.getRequestHeaders().getFirst(CLIENT_HEADER) == null) {
            sendError(exchange, 403, "Missing " + CLIENT_HEADER + " header.");
            return;
        }
        if (path.equals("/api/import")) {
            handleImport(exchange);
            return;
        }
        if (path.equals("/api/history/undo")) {
            String undone = service.undoLast();
            sendState(exchange, Map.of("undone", Json.quote(undone)));
            return;
        }
        Map<String, String> form = parseUrlEncoded(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        switch (path) {
            case "/api/habits/add" -> service.addHabit(details(form, form.get("name")));
            case "/api/habits/update" -> service.updateHabit(required(form, "name"), details(form, form.get("newName")));
            case "/api/habits/rename" -> service.renameHabit(required(form, "name"), form.get("newName"));
            case "/api/habits/delete" -> service.deleteHabit(required(form, "name"));
            case "/api/habits/archive" -> service.setArchived(required(form, "name"), bool(form, "archived", true));
            case "/api/habits/move" -> service.moveHabit(required(form, "name"), parseInt(required(form, "index"), "index"));
            case "/api/checkin" -> service.checkIn(required(form, "name"));
            case "/api/undo" -> service.undoToday(required(form, "name"));
            case "/api/toggle" -> service.toggleDate(required(form, "name"), LocalDate.parse(required(form, "date")));
            case "/api/progress" -> service.addProgress(required(form, "name"), date(form),
                    parseInt(required(form, "delta"), "delta"));
            case "/api/note" -> service.setNote(required(form, "name"), date(form), form.get("note"));
            case "/api/demo" -> service.loadDemoData();
            case "/api/notifications/snooze" -> service.snooze(required(form, "name"),
                    form.containsKey("minutes") ? parseInt(form.get("minutes"), "minutes") : 10);
            case "/api/notifications/test" -> notifications.publish(new ReminderEngine.Reminder(
                    ReminderEngine.Kind.NUDGE, null, "Test notification", "Reminders are working. Have a lovely day!"),
                    service.now());
            case "/api/settings" -> {
                updateSettings(form);
                sendSettings(exchange);
                return;
            }
            default -> {
                sendError(exchange, 404, "Not found.");
                return;
            }
        }
        sendState(exchange);
    }

    // ---------------------------------------------------------------- parsing requests

    /** Reads the habit fields the add / edit dialog sends. Missing fields use plain defaults. */
    private static HabitDetails details(Map<String, String> form, String name) {
        return new HabitDetails(
                name,
                schedule(form),
                form.containsKey("target") && !form.get("target").isBlank() ? parseInt(form.get("target"), "Target") : 1,
                optionalTime(form.get("reminder"), "Reminder time"),
                form.containsKey("color") && !form.get("color").isBlank() ? parseInt(form.get("color"), "Colour") : null,
                form.get("emoji"));
    }

    private static Schedule schedule(Map<String, String> form) {
        String type = form.getOrDefault("schedule", "DAILY").trim().toUpperCase(Locale.ROOT);
        return switch (type) {
            case "DAILY", "" -> Schedule.daily();
            case "WEEKDAYS" -> Schedule.onDays(Schedule.parseDays(form.getOrDefault("days", "")));
            case "WEEKLY" -> Schedule.timesPerWeek(parseInt(form.getOrDefault("timesPerWeek", ""), "Times per week"));
            default -> throw new IllegalArgumentException("Unknown schedule \"" + type + "\".");
        };
    }

    private void updateSettings(Map<String, String> form) throws IOException {
        Settings current = service.getSettings();
        Settings updated = new Settings(
                bool(form, "remindersEnabled", current.remindersEnabled()),
                bool(form, "nudgeEnabled", current.nudgeEnabled()),
                timeOr(form, "nudgeTime", current.nudgeTime(), "Evening nudge time"),
                bool(form, "streakAlertEnabled", current.streakAlertEnabled()),
                timeOr(form, "streakAlertTime", current.streakAlertTime(), "Streak alert time"),
                timeOr(form, "dayEndsAt", current.dayEndsAt(), "Day end time"));
        service.updateSettings(updated);
        if (form.containsKey("startAtLogin") && startup != null) {
            boolean wanted = bool(form, "startAtLogin", false);
            if (wanted != startup.isEnabled()) {
                startup.setEnabled(wanted);
            }
        }
    }

    private LocalDate date(Map<String, String> form) {
        String text = form.get("date");
        return text == null || text.isBlank() ? service.today() : LocalDate.parse(text.trim());
    }

    private static LocalTime optionalTime(String text, String what) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(text.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(what + " must look like 07:30.");
        }
    }

    private static LocalTime timeOr(Map<String, String> form, String key, LocalTime fallback, String what) {
        LocalTime time = optionalTime(form.get(key), what);
        return time == null ? fallback : time;
    }

    private static boolean bool(Map<String, String> form, String key, boolean fallback) {
        String value = form.get(key);
        return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value.trim());
    }

    private static int parseInt(String text, String what) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException | NullPointerException e) {
            throw new IllegalArgumentException(what + " must be a whole number.");
        }
    }

    private static long parseLong(String text, String what) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(what + " must be a whole number.");
        }
    }

    private static String required(Map<String, String> form, String key) {
        String value = form.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing \"" + key + "\".");
        }
        return value;
    }

    private static Map<String, String> parseUrlEncoded(String text) {
        Map<String, String> values = new HashMap<>();
        if (text == null) {
            return values;
        }
        for (String pair : text.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            values.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return values;
    }

    // ---------------------------------------------------------------- responses

    private void sendState(HttpExchange exchange) throws IOException {
        sendState(exchange, Map.of());
    }

    private void sendState(HttpExchange exchange, Map<String, String> extras) throws IOException {
        LocalDate today = service.today();
        String undoLabel = service.undoLabel();
        sendJson(exchange, 200, service.read(habits ->
                StateSerializer.toJson(habits, today, loadWarnings, undoLabel, extras)));
    }

    /** Sends text as a file download (the browser saves it instead of showing it). */
    private static void sendDownload(HttpExchange exchange, String contentType, String fileName, String text)
            throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private void sendSettings(HttpExchange exchange) throws IOException {
        sendJson(exchange, 200, StateSerializer.settingsJson(service.getSettings(), startup, trayAvailable));
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
}
