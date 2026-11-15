import habitmanager.model.Habit;
import habitmanager.service.HabitManager;
import habitmanager.service.InsightEngine;
import habitmanager.service.StreakCalculator;
import habitmanager.storage.FileStorage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;

/**
 * Plain-Java tests (no JUnit). Run with:
 *   java -cp out StreakAndInsightTests
 * Exits with status 1 if any check fails.
 */
public class StreakAndInsightTests {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws IOException {
        currentStreakWhenTodayIsDone();
        currentStreakWhenOnlyYesterdayIsDone();
        currentStreakWhenYesterdayIsMissed();
        longestStreakWithGapInTheMiddle();
        streakAcrossMonthBoundary();
        streakAcrossLeapDay();
        streakAcrossFebruaryInNonLeapYear();
        completionPercentageForHabitCreatedToday();
        completionPercentageRounding();
        weekdayRatesNormalisedForUnequalOccurrences();
        weekdayRatesSkipWeekdaysThatHaveNotOccurred();
        needsAttention();
        managerValidation();
        backfillValidation();
        fileStorageRoundTrip();
        fileStorageSkipsMalformedLines();
        fileStorageMissingFileStartsEmpty();

        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- streaks

    private static void currentStreakWhenTodayIsDone() {
        LocalDate today = LocalDate.of(2024, 5, 10);
        Habit habit = habitDoneOn(today.minusDays(10), today, today.minusDays(1), today.minusDays(2));
        check("current streak counts today when today is done", 3, StreakCalculator.currentStreak(habit, today));
    }

    private static void currentStreakWhenOnlyYesterdayIsDone() {
        LocalDate today = LocalDate.of(2024, 5, 10);
        Habit habit = habitDoneOn(today.minusDays(10), today.minusDays(1), today.minusDays(2));
        check("current streak starts from yesterday when today is not done yet",
                2, StreakCalculator.currentStreak(habit, today));
    }

    private static void currentStreakWhenYesterdayIsMissed() {
        LocalDate today = LocalDate.of(2024, 5, 10);
        Habit habit = habitDoneOn(today.minusDays(10), today.minusDays(2), today.minusDays(3));
        check("current streak is 0 when yesterday was missed", 0, StreakCalculator.currentStreak(habit, today));

        habit.markDone(today);
        check("current streak is 1 when today is done but yesterday was missed",
                1, StreakCalculator.currentStreak(habit, today));
    }

    private static void longestStreakWithGapInTheMiddle() {
        LocalDate start = LocalDate.of(2024, 3, 1);
        Habit habit = habitDoneOn(start,
                start, start.plusDays(1), start.plusDays(2),                       // run of 3
                start.plusDays(5), start.plusDays(6), start.plusDays(7), start.plusDays(8), // run of 4
                start.plusDays(10));                                               // run of 1
        check("longest streak picks the longest run around gaps", 4, StreakCalculator.longestStreak(habit));
        check("longest streak of a habit with no check ins is 0",
                0, StreakCalculator.longestStreak(new Habit("Empty", start)));
    }

    private static void streakAcrossMonthBoundary() {
        Habit habit = habitDoneOn(LocalDate.of(2023, 1, 1),
                LocalDate.of(2023, 1, 30), LocalDate.of(2023, 1, 31),
                LocalDate.of(2023, 2, 1), LocalDate.of(2023, 2, 2));
        check("longest streak across Jan 31 -> Feb 1", 4, StreakCalculator.longestStreak(habit));
        check("current streak across Jan 31 -> Feb 1",
                4, StreakCalculator.currentStreak(habit, LocalDate.of(2023, 2, 2)));
    }

    private static void streakAcrossLeapDay() {
        Habit habit = habitDoneOn(LocalDate.of(2024, 2, 1),
                LocalDate.of(2024, 2, 27), LocalDate.of(2024, 2, 28),
                LocalDate.of(2024, 2, 29), LocalDate.of(2024, 3, 1));
        check("longest streak across Feb 28 -> 29 -> Mar 1 in a leap year",
                4, StreakCalculator.longestStreak(habit));
        check("current streak across leap day",
                4, StreakCalculator.currentStreak(habit, LocalDate.of(2024, 3, 1)));

        // Skipping Feb 29 in a leap year must break the streak.
        Habit skipped = habitDoneOn(LocalDate.of(2024, 2, 1),
                LocalDate.of(2024, 2, 28), LocalDate.of(2024, 3, 1));
        check("missing Feb 29 in a leap year breaks the streak", 1, StreakCalculator.longestStreak(skipped));
    }

    private static void streakAcrossFebruaryInNonLeapYear() {
        Habit habit = habitDoneOn(LocalDate.of(2023, 2, 1),
                LocalDate.of(2023, 2, 28), LocalDate.of(2023, 3, 1));
        check("Feb 28 -> Mar 1 is consecutive in a non leap year", 2, StreakCalculator.longestStreak(habit));
    }

