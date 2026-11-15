package habitmanager.storage;

import habitmanager.model.Habit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reads and writes habits as plain text, one habit per line:
 * <pre>name|createdDate|date1,date2,date3</pre>
 * Dates use ISO format (yyyy-MM-dd). The date list may be empty.
 */
public class FileStorage {

    private static final String FIELD_SEPARATOR = "|";
    private static final String DATE_SEPARATOR = ",";

    private final Path file;

    /** Habits that loaded successfully plus a warning for every line that was skipped. */
    public record LoadResult(List<Habit> habits, List<String> warnings) {
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
            return new LoadResult(habits, warnings);
        }

        Set<String> seenNames = new HashSet<>();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            int lineNumber = i + 1;
            try {
                Habit habit = parseLine(line);
                if (!seenNames.add(habit.getName().toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("duplicate habit name \"" + habit.getName() + "\"");
                }
                habits.add(habit);
            } catch (IllegalArgumentException | DateTimeParseException e) {
                warnings.add("Skipping malformed line " + lineNumber + " in " + file.getFileName()
                        + ": " + e.getMessage());
            }
        }
        return new LoadResult(habits, warnings);
    }

    /** Writes to a temporary file first, then swaps it in, so a crash never leaves half a file. */
    public void save(Collection<Habit> habits) throws IOException {
        List<String> lines = habits.stream().map(FileStorage::formatLine).toList();
        Path absolute = file.toAbsolutePath();
        Path temp = absolute.resolveSibling(absolute.getFileName() + ".tmp");
        Files.write(temp, lines, StandardCharsets.UTF_8);
        try {
            Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static String formatLine(Habit habit) {
        String dates = habit.getCompletedDates().stream()
                .map(LocalDate::toString)
                .collect(Collectors.joining(DATE_SEPARATOR));
        return habit.getName() + FIELD_SEPARATOR + habit.getCreatedDate() + FIELD_SEPARATOR + dates;
    }

    static Habit parseLine(String line) {
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
        return new Habit(name, created, parseDates(fields[2]));
    }

    private static List<LocalDate> parseDates(String field) {
        List<LocalDate> dates = new ArrayList<>();
        for (String part : field.split(DATE_SEPARATOR)) {
            if (!part.isBlank()) {
                dates.add(LocalDate.parse(part.trim()));
            }
        }
        return dates;
    }
}
