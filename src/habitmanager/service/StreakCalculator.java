package habitmanager.service;

import habitmanager.model.Habit;

import java.time.LocalDate;

/** Calculates current and longest streaks of consecutive completed days. */
public final class StreakCalculator {

    private StreakCalculator() {
    }

    /**
     * Counts consecutive completed days going backwards from today.
     *
     * If today is not done yet we start counting from yesterday instead. The day is
     * not over, so an unbroken run up to yesterday is still "alive" and should not
     * show as 0 just because the user has not checked in yet.
     */
    public static int currentStreak(Habit habit, LocalDate today) {
        LocalDate day = habit.isDoneOn(today) ? today : today.minusDays(1);
        int streak = 0;
        while (habit.isDoneOn(day)) {
            streak++;
            day = day.minusDays(1);
        }
        return streak;
    }

    /**
     * Finds the longest run of consecutive days ever completed.
     *
     * Completed dates are already sorted, so a single pass is enough: a run continues
     * while each date is exactly one day after the previous one, otherwise it resets.
     * LocalDate.plusDays handles month ends and leap years for us.
     */
    public static int longestStreak(Habit habit) {
        int longest = 0;
        int run = 0;
        LocalDate previous = null;
        for (LocalDate current : habit.getCompletedDates()) {
            boolean continuesRun = previous != null && previous.plusDays(1).equals(current);
            run = continuesRun ? run + 1 : 1;
            longest = Math.max(longest, run);
            previous = current;
        }
        return longest;
    }
}
