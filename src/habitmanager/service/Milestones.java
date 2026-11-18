package habitmanager.service;

import habitmanager.model.Habit;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Badges for reaching streak and check-in milestones.
 * <ul>
 *   <li>Streak badges per habit, earned by the best streak ever (so they are never lost):
 *       3, 7, 14, 30, 50, 100, 200 and 365 days, or 2, 4, 8, 12, 26 and 52 weeks.</li>
 *   <li>Overall badges for total check-ins across all habits: 10, 50, 100, 250, 500, 1000.</li>
 * </ul>
 * The dashboard compares earned badge ids before and after a change to know when to celebrate.
 */
public final class Milestones {

    static final int[] DAY_STREAKS = {3, 7, 14, 30, 50, 100, 200, 365};
    static final int[] WEEK_STREAKS = {2, 4, 8, 12, 26, 52};
    static final int[] TOTAL_CHECK_INS = {10, 50, 100, 250, 500, 1000};

    private Milestones() {
    }

    /**
     * @param id       stable identifier, e.g. "streak:drink water:7" or "total:100"
     * @param habit    the habit's name, or null for overall badges
     * @param progress how far along the next badge is (current streak, or total check-ins)
     */
    public record Badge(String id, String habit, String label, int goal, boolean earned, int progress) {
    }

    /** Every streak badge for the habit: the earned ones, plus the next one to aim for. */
    public static List<Badge> streakBadges(Habit habit, LocalDate today) {
        boolean weekly = habit.getSchedule().isWeekly();
        int[] thresholds = weekly ? WEEK_STREAKS : DAY_STREAKS;
        String unit = StreakCalculator.unit(habit);
        int best = StreakCalculator.longestStreak(habit);
        int current = StreakCalculator.currentStreak(habit, today);
        String key = habit.getName().toLowerCase(Locale.ROOT);

        List<Badge> badges = new ArrayList<>();
        for (int goal : thresholds) {
            boolean earned = best >= goal;
            badges.add(new Badge("streak:" + key + ":" + goal, habit.getName(), goal + "-" + unit + " streak",
                    goal, earned, Math.min(current, goal)));
            if (!earned) {
                break; // only show the next badge to aim for, not every locked one
            }
        }
        return badges;
    }

    /** Overall check-in badges: the earned ones plus the next one. */
    public static List<Badge> totalBadges(List<Habit> habits) {
        int total = habits.stream().mapToInt(h -> h.getCompletedDates().size()).sum();
        List<Badge> badges = new ArrayList<>();
        for (int goal : TOTAL_CHECK_INS) {
            boolean earned = total >= goal;
            badges.add(new Badge("total:" + goal, null, goal + " check-ins", goal, earned, Math.min(total, goal)));
            if (!earned) {
                break;
            }
        }
        return badges;
    }
}
