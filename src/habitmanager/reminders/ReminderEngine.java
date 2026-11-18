package habitmanager.reminders;

import habitmanager.model.Habit;
import habitmanager.service.InsightEngine;
import habitmanager.service.StreakCalculator;
import habitmanager.settings.Settings;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Decides which reminders are due. Pure logic with no threads or UI, so it can be tested
 * by passing in any time: call {@link #due} regularly (e.g. every 30 seconds).
 *
 * Three kinds of reminders, each sent at most once per day:
 * <ul>
 *   <li>HABIT - at a habit's own reminder time, if it is due today and not done yet.</li>
 *   <li>NUDGE - in the evening, if any habit due today is still not done.</li>
 *   <li>STREAK - later in the evening, if a running streak will break tonight.</li>
 * </ul>
 * If the computer was asleep, a reminder up to two hours late is still sent; older ones are
 * skipped rather than arriving at a confusing time. Snoozed habit reminders come back after
 * the snooze, even outside that window.
 */
public class ReminderEngine {

    public enum Kind { HABIT, NUDGE, STREAK }

    /** A reminder ready to show. habitName is null for the nudge and streak alerts. */
    public record Reminder(Kind kind, String habitName, String title, String message) {
    }

    static final Duration LATE_LIMIT = Duration.ofHours(2);
    /** Only streaks at least this long get an "ends tonight" alert. */
    static final int MIN_STREAK_TO_PROTECT = 2;

    private final Set<String> sent = new HashSet<>();
    private final Map<String, LocalDateTime> snoozedUntil = new HashMap<>();

    /**
     * @param today the logical date (respects "day ends at"), used for done / due checks
     * @param now   the wall-clock time
     */
    public synchronized List<Reminder> due(List<Habit> habits, Settings settings, LocalDate today, LocalDateTime now) {
        String dayKey = today.toString();
        sent.removeIf(key -> !key.endsWith(dayKey)); // forget previous days
        List<Reminder> result = new ArrayList<>();
        if (!settings.remindersEnabled()) {
            return result;
        }

        List<Habit> pending = habits.stream()
                .filter(h -> InsightEngine.isDueOn(h, today) && !h.isDoneOn(today))
                .toList();
        snoozedUntil.keySet().removeIf(key -> pending.stream().noneMatch(h -> keyOf(h).equals(key)));

        for (Habit habit : pending) {
            habitReminder(habit, settings, today, now, dayKey).ifPresent(result::add);
        }
        if (settings.nudgeEnabled() && !pending.isEmpty()
                && fireOnce("nudge|" + dayKey, settings.nudgeTime(), settings, today, now)) {
            result.add(nudge(pending));
        }
        List<Habit> streaksAtRisk = pending.stream()
                .filter(h -> !h.getSchedule().isWeekly())
                .filter(h -> StreakCalculator.currentStreak(h, today) >= MIN_STREAK_TO_PROTECT)
                .toList();
        if (settings.streakAlertEnabled() && !streaksAtRisk.isEmpty()
                && fireOnce("streak|" + dayKey, settings.streakAlertTime(), settings, today, now)) {
            result.add(streakAlert(streaksAtRisk, today));
        }
        return result;
    }

    /** Brings a habit's reminder back after the given number of minutes. */
    public synchronized void snooze(String habitName, int minutes, LocalDateTime now) {
        snoozedUntil.put(habitName.toLowerCase(Locale.ROOT), now.plusMinutes(minutes));
    }

    public synchronized boolean isSnoozed(String habitName) {
        return snoozedUntil.containsKey(habitName.toLowerCase(Locale.ROOT));
    }

    private Optional<Reminder> habitReminder(Habit habit, Settings settings, LocalDate today,
                                                       LocalDateTime now, String dayKey) {
        LocalDateTime snooze = snoozedUntil.get(keyOf(habit));
        if (snooze != null) {
            if (now.isBefore(snooze)) {
                return Optional.empty();
            }
            snoozedUntil.remove(keyOf(habit));
            sent.add("habit|" + keyOf(habit) + "|" + dayKey);
            return Optional.of(habitMessage(habit, today, "Snoozed reminder"));
        }
        if (habit.getReminderTime() == null
                || !fireOnce("habit|" + keyOf(habit) + "|" + dayKey, habit.getReminderTime(), settings, today, now)) {
            return Optional.empty();
        }
        return Optional.of(habitMessage(habit, today, "Time for " + habit.getName()));
    }

    /**
     * True (and remembers it) the first time "now" is inside the window after the
     * scheduled time. Times before the day-end cut-off belong to the next calendar day,
     * e.g. with the day ending at 3:00, a 1:00 reminder fires after midnight.
     */
    private boolean fireOnce(String key, LocalTime time, Settings settings, LocalDate today, LocalDateTime now) {
        if (sent.contains(key)) {
            return false;
        }
        LocalDateTime fireAt = today.atTime(time);
        if (time.isBefore(settings.dayEndsAt())) {
            fireAt = fireAt.plusDays(1);
        }
        if (now.isBefore(fireAt) || !now.isBefore(fireAt.plus(LATE_LIMIT))) {
            return false;
        }
        sent.add(key);
        return true;
    }

    private static Reminder habitMessage(Habit habit, LocalDate today, String title) {
        String message;
        if (habit.getTarget() > 1) {
            message = habit.getCount(today) + " of " + habit.getTarget() + " done so far today. You've got this!";
        } else {
            int streak = StreakCalculator.currentStreak(habit, today);
            message = streak > 0
                    ? "Keep your " + streak + "-" + StreakCalculator.unit(habit) + " streak going."
                    : "A little step today counts.";
        }
        return new Reminder(Kind.HABIT, habit.getName(), title, message);
    }

    private static Reminder nudge(List<Habit> pending) {
        String names = joinNames(pending);
        String title = pending.size() == 1 ? "1 habit still to do today" : pending.size() + " habits still to do today";
        return new Reminder(Kind.NUDGE, null, title, names + ". There's still time!");
    }

    private static Reminder streakAlert(List<Habit> atRisk, LocalDate today) {
        if (atRisk.size() == 1) {
            Habit habit = atRisk.get(0);
            int streak = StreakCalculator.currentStreak(habit, today);
            return new Reminder(Kind.STREAK, habit.getName(), "Your streak ends tonight",
                    "Your " + streak + "-day streak on " + habit.getName() + " ends tonight. Check in to keep it!");
        }
        return new Reminder(Kind.STREAK, null, atRisk.size() + " streaks end tonight",
                joinNames(atRisk) + ". Check in to keep them going!");
    }

    private static String joinNames(List<Habit> habits) {
        List<String> names = habits.stream().map(Habit::getName).limit(3).toList();
        String joined = String.join(", ", names);
        return habits.size() > 3 ? joined + " and " + (habits.size() - 3) + " more" : joined;
    }

    private static String keyOf(Habit habit) {
        return habit.getName().toLowerCase(Locale.ROOT);
    }
}
