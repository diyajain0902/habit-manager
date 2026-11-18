package habitmanager.service;

import habitmanager.model.Habit;
import habitmanager.reminders.ReminderEngine;
import habitmanager.service.HabitManager.HabitDetails;
import habitmanager.settings.Settings;
import habitmanager.settings.SettingsStorage;
import habitmanager.storage.FileStorage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Application-level facade used by the web UI, the tray icon and the reminder scheduler:
 * looks habits up by name, applies a change through HabitManager and saves straight away.
 * All methods are synchronized so the web server and the reminder thread never interleave
 * a change and a save.
 *
 * Validation problems surface as IllegalArgumentException; save failures as
 * UncheckedIOException.
 */
public class HabitService {

    private final HabitManager manager;
    private final FileStorage storage;
    private final SettingsStorage settingsStorage;
    private final Clock clock;
    private final ReminderEngine reminders = new ReminderEngine();
    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();
    private Settings settings = Settings.defaults();

    /** Settings are kept in memory only (used by tests). */
    public HabitService(HabitManager manager, FileStorage storage, Clock clock) {
        this(manager, storage, null, clock);
    }

    public HabitService(HabitManager manager, FileStorage storage, SettingsStorage settingsStorage, Clock clock) {
        this.manager = manager;
        this.storage = storage;
        this.settingsStorage = settingsStorage;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- loading

    /**
     * Loads settings and habits and returns a warning for every skipped line. A file in the
     * old format is backed up (habits.txt.v1.bak) and converted to the new format at once.
     */
    public synchronized List<String> load() throws IOException {
        if (settingsStorage != null) {
            settings = settingsStorage.load();
        }
        FileStorage.LoadResult result = storage.load();
        manager.replaceAll(result.habits());
        if (result.legacyFormat() && !result.habits().isEmpty()) {
            Path backup = storage.backupLegacyFile();
            storage.save(manager.listHabits());
            System.out.println("Upgraded " + storage.getFile() + " to the new format. The original is saved as " + backup);
        }
        return result.warnings();
    }

    // ---------------------------------------------------------------- time

    /**
     * The logical date. Read fresh on every call so a long-running server rolls over, and
     * shifted by "day ends at", so with 03:00 a check in at 1 AM counts for yesterday.
     */
    public synchronized LocalDate today() {
        return now().minusHours(settings.dayEndsAt().getHour())
                .minusMinutes(settings.dayEndsAt().getMinute())
                .toLocalDate();
    }

    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    // ---------------------------------------------------------------- reading

    public synchronized List<Habit> habits() {
        return manager.listHabits();
    }

    /** Runs a read-only computation while holding the lock, so no change happens half way. */
    public synchronized <T> T read(Function<List<Habit>, T> reader) {
        return reader.apply(manager.listHabits());
    }

    public synchronized Settings getSettings() {
        return settings;
    }

    // ---------------------------------------------------------------- changing habits

    public synchronized Habit addHabit(String name) {
        return addHabit(HabitDetails.simple(name));
    }

    public synchronized Habit addHabit(HabitDetails details) {
        Habit habit = manager.addHabit(details, today());
        save();
        return habit;
    }

    public synchronized void updateHabit(String name, HabitDetails details) {
        manager.updateHabit(require(name), details);
        save();
    }

    public synchronized void renameHabit(String name, String newName) {
        manager.renameHabit(require(name), newName);
        save();
    }

    public synchronized void deleteHabit(String name) {
        manager.deleteHabit(require(name));
        save();
    }

    public synchronized void setArchived(String name, boolean archived) {
        manager.setArchived(require(name), archived);
        save();
    }

    public synchronized void moveHabit(String name, int newIndex) {
        manager.moveHabit(require(name), newIndex);
        save();
    }

    /** Returns false if the habit was already done today. */
    public synchronized boolean checkIn(String name) {
        boolean changed = manager.checkIn(require(name), today());
        if (changed) {
            save();
        }
        return changed;
    }

    /** Returns false if the habit was not done today. */
    public synchronized boolean undoToday(String name) {
        boolean changed = manager.undoCheckIn(require(name), today());
        if (changed) {
            save();
        }
        return changed;
    }

    /** Flips a single date on or off. Returns true if the date is now done. */
    public synchronized boolean toggleDate(String name, LocalDate date) {
        boolean nowDone = manager.toggleDate(require(name), date, today());
        save();
        return nowDone;
    }

    /** One more (or one less) towards the daily target. Returns the new count. */
    public synchronized int addProgress(String name, LocalDate date, int delta) {
        int count = manager.addProgress(require(name), date, delta, today());
        save();
        return count;
    }

    public synchronized void setNote(String name, LocalDate date, String note) {
        manager.setNote(require(name), date, note, today());
        save();
    }

    public synchronized void loadDemoData() {
        manager.replaceAll(DemoDataGenerator.generate(today()));
        save();
    }

    // ---------------------------------------------------------------- settings & reminders

    public synchronized void updateSettings(Settings newSettings) {
        if (settingsStorage != null) {
            try {
                settingsStorage.save(newSettings);
            } catch (IOException e) {
                throw new UncheckedIOException("Could not save settings to " + settingsStorage.getFile(), e);
            }
        }
        settings = newSettings;
        fireChanged();
    }

    /** Reminders that should be shown right now. Each one is returned only once. */
    public synchronized List<ReminderEngine.Reminder> dueReminders() {
        return reminders.due(manager.listHabits(), settings, today(), now());
    }

    public synchronized void snooze(String name, int minutes) {
        if (minutes < 1 || minutes > 240) {
            throw new IllegalArgumentException("Snooze for 1 to 240 minutes.");
        }
        reminders.snooze(require(name).getName(), minutes, now());
    }

    /** Called after every change, e.g. so the tray menu can refresh. */
    public void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }

    // ---------------------------------------------------------------- persistence

    public synchronized void save() {
        try {
            storage.save(manager.listHabits());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save to " + storage.getFile(), e);
        }
        fireChanged();
    }

    private void fireChanged() {
        for (Runnable listener : changeListeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                System.out.println("Warning: " + e.getMessage());
            }
        }
    }

    private Habit require(String name) {
        return manager.findHabit(name)
                .orElseThrow(() -> new IllegalArgumentException("No habit named \"" + name + "\"."));
    }
}
