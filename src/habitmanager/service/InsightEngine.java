package habitmanager.service;

import habitmanager.model.Habit;
import habitmanager.model.Schedule;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns raw check-in data into percentages, weekday patterns and summaries.
 * Every calculation respects the habit's schedule, so rest days never count against it.
 */
public final class InsightEngine {

    /** A daily habit with no check in for this many days (including today) needs attention. */
    public static final int ATTENTION_WINDOW_DAYS = 3;

    private InsightEngine() {
    }

    /**
     * One day in a habit's history. beforeCreation is true for days the habit did not
     * exist yet; scheduled is false on rest days of a specific-weekdays habit.
     */
    public record DayStatus(LocalDate date, boolean done, boolean beforeCreation, boolean scheduled) {
    }

    /** Progress towards a weekly goal (times-per-week habits). */
    public record WeekProgress(int done, int goal) {
    }

    /**
     * Snapshot of how all active habits are doing today. doneToday / totalHabits only count
     * habits that are due today, so rest days and met weekly goals do not drag it down.
     */
    public record OverallSummary(int doneToday,
                                 int totalHabits,
                                 Optional<Habit> strongest,
                                 Optional<Habit> weakest,
                                 List<Habit> needingAttention) {
    }

    // ---------------------------------------------------------------- completion %

    /**
     * Share of expected check ins that happened since the habit was created, x 100,
     * rounded to 1 decimal. Today is included, like in the original daily rule.
     * <ul>
     *   <li>Every day: completed days / days since creation (inclusive).</li>
     *   <li>Specific weekdays: completed scheduled days / scheduled days so far.</li>
     *   <li>X times a week: per week, min(done, expected) / expected, where expected is the
     *       goal, capped by how many days of that week the habit existed.</li>
     * </ul>
     * Never divides by zero: with nothing expected yet the result is 0.
     */
    public static double completionPercentage(Habit habit, LocalDate today) {
        LocalDate created = habit.getCreatedDate();
        if (today.isBefore(created)) {
            return 0.0;
        }
        return switch (habit.getSchedule().getType()) {
            case DAILY -> percentage(StreakCalculator.completionsBetween(habit, created, today),
                    ChronoUnit.DAYS.between(created, today) + 1);
            case WEEKDAYS -> scheduledDaysPercentage(habit, today);
            case WEEKLY -> weeklyPercentage(habit, today);
        };
    }

    private static double scheduledDaysPercentage(Habit habit, LocalDate today) {
        Schedule schedule = habit.getSchedule();
        LocalDate created = habit.getCreatedDate();
        long expected = schedule.getDays().stream().mapToLong(d -> occurrencesOf(d, created, today)).sum();
        long done = habit.getCompletedDates().subSet(created, true, today, true).stream()
                .filter(schedule::isScheduledOn)
                .count();
        return percentage(done, expected);
    }

    private static double weeklyPercentage(Habit habit, LocalDate today) {
        int goal = habit.getSchedule().getTimesPerWeek();
        LocalDate created = habit.getCreatedDate();
        long expected = 0;
        long achieved = 0;
        for (LocalDate monday = StreakCalculator.weekStart(created); !monday.isAfter(today); monday = monday.plusWeeks(1)) {
            LocalDate from = monday.isBefore(created) ? created : monday;
            LocalDate to = monday.plusDays(6).isAfter(today) ? today : monday.plusDays(6);
            int daysAlive = (int) ChronoUnit.DAYS.between(from, to) + 1;
            int expectedThisWeek = Math.min(goal, daysAlive);
            expected += expectedThisWeek;
            achieved += Math.min(StreakCalculator.completionsBetween(habit, from, to), expectedThisWeek);
        }
        return percentage(achieved, expected);
    }

    private static double percentage(long done, long expected) {
        if (expected <= 0) {
            return 0.0;
        }
        double value = Math.min(100.0, done * 100.0 / expected);
        return Math.round(value * 10.0) / 10.0;
    }

    // ---------------------------------------------------------------- weekday rates

