package habitmanager.model;

/**
 * What happened on one day: how many times the habit was done (for targets such as
 * "8 glasses") and an optional note. A simple habit has a target of 1, so count is 0 or 1.
 */
public record CheckIn(int count, String note) {

    public static final int MAX_NOTE_LENGTH = 280;

    public CheckIn {
        if (count < 0) {
            throw new IllegalArgumentException("count cannot be negative");
        }
        note = (note == null || note.isBlank()) ? null : note.strip();
    }

    public boolean hasNote() {
        return note != null;
    }

    /** True when there is nothing worth storing (no progress and no note). */
    public boolean isEmpty() {
        return count == 0 && note == null;
    }
}
