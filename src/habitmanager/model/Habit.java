package habitmanager.model;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Collections;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.TreeSet;

/**
 * A single habit: its name, the day it was created and every day it was completed.
 * Completed dates are kept sorted so streak calculations can walk them in order.
 */
public class Habit {

    private String name;
    private final LocalDate createdDate;
    private final TreeSet<LocalDate> completedDates = new TreeSet<>();

    public Habit(String name, LocalDate createdDate) {
        this(name, createdDate, Collections.emptyList());
    }

    public Habit(String name, LocalDate createdDate, Collection<LocalDate> completedDates) {
        this.name = Objects.requireNonNull(name, "name");
        this.createdDate = Objects.requireNonNull(createdDate, "createdDate");
        this.completedDates.addAll(completedDates);
    }

    /** Marks the habit as done on the given date. Returns false if it was already done. */
    public boolean markDone(LocalDate date) {
        return completedDates.add(Objects.requireNonNull(date, "date"));
    }

    /** Removes the check in for the given date. Returns false if there was nothing to undo. */
    public boolean undo(LocalDate date) {
        return completedDates.remove(date);
    }

    public boolean isDoneOn(LocalDate date) {
        return completedDates.contains(date);
    }

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

    /** Read-only, sorted view of the completed dates. */
    public NavigableSet<LocalDate> getCompletedDates() {
        return Collections.unmodifiableNavigableSet(completedDates);
    }

    @Override
    public String toString() {
        return name;
    }
}
