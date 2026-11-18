package habitmanager.model;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A single habit: its name, the day it was created and what happened on each day.
 *
 * Each day can hold a count (towards the daily target) and a note. A day is "done"
 * once its count reaches the target. With the default target of 1 this behaves exactly
 * like a simple done / not done habit.
 */
public class Habit {

    public static final int MAX_TARGET = 99;

    private String name;
    private final LocalDate createdDate;
    private final TreeMap<LocalDate, CheckIn> checkIns = new TreeMap<>();

    private Schedule schedule = Schedule.daily();
    private int target = 1;
    private LocalTime reminderTime;
    private Integer colorIndex;
    private String emoji;
    private boolean archived;

    public Habit(String name, LocalDate createdDate) {
        this(name, createdDate, Collections.emptyList());
    }

    public Habit(String name, LocalDate createdDate, Collection<LocalDate> completedDates) {
        this.name = Objects.requireNonNull(name, "name");
        this.createdDate = Objects.requireNonNull(createdDate, "createdDate");
        completedDates.forEach(this::markDone);
    }

    // ---------------------------------------------------------------- daily progress

    /** Marks the habit fully done on the date (count = target). Returns false if it was already done. */
    public boolean markDone(LocalDate date) {
        Objects.requireNonNull(date, "date");
        if (isDoneOn(date)) {
            return false;
        }
        checkIns.put(date, new CheckIn(target, getNote(date)));
        return true;
    }

    /** Clears the progress for the date but keeps any note. Returns false if there was nothing to undo. */
    public boolean undo(LocalDate date) {
        if (getCount(date) == 0) {
            return false;
        }
        store(date, new CheckIn(0, getNote(date)));
        return true;
    }

    /** Adds (or with a negative delta removes) progress, kept between 0 and the target. Returns the new count. */
    public int addProgress(LocalDate date, int delta) {
        int count = Math.max(0, Math.min(target, getCount(date) + delta));
        store(date, new CheckIn(count, getNote(date)));
        return count;
    }

    public void setNote(LocalDate date, String note) {
        if (note != null && note.strip().length() > CheckIn.MAX_NOTE_LENGTH) {
            throw new IllegalArgumentException("Notes can be at most " + CheckIn.MAX_NOTE_LENGTH + " characters.");
        }
        store(date, new CheckIn(getCount(date), note));
    }

    /** Used when loading from file: stores a day exactly as saved. */
    public void restoreCheckIn(LocalDate date, CheckIn checkIn) {
        store(date, new CheckIn(Math.min(checkIn.count(), target), checkIn.note()));
    }

    public boolean isDoneOn(LocalDate date) {
        return getCount(date) >= target;
    }

    public int getCount(LocalDate date) {
        CheckIn checkIn = checkIns.get(date);
        return checkIn == null ? 0 : checkIn.count();
    }

    public String getNote(LocalDate date) {
        CheckIn checkIn = checkIns.get(date);
        return checkIn == null ? null : checkIn.note();
    }

    /** Sorted, read-only set of the days on which the target was reached. */
    public NavigableSet<LocalDate> getCompletedDates() {
        TreeSet<LocalDate> done = new TreeSet<>();
        checkIns.forEach((date, checkIn) -> {
            if (checkIn.count() >= target) {
                done.add(date);
            }
        });
        return Collections.unmodifiableNavigableSet(done);
    }

    /** Every stored day (including partial progress and notes), read-only and sorted. */
    public NavigableMap<LocalDate, CheckIn> getCheckIns() {
        return Collections.unmodifiableNavigableMap(checkIns);
    }

    private void store(LocalDate date, CheckIn checkIn) {
        Objects.requireNonNull(date, "date");
        if (checkIn.isEmpty()) {
            checkIns.remove(date);
        } else {
            checkIns.put(date, checkIn);
        }
    }

    // ---------------------------------------------------------------- settings of the habit

    public String getName() {
        return name;
    }

    /** Only HabitManager should call this, because it also has to re-key its map. */
    public void setName(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    public LocalDate getCreatedDate() {
        return createdDate;
    }

    public Schedule getSchedule() {
        return schedule;
    }

    public void setSchedule(Schedule schedule) {
        this.schedule = Objects.requireNonNull(schedule, "schedule");
    }

    public int getTarget() {
        return target;
    }

    /**
     * Changes how many times a day the habit must be done. Days that were complete
     * stay complete, so raising the target never erases your history.
     */
    public void setTarget(int newTarget) {
        if (newTarget < 1 || newTarget > MAX_TARGET) {
            throw new IllegalArgumentException("The daily target must be between 1 and " + MAX_TARGET + ".");
        }
        for (Map.Entry<LocalDate, CheckIn> entry : checkIns.entrySet()) {
            CheckIn old = entry.getValue();
            int count = old.count() >= target ? newTarget : Math.min(old.count(), newTarget);
            entry.setValue(new CheckIn(count, old.note()));
        }
        checkIns.values().removeIf(CheckIn::isEmpty);
        this.target = newTarget;
    }

    /** Raw setter used while loading, before any check ins exist. */
    public void restoreTarget(int savedTarget) {
        if (!checkIns.isEmpty()) {
            throw new IllegalStateException("restoreTarget must be called before check ins are restored");
        }
        setTarget(savedTarget);
    }

    /** Null when the habit has no reminder. */
    public LocalTime getReminderTime() {
        return reminderTime;
    }

    public void setReminderTime(LocalTime reminderTime) {
        this.reminderTime = reminderTime;
    }

    /** Index into the dashboard's colour palette, or null to pick one automatically. */
    public Integer getColorIndex() {
        return colorIndex;
    }

    public void setColorIndex(Integer colorIndex) {
        this.colorIndex = colorIndex;
    }

    /** Optional emoji shown next to the name, or null. */
    public String getEmoji() {
        return emoji;
    }

    public void setEmoji(String emoji) {
        this.emoji = (emoji == null || emoji.isBlank()) ? null : emoji.strip();
    }

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
    }

    @Override
    public String toString() {
        return name;
    }
}
