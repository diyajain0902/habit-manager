package habitmanager.settings;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Properties;
import java.util.function.Function;

/**
 * Stores Settings in a small .properties file (e.g. settings.properties next to habits.txt).
 * A missing file or a bad value falls back to the default, so settings can never stop the app.
 */
public class SettingsStorage {

    private final Path file;

    public SettingsStorage(Path file) {
        this.file = file;
    }

    public Path getFile() {
        return file;
    }

    public Settings load() throws IOException {
        Settings defaults = Settings.defaults();
        if (!Files.exists(file)) {
            return defaults;
        }
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        LocalTime dayEndsAt = read(props, "day.endsAt", LocalTime::parse, defaults.dayEndsAt());
        if (dayEndsAt.isAfter(Settings.LATEST_DAY_END)) {
            dayEndsAt = defaults.dayEndsAt();
        }
        return new Settings(
                read(props, "reminders.enabled", Boolean::parseBoolean, defaults.remindersEnabled()),
                read(props, "nudge.enabled", Boolean::parseBoolean, defaults.nudgeEnabled()),
                read(props, "nudge.time", LocalTime::parse, defaults.nudgeTime()),
                read(props, "streakAlert.enabled", Boolean::parseBoolean, defaults.streakAlertEnabled()),
                read(props, "streakAlert.time", LocalTime::parse, defaults.streakAlertTime()),
                dayEndsAt);
    }

    public void save(Settings settings) throws IOException {
        Properties props = new Properties();
        props.setProperty("reminders.enabled", String.valueOf(settings.remindersEnabled()));
        props.setProperty("nudge.enabled", String.valueOf(settings.nudgeEnabled()));
        props.setProperty("nudge.time", settings.nudgeTime().toString());
        props.setProperty("streakAlert.enabled", String.valueOf(settings.streakAlertEnabled()));
        props.setProperty("streakAlert.time", settings.streakAlertTime().toString());
        props.setProperty("day.endsAt", settings.dayEndsAt().toString());
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            props.store(writer, "Habit Manager settings");
        }
    }

    private static <T> T read(Properties props, String key, Function<String, T> parser, T fallback) {
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return parser.apply(value.trim());
        } catch (DateTimeParseException | IllegalArgumentException e) {
            return fallback;
        }
    }
}
