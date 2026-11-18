package habitmanager.storage;

import habitmanager.model.CheckIn;
import habitmanager.model.Habit;
import habitmanager.model.Schedule;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads and writes habits as plain text.
 *
 * <h2>Format 2 (current)</h2>
 * A header line, then one habit per line as key=value fields separated by '|':
 * <pre>
 * # habitmanager format 2
 * name=Drink+water|created=2024-03-01|schedule=DAILY|target=8|reminder=07:30|color=2|emoji=%F0%9F%92%A7|archived=false|checkins=2024-03-01:8,2024-03-02:3:felt+great
 * </pre>
 * Free text (name, emoji, notes) is URL-encoded, so it can safely contain '|', ',' or ':'.
 * Each check in is date:count or date:count:note. Unknown keys are ignored.
 *
 * <h2>Format 1 (legacy)</h2>
 * Files without the header are read as the original format, name|createdDate|date1,date2.
 * They are converted to format 2 the next time the app saves, after a backup copy is made.
 */
public class FileStorage {

    public static final String HEADER = "# habitmanager format 2";
    private static final String HEADER_PREFIX = "# habitmanager format";
    private static final String LEGACY_BACKUP_SUFFIX = ".v1.bak";

    private final Path file;

    /**
     * Habits that loaded successfully, a warning for every skipped line, and whether
     * the file was in the legacy format and still needs converting.
     */
    public record LoadResult(List<Habit> habits, List<String> warnings, boolean legacyFormat) {
        public LoadResult(List<Habit> habits, List<String> warnings) {
            this(habits, warnings, false);
        }
    }

    public FileStorage(Path file) {
        this.file = file;
    }

    public Path getFile() {
        return file;
    }

    /** Loads all habits. A missing file is not an error: it just means there is no data yet. */
    public LoadResult load() throws IOException {
        List<Habit> habits = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (!Files.exists(file)) {
            return new LoadResult(habits, warnings, false);
        }

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        boolean legacy = lines.stream().filter(l -> !l.isBlank()).findFirst()
                .map(first -> !first.startsWith(HEADER_PREFIX)).orElse(false);

        Set<String> seenNames = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            int lineNumber = i + 1;
            try {
                Habit habit = legacy ? parseLegacyLine(line) : parseLine(line);
                if (!seenNames.add(habit.getName().toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("duplicate habit name \"" + habit.getName() + "\"");
                }
                habits.add(habit);
            } catch (IllegalArgumentException | DateTimeParseException e) {
                warnings.add("Skipping malformed line " + lineNumber + " in " + file.getFileName()
                        + ": " + e.getMessage());
            }
        }
        return new LoadResult(habits, warnings, legacy);
    }

    /** Writes to a temporary file first, then swaps it in, so a crash never leaves half a file. */
    public void save(Collection<Habit> habits) throws IOException {
        if (isLegacyFile()) {
            backupLegacyFile(); // never overwrite an old-format file without keeping a copy
        }
        List<String> lines = new ArrayList<>();
        lines.add(HEADER);
        habits.forEach(h -> lines.add(formatLine(h)));

        Path absolute = file.toAbsolutePath();
        Path temp = absolute.resolveSibling(absolute.getFileName() + ".tmp");
        Files.write(temp, lines, StandardCharsets.UTF_8);
        try {
            Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Copies a legacy file to e.g. habits.txt.v1.bak before it is converted. Does nothing
     * if the backup already exists. Returns the backup path.
     */
    public Path backupLegacyFile() throws IOException {
        Path absolute = file.toAbsolutePath();
        Path backup = absolute.resolveSibling(absolute.getFileName() + LEGACY_BACKUP_SUFFIX);
        if (Files.exists(absolute) && !Files.exists(backup)) {
            Files.copy(absolute, backup);
        }
        return backup;
    }

    /** True if the file exists, has content, and does not start with the format 2 header. */
    private boolean isLegacyFile() throws IOException {
        if (!Files.exists(file)) {
            return false;
        }
        try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
            return lines.filter(l -> !l.isBlank()).findFirst()
                    .map(first -> !first.startsWith(HEADER_PREFIX)).orElse(false);
        }
    }

    // ---------------------------------------------------------------- format 2

    static String formatLine(Habit habit) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("name", encode(habit.getName()));
        fields.put("created", habit.getCreatedDate().toString());
        fields.put("schedule", habit.getSchedule().encode());
        fields.put("target", String.valueOf(habit.getTarget()));
        if (habit.getReminderTime() != null) {
            fields.put("reminder", habit.getReminderTime().toString());
        }
        if (habit.getColorIndex() != null) {
            fields.put("color", habit.getColorIndex().toString());
        }
        if (habit.getEmoji() != null) {
            fields.put("emoji", encode(habit.getEmoji()));
        }
        fields.put("archived", String.valueOf(habit.isArchived()));
        fields.put("checkins", formatCheckIns(habit));

        StringBuilder line = new StringBuilder();
        fields.forEach((key, value) -> {
            if (!line.isEmpty()) {
                line.append('|');
            }
            line.append(key).append('=').append(value);
        });
        return line.toString();
    }

