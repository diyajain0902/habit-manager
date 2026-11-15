package habitmanager.service;

import habitmanager.model.Habit;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Random;

/**
 * Builds three sample habits created 30 days before {@code today}, each with a
 * recognisable pattern. A fixed random seed keeps the demo reproducible.
 */
public final class DemoDataGenerator {

    public static final int DAYS_OF_HISTORY = 30;

    private DemoDataGenerator() {
    }

    public static List<Habit> generate(LocalDate today) {
        LocalDate created = today.minusDays(DAYS_OF_HISTORY);
        return List.of(
                veryConsistent(created, today),
                strongOnWeekdays(created, today),
                patchyWithRecentStreak(created, today));
    }

    /** Done almost every day, including today. Missed only twice. */
    private static Habit veryConsistent(LocalDate created, LocalDate today) {
        Habit habit = new Habit("Drink 2L water", created);
        for (LocalDate day = created; !day.isAfter(today); day = day.plusDays(1)) {
            habit.markDone(day);
        }
        habit.undo(created.plusDays(8));
        habit.undo(created.plusDays(19));
        return habit;
    }

    /** Done on nearly every weekday, rarely at the weekend. Not done yet today. */
    private static Habit strongOnWeekdays(LocalDate created, LocalDate today) {
        Habit habit = new Habit("Morning run", created);
        Random random = new Random(42);
        for (LocalDate day = created; day.isBefore(today); day = day.plusDays(1)) {
            boolean weekend = day.getDayOfWeek() == DayOfWeek.SATURDAY
                    || day.getDayOfWeek() == DayOfWeek.SUNDAY;
            double chance = weekend ? 0.15 : 0.9;
            if (random.nextDouble() < chance) {
                habit.markDone(day);
            }
        }
        return habit;
    }

    /**
     * Patchy for most of the month, then a clean 4 day streak ending yesterday.
     * Today is not done, which shows the streak still counting from yesterday.
     */
    private static Habit patchyWithRecentStreak(LocalDate created, LocalDate today) {
        Habit habit = new Habit("Read 20 pages", created);
        Random random = new Random(7);
        LocalDate streakStart = today.minusDays(4);
        LocalDate gapStart = streakStart.minusDays(2);
        for (LocalDate day = created; day.isBefore(gapStart); day = day.plusDays(1)) {
            if (random.nextDouble() < 0.4) {
                habit.markDone(day);
            }
        }
        for (LocalDate day = streakStart; day.isBefore(today); day = day.plusDays(1)) {
            habit.markDone(day);
        }
        return habit;
    }
}
