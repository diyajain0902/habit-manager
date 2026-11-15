package habitmanager.service;

import habitmanager.model.Habit;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Turns raw check-in data into percentages, weekday patterns and summaries. */
public final class InsightEngine {

    /** A habit that has had no check in for this many days (including today) needs attention. */
    public static final int ATTENTION_WINDOW_DAYS = 3;

    private InsightEngine() {
    }

    /** One day in a habit's history. beforeCreation is true for days the habit did not exist yet. */
    public record DayStatus(LocalDate date, boolean done, boolean beforeCreation) {
    }

    /** Snapshot of how all habits are doing today. */
    public record OverallSummary(int doneToday,
                                 int totalHabits,
                                 Optional<Habit> strongest,
                                 Optional<Habit> weakest,
                                 List<Habit> needingAttention) {
    }

    /**
     * Completed days / days since creation (both ends inclusive) x 100, rounded to 1 decimal.
     * A habit created today has 1 tracked day, so there is never a divide by zero.
     */
    public static double completionPercentage(Habit habit, LocalDate today) {
        long trackedDays = daysTracked(habit, today);
        if (trackedDays <= 0) {
            return 0.0;
        }
        long completed = completionsInRange(habit, today);
        return roundToOneDecimal(completed * 100.0 / trackedDays);
    }

    /**
     * Completion rate for each weekday, from 0.0 to 1.0.
     *
     * Raw counts would be misleading: in a 10 day window starting on a Monday there are
     * two Mondays but only one Friday. So each weekday's completions are divided by the
     * number of times that weekday actually occurred between createdDate and today.
     * Weekdays that have not occurred yet are left out of the map.
     */
    public static EnumMap<DayOfWeek, Double> weekdayRates(Habit habit, LocalDate today) {
        EnumMap<DayOfWeek, Integer> completions = new EnumMap<>(DayOfWeek.class);
        for (LocalDate date : habit.getCompletedDates().subSet(habit.getCreatedDate(), true, today, true)) {
            completions.merge(date.getDayOfWeek(), 1, Integer::sum);
        }

        EnumMap<DayOfWeek, Double> rates = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek day : DayOfWeek.values()) {
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

    public static OverallSummary overallSummary(List<Habit> habits, LocalDate today) {
        int doneToday = (int) habits.stream().filter(h -> h.isDoneOn(today)).count();
        Comparator<Habit> byCompletion = Comparator.comparingDouble(h -> completionPercentage(h, today));

        Optional<Habit> strongest = habits.stream().max(byCompletion);
        Optional<Habit> weakest = habits.stream().min(byCompletion);
        List<Habit> needingAttention = habits.stream()
                .filter(h -> needsAttention(h, today))
                .toList();
        return new OverallSummary(doneToday, habits.size(), strongest, weakest, needingAttention);
    }

    /**
     * True when the habit has existed for the whole attention window (today and the two
     * days before) but was not completed on any of those days. Brand new habits are not
     * flagged, since they have not had a fair chance yet.
     */
    public static boolean needsAttention(Habit habit, LocalDate today) {
        LocalDate windowStart = today.minusDays(ATTENTION_WINDOW_DAYS - 1);
        if (habit.getCreatedDate().isAfter(windowStart)) {
            return false;
        }
        return habit.getCompletedDates().subSet(windowStart, true, today, true).isEmpty();
    }

    /** The last {@code days} days ending today, oldest first. */
    public static List<DayStatus> history(Habit habit, LocalDate today, int days) {
        List<DayStatus> result = new ArrayList<>();
        for (int offset = days - 1; offset >= 0; offset--) {
            LocalDate date = today.minusDays(offset);
            boolean beforeCreation = date.isBefore(habit.getCreatedDate());
            result.add(new DayStatus(date, habit.isDoneOn(date), beforeCreation));
        }
        return result;
    }

    private static long daysTracked(Habit habit, LocalDate today) {
        return ChronoUnit.DAYS.between(habit.getCreatedDate(), today) + 1;
    }

    /** Only counts check ins inside [createdDate, today], so stray dates cannot push % over 100. */
    private static long completionsInRange(Habit habit, LocalDate today) {
        if (today.isBefore(habit.getCreatedDate())) {
            return 0;
        }
        return habit.getCompletedDates().subSet(habit.getCreatedDate(), true, today, true).size();
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

    private static double roundToOneDecimal(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