    private static String formatCheckIns(Habit habit) {
        List<String> parts = new ArrayList<>();
        habit.getCheckIns().forEach((date, checkIn) -> {
            String part = date + ":" + checkIn.count();
            if (checkIn.hasNote()) {
                part += ":" + encode(checkIn.note());
            }
            parts.add(part);
        });
        return String.join(",", parts);
    }

    static Habit parseLine(String line) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String field : line.split("\\|", -1)) {
            int eq = field.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("expected key=value but found \"" + field + "\"");
            }
            fields.put(field.substring(0, eq).trim(), field.substring(eq + 1));
        }

        String name = decode(require(fields, "name")).trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("habit name is blank");
        }
        Habit habit = new Habit(name, LocalDate.parse(require(fields, "created").trim()));

        // Target must be set before check ins, because counts are clamped to it.
        if (fields.containsKey("target")) {
            habit.restoreTarget(parseInt(fields.get("target"), "target"));
        }
        if (fields.containsKey("schedule")) {
            habit.setSchedule(Schedule.parse(fields.get("schedule")));
        }
        String reminder = fields.getOrDefault("reminder", "").trim();
        if (!reminder.isEmpty()) {
            habit.setReminderTime(LocalTime.parse(reminder));
        }
        String color = fields.getOrDefault("color", "").trim();
        if (!color.isEmpty()) {
            habit.setColorIndex(parseInt(color, "color"));
        }
        habit.setEmoji(decode(fields.getOrDefault("emoji", "")));
        habit.setArchived(Boolean.parseBoolean(fields.getOrDefault("archived", "false").trim()));
        parseCheckIns(habit, fields.getOrDefault("checkins", ""));
        return habit;
    }

    private static void parseCheckIns(Habit habit, String field) {
        for (String part : field.split(",")) {
            if (part.isBlank()) {
                continue;
            }
            String[] pieces = part.trim().split(":", 3);
            if (pieces.length < 2) {
                throw new IllegalArgumentException("check in \"" + part + "\" should look like date:count");
            }
            LocalDate date = LocalDate.parse(pieces[0]);
            int count = parseInt(pieces[1], "check in count");
            String note = pieces.length == 3 ? decode(pieces[2]) : null;
            habit.restoreCheckIn(date, new CheckIn(Math.max(0, count), note));
        }
    }

    // ---------------------------------------------------------------- format 1 (legacy)

    static Habit parseLegacyLine(String line) {
        // Limit -1 keeps the trailing empty field when a habit has no completed dates.
        String[] fields = line.split("\\|", -1);
        if (fields.length != 3) {
            throw new IllegalArgumentException("expected 3 fields separated by '|' but found " + fields.length);
        }
        String name = fields[0].trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("habit name is blank");
        }
        LocalDate created = LocalDate.parse(fields[1].trim());
        return new Habit(name, created, parseLegacyDates(fields[2]));
    }

    private static List<LocalDate> parseLegacyDates(String field) {
        List<LocalDate> dates = new ArrayList<>();
        for (String part : field.split(",")) {
            if (!part.isBlank()) {
                dates.add(LocalDate.parse(part.trim()));
            }
        }
        return dates;
    }

    // ---------------------------------------------------------------- helpers

    private static String require(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing \"" + key + "\"");
        }
        return value;
    }

    private static int parseInt(String text, String what) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(what + " \"" + text + "\" is not a number");
        }
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    private static String decode(String text) {
        return URLDecoder.decode(text, StandardCharsets.UTF_8);
    }
}
