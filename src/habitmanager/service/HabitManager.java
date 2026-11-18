package habitmanager.service;

import habitmanager.model.Habit;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Owns the collection of habits and enforces all the rules for changing them.
 * Habits are keyed by lowercase name so lookups and uniqueness are case insensitive,
 * and a LinkedHashMap keeps them in the order they were added.
 *
 * Validation failures are reported with IllegalArgumentException carrying a
 * user-friendly message.
 */
public class HabitManager {

    public static final String FORBIDDEN_CHARACTER = "|";

    private final Map<String, Habit> habits = new LinkedHashMap<>();

    public Habit addHabit(String rawName, LocalDate today) {
        String name = validateName(rawName, null);
        Habit habit = new Habit(name, today);
        habits.put(keyOf(name), habit);
        return habit;
    }

    public void renameHabit(Habit habit, String rawNewName) {
        String newName = validateName(rawNewName, habit);
        String oldKey = keyOf(habit.getName());

        // Rebuild the map so the renamed habit keeps its position in the list.
        Map<String, Habit> reordered = new LinkedHashMap<>();
        for (Map.Entry<String, Habit> entry : habits.entrySet()) {
            if (entry.getKey().equals(oldKey)) {
                reordered.put(keyOf(newName), habit);
            } else {
                reordered.put(entry.getKey(), entry.getValue());
            }
        }
        habit.setName(newName);
        habits.clear();
        habits.putAll(reordered);
    }

    public boolean deleteHabit(Habit habit) {
        return habits.remove(keyOf(habit.getName())) != null;
    }

    public Optional<Habit> findHabit(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(habits.get(keyOf(name.trim())));
    }

    public List<Habit> listHabits() {
        return new ArrayList<>(habits.values());
    }

    public boolean isEmpty() {
        return habits.isEmpty();
    }

    public int size() {
        return habits.size();
    }

    /** Marks the habit done today. Returns false if it was already done. */
    public boolean checkIn(Habit habit, LocalDate today) {
        return habit.markDone(today);
    }

    /** Removes today's check in. Returns false if the habit was not done today. */
    public boolean undoCheckIn(Habit habit, LocalDate today) {
        return habit.undo(today);
    }

    /**
     * Marks the habit done on a past date. Rejects future dates and dates before
     * the habit existed. Returns false if that date was already marked done.
     */
    public boolean backfill(Habit habit, LocalDate date, LocalDate today) {
        requireTrackableDate(habit, date, today);
        return habit.markDone(date);
    }

    /**
     * Flips the done state of any trackable date (used by the calendar view).
     * Returns true if the date is now done, false if it was just undone.
     */
    public boolean toggleDate(Habit habit, LocalDate date, LocalDate today) {
        requireTrackableDate(habit, date, today);
        if (habit.isDoneOn(date)) {
            habit.undo(date);
            return false;
        }
        habit.markDone(date);
        return true;
    }

    /** A date can be tracked if it is between the habit's creation and today, inclusive. */
    private static void requireTrackableDate(Habit habit, LocalDate date, LocalDate today) {
        if (date.isAfter(today)) {
            throw new IllegalArgumentException("You cannot backfill a future date.");
        }
        if (date.isBefore(habit.getCreatedDate())) {
            throw new IllegalArgumentException("That date is before the habit was created ("
                    + habit.getCreatedDate() + ").");
        }
    }

    /** Replaces every habit, e.g. after loading from file or loading demo data. */
    public void replaceAll(Collection<Habit> newHabits) {
        habits.clear();
        for (Habit habit : newHabits) {
            habits.putIfAbsent(keyOf(habit.getName()), habit);
        }
    }

    /**
     * Checks a proposed name and returns it trimmed.
     *
     * @param habitBeingRenamed the habit allowed to already own this name (so "run" can
     *                          become "Run"), or null when adding a new habit
     */
    public String validateName(String rawName, Habit habitBeingRenamed) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Habit name cannot be blank.");
        }
        if (name.contains(FORBIDDEN_CHARACTER)) {
            throw new IllegalArgumentException("Habit name cannot contain the '|' character.");
        }
        Habit existing = habits.get(keyOf(name));
        if (existing != null && existing != habitBeingRenamed) {
            throw new IllegalArgumentException("A habit named \"" + existing.getName() + "\" already exists.");
        }
        return name;
    }

    private static String keyOf(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
