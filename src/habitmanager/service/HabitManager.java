package habitmanager.service;

import habitmanager.model.Habit;
import habitmanager.model.Schedule;

import java.time.LocalDate;
import java.time.LocalTime;
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
 * and a LinkedHashMap keeps them in the user's chosen order.
 *
 * Validation failures are reported with IllegalArgumentException carrying a
 * user-friendly message.
 */
public class HabitManager {

    public static final String FORBIDDEN_CHARACTER = "|";
    /** Number of colours in the dashboard palette. */
    public static final int PALETTE_SIZE = 6;
    private static final int MAX_EMOJI_LENGTH = 16;
    private static final int MAX_NAME_LENGTH = 60;

    /** Everything about a habit the user can edit. Null reminder / colour / emoji mean "none". */
    public record HabitDetails(String name, Schedule schedule, int target,
                               LocalTime reminderTime, Integer colorIndex, String emoji) {

        /** A plain daily habit with no extras. */
        public static HabitDetails simple(String name) {
            return new HabitDetails(name, Schedule.daily(), 1, null, null, null);
        }
    }

    private final Map<String, Habit> habits = new LinkedHashMap<>();

    // ---------------------------------------------------------------- add, edit, delete

    public Habit addHabit(String rawName, LocalDate today) {
        return addHabit(HabitDetails.simple(rawName), today);
    }

    public Habit addHabit(HabitDetails details, LocalDate today) {
        String name = validateName(details.name(), null);
        validateExtras(details);
        Habit habit = new Habit(name, today);
        applyExtras(habit, details);
        habits.put(keyOf(name), habit);
        return habit;
    }

    /** Validates everything first, then applies it, so a bad field never leaves a half-edited habit. */
    public void updateHabit(Habit habit, HabitDetails details) {
        String newName = validateName(details.name(), habit);
        validateExtras(details);
        if (!newName.equals(habit.getName())) {
            renameHabit(habit, newName);
        }
        applyExtras(habit, details);
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

    /** Archived habits keep their history but leave the daily list, stats and reminders. */
    public void setArchived(Habit habit, boolean archived) {
        habit.setArchived(archived);
    }

    /** Moves a habit to a new position (0 = first). Out-of-range positions are clamped. */
    public void moveHabit(Habit habit, int newIndex) {
        List<Habit> ordered = listHabits();
        ordered.remove(habit);
        ordered.add(Math.max(0, Math.min(newIndex, ordered.size())), habit);
        habits.clear();
        ordered.forEach(h -> habits.put(keyOf(h.getName()), h));
    }

    // ---------------------------------------------------------------- lookup

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

    // ---------------------------------------------------------------- daily tracking

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

    /** Adds or removes progress towards the daily target (e.g. one more glass). Returns the new count. */
    public int addProgress(Habit habit, LocalDate date, int delta, LocalDate today) {
        requireTrackableDate(habit, date, today);
        return habit.addProgress(date, delta);
    }

    /** Sets or clears (blank) the note for a day. */
    public void setNote(Habit habit, LocalDate date, String note, LocalDate today) {
        requireTrackableDate(habit, date, today);
        habit.setNote(date, note);
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

    // ---------------------------------------------------------------- bulk

    /** Replaces every habit, e.g. after loading from file or loading demo data. */
    public void replaceAll(Collection<Habit> newHabits) {
        habits.clear();
        for (Habit habit : newHabits) {
            habits.putIfAbsent(keyOf(habit.getName()), habit);
        }
    }

    // ---------------------------------------------------------------- validation

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
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Habit name can be at most " + MAX_NAME_LENGTH + " characters.");
        }
        Habit existing = habits.get(keyOf(name));
        if (existing != null && existing != habitBeingRenamed) {
            throw new IllegalArgumentException("A habit named \"" + existing.getName() + "\" already exists.");
        }
        return name;
    }

    private static void validateExtras(HabitDetails details) {
        if (details.schedule() == null) {
            throw new IllegalArgumentException("Choose how often the habit should happen.");
        }
        if (details.target() < 1 || details.target() > Habit.MAX_TARGET) {
            throw new IllegalArgumentException("The daily target must be between 1 and " + Habit.MAX_TARGET + ".");
        }
        Integer color = details.colorIndex();
        if (color != null && (color < 0 || color >= PALETTE_SIZE)) {
            throw new IllegalArgumentException("Unknown colour.");
        }
        if (details.emoji() != null && details.emoji().strip().length() > MAX_EMOJI_LENGTH) {
            throw new IllegalArgumentException("Pick a single emoji.");
        }
    }

    private static void applyExtras(Habit habit, HabitDetails details) {
        habit.setSchedule(details.schedule());
        habit.setTarget(details.target());
        habit.setReminderTime(details.reminderTime());
        habit.setColorIndex(details.colorIndex());
        habit.setEmoji(details.emoji());
    }

    private static String keyOf(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
