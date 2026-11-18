package habitmanager.web;

import habitmanager.desktop.StartupManager;
import habitmanager.model.CheckIn;
import habitmanager.model.Habit;
import habitmanager.model.Schedule;
import habitmanager.reminders.NotificationCenter;
import habitmanager.service.InsightEngine;
import habitmanager.service.InsightEngine.DayStatus;
import habitmanager.service.InsightEngine.OverallSummary;
import habitmanager.service.Milestones;
import habitmanager.service.StreakCalculator;
import habitmanager.settings.Settings;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the current habits, settings and notifications into the JSON the dashboard renders.
 * Every number shown in the UI is computed here by the service classes,
 * so the browser only draws, it never re-implements the business rules.
 */
final class StateSerializer {

    private static final int HISTORY_DAYS = 7;

    private StateSerializer() {
    }

    // ---------------------------------------------------------------- state

    static String toJson(List<Habit> habits, LocalDate today, List<String> warnings) {
        return toJson(habits, today, warnings, null, Map.of());
    }

    /**
     * @param undoLabel what Ctrl+Z would undo, or null
     * @param extras    extra top-level fields (already JSON), e.g. the result of an import
     */
    static String toJson(List<Habit> habits, LocalDate today, List<String> warnings,
                         String undoLabel, Map<String, String> extras) {
        Map<String, String> root = new LinkedHashMap<>();
        root.put("today", Json.quote(today.toString()));
        root.put("summary", summaryJson(habits, today));
        root.put("week", weekJson(InsightEngine.weekSummary(habits, today)));
        root.put("totalBadges", Json.array(Milestones.totalBadges(habits).stream().map(StateSerializer::badgeJson).toList()));
        root.put("habits", Json.array(habits.stream().map(h -> habitJson(h, today)).toList()));
        root.put("undoLabel", Json.quote(undoLabel));
        root.put("warnings", Json.array(warnings.stream().map(Json::quote).toList()));
        root.putAll(extras);
        return Json.object(root);
    }