    /**
     * Completion rate for each weekday, from 0.0 to 1.0.
     *
     * Raw counts would be misleading: in a 10 day window starting on a Monday there are
     * two Mondays but only one Friday. So each weekday's completions are divided by the
     * number of times that weekday actually occurred between createdDate and today.
     * Weekdays that have not occurred yet, and rest days of the schedule, are left out.
     */
    public static EnumMap<DayOfWeek, Double> weekdayRates(Habit habit, LocalDate today) {
        EnumMap<DayOfWeek, Integer> completions = new EnumMap<>(DayOfWeek.class);
        if (!today.isBefore(habit.getCreatedDate())) {
            for (LocalDate date : habit.getCompletedDates().subSet(habit.getCreatedDate(), true, today, true)) {
                completions.merge(date.getDayOfWeek(), 1, Integer::sum);
            }
        }

        EnumMap<DayOfWeek, Double> rates = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek day : habit.getSchedule().getDays()) {
            long occurrences = occurrencesOf(day, habit.getCreatedDate(), today);
            if (occurrences > 0) {
                rates.put(day, completions.getOrDefault(day, 0) / (double) occurrences);
            }
        }
        return rates;
    }

    /** Weekday with the highest rate; ties go to the earlier weekday (Monday first). */
    public static Optional<DayOfWeek> bestWeekday(Habit habit, LocalDate today) {
        return weekdayRates(habit, today).entrySet().stream()
                .max(Map.Entry.<DayOfWeek, Double>comparingByValue()
                        .thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
                .map(Map.Entry::getKey);
    }

    /** Weekday with the lowest rate; ties go to the earlier weekday (Monday first). */
    public static Optional<DayOfWeek> worstWeekday(Habit habit, LocalDate today) {
        return weekdayRates(habit, today).entrySet().stream()
                .min(Map.Entry.<DayOfWeek, Double>comparingByValue()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey);
    }

    // ---------------------------------------------------------------- today

    /**
     * Whether the habit still "counts" today: always for daily habits, only on chosen days
     * for weekday habits, and for weekly habits until this week's goal is met (a day that
     * is already done stays due, so it still shows as done).
     */
    public static boolean isDueOn(Habit habit, LocalDate date) {
        if (habit.isArchived() || date.isBefore(habit.getCreatedDate())) {
            return false;
        }
        Schedule schedule = habit.getSchedule();
        return switch (schedule.getType()) {
            case DAILY -> true;
            case WEEKDAYS -> schedule.isScheduledOn(date);
            case WEEKLY -> habit.isDoneOn(date)
                    || StreakCalculator.completionsBetween(habit, StreakCalculator.weekStart(date), date.minusDays(1))
                    < schedule.getTimesPerWeek();
        };
    }

    /** Check ins this week (Mon-Sun) against the weekly goal. */
    public static WeekProgress weekProgress(Habit habit, LocalDate today) {
        int done = StreakCalculator.completionsInWeek(habit, StreakCalculator.weekStart(today));
        return new WeekProgress(done, habit.getSchedule().getTimesPerWeek());
    }

    public static OverallSummary overallSummary(List<Habit> habits, LocalDate today) {
        List<Habit> active = habits.stream().filter(h -> !h.isArchived()).toList();
        List<Habit> dueToday = active.stream().filter(h -> isDueOn(h, today)).toList();
        int doneToday = (int) dueToday.stream().filter(h -> h.isDoneOn(today)).count();
        Comparator<Habit> byCompletion = Comparator.comparingDouble(h -> completionPercentage(h, today));

        Optional<Habit> strongest = active.stream().max(byCompletion);
        Optional<Habit> weakest = active.stream().min(byCompletion);
        List<Habit> needingAttention = active.stream()
                .filter(h -> needsAttention(h, today))
                .toList();
        return new OverallSummary(doneToday, dueToday.size(), strongest, weakest, needingAttention);
    }

    /**
     * True when a habit has been neglected:
     * <ul>
     *   <li>Every day: existed for the whole window (today and the two days before) and was
     *       not done on any of them.</li>
     *   <li>Specific weekdays: its last 3 scheduled days were all missed.</li>
     *   <li>X times a week: no check in at all in the last 7 days.</li>
     * </ul>
     * Brand new habits are not flagged, since they have not had a fair chance yet.
     */
    public static boolean needsAttention(Habit habit, LocalDate today) {
        if (habit.isArchived()) {
            return false;
        }
        return switch (habit.getSchedule().getType()) {
            case DAILY -> nothingDoneSince(habit, today.minusDays(ATTENTION_WINDOW_DAYS - 1), today);
            case WEEKLY -> nothingDoneSince(habit, today.minusDays(6), today);
            case WEEKDAYS -> lastScheduledDaysMissed(habit, today);
        };
    }

    private static boolean nothingDoneSince(Habit habit, LocalDate windowStart, LocalDate today) {
        if (habit.getCreatedDate().isAfter(windowStart)) {
            return false;
        }
        return StreakCalculator.completionsBetween(habit, windowStart, today) == 0;
    }

    private static boolean lastScheduledDaysMissed(Habit habit, LocalDate today) {
        List<LocalDate> lastScheduled = new ArrayList<>();
        for (LocalDate day = today; lastScheduled.size() < ATTENTION_WINDOW_DAYS
                && !day.isBefore(habit.getCreatedDate()); day = day.minusDays(1)) {
            if (habit.getSchedule().isScheduledOn(day)) {
                lastScheduled.add(day);
            }
        }
        return lastScheduled.size() == ATTENTION_WINDOW_DAYS && lastScheduled.stream().noneMatch(habit::isDoneOn);
    }

    // ---------------------------------------------------------------- weekly summary

    /**
     * Check ins this week (Monday to today) against what was expected so far, the same for
     * last week, and whether this week already beats every earlier week.
     */
    public record WeekSummary(int done, int expected, int lastWeekDone, int lastWeekExpected, boolean bestWeek) {
    }

    public static WeekSummary weekSummary(List<Habit> habits, LocalDate today) {
        List<Habit> active = habits.stream().filter(h -> !h.isArchived()).toList();
        LocalDate monday = StreakCalculator.weekStart(today);
        int[] thisWeek = totals(active, monday, today);
        int[] lastWeek = totals(active, monday.minusWeeks(1), monday.minusDays(1));

        boolean best = thisWeek[1] > 0 && !active.isEmpty();
        LocalDate firstWeek = active.stream().map(Habit::getCreatedDate).min(LocalDate::compareTo)
                .map(StreakCalculator::weekStart).orElse(monday);
        for (LocalDate week = firstWeek; best && week.isBefore(monday); week = week.plusWeeks(1)) {
            if (totals(active, week, week.plusDays(6))[1] >= thisWeek[1]) {
                best = false;
            }
        }
        return new WeekSummary(thisWeek[1], thisWeek[0], lastWeek[1], lastWeek[0], best);
    }

    /** {expected, done} summed over the habits for a range inside one Monday-Sunday week. */
    private static int[] totals(List<Habit> habits, LocalDate from, LocalDate to) {
        int expected = 0;
        int done = 0;
        for (Habit habit : habits) {
            LocalDate start = from.isBefore(habit.getCreatedDate()) ? habit.getCreatedDate() : from;
            if (to.isBefore(start)) {
                continue;
            }
            Schedule schedule = habit.getSchedule();
            int days = (int) ChronoUnit.DAYS.between(start, to) + 1;
            switch (schedule.getType()) {
                case DAILY -> {
                    expected += days;
                    done += StreakCalculator.completionsBetween(habit, start, to);
                }
                case WEEKDAYS -> {
                    for (LocalDate day = start; !day.isAfter(to); day = day.plusDays(1)) {
                        if (schedule.isScheduledOn(day)) {
                            expected++;
                            done += habit.isDoneOn(day) ? 1 : 0;
                        }
                    }
                }
                case WEEKLY -> {
                    int goal = Math.min(schedule.getTimesPerWeek(), days);
                    expected += goal;
                    done += Math.min(StreakCalculator.completionsBetween(habit, start, to), goal);
                }
            }
        }
        return new int[] {expected, done};
    }

    /** The last {@code days} days ending today, oldest first. */
    public static List<DayStatus> history(Habit habit, LocalDate today, int days) {
        List<DayStatus> result = new ArrayList<>();
        for (int offset = days - 1; offset >= 0; offset--) {
            LocalDate date = today.minusDays(offset);
            boolean beforeCreation = date.isBefore(habit.getCreatedDate());
            result.add(new DayStatus(date, habit.isDoneOn(date), beforeCreation, habit.getSchedule().isScheduledOn(date)));
        }
        return result;
    }

    /**
     * How many times {@code day} falls in [start, end] inclusive.
     * Every full week contains each weekday once; the leftover days (fewer than 7)
     * start at start's weekday, so we check whether {@code day} is among them.
     */
    static long occurrencesOf(DayOfWeek day, LocalDate start, LocalDate end) {
        long totalDays = ChronoUnit.DAYS.between(start, end) + 1;
        if (totalDays <= 0) {
            return 0;
        }
        long fullWeeks = totalDays / 7;
        long leftoverDays = totalDays % 7;
        int stepsFromStart = (day.getValue() - start.getDayOfWeek().getValue() + 7) % 7;
        return fullWeeks + (stepsFromStart < leftoverDays ? 1 : 0);
    }
}
