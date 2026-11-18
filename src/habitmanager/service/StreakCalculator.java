package habitmanager.service;

import habitmanager.model.Habit;
import habitmanager.model.Schedule;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.NavigableSet;

/**
 * Calculates current and longest streaks, respecting each habit's schedule:
 * <ul>
 *   <li>Every day: consecutive completed days.</li>
 *   <li>Specific weekdays: consecutive completed <em>scheduled</em> days. Rest days are
 *       skipped, so a Mon/Wed/Fri habit keeps its streak over Tuesday.</li>
 *   <li>X times a week: consecutive weeks (Mon-Sun) in which the goal was met.</li>
 * </ul>
 */
public final class StreakCalculator {

    private StreakCalculator() {
    }

    /** "week" for times-per-week habits, otherwise "day". */
    public static String unit(Habit habit) {
        return habit.getSchedule().isWeekly() ? "week" : "day";
    }

    /**
     * Counts the current run backwards from today.
     *
     * If today (or this week, for weekly habits) is not complete yet, it is still "open":
     * we start counting from the previous scheduled day / week instead, so the streak does
     * not drop to 0 just because the user has not checked in yet.
     */
    public static int currentStreak(Habit habit, LocalDate today) {
        return habit.getSchedule().isWeekly()
                ? currentWeekStreak(habit, today)
                : currentDayStreak(habit, today);
    }

    private static int currentDayStreak(Habit habit, LocalDate today) {
        Schedule schedule = habit.getSchedule();
        LocalDate floor = earliestTrackedDay(habit);
        LocalDate day = habit.isDoneOn(today) && schedule.isScheduledOn(today) ? today : today.minusDays(1);
        int streak = 0;
        for (; !day.isBefore(floor); day = day.minusDays(1)) {
            if (!schedule.isScheduledOn(day)) {
                continue; // rest days neither count nor break the streak
            }
            if (!habit.isDoneOn(day)) {
                break;
            }
            streak++;
        }
        return streak;
    }

    private static int currentWeekStreak(Habit habit, LocalDate today) {
        int goal = habit.getSchedule().getTimesPerWeek();
        LocalDate week = weekStart(today);
        if (completionsInWeek(habit, week) < goal) {
            week = week.minusWeeks(1); // this week is still open
        }
        LocalDate floor = weekStart(earliestTrackedDay(habit));
        int streak = 0;
        while (!week.isBefore(floor) && completionsInWeek(habit, week) >= goal) {
            streak++;
            week = week.minusWeeks(1);
        }
        return streak;
    }

    /**
     * Finds the longest run ever.
     *
     * For daily habits the completed dates are already sorted, so a single pass is enough:
     * a run continues while each date is exactly one day after the previous one.
     * LocalDate.plusDays handles month ends and leap years for us.
     */
    public static int longestStreak(Habit habit) {
        NavigableSet<LocalDate> done = habit.getCompletedDates();
        if (done.isEmpty()) {
            return 0;
        }
        return switch (habit.getSchedule().getType()) {
            case DAILY -> longestDailyRun(done);
            case WEEKDAYS -> longestScheduledRun(habit, done);
            case WEEKLY -> longestWeekRun(habit, done);
        };
    }

    private static int longestDailyRun(NavigableSet<LocalDate> done) {
        int longest = 0;
        int run = 0;
        LocalDate previous = null;
        for (LocalDate current : done) {
            boolean continuesRun = previous != null && previous.plusDays(1).equals(current);
            run = continuesRun ? run + 1 : 1;
            longest = Math.max(longest, run);
            previous = current;
        }
        return longest;
    }

    /** Walks every scheduled day between the first and last completion; rest days are skipped. */
    private static int longestScheduledRun(Habit habit, NavigableSet<LocalDate> done) {
        Schedule schedule = habit.getSchedule();
        int longest = 0;
        int run = 0;
        for (LocalDate day = done.first(); !day.isAfter(done.last()); day = day.plusDays(1)) {
            if (!schedule.isScheduledOn(day)) {
                continue;
            }
            run = done.contains(day) ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        return longest;
    }

    private static int longestWeekRun(Habit habit, NavigableSet<LocalDate> done) {
        int goal = habit.getSchedule().getTimesPerWeek();
        int longest = 0;
        int run = 0;
        for (LocalDate week = weekStart(done.first()); !week.isAfter(done.last()); week = week.plusWeeks(1)) {
            run = completionsInWeek(habit, week) >= goal ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        return longest;
    }

    // ---------------------------------------------------------------- shared date helpers

    /** The Monday of the week containing the date. */
    public static LocalDate weekStart(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /** Completed days in [from, to], both inclusive. */
    public static int completionsBetween(Habit habit, LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            return 0;
        }
        return habit.getCompletedDates().subSet(from, true, to, true).size();
    }

    public static int completionsInWeek(Habit habit, LocalDate monday) {
        return completionsBetween(habit, monday, monday.plusDays(6));
    }

    /** The created date, or an earlier check in if the data file has one. */
    private static LocalDate earliestTrackedDay(Habit habit) {
        NavigableSet<LocalDate> done = habit.getCompletedDates();
        LocalDate created = habit.getCreatedDate();
        return done.isEmpty() || !done.first().isBefore(created) ? created : done.first();
    }
}
