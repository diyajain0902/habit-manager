import habitmanager.desktop.StartupManager;
import habitmanager.model.Habit;
import habitmanager.model.Schedule;
import habitmanager.reminders.ReminderEngine;
import habitmanager.reminders.ReminderEngine.Kind;
import habitmanager.reminders.ReminderEngine.Reminder;
import habitmanager.service.HabitManager;
import habitmanager.service.HabitManager.HabitDetails;
import habitmanager.service.HabitService;
import habitmanager.service.InsightEngine;
import habitmanager.service.StreakCalculator;
import habitmanager.settings.Settings;
import habitmanager.settings.SettingsStorage;
import habitmanager.storage.FileStorage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * Plain-Java tests (no JUnit) for Phase 1: schedules, targets, notes, the new file
 * format and its migration, settings, reminders and Start with Windows. Run with:
 *   java -cp out ScheduleAndReminderTests
 * Exits with status 1 if any check fails.
 *
 * Dates used: 2024-01-01 is a Monday, so 2024-01-03 is Wednesday, 2024-01-06 Saturday.
 */
public class ScheduleAndReminderTests {

    private static final Schedule MON_WED_FRI =
            Schedule.onDays(EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY));
    private static final LocalDate MONDAY = LocalDate.of(2024, 1, 1);
    // Emoji built from code points so this file stays plain ASCII (no -encoding flag needed).
    private static final String BOOKS = new String(Character.toChars(0x1F4DA));
    private static final String TEAPOT = new String(Character.toChars(0x1FAD6));

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws IOException {
        scheduleEncodingAndRules();
        weekdayStreakSkipsRestDays();
        weekdayLongestStreak();
        weeklyStreaks();
        scheduleAwareCompletion();
        dueTodayAndSummary();
        scheduleAwareAttention();
        targetsAndProgress();
        raisingTargetKeepsHistory();
        notes();
        managerEditsAreAtomic();
        reorderAndArchive();
        fileFormatRoundTrip();
        legacyFileIsBackedUpAndConverted();
        malformedNewFormatLines();
        settingsStorage();
        dayEndShiftsToday();
        habitRemindersFireOnceInWindow();
        snoozeBringsReminderBack();
        nudgeAndStreakAlert();
        remindersRespectSettings();
        startWithWindowsScript();

        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- schedules

    private static void scheduleEncodingAndRules() {
        check("daily encodes as DAILY", "DAILY", Schedule.daily().encode());
        check("weekdays round trip", MON_WED_FRI, Schedule.parse(MON_WED_FRI.encode()));
        check("weekdays label", "Mon, Wed, Fri", MON_WED_FRI.describe());
        check("weekly round trip", Schedule.timesPerWeek(3), Schedule.parse("weekly:3"));
        check("all seven days is daily", true, Schedule.onDays(EnumSet.allOf(DayOfWeek.class)).isDaily());
        check("7 times a week is daily", true, Schedule.timesPerWeek(7).isDaily());
        check("Tuesday is a rest day for Mon/Wed/Fri", false, MON_WED_FRI.isScheduledOn(MONDAY.plusDays(1)));
        checkThrows("no days is rejected", () -> Schedule.onDays(EnumSet.noneOf(DayOfWeek.class)));
        checkThrows("8 times a week is rejected", () -> Schedule.timesPerWeek(8));
        checkThrows("unknown schedule is rejected", () -> Schedule.parse("SOMETIMES"));
    }

    private static void weekdayStreakSkipsRestDays() {
        // Done Mon 1, Wed 3, Fri 5, Mon 8 -> 4 in a row, rest days in between don't break it.
        Habit habit = habit("Gym", MONDAY, MON_WED_FRI, MONDAY, MONDAY.plusDays(2), MONDAY.plusDays(4), MONDAY.plusDays(7));
        check("weekday streak on a scheduled, done day", 4, StreakCalculator.currentStreak(habit, MONDAY.plusDays(7)));
        check("weekday streak survives a rest day (Tue 9)", 4, StreakCalculator.currentStreak(habit, MONDAY.plusDays(8)));
        check("weekday streak still open on Wed 10 before check in", 4, StreakCalculator.currentStreak(habit, MONDAY.plusDays(9)));
        check("weekday streak breaks after a missed Wed 10 (seen on Thu 11)", 0, StreakCalculator.currentStreak(habit, MONDAY.plusDays(10)));
        check("unit is day", "day", StreakCalculator.unit(habit));
    }

    private static void weekdayLongestStreak() {
        // Mon 1, Wed 3, (missed Fri 5), Mon 8, Wed 10, Fri 12 -> longest 3. A done Tuesday is ignored.
        Habit habit = habit("Gym", MONDAY, MON_WED_FRI, MONDAY, MONDAY.plusDays(2), MONDAY.plusDays(7),
                MONDAY.plusDays(8), MONDAY.plusDays(9), MONDAY.plusDays(11));
        check("longest weekday run ignores rest days", 3, StreakCalculator.longestStreak(habit));
    }

    private static void weeklyStreaks() {
        Habit habit = habit("Yoga", MONDAY, Schedule.timesPerWeek(2),
                MONDAY, MONDAY.plusDays(3),          // week 1: 2 -> met
                MONDAY.plusDays(8), MONDAY.plusDays(12), // week 2: 2 -> met
                MONDAY.plusDays(14));                // week 3: 1 so far
        LocalDate week3Wednesday = MONDAY.plusDays(16);
        check("weekly streak counts met weeks, current week still open", 2, StreakCalculator.currentStreak(habit, week3Wednesday));
        habit.markDone(week3Wednesday);
        check("weekly streak includes this week once the goal is met", 3, StreakCalculator.currentStreak(habit, week3Wednesday));
        check("weekly longest streak", 3, StreakCalculator.longestStreak(habit));
        check("weekly unit", "week", StreakCalculator.unit(habit));
        check("week progress", new InsightEngine.WeekProgress(2, 2), InsightEngine.weekProgress(habit, week3Wednesday));
        LocalDate week5Monday = MONDAY.plusDays(28);
        check("weekly streak broken by a missed week", 0, StreakCalculator.currentStreak(habit, week5Monday));
    }

    private static void scheduleAwareCompletion() {
        // Mon/Wed/Fri over Mon 1 .. Sun 7: 3 scheduled days, 2 done (+1 done on a rest day, ignored).
        Habit weekdays = habit("Gym", MONDAY, MON_WED_FRI, MONDAY, MONDAY.plusDays(1), MONDAY.plusDays(2));
        check("weekday completion counts scheduled days only", 66.7,
                InsightEngine.completionPercentage(weekdays, MONDAY.plusDays(6)));
        check("weekday completion with nothing scheduled yet is 0", 0.0,
                InsightEngine.completionPercentage(new Habit("New", MONDAY.plusDays(1)) {{ setSchedule(MON_WED_FRI); }},
                        MONDAY.plusDays(1)));

        // 3x a week, created Mon: week 1 done 4 times (capped at 3), week 2 (Mon only so far) expects 1, done 0.
        Habit weekly = habit("Yoga", MONDAY, Schedule.timesPerWeek(3),
                MONDAY, MONDAY.plusDays(1), MONDAY.plusDays(2), MONDAY.plusDays(3));
        check("weekly completion caps extra check ins and prorates the current week", 75.0,
                InsightEngine.completionPercentage(weekly, MONDAY.plusDays(7)));
        check("weekday rates leave out rest days", false,
                InsightEngine.weekdayRates(weekdays, MONDAY.plusDays(6)).containsKey(DayOfWeek.TUESDAY));
    }

    private static void dueTodayAndSummary() {
        LocalDate tuesday = MONDAY.plusDays(1);
        Habit gym = habit("Gym", MONDAY, MON_WED_FRI);
        Habit yoga = habit("Yoga", MONDAY, Schedule.timesPerWeek(1), MONDAY);
        Habit daily = habit("Water", MONDAY, Schedule.daily());
        Habit archived = habit("Old", MONDAY, Schedule.daily());
        archived.setArchived(true);
        check("weekday habit is not due on a rest day", false, InsightEngine.isDueOn(gym, tuesday));
        check("weekly habit is not due once the goal is met", false, InsightEngine.isDueOn(yoga, tuesday));
        check("weekly habit done today stays due (shows as done)", true, InsightEngine.isDueOn(yoga, MONDAY));
        check("archived habit is never due", false, InsightEngine.isDueOn(archived, tuesday));
        InsightEngine.OverallSummary summary = InsightEngine.overallSummary(List.of(gym, yoga, daily, archived), tuesday);
        check("summary only counts habits due today", 1, summary.totalHabits());
    }

    private static void scheduleAwareAttention() {
        LocalDate friday = MONDAY.plusDays(11); // Fri 12: last three scheduled days are Mon 8, Wed 10, Fri 12
        Habit missedThree = habit("Gym", MONDAY, MON_WED_FRI, MONDAY.plusDays(4));
        Habit doneOne = habit("Gym", MONDAY, MON_WED_FRI, MONDAY.plusDays(9));
        check("weekday habit: last 3 scheduled days missed", true, InsightEngine.needsAttention(missedThree, friday));
        check("weekday habit: one of the last 3 done", false, InsightEngine.needsAttention(doneOne, friday));
        Habit weekly = habit("Yoga", MONDAY, Schedule.timesPerWeek(2), MONDAY);
        check("weekly habit with nothing in 7 days needs attention", true, InsightEngine.needsAttention(weekly, MONDAY.plusDays(8)));
        check("weekly habit done 6 days ago is fine", false, InsightEngine.needsAttention(weekly, MONDAY.plusDays(6)));
    }

    // ---------------------------------------------------------------- targets & notes

    private static void targetsAndProgress() {
        Habit water = new Habit("Water", MONDAY);
        water.setTarget(8);
        check("progress adds up", 3, water.addProgress(MONDAY, 3));
        check("not done below target", false, water.isDoneOn(MONDAY));
        check("progress is capped at the target", 8, water.addProgress(MONDAY, 20));
        check("done at the target", true, water.isDoneOn(MONDAY));
        check("progress never goes below zero", 0, water.addProgress(MONDAY, -50));
        check("empty days are not stored", 0, water.getCheckIns().size());
        check("markDone fills the target", true, water.markDone(MONDAY) && water.getCount(MONDAY) == 8);
        checkThrows("target 0 is rejected", () -> water.setTarget(0));
    }

    private static void raisingTargetKeepsHistory() {
        Habit water = habit("Water", MONDAY, Schedule.daily(), MONDAY, MONDAY.plusDays(1));
        water.setTarget(8);
        check("days done before raising the target stay done", 2, water.getCompletedDates().size());
        check("streak is unchanged by a new target", 2, StreakCalculator.currentStreak(water, MONDAY.plusDays(1)));
    }

    private static void notes() {
        Habit habit = new Habit("Run", MONDAY);
        habit.setNote(MONDAY, "  5 km  ");
        check("note is trimmed", "5 km", habit.getNote(MONDAY));
        check("a note alone does not mark the day done", false, habit.isDoneOn(MONDAY));
        habit.markDone(MONDAY);
        habit.undo(MONDAY);
        check("undo keeps the note", "5 km", habit.getNote(MONDAY));
        habit.setNote(MONDAY, " ");
        check("blank note removes the day entirely", 0, habit.getCheckIns().size());
        checkThrows("very long notes are rejected", () -> habit.setNote(MONDAY, "x".repeat(281)));
    }

    // ---------------------------------------------------------------- manager

    private static void managerEditsAreAtomic() {
        HabitManager manager = new HabitManager();
        Habit habit = manager.addHabit(new HabitDetails("Read", MON_WED_FRI, 2, LocalTime.of(21, 0), 3, BOOKS), MONDAY);
        check("details applied on add", true, habit.getSchedule().equals(MON_WED_FRI) && habit.getTarget() == 2
                && habit.getReminderTime().equals(LocalTime.of(21, 0)) && habit.getColorIndex() == 3);
        checkThrows("bad colour is rejected", () -> manager.updateHabit(habit,
                new HabitDetails("Read more", Schedule.daily(), 1, null, 9, null)));
        check("a rejected edit changes nothing (name)", "Read", habit.getName());
        check("a rejected edit changes nothing (schedule)", MON_WED_FRI, habit.getSchedule());
        manager.updateHabit(habit, new HabitDetails("Read more", Schedule.daily(), 1, null, null, null));
        check("valid edit renames", true, manager.findHabit("read more").isPresent());
        check("valid edit clears the reminder", null, habit.getReminderTime());
        checkThrows("target over 99 is rejected", () -> manager.addHabit(
                new HabitDetails("Water", Schedule.daily(), 100, null, null, null), MONDAY));
    }

    private static void reorderAndArchive() {
        HabitManager manager = new HabitManager();
        Habit a = manager.addHabit("A", MONDAY);
        manager.addHabit("B", MONDAY);
        manager.addHabit("C", MONDAY);
        manager.moveHabit(a, 2);
        check("move to the end", "B,C,A", names(manager));
        manager.moveHabit(a, 0);
        check("move to the start", "A,B,C", names(manager));
        manager.moveHabit(a, 99);
        check("out of range index is clamped", "B,C,A", names(manager));
        manager.setArchived(a, true);
        a.markDone(MONDAY);
        check("archived habits keep history", true, a.isDoneOn(MONDAY));
        check("archived habits leave the summary", 2,
                InsightEngine.overallSummary(manager.listHabits(), MONDAY).totalHabits());
    }

    // ---------------------------------------------------------------- storage

    private static void fileFormatRoundTrip() throws IOException {
        Path file = Files.createTempFile("habits-v2", ".txt");
        try {
            Habit habit = new Habit("Tea | biscuits, yes: 100%", MONDAY);
            habit.setTarget(3);
            habit.setSchedule(MON_WED_FRI);
            habit.setReminderTime(LocalTime.of(7, 30));
            habit.setColorIndex(5);
            habit.setEmoji(TEAPOT);
            habit.setArchived(true);
            habit.markDone(MONDAY);
            habit.addProgress(MONDAY.plusDays(2), 2);
            habit.setNote(MONDAY.plusDays(2), "Earl grey, then | green: lovely\nline two");
            Habit plain = new Habit("Plain", MONDAY);

            FileStorage storage = new FileStorage(file);
            storage.save(List.of(habit, plain));
            check("file starts with the format header", true, Files.readString(file).startsWith(FileStorage.HEADER));
            FileStorage.LoadResult result = storage.load();
            Habit loaded = result.habits().get(0);
            check("v2: no warnings", 0, result.warnings().size());
            check("v2: not legacy", false, result.legacyFormat());
            check("v2: name with | , : %", habit.getName(), loaded.getName());
            check("v2: target", 3, loaded.getTarget());
            check("v2: schedule", MON_WED_FRI, loaded.getSchedule());
            check("v2: reminder", LocalTime.of(7, 30), loaded.getReminderTime());
            check("v2: colour", 5, loaded.getColorIndex());
            check("v2: emoji", TEAPOT, loaded.getEmoji());
            check("v2: archived", true, loaded.isArchived());
            check("v2: check ins incl. partial day and note", habit.getCheckIns(), loaded.getCheckIns());
            check("v2: plain habit defaults", true, result.habits().get(1).getSchedule().isDaily()
                    && result.habits().get(1).getTarget() == 1 && result.habits().get(1).getReminderTime() == null);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static void legacyFileIsBackedUpAndConverted() throws IOException {
        Path dir = Files.createTempDirectory("habits-legacy");
        Path file = dir.resolve("habits.txt");
        Path backup = dir.resolve("habits.txt.v1.bak");
        try {
            List<String> legacy = List.of("Drink water|2024-01-01|2024-01-01,2024-01-02", "Run|2024-01-02|");
            Files.write(file, legacy, StandardCharsets.UTF_8);
            Clock clock = Clock.fixed(MONDAY.plusDays(5).atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
            HabitService service = new HabitService(new HabitManager(), new FileStorage(file), clock);
            List<String> warnings = service.load();
            check("legacy: loads without warnings", 0, warnings.size());
            check("legacy: backup made", legacy, Files.readAllLines(backup, StandardCharsets.UTF_8));
            check("legacy: file converted to the new format", true, Files.readString(file).startsWith(FileStorage.HEADER));
            FileStorage.LoadResult reloaded = new FileStorage(file).load();
            check("legacy: data survives conversion", 2, reloaded.habits().get(0).getCompletedDates().size());
            check("legacy: habit with no dates survives", "Run", reloaded.habits().get(1).getName());
        } finally {
            Files.deleteIfExists(backup);
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }

    private static void malformedNewFormatLines() throws IOException {
        Path file = Files.createTempFile("habits-v2-bad", ".txt");
        try {
            Files.write(file, List.of(
                    FileStorage.HEADER,
                    "name=Good|created=2024-01-01|schedule=DAILY|target=1|archived=false|checkins=2024-01-01:1",
                    "name=No+date|schedule=DAILY",
                    "name=Bad+schedule|created=2024-01-01|schedule=SOMETIMES",
                    "name=Bad+checkin|created=2024-01-01|checkins=2024-01-01",
                    "name=Bad+time|created=2024-01-01|reminder=25:99",
                    "not a key value line",
                    "name=Future+field|created=2024-01-01|sparkles=yes"), StandardCharsets.UTF_8);
            FileStorage.LoadResult result = new FileStorage(file).load();
            check("v2 malformed: good lines load (unknown keys ignored)", 2, result.habits().size());
            check("v2 malformed: one warning per bad line", 5, result.warnings().size());
            check("v2 malformed: warning names the line", true, result.warnings().get(0).contains("line 3"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    // ---------------------------------------------------------------- settings

    private static void settingsStorage() throws IOException {
        Path dir = Files.createTempDirectory("settings");
        Path file = dir.resolve("settings.properties");
        try {
            SettingsStorage storage = new SettingsStorage(file);
            check("missing settings file gives defaults", Settings.defaults(), storage.load());
            Settings custom = new Settings(false, true, LocalTime.of(19, 15), false, LocalTime.of(22, 0), LocalTime.of(3, 0));
            storage.save(custom);
            check("settings round trip", custom, storage.load());
            Files.writeString(file, "nudge.time=late\nday.endsAt=09:00\nreminders.enabled=false\n");
            Settings fallback = storage.load();
            check("bad values fall back to defaults", Settings.defaults().nudgeTime(), fallback.nudgeTime());
            check("day end after 5 AM falls back", LocalTime.MIDNIGHT, fallback.dayEndsAt());
            check("good values are kept", false, fallback.remindersEnabled());
            checkThrows("day end after 5 AM is rejected", () -> new Settings(true, true, LocalTime.NOON,
                    true, LocalTime.NOON, LocalTime.of(6, 0)));
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }

    private static void dayEndShiftsToday() throws IOException {
        Path file = Files.createTempFile("habits-dayend", ".txt");
        try {
            Clock oneAm = Clock.fixed(MONDAY.plusDays(1).atTime(1, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
            HabitService service = new HabitService(new HabitManager(), new FileStorage(file), oneAm);
            check("at 1 AM with midnight day end, today is the new day", MONDAY.plusDays(1), service.today());
            service.updateSettings(new Settings(true, true, LocalTime.of(20, 0), true, LocalTime.of(21, 30), LocalTime.of(3, 0)));
            check("at 1 AM with a 3 AM day end, today is still yesterday", MONDAY, service.today());
            service.addHabit("Night owl");
            service.checkIn("Night owl");
            check("a 1 AM check in counts for yesterday", true, service.habits().get(0).isDoneOn(MONDAY));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    // ---------------------------------------------------------------- reminders

    private static Settings settings(boolean nudge, boolean streak) {
        return new Settings(true, nudge, LocalTime.of(20, 0), streak, LocalTime.of(21, 30), LocalTime.MIDNIGHT);
    }

    private static void habitRemindersFireOnceInWindow() {
        ReminderEngine engine = new ReminderEngine();
        Habit run = habit("Run", MONDAY, Schedule.daily());
        run.setReminderTime(LocalTime.of(7, 0));
        List<Habit> list = List.of(run);
        Settings quiet = settings(false, false);
        LocalDate day = MONDAY.plusDays(2);

        check("not before the reminder time", 0, engine.due(list, quiet, day, day.atTime(6, 59)).size());
        List<Reminder> fired = engine.due(list, quiet, day, day.atTime(7, 0, 20));
        check("fires at the reminder time", 1, fired.size());
        check("it is a habit reminder for Run", "HABIT:Run", fired.get(0).kind() + ":" + fired.get(0).habitName());
        check("only once a day", 0, engine.due(list, quiet, day, day.atTime(7, 1)).size());

        ReminderEngine late = new ReminderEngine();
        check("still sent up to 2 hours late (computer was asleep)", 1, late.due(list, quiet, day, day.atTime(8, 59)).size());
        ReminderEngine tooLate = new ReminderEngine();
        check("skipped when more than 2 hours late", 0, tooLate.due(list, quiet, day, day.atTime(9, 1)).size());

        ReminderEngine done = new ReminderEngine();
        run.markDone(day);
        check("no reminder once the habit is done", 0, done.due(list, quiet, day, day.atTime(7, 0)).size());

        ReminderEngine rest = new ReminderEngine();
        Habit gym = habit("Gym", MONDAY, MON_WED_FRI);
        gym.setReminderTime(LocalTime.of(7, 0));
        check("no reminder on a rest day", 0, rest.due(List.of(gym), quiet, MONDAY.plusDays(1), MONDAY.plusDays(1).atTime(7, 0)).size());
        Habit tomorrowRun = habit("Run", MONDAY, Schedule.daily());
        tomorrowRun.setReminderTime(LocalTime.of(7, 0));
        check("next day it fires again", 1,
                engine.due(List.of(tomorrowRun), quiet, day.plusDays(1), day.plusDays(1).atTime(7, 5)).size());
    }

    private static void snoozeBringsReminderBack() {
        ReminderEngine engine = new ReminderEngine();
        Habit run = habit("Run", MONDAY, Schedule.daily());
        run.setReminderTime(LocalTime.of(7, 0));
        List<Habit> list = List.of(run);
        Settings quiet = settings(false, false);
        LocalDate day = MONDAY.plusDays(2);
        engine.due(list, quiet, day, day.atTime(7, 0));
        engine.snooze("run", 10, day.atTime(7, 1));
        check("snoozed: quiet during the snooze", 0, engine.due(list, quiet, day, day.atTime(7, 10)).size());
        List<Reminder> back = engine.due(list, quiet, day, day.atTime(7, 11));
        check("snoozed: comes back after 10 minutes", 1, back.size());
        check("snoozed: title says so", "Snoozed reminder", back.get(0).title());
        check("snoozed: only once", 0, engine.due(list, quiet, day, day.atTime(7, 12)).size());

        engine.snooze("Run", 10, day.atTime(8, 0));
        run.markDone(day);
        check("snoozed: dropped if done in the meantime", 0, engine.due(list, quiet, day, day.atTime(8, 20)).size());
        check("snoozed: no longer tracked", false, engine.isSnoozed("Run"));
    }

    private static void nudgeAndStreakAlert() {
        LocalDate day = MONDAY.plusDays(5);
        Habit streaky = habit("Read", MONDAY, Schedule.daily(), MONDAY.plusDays(2), MONDAY.plusDays(3), MONDAY.plusDays(4));
        Habit doneToday = habit("Water", MONDAY, Schedule.daily(), day);
        List<Habit> list = List.of(streaky, doneToday);
        ReminderEngine engine = new ReminderEngine();
        Settings both = settings(true, true);

        List<Reminder> evening = engine.due(list, both, day, day.atTime(20, 0, 30));
        check("nudge fires at 8 PM", Kind.NUDGE, evening.get(0).kind());
        check("nudge mentions only habits still to do", "1 habit still to do today", evening.get(0).title());
        check("no streak alert before its time", 1, evening.size());

        List<Reminder> later = engine.due(list, both, day, day.atTime(21, 30, 30));
        check("streak alert fires at 9:30 PM", Kind.STREAK, later.get(0).kind());
        check("streak alert names the habit and length", true, later.get(0).message().contains("3-day streak on Read"));

        ReminderEngine noStreak = new ReminderEngine();
        Habit fresh = habit("New", MONDAY, Schedule.daily());
        check("no streak alert without a streak worth protecting", 0,
                noStreak.due(List.of(fresh), settings(false, true), day, day.atTime(21, 31)).size());
        ReminderEngine allDone = new ReminderEngine();
        check("no nudge when everything is done", 0,
                allDone.due(List.of(doneToday), both, day, day.atTime(20, 1)).size());
    }

    private static void remindersRespectSettings() {
        LocalDate day = MONDAY.plusDays(2);
        Habit run = habit("Run", MONDAY, Schedule.daily());
        run.setReminderTime(LocalTime.of(7, 0));
        Settings off = new Settings(false, true, LocalTime.of(20, 0), true, LocalTime.of(21, 30), LocalTime.MIDNIGHT);
        check("master switch off: nothing fires", 0, new ReminderEngine().due(List.of(run), off, day, day.atTime(7, 0)).size());

        // With the day ending at 3 AM, a 1 AM reminder belongs to the night after the logical day.
        Habit late = habit("Journal", MONDAY, Schedule.daily());
        late.setReminderTime(LocalTime.of(1, 0));
        Settings nightOwl = new Settings(true, false, LocalTime.of(20, 0), false, LocalTime.of(21, 30), LocalTime.of(3, 0));
        ReminderEngine engine = new ReminderEngine();
        check("night owl: no 1 AM reminder at the start of the day", 0, engine.due(List.of(late), nightOwl, day, day.atTime(3, 30)).size());
        check("night owl: 1 AM reminder fires after midnight", 1, engine.due(List.of(late), nightOwl, day, day.plusDays(1).atTime(1, 0)).size());
    }

    // ---------------------------------------------------------------- start with Windows

    private static void startWithWindowsScript() throws IOException {
        Path startup = Files.createTempDirectory("startup");
        Path script = startup.resolve("HabitManager.cmd");
        try {
            StartupManager manager = new StartupManager(startup, Path.of("C:/Habits"), "\"javaw\" -cp \"out\" habitmanager.Main --no-browser");
            check("startup: supported with a folder", true, manager.isSupported());
            check("startup: off by default", false, manager.isEnabled());
            manager.setEnabled(true);
            String content = Files.readString(script);
            check("startup: script launches the app in the background", true,
                    content.contains("start \"\" \"javaw\" -cp \"out\" habitmanager.Main --no-browser"));
            check("startup: script changes to the app folder", true, content.contains("cd /d \"C:" + java.io.File.separator + "Habits\""));
            check("startup: reported as on", true, manager.isEnabled());
            manager.setEnabled(false);
            check("startup: switching off removes the script", false, Files.exists(script));
            StartupManager unsupported = new StartupManager(null, Path.of("."), "x");
            check("startup: unsupported without a folder", false, unsupported.isSupported());
            checkThrows("startup: enabling when unsupported is rejected", () -> {
                try {
                    unsupported.setEnabled(true);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } finally {
            Files.deleteIfExists(script);
            Files.deleteIfExists(startup);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static Habit habit(String name, LocalDate created, Schedule schedule, LocalDate... doneDates) {
        Habit habit = new Habit(name, created);
        habit.setSchedule(schedule);
        for (LocalDate date : doneDates) {
            habit.markDone(date);
        }
        return habit;
    }

    private static String names(HabitManager manager) {
        return String.join(",", manager.listHabits().stream().map(Habit::getName).toList());
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
