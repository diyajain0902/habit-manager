package habitmanager.service;

import habitmanager.model.Habit;
import habitmanager.model.Schedule;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;

/**
 * Builds four sample habits created 30 days before {@code today}, each showing off a
 * different feature (targets, weekday schedules, notes, weekly goals). A fixed random
 * seed keeps the demo reproducible.
 */
public final class DemoDataGenerator {

    public static final int DAYS_OF_HISTORY = 30;

    private DemoDataGenerator() {
    }

    public static List<Habit> generate(LocalDate today) {
        LocalDate created = today.minusDays(DAYS_OF_HISTORY);
        return List.of(
                veryConsistentWithTarget(created, today),
                strongOnWeekdays(created, today),
                patchyWithRecentStreak(created, today),
                threeTimesAWeek(created, today));
    }

    /** 8 glasses a day, nearly always reached. Today is part way there (3 of 8). */
    private static Habit veryConsistentWithTarget(LocalDate created, LocalDate today) {
        Habit habit = new Habit("Drink water", created);
        habit.setTarget(8);
        habit.setEmoji(emoji(0x1F4A7)); // droplet
        habit.setColorIndex(4);
        habit.setReminderTime(LocalTime.of(10, 0));
        for (LocalDate day = created; day.isBefore(today); day = day.plusDays(1)) {
            habit.markDone(day);
        }
        habit.undo(created.plusDays(8));
        habit.addProgress(created.plusDays(8), 5);
        habit.undo(created.plusDays(19));
        habit.addProgress(created.plusDays(19), 6);
        habit.addProgress(today, 3);
        return habit;
    }

    /** Scheduled Monday to Friday and done on nearly all of them. Not done yet today. */
    private static Habit strongOnWeekdays(LocalDate created, LocalDate today) {
        Habit habit = new Habit("Morning run", created);
        habit.setSchedule(Schedule.onDays(EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)));
        habit.setEmoji(emoji(0x1F3C3)); // runner
        habit.setColorIndex(1);
        habit.setReminderTime(LocalTime.of(7, 0));
        Random random = new Random(42);
        for (LocalDate day = created; day.isBefore(today); day = day.plusDays(1)) {
            if (habit.getSchedule().isScheduledOn(day) && random.nextDouble() < 0.9) {
                habit.markDone(day);
            }
        }
        habit.setNote(today.minusDays(1), "5 km along the river, felt great");
        return habit;
    }

    /**
     * Patchy for most of the month, then a clean 4 day streak ending yesterday.
     * Today is not done, which shows the streak still counting from yesterday.
     */
    private static Habit patchyWithRecentStreak(LocalDate created, LocalDate today) {
        Habit habit = new Habit("Read 20 pages", created);
        habit.setEmoji(emoji(0x1F4DA)); // books
        habit.setColorIndex(5);
        habit.setReminderTime(LocalTime.of(21, 0));
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
        habit.setNote(today.minusDays(2), "Finished the first book of the series!");
        return habit;
    }

    /** Three times a week on any days; the goal is met most weeks. */
    private static Habit threeTimesAWeek(LocalDate created, LocalDate today) {
        Habit habit = new Habit("Yoga", created);
        habit.setSchedule(Schedule.timesPerWeek(3));
        habit.setEmoji(emoji(0x1F9D8)); // person in lotus position
        habit.setColorIndex(3);
        Random random = new Random(11);
        for (LocalDate day = created; day.isBefore(today); day = day.plusDays(1)) {
            if (random.nextDouble() < 0.5) {
                habit.markDone(day);
            }
        }
        return habit;
    }

    /** Emoji are built from code points so the source file stays plain ASCII. */
    private static String emoji(int codePoint) {
        return new String(Character.toChars(codePoint));
    }
}
