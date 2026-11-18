package habitmanager.service;

import habitmanager.model.CheckIn;
import habitmanager.model.Habit;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Export to CSV, and merging habits from a backup into the current ones. */
public final class DataExchange {

    private DataExchange() {
    }

    /** What an import did. */
    public record ImportResult(int added, int merged, int replaced) {
    }

    /**
     * One row per tracked day: habit, date, count, target, done, note.
     * Fields are quoted when needed, so the file opens cleanly in Excel or Google Sheets.
     */
    public static String toCsv(List<Habit> habits) {
        StringBuilder csv = new StringBuilder("habit,date,count,target,done,note\r\n");
        for (Habit habit : habits) {
            for (Map.Entry<LocalDate, CheckIn> entry : habit.getCheckIns().entrySet()) {
                CheckIn checkIn = entry.getValue();
                csv.append(csvField(habit.getName())).append(',')
                        .append(entry.getKey()).append(',')
                        .append(checkIn.count()).append(',')
                        .append(habit.getTarget()).append(',')
                        .append(checkIn.count() >= habit.getTarget() ? "yes" : "no").append(',')
                        .append(csvField(checkIn.note() == null ? "" : checkIn.note()))
                        .append("\r\n");
            }
        }
        return csv.toString();
    }

    /** Quotes a field if it contains a comma, quote or line break (RFC 4180). */
    static String csvField(String value) {
        // A leading =, +, - or @ would be treated as a formula by spreadsheet apps.
        String safe = !value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }

    /**
     * Brings habits from a backup into the manager.
     * <ul>
     *   <li>replace: the backup becomes the whole list.</li>
     *   <li>merge: new habits are added; for a habit that already exists (same name), each
     *       day keeps the higher count, and an existing note wins over the imported one.
     *       The existing habit's settings (schedule, target, reminder...) are kept.</li>
     * </ul>
     */
    public static ImportResult importHabits(HabitManager manager, List<Habit> imported, boolean replace) {
        if (replace) {
            manager.replaceAll(imported);
            return new ImportResult(0, 0, imported.size());
        }
        int added = 0;
        int merged = 0;
        List<Habit> combined = new ArrayList<>(manager.listHabits());
        for (Habit incoming : imported) {
            Habit existing = manager.findHabit(incoming.getName()).orElse(null);
            if (existing == null) {
                combined.add(incoming);
                added++;
            } else {
                mergeInto(existing, incoming);
                merged++;
            }
        }
        manager.replaceAll(combined);
        return new ImportResult(added, merged, 0);
    }

    private static void mergeInto(Habit existing, Habit incoming) {
        for (Map.Entry<LocalDate, CheckIn> entry : incoming.getCheckIns().entrySet()) {
            LocalDate date = entry.getKey();
            CheckIn theirs = entry.getValue();
            // Scale a completed day to this habit's target, so "done" stays "done".
            int theirCount = theirs.count() >= incoming.getTarget() ? existing.getTarget() : theirs.count();
            int count = Math.max(existing.getCount(date), Math.min(theirCount, existing.getTarget()));
            String note = existing.getNote(date) != null ? existing.getNote(date) : theirs.note();
            existing.restoreCheckIn(date, new CheckIn(count, note));
        }
    }
}