    private static String weekJson(InsightEngine.WeekSummary week) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("done", Json.number(week.done()));
        fields.put("expected", Json.number(week.expected()));
        fields.put("lastWeekDone", Json.number(week.lastWeekDone()));
        fields.put("lastWeekExpected", Json.number(week.lastWeekExpected()));
        fields.put("bestWeek", Json.bool(week.bestWeek()));
        return Json.object(fields);
    }

    private static String badgeJson(Milestones.Badge badge) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("id", Json.quote(badge.id()));
        fields.put("habit", Json.quote(badge.habit()));
        fields.put("label", Json.quote(badge.label()));
        fields.put("goal", Json.number(badge.goal()));
        fields.put("earned", Json.bool(badge.earned()));
        fields.put("progress", Json.number(badge.progress()));
        return Json.object(fields);
    }

    private static String summaryJson(List<Habit> habits, LocalDate today) {
        OverallSummary summary = InsightEngine.overallSummary(habits, today);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("doneToday", Json.number(summary.doneToday()));
        fields.put("total", Json.number(summary.totalHabits()));
        fields.put("strongest", Json.quote(summary.strongest().map(Habit::getName).orElse(null)));
        fields.put("weakest", Json.quote(summary.weakest().map(Habit::getName).orElse(null)));
        fields.put("needingAttention",
                Json.array(summary.needingAttention().stream().map(h -> Json.quote(h.getName())).toList()));
        return Json.object(fields);
    }

    private static String habitJson(Habit habit, LocalDate today) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("name", Json.quote(habit.getName()));
        fields.put("createdDate", Json.quote(habit.getCreatedDate().toString()));
        fields.put("emoji", Json.quote(habit.getEmoji()));
        fields.put("color", habit.getColorIndex() == null ? "null" : Json.number(habit.getColorIndex()));
        fields.put("archived", Json.bool(habit.isArchived()));
        fields.put("schedule", scheduleJson(habit.getSchedule()));
        fields.put("target", Json.number(habit.getTarget()));
        fields.put("reminder", Json.quote(habit.getReminderTime() == null ? null : habit.getReminderTime().toString()));

        fields.put("dueToday", Json.bool(InsightEngine.isDueOn(habit, today)));
        fields.put("doneToday", Json.bool(habit.isDoneOn(today)));
        fields.put("todayCount", Json.number(habit.getCount(today)));
        fields.put("todayNote", Json.quote(habit.getNote(today)));
        if (habit.getSchedule().isWeekly()) {
            InsightEngine.WeekProgress week = InsightEngine.weekProgress(habit, today);
            fields.put("weekProgress", Json.object(Map.of("done", Json.number(week.done()), "goal", Json.number(week.goal()))));
        }

        fields.put("streakUnit", Json.quote(StreakCalculator.unit(habit)));
        fields.put("currentStreak", Json.number(StreakCalculator.currentStreak(habit, today)));
        fields.put("longestStreak", Json.number(StreakCalculator.longestStreak(habit)));
        fields.put("completion", Json.number(InsightEngine.completionPercentage(habit, today)));
        fields.put("needsAttention", Json.bool(InsightEngine.needsAttention(habit, today)));
        fields.put("completedDates",
                Json.array(habit.getCompletedDates().stream().map(d -> Json.quote(d.toString())).toList()));
        fields.put("checkIns", checkInsJson(habit));
        fields.put("weekdayRates", weekdayRatesJson(InsightEngine.weekdayRates(habit, today)));
        fields.put("bestWeekday", Json.quote(InsightEngine.bestWeekday(habit, today).map(Enum::name).orElse(null)));
        fields.put("worstWeekday", Json.quote(InsightEngine.worstWeekday(habit, today).map(Enum::name).orElse(null)));
        fields.put("lastSevenDays", Json.array(InsightEngine.history(habit, today, HISTORY_DAYS).stream()
                .map(StateSerializer::dayJson).toList()));
        fields.put("badges", Json.array(Milestones.streakBadges(habit, today).stream().map(StateSerializer::badgeJson).toList()));
        return Json.object(fields);
    }

    private static String scheduleJson(Schedule schedule) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("type", Json.quote(schedule.getType().name()));
        fields.put("days", Json.array(schedule.getDays().stream().map(d -> Json.quote(Schedule.code(d))).toList()));
        fields.put("timesPerWeek", Json.number(schedule.getTimesPerWeek()));
        fields.put("label", Json.quote(schedule.describe()));
        return Json.object(fields);
    }

    /** Days with partial progress or a note (fully done days are in completedDates). */
    private static String checkInsJson(Habit habit) {
        List<String> items = new ArrayList<>();
        for (Map.Entry<LocalDate, CheckIn> entry : habit.getCheckIns().entrySet()) {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("date", Json.quote(entry.getKey().toString()));
            fields.put("count", Json.number(entry.getValue().count()));
            fields.put("note", Json.quote(entry.getValue().note()));
            items.add(Json.object(fields));
        }
        return Json.array(items);
    }

    private static String weekdayRatesJson(EnumMap<DayOfWeek, Double> rates) {
        Map<String, String> fields = new LinkedHashMap<>();
        rates.forEach((day, rate) -> fields.put(day.name(), Json.number(rate)));
        return Json.object(fields);
    }

    private static String dayJson(DayStatus day) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("date", Json.quote(day.date().toString()));
        fields.put("done", Json.bool(day.done()));
        fields.put("beforeCreation", Json.bool(day.beforeCreation()));
        fields.put("scheduled", Json.bool(day.scheduled()));
        return Json.object(fields);
    }

    // ---------------------------------------------------------------- settings

    static String settingsJson(Settings settings, StartupManager startup, boolean trayAvailable) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("remindersEnabled", Json.bool(settings.remindersEnabled()));
        fields.put("nudgeEnabled", Json.bool(settings.nudgeEnabled()));
        fields.put("nudgeTime", Json.quote(settings.nudgeTime().toString()));
        fields.put("streakAlertEnabled", Json.bool(settings.streakAlertEnabled()));
        fields.put("streakAlertTime", Json.quote(settings.streakAlertTime().toString()));
        fields.put("dayEndsAt", Json.quote(settings.dayEndsAt().toString()));
        fields.put("startAtLoginSupported", Json.bool(startup != null && startup.isSupported()));
        fields.put("startAtLogin", Json.bool(startup != null && startup.isEnabled()));
        fields.put("trayAvailable", Json.bool(trayAvailable));
        return Json.object(fields);
    }

    // ---------------------------------------------------------------- notifications

    static String noticesJson(List<NotificationCenter.Notice> notices, long latestId) {
        List<String> items = notices.stream().map(n -> {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("id", Json.number(n.id()));
            fields.put("time", Json.quote(n.time().withNano(0).toString()));
            fields.put("kind", Json.quote(n.kind().name()));
            fields.put("habit", Json.quote(n.habitName()));
            fields.put("title", Json.quote(n.title()));
            fields.put("message", Json.quote(n.message()));
            return Json.object(fields);
        }).toList();
        return Json.object(Map.of("latestId", Json.number(latestId), "notices", Json.array(items)));
    }
}
