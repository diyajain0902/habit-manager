import habitmanager.desktop.IconExporter;
import habitmanager.model.Habit;
import habitmanager.model.Schedule;
import habitmanager.service.DataExchange;
import habitmanager.service.HabitManager;
import habitmanager.service.HabitService;
import habitmanager.service.InsightEngine;
import habitmanager.service.Milestones;
import habitmanager.storage.AutoBackup;
import habitmanager.storage.FileStorage;
import habitmanager.web.StaticFileHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Plain-Java tests (no JUnit) for Phase 2: undo, export / import, automatic backups,
 * badges, the weekly summary, the static file path check and the app icon. Run with:
 *   java -cp out DataAndBadgeTests
 * Exits with status 1 if any check fails.
 *
 * Dates used: 2024-01-01 is a Monday.
 */
public class DataAndBadgeTests {

    private static final LocalDate MONDAY = LocalDate.of(2024, 1, 1);

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws IOException {
        undoRollsBackAnyChange();
        undoHistoryLimits();
        importReplaceAndMerge();
        importRejectsJunkAndAcceptsOldFormat();
        csvExport();
        autoBackupOncePerDayAndPrunes();
        streakBadges();
        totalBadges();
        weeklySummary();
        staticFilePathCheck();
        iconFile();

        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- undo

    private static void undoRollsBackAnyChange() throws IOException {
        Path file = Files.createTempFile("undo", ".txt");
        try {
            HabitService service = service(file, MONDAY);
            service.addHabit("Read");
            service.addHabit("Run");
            service.checkIn("Read");
            check("undo label names the last change", "check in \"Read\"", service.undoLabel());
            check("undo returns what was undone", "check in \"Read\"", service.undoLast());
            check("undo removed the check in", false, service.habits().get(0).isDoneOn(MONDAY));

            service.checkIn("Read");
            service.setNote("Read", MONDAY, "chapter 3");
            service.deleteHabit("Read");
            check("delete removed the habit", 1, service.habits().size());
            service.undoLast();
            Habit restored = service.habits().get(0);
            check("undo brings a deleted habit back", "Read", restored.getName());
            check("...in its old position", "Read,Run", names(service));
            check("...with its history", true, restored.isDoneOn(MONDAY));
            check("...and its note", "chapter 3", restored.getNote(MONDAY));
            check("undo is saved to the file", 2, new FileStorage(file).load().habits().size());

            service.loadDemoData();
            service.undoLast();
            check("undo even reverts loading demo data", "Read,Run", names(service));

            checkThrows("a rejected change adds nothing to undo", () -> service.addHabit("read"));
            check("so the next undo is the earlier change", "edit the note for \"Read\"", service.undoLabel());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static void undoHistoryLimits() throws IOException {
        Path file = Files.createTempFile("undo-limit", ".txt");
        try {
            HabitService service = service(file, MONDAY);
            for (int i = 0; i < HabitService.UNDO_LIMIT + 5; i++) {
                service.addHabit("Habit " + i);
            }
            int undone = 0;
            while (service.undoLabel() != null) {
                service.undoLast();
                undone++;
            }
            check("only the last " + HabitService.UNDO_LIMIT + " changes are kept", HabitService.UNDO_LIMIT, undone);
            check("the oldest changes stay", 5, service.habits().size());
            checkThrows("nothing left to undo", service::undoLast);
            service.load();
            check("loading clears the undo history", null, service.undoLabel());
            check("checking in twice adds only one undo step", true, undoSteps(service, () -> {
                service.checkIn("Habit 0");
                service.checkIn("Habit 0");
            }) == 1);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static int undoSteps(HabitService service, Runnable action) {
        action.run();
        int steps = 0;
        String before = null;
        while (service.undoLabel() != null && !service.undoLabel().equals(before)) {
            before = service.undoLabel();
            if (!before.startsWith("check in")) {
                break;
            }
            service.undoLast();
            steps++;
        }
        return steps;
    }

    // ---------------------------------------------------------------- import

    private static void importReplaceAndMerge() throws IOException {
        Path file = Files.createTempFile("import", ".txt");
        try {
            HabitService service = service(file, MONDAY.plusDays(3));
            Habit water = new Habit("Water", MONDAY);
            water.setTarget(8);
            water.addProgress(MONDAY, 3);
            water.setNote(MONDAY, "mine");
            Habit read = new Habit("Read", MONDAY, List.of(MONDAY));
            String backup = String.join("\n", FileStorage.toLines(List.of(water, read)));

            HabitService.ImportReport replaced = service.importBackup(backup, true);
            check("replace: reports the restored habits", 2, replaced.result().replaced());
            check("replace: list is the backup", "Water,Read", names(service));

            // A second backup: Water done fully on Monday (target 1 there) with a note, plus a new habit.
            Habit theirWater = new Habit("water", MONDAY, List.of(MONDAY, MONDAY.plusDays(1)));
            theirWater.setNote(MONDAY, "theirs");
            theirWater.setNote(MONDAY.plusDays(2), "only in backup");
            Habit yoga = new Habit("Yoga", MONDAY, List.of(MONDAY));
            HabitService.ImportReport merged = service.importBackup(
                    String.join("\n", FileStorage.toLines(List.of(theirWater, yoga))), false);
            check("merge: one added, one merged", "1/1", merged.result().added() + "/" + merged.result().merged());
            Habit mine = service.habits().get(0);
            check("merge: a done day stays done under this habit's target", true, mine.isDoneOn(MONDAY));
            check("merge: count scaled to this habit's target", 8, mine.getCount(MONDAY.plusDays(1)));
            check("merge: existing note wins", "mine", mine.getNote(MONDAY));
            check("merge: new notes come across", "only in backup", mine.getNote(MONDAY.plusDays(2)));
            check("merge: this habit keeps its own target", 8, mine.getTarget());
            check("merge: new habit added at the end", "Water,Read,Yoga", names(service));
            check("merge can be undone", "import a backup", service.undoLast());
            check("...back to the replaced list", "Water,Read", names(service));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static void importRejectsJunkAndAcceptsOldFormat() throws IOException {
        Path file = Files.createTempFile("import-junk", ".txt");
        try {
            HabitService service = service(file, MONDAY);
            service.addHabit("Keep me");
            checkThrows("a file with no habits is rejected", () -> service.importBackup("hello\nworld\n", true));
            checkThrows("an empty file is rejected", () -> service.importBackup("", false));
            check("a rejected import changes nothing", "Keep me", names(service));
            HabitService.ImportReport old = service.importBackup("Run|2024-01-01|2024-01-01\nbroken line\n", false);
            check("an old-format habits.txt can be imported", true, service.habits().stream().anyMatch(h -> h.getName().equals("Run")));
            check("broken lines are reported, not fatal", 1, old.warnings().size());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    // ---------------------------------------------------------------- CSV

    private static void csvExport() {
        Habit habit = new Habit("Tea, \"green\"", MONDAY);
        habit.setTarget(2);
        habit.addProgress(MONDAY, 1);
        habit.markDone(MONDAY.plusDays(1));
        habit.setNote(MONDAY.plusDays(1), "line one\nline two");
        Habit sneaky = new Habit("=HYPERLINK(\"x\")", MONDAY, List.of(MONDAY));
        String[] lines = DataExchange.toCsv(List.of(habit, sneaky)).split("\r\n");
        check("csv header", "habit,date,count,target,done,note", lines[0]);
        check("csv quotes commas and doubles quotes", "\"Tea, \"\"green\"\"\",2024-01-01,1,2,no,", lines[1]);
        check("csv keeps line breaks inside quotes", "\"Tea, \"\"green\"\"\",2024-01-02,2,2,yes,\"line one\nline two\"", lines[2]);
        check("csv defuses spreadsheet formulas", true, lines[3].startsWith("\"'=HYPERLINK("));
    }

    // ---------------------------------------------------------------- automatic backups

    private static void autoBackupOncePerDayAndPrunes() throws IOException {
        Path dir = Files.createTempDirectory("auto-backup");
        Path data = dir.resolve("habits.txt");
        AutoBackup backup = new AutoBackup(data);
        try {
            backup.backUpIfNeeded(MONDAY);
            check("no data file yet: nothing to back up", 0, backup.list().size());
            Files.writeString(data, "first");
            backup.backUpIfNeeded(MONDAY);
            Files.writeString(data, "second");
            backup.backUpIfNeeded(MONDAY);
            check("one copy per day", 1, backup.list().size());
            check("the copy is from the start of the day", "first", Files.readString(backup.list().get(0)));
            for (int i = 1; i <= 20; i++) {
                backup.backUpIfNeeded(MONDAY.plusDays(i));
            }
            List<Path> kept = backup.list();
            check("only the newest " + AutoBackup.KEEP + " are kept", AutoBackup.KEEP, kept.size());
            check("newest first", "habits-2024-01-21.txt", kept.get(0).getFileName().toString());
        } finally {
            try (Stream<Path> files = Files.walk(dir)) {
                files.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> p.toFile().delete());
            }
        }
    }

    // ---------------------------------------------------------------- badges

    private static void streakBadges() {
        Habit habit = new Habit("Run", MONDAY);
        for (int i = 0; i < 8; i++) {
            habit.markDone(MONDAY.plusDays(i));
        }
        List<Milestones.Badge> badges = Milestones.streakBadges(habit, MONDAY.plusDays(7));
        check("8-day streak earns 3 and 7, and shows 14 next", "3:true,7:true,14:false", summary(badges));
        check("badge label", "7-day streak", badges.get(1).label());
        check("next badge shows current progress", 8, badges.get(2).progress());
        check("badges are kept after the streak lapses (best ever counts)", "3:true,7:true,14:false",
                summary(Milestones.streakBadges(habit, MONDAY.plusDays(30))));
        check("...even though the current streak is 0", 0,
                Milestones.streakBadges(habit, MONDAY.plusDays(30)).get(2).progress());
        check("badge ids are stable and case-insensitive", "streak:run:7", badges.get(1).id());

        Habit weekly = new Habit("Yoga", MONDAY);
        weekly.setSchedule(Schedule.timesPerWeek(1));
        for (int w = 0; w < 5; w++) {
            weekly.markDone(MONDAY.plusWeeks(w));
        }
        check("weekly habits earn week badges", "2:true,4:true,8:false",
                summary(Milestones.streakBadges(weekly, MONDAY.plusWeeks(4))));
        check("brand new habit: only the first badge, not earned", "3:false",
                summary(Milestones.streakBadges(new Habit("New", MONDAY), MONDAY)));
    }

    private static void totalBadges() {
        Habit a = new Habit("A", MONDAY);
        Habit b = new Habit("B", MONDAY);
        for (int i = 0; i < 30; i++) {
            a.markDone(MONDAY.plusDays(i));
            b.markDone(MONDAY.plusDays(i));
        }
        check("60 check-ins earn 10 and 50, 100 next", "10:true,50:true,100:false",
                summary(Milestones.totalBadges(List.of(a, b))));
    }

    // ---------------------------------------------------------------- weekly summary

    private static void weeklySummary() {
        LocalDate wednesday = MONDAY.plusDays(9); // week 2: Mon 8 .. Wed 10
        Habit daily = new Habit("Water", MONDAY);
        Habit gym = new Habit("Gym", MONDAY);
        gym.setSchedule(Schedule.onDays(EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)));
        for (int i = 0; i < 7; i++) {
            daily.markDone(MONDAY.plusDays(i)); // last week: 7 of 7
        }
        gym.markDone(MONDAY);                     // last week: 1 of 3
        daily.markDone(MONDAY.plusDays(7));       // this week: Mon
        daily.markDone(MONDAY.plusDays(8));       // Tue
        gym.markDone(MONDAY.plusDays(7));         // Mon (Wed not yet)
        InsightEngine.WeekSummary week = InsightEngine.weekSummary(List.of(daily, gym), wednesday);
        check("this week: done so far", 3, week.done());
        check("this week: expected so far (3 daily + Mon & Wed gym)", 5, week.expected());
        check("last week done", 8, week.lastWeekDone());
        check("last week expected", 10, week.lastWeekExpected());
        check("not the best week yet", false, week.bestWeek());

        Habit fresh = new Habit("Fresh", MONDAY.plusDays(7));
        fresh.markDone(MONDAY.plusDays(7));
        check("first week with a check-in is the best week", true,
                InsightEngine.weekSummary(List.of(fresh), MONDAY.plusDays(8)).bestWeek());
    }

    // ---------------------------------------------------------------- web & desktop

    private static void staticFilePathCheck() {
        check("normal file is allowed", true, StaticFileHandler.isSafe("styles.css"));
        check("sub folder is allowed", true, StaticFileHandler.isSafe("img/bubble.svg"));
        check("parent folder is refused", false, StaticFileHandler.isSafe("../habits.txt"));
        check("hidden parent in the middle is refused", false, StaticFileHandler.isSafe("img/../../secret"));
        check("backslashes are refused", false, StaticFileHandler.isSafe("..\\habits.txt"));
        check("drive letters are refused", false, StaticFileHandler.isSafe("C:/Windows/win.ini"));
        check("absolute paths are refused", false, StaticFileHandler.isSafe("/etc/passwd"));
    }

    private static void iconFile() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        IconExporter.writeIco(bytes);
        ByteBuffer ico = ByteBuffer.wrap(bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
        check("ico: reserved field", 0, (int) ico.getShort(0));
        check("ico: type is icon", 1, (int) ico.getShort(2));
        check("ico: holds 7 sizes", 7, (int) ico.getShort(4));
        int firstOffset = ico.getInt(6 + 12);
        byte[] signature = new byte[4];
        ico.position(firstOffset);
        ico.get(signature);
        check("ico: images are PNGs", "PNG", new String(signature, 1, 3, StandardCharsets.US_ASCII));
    }

    // ---------------------------------------------------------------- helpers

    private static HabitService service(Path file, LocalDate today) throws IOException {
        Clock clock = Clock.fixed(today.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        HabitService service = new HabitService(new HabitManager(), new FileStorage(file), clock);
        service.load();
        return service;
    }

    private static String names(HabitService service) {
        return String.join(",", service.habits().stream().map(Habit::getName).toList());
    }

    private static String summary(List<Milestones.Badge> badges) {
        return String.join(",", badges.stream().map(b -> b.goal() + ":" + b.earned()).toList());
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
