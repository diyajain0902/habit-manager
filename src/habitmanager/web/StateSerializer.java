package habitmanager.web;

import habitmanager.model.Habit;
import habitmanager.service.InsightEngine;
import habitmanager.service.InsightEngine.DayStatus;
import habitmanager.service.InsightEngine.OverallSummary;
import habitmanager.service.StreakCalculator;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the current habits into the JSON document the dashboard renders.
 * Every number shown in the UI is computed here by the service classes,
 * so the browser only draws, it never re-implements the business rules.
 */
final class StateSerializer {

    private static final int HISTORY_DAYS = 7;

    private StateSerializer() {
    }

    static String toJson(List<Habit> habits, LocalDate today, List<String> warnings) {
        Map<String, String> root = new LinkedHashMap<>();
        root.put("today", Json.quote(today.toString()));
        root.put("summary", summaryJson(habits, today));
        root.put("habits", Json.array(habits.stream().map(h -> habitJson(h, today)).toList()));
        root.put("warnings", Json.array(warnings.stream().map(Json::quote).toList()));
        return Json.object(root);
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
        fields.put("doneToday", Json.bool(habit.isDoneOn(today)));
        fields.put("currentStreak", Json.number(StreakCalculator.currentStreak(habit, today)));
        fields.put("longestStreak", Json.number(StreakCalculator.longestStreak(habit)));
        fields.put("completion", Json.number(InsightEngine.completionPercentage(habit, today)));
        fields.put("needsAttention", Json.bool(InsightEngine.needsAttention(habit, today)));
        fields.put("completedDates",
                Json.array(habit.getCompletedDates().stream().map(d -> Json.quote(d.toString())).toList()));
        fields.put("weekdayRates", weekdayRatesJson(InsightEngine.weekdayRates(habit, today)));
        fields.put("bestWeekday", Json.quote(InsightEngine.bestWeekday(habit, today).map(Enum::name).orElse(null)));
        fields.put("worstWeekday", Json.quote(InsightEngine.worstWeekday(habit, today).map(Enum::name).orElse(null)));
        fields.put("lastSevenDays", Json.array(InsightEngine.history(habit, today, HISTORY_DAYS).stream()
                .map(StateSerializer::dayJson).toList()));
        return Json.object(fields);
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
        return Json.object(fields);
    }
}
