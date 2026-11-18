package habitmanager.service;

import habitmanager.model.Habit;
import habitmanager.reminders.ReminderEngine;
import habitmanager.service.HabitManager.HabitDetails;
import habitmanager.settings.Settings;
import habitmanager.settings.SettingsStorage;
import habitmanager.storage.AutoBackup;
import habitmanager.storage.FileStorage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Application-level facade used by the web UI, the tray icon and the reminder scheduler:
 * looks habits up by name, applies a change through HabitManager and saves straight away.
 * All methods are synchronized so the web server and the reminder thread never interleave
 * a change and a save.
 *
 * Every change first takes a snapshot of all habits, so {@link #undoLast()} can roll back
 * any of the last {@link #UNDO_LIMIT} changes, even a delete or a demo-data load.
 *
 * Validation problems surface as IllegalArgumentException; save failures as
 * UncheckedIOException.
 */
public class HabitService {

    public static final int UNDO_LIMIT = 30;

    /** The state before a change, and a short description of that change. */
    private record Snapshot(String label, List<String> lines) {
    }

    /** What an import did, plus any lines of the file that had to be skipped. */
    public record ImportReport(DataExchange.ImportResult result, List<String> warnings) {
    }

    private final HabitManager manager;
    private final FileStorage storage;
    private final SettingsStorage settingsStorage;
    private final Clock clock;
    private final ReminderEngine reminders = new ReminderEngine();
    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();
    private final Deque<Snapshot> undoStack = new ArrayDeque<>();
    private AutoBackup autoBackup;
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

    /** Turns on the daily safety copy in a "backups" folder next to the data file. */
    public synchronized void enableAutoBackup() {
        autoBackup = new AutoBackup(storage.getFile());
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
        undoStack.clear();
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
        Snapshot before = snapshot("add \"" + clean(details.name()) + "\"");
        Habit habit = manager.addHabit(details, today());
        commit(before);
        return habit;
    }

    public synchronized void updateHabit(String name, HabitDetails details) {
        Habit habit = require(name);
        Snapshot before = snapshot("edit \"" + habit.getName() + "\"");
        manager.updateHabit(habit, details);
        commit(before);
    }

    public synchronized void renameHabit(String name, String newName) {
        Habit habit = require(name);
        Snapshot before = snapshot("rename \"" + habit.getName() + "\"");
        manager.renameHabit(habit, newName);
        commit(before);
    }

    public synchronized void deleteHabit(String name) {
        Habit habit = require(name);
        Snapshot before = snapshot("delete \"" + habit.getName() + "\"");
        manager.deleteHabit(habit);
        commit(before);
    }

    public synchronized void setArchived(String name, boolean archived) {
        Habit habit = require(name);
        Snapshot before = snapshot((archived ? "archive" : "restore") + " \"" + habit.getName() + "\"");
        manager.setArchived(habit, archived);
        commit(before);
    }

    public synchronized void moveHabit(String name, int newIndex) {
        Habit habit = require(name);
        Snapshot before = snapshot("reorder habits");
        manager.moveHabit(habit, newIndex);
        commit(before);
    }

    /** Returns false if the habit was already done today. */
    public synchronized boolean checkIn(String name) {
        Habit habit = require(name);
        Snapshot before = snapshot("check in \"" + habit.getName() + "\"");
        boolean changed = manager.checkIn(habit, today());
        if (changed) {
            commit(before);
        }
        return changed;
    }

    /** Returns false if the habit was not done today. */
    public synchronized boolean undoToday(String name) {
        Habit habit = require(name);
        Snapshot before = snapshot("uncheck \"" + habit.getName() + "\"");
        boolean changed = manager.undoCheckIn(habit, today());
        if (changed) {
            commit(before);
        }
        return changed;
    }

    /** Flips a single date on or off. Returns true if the date is now done. */
    public synchronized boolean toggleDate(String name, LocalDate date) {
        Habit habit = require(name);
        Snapshot before = snapshot("change " + date + " for \"" + habit.getName() + "\"");
        boolean nowDone = manager.toggleDate(habit, date, today());
        commit(before);
        return nowDone;
    }

    /** One more (or one less) towards the daily target. Returns the new count. */
    public synchronized int addProgress(String name, LocalDate date, int delta) {
        Habit habit = require(name);
        Snapshot before = snapshot("update \"" + habit.getName() + "\" progress");
        int count = manager.addProgress(habit, date, delta, today());
        commit(before);
        return count;
    }

    public synchronized void setNote(String name, LocalDate date, String note) {
        Habit habit = require(name);
        Snapshot before = snapshot("edit the note for \"" + habit.getName() + "\"");
        manager.setNote(habit, date, note, today());
        commit(before);
    }

    public synchronized void loadDemoData() {
        Snapshot before = snapshot("load demo data");
        manager.replaceAll(DemoDataGenerator.generate(today()));
        commit(before);
    }

    // ---------------------------------------------------------------- undo

    /** Rolls back the most recent change and returns what was undone, e.g. "check in \"Run\"". */
    public synchronized String undoLast() {
        Snapshot snapshot = undoStack.pollFirst();
        if (snapshot == null) {
            throw new IllegalArgumentException("Nothing to undo.");
        }
        manager.replaceAll(FileStorage.parseLines(snapshot.lines(), "undo history").habits());
        save();
        return snapshot.label();
    }

    /** What Ctrl+Z would undo right now, or null if there is nothing. */
    public synchronized String undoLabel() {
        Snapshot top = undoStack.peekFirst();
        return top == null ? null : top.label();
    }

    private Snapshot snapshot(String label) {
        return new Snapshot(label, FileStorage.toLines(manager.listHabits()));
    }

    /** Remembers the state from before a successful change and saves the new state. */
    private void commit(Snapshot before) {
        undoStack.addFirst(before);
        while (undoStack.size() > UNDO_LIMIT) {
            undoStack.removeLast();
        }
        save();
    }

    // ---------------------------------------------------------------- export & import

    /** All check ins as CSV, for spreadsheets. */
    public synchronized String exportCsv() {
        return DataExchange.toCsv(manager.listHabits());
    }

    /** A complete backup: the same text as habits.txt. */
    public synchronized String exportBackup() {
        return String.join("\n", FileStorage.toLines(manager.listHabits())) + "\n";
    }

    /**
     * Imports a backup (or an old habits.txt in either format). With replace, it becomes the
     * whole list; otherwise it is merged in. Can be undone like any other change.
     */
    public synchronized ImportReport importBackup(String text, boolean replace) {
        FileStorage.LoadResult parsed = FileStorage.parseLines(text.lines().toList(), "the imported file");
        if (parsed.habits().isEmpty()) {
            throw new IllegalArgumentException(parsed.warnings().isEmpty()
                    ? "That file doesn't contain any habits."
                    : "No habits could be read from that file. " + parsed.warnings().get(0));
        }
        Snapshot before = snapshot(replace ? "replace habits from a backup" : "import a backup");
        DataExchange.ImportResult result = DataExchange.importHabits(manager, parsed.habits(), replace);
        commit(before);
        return new ImportReport(result, parsed.warnings());
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
            if (autoBackup != null) {
                autoBackup.backUpIfNeeded(today());
            }
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

    private static String clean(String name) {
        return name == null ? "" : name.trim();
    }
}
