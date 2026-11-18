package habitmanager.service;

import habitmanager.model.Habit;
import habitmanager.storage.FileStorage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * Application-level facade used by the web UI: looks habits up by name, applies a
 * change through HabitManager and saves straight away. All methods are synchronized
 * so concurrent HTTP requests cannot interleave a change and a save.
 *
 * Validation problems surface as IllegalArgumentException; save failures as
 * UncheckedIOException.
 */
public class HabitService {

    private final HabitManager manager;
    private final FileStorage storage;
    private final Clock clock;

    public HabitService(HabitManager manager, FileStorage storage, Clock clock) {
        this.manager = manager;
        this.storage = storage;
        this.clock = clock;
    }

    /** Loads habits from storage and returns a warning for every skipped line. */
    public synchronized List<String> load() throws IOException {
        FileStorage.LoadResult result = storage.load();
        manager.replaceAll(result.habits());
        return result.warnings();
    }

    /** Read fresh on every call so a long-running server rolls over at midnight. */
    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public synchronized List<Habit> habits() {
        return manager.listHabits();
    }

    public synchronized Habit addHabit(String name) {
        Habit habit = manager.addHabit(name, today());
        save();
        return habit;
    }

    public synchronized void renameHabit(String name, String newName) {
        manager.renameHabit(require(name), newName);
        save();
    }

    public synchronized void deleteHabit(String name) {
        manager.deleteHabit(require(name));
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

    public synchronized void loadDemoData() {
        manager.replaceAll(DemoDataGenerator.generate(today()));
        save();
    }

    public synchronized void save() {
        try {
            storage.save(manager.listHabits());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save to " + storage.getFile(), e);
        }
    }

    private Habit require(String name) {
        return manager.findHabit(name)
                .orElseThrow(() -> new IllegalArgumentException("No habit named \"" + name + "\"."));
    }
}