    // ---------------------------------------------------------------- completion %

    private static void completionPercentageForHabitCreatedToday() {
        LocalDate today = LocalDate.of(2024, 5, 10);
        Habit habit = new Habit("New", today);
        check("completion % for a habit created today with no check in", 0.0,
                InsightEngine.completionPercentage(habit, today));
        habit.markDone(today);
        check("completion % for a habit created today and done today", 100.0,
                InsightEngine.completionPercentage(habit, today));
    }

    private static void completionPercentageRounding() {
        LocalDate created = LocalDate.of(2024, 5, 1);
        LocalDate today = created.plusDays(2); // 3 tracked days
        Habit habit = habitDoneOn(created, created);
        check("completion % is rounded to 1 decimal (1/3 -> 33.3)", 33.3,
                InsightEngine.completionPercentage(habit, today));
    }

    // ---------------------------------------------------------------- weekday rates

    private static void weekdayRatesNormalisedForUnequalOccurrences() {
        // Monday 2024-01-01 to Wednesday 2024-01-10 = 10 days:
        // Mon, Tue and Wed occur twice; Thu..Sun occur once.
        LocalDate created = LocalDate.of(2024, 1, 1);
        LocalDate today = LocalDate.of(2024, 1, 10);
        check("2024-01-01 is a Monday", DayOfWeek.MONDAY, created.getDayOfWeek());

        Habit habit = habitDoneOn(created,
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 8),  // both Mondays
                LocalDate.of(2024, 1, 2),                            // one of two Tuesdays
                LocalDate.of(2024, 1, 4));                           // the only Thursday

        EnumMap<DayOfWeek, Double> rates = InsightEngine.weekdayRates(habit, today);
        check("Monday rate = 2/2", 1.0, rates.get(DayOfWeek.MONDAY));
        check("Tuesday rate = 1/2", 0.5, rates.get(DayOfWeek.TUESDAY));
        check("Wednesday rate = 0/2", 0.0, rates.get(DayOfWeek.WEDNESDAY));
        check("Thursday rate = 1/1", 1.0, rates.get(DayOfWeek.THURSDAY));
        check("Friday rate = 0/1", 0.0, rates.get(DayOfWeek.FRIDAY));
        check("best weekday is the first with the top rate", DayOfWeek.MONDAY,
                InsightEngine.bestWeekday(habit, today).orElse(null));
        check("worst weekday is the first with the lowest rate", DayOfWeek.WEDNESDAY,
                InsightEngine.worstWeekday(habit, today).orElse(null));
    }

    private static void weekdayRatesSkipWeekdaysThatHaveNotOccurred() {
        // Created Monday, today is Wednesday: only Mon, Tue, Wed have occurred.
        LocalDate created = LocalDate.of(2024, 1, 1);
        LocalDate today = LocalDate.of(2024, 1, 3);
        Habit habit = habitDoneOn(created, created, created.plusDays(1));

        EnumMap<DayOfWeek, Double> rates = InsightEngine.weekdayRates(habit, today);
        check("only weekdays that occurred are included", 3, rates.size());
        check("Sunday has not occurred yet", false, rates.containsKey(DayOfWeek.SUNDAY));
        check("worst weekday ignores weekdays that have not occurred", DayOfWeek.WEDNESDAY,
                InsightEngine.worstWeekday(habit, today).orElse(null));
    }

    // ---------------------------------------------------------------- summary

    private static void needsAttention() {
        LocalDate today = LocalDate.of(2024, 5, 10);
        Habit stale = habitDoneOn(today.minusDays(20), today.minusDays(3));
        Habit recent = habitDoneOn(today.minusDays(20), today.minusDays(2));
        Habit brandNew = new Habit("Brand new", today.minusDays(1));
        check("habit not done in last 3 days needs attention", true, InsightEngine.needsAttention(stale, today));
        check("habit done 2 days ago does not need attention", false, InsightEngine.needsAttention(recent, today));
        check("habit created yesterday is not flagged yet", false, InsightEngine.needsAttention(brandNew, today));

        InsightEngine.OverallSummary summary = InsightEngine.overallSummary(List.of(stale, recent), today);
        check("summary total", 2, summary.totalHabits());
        check("summary done today", 0, summary.doneToday());
        check("summary needing attention", List.of(stale), summary.needingAttention());
    }

    // ---------------------------------------------------------------- manager

    private static void managerValidation() {
        LocalDate today = LocalDate.of(2024, 5, 10);
        HabitManager manager = new HabitManager();
        manager.addHabit("  Read  ", today);
        check("names are trimmed", "Read", manager.listHabits().get(0).getName());
        checkThrows("blank name is rejected", () -> manager.addHabit("   ", today));
        checkThrows("'|' is rejected", () -> manager.addHabit("a|b", today));
        checkThrows("duplicate name is rejected case insensitively", () -> manager.addHabit("READ", today));

        Habit read = manager.findHabit("read").orElseThrow();
        manager.addHabit("Run", today);
        manager.renameHabit(read, "READ");
        check("rename to a different case of the same name is allowed", "READ", read.getName());
        check("renamed habit keeps its position", "READ", manager.listHabits().get(0).getName());
        check("find works after rename", true, manager.findHabit("read").isPresent());
        checkThrows("rename onto another habit's name is rejected", () -> manager.renameHabit(read, "run"));

        check("check in once", true, manager.checkIn(read, today));
        check("second check in is not duplicated", false, manager.checkIn(read, today));
        check("undo today", true, manager.undoCheckIn(read, today));
        check("undo again has nothing to undo", false, manager.undoCheckIn(read, today));
    }

    private static void backfillValidation() {
        LocalDate today = LocalDate.of(2024, 5, 10);
        HabitManager manager = new HabitManager();
        Habit habit = manager.addHabit("Walk", today.minusDays(5));
        checkThrows("future date is rejected", () -> manager.backfill(habit, today.plusDays(1), today));
        checkThrows("date before creation is rejected", () -> manager.backfill(habit, today.minusDays(6), today));
        check("valid backfill", true, manager.backfill(habit, today.minusDays(5), today));
        check("backfilled date is recorded", true, habit.isDoneOn(today.minusDays(5)));
    }

    // ---------------------------------------------------------------- storage

    private static void fileStorageRoundTrip() throws IOException {
        Path file = Files.createTempFile("habits-test", ".txt");
        try {
            Habit withDates = habitDoneOn(LocalDate.of(2024, 1, 1),
                    LocalDate.of(2024, 1, 3), LocalDate.of(2024, 1, 1), LocalDate.of(2024, 2, 29));
            Habit withoutDates = new Habit("No check ins yet", LocalDate.of(2024, 4, 1));
            FileStorage storage = new FileStorage(file);
            storage.save(List.of(withDates, withoutDates));

            FileStorage.LoadResult result = storage.load();
            check("round trip: no warnings", 0, result.warnings().size());
            check("round trip: habit count", 2, result.habits().size());
            assertSameHabit(withDates, result.habits().get(0));
            assertSameHabit(withoutDates, result.habits().get(1));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static void fileStorageSkipsMalformedLines() throws IOException {
        Path file = Files.createTempFile("habits-test", ".txt");
        try {
            Files.write(file, List.of(
                    "Good|2024-01-01|2024-01-01,2024-01-02",
                    "missing fields",
                    "Bad date|2024-13-01|",
                    "",
                    "Bad check in|2024-01-01|2024-01-01,yesterday",
                    "|2024-01-01|",
                    "good|2024-01-01|",
                    "Also good|2024-02-01|"), StandardCharsets.UTF_8);
            FileStorage.LoadResult result = new FileStorage(file).load();
            check("malformed: valid habits still load", 2, result.habits().size());
            check("malformed: one warning per bad line", 5, result.warnings().size());
            check("malformed: warning mentions the line number", true,
                    result.warnings().get(0).contains("line 2"));
            check("malformed: blank line is skipped silently, line numbers stay correct", true,
                    result.warnings().get(2).contains("line 5"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static void fileStorageMissingFileStartsEmpty() throws IOException {
        Path dir = Files.createTempDirectory("habits-test");
        Path file = dir.resolve("habits.txt");
        try {
            FileStorage storage = new FileStorage(file);
            check("missing file loads as empty", 0, storage.load().habits().size());
            storage.save(List.of(new Habit("Created on first save", LocalDate.of(2024, 1, 1))));
            check("file is created on first save", true, Files.exists(file));
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static Habit habitDoneOn(LocalDate created, LocalDate... doneDates) {
        Habit habit = new Habit("Test habit", created);
        for (LocalDate date : doneDates) {
            habit.markDone(date);
        }
        return habit;
    }

    private static void assertSameHabit(Habit expected, Habit actual) {
        check("round trip: name of " + expected.getName(), expected.getName(), actual.getName());
        check("round trip: created date of " + expected.getName(), expected.getCreatedDate(), actual.getCreatedDate());
        check("round trip: completed dates of " + expected.getName(),
                List.copyOf(expected.getCompletedDates()), List.copyOf(actual.getCompletedDates()));
    }

    private static void check(String description, Object expected, Object actual) {
        if (Objects.equals(expected, actual)) {
            passed++;
            System.out.println("PASS  " + description);
        } else {
            failed++;
            System.out.println("FAIL  " + description + " -> expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void checkThrows(String description, Runnable action) {
        try {
            action.run();
            failed++;
            System.out.println("FAIL  " + description + " -> expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            passed++;
            System.out.println("PASS  " + description);
        }
    }
}
