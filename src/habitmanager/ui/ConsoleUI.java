package habitmanager.ui;

import habitmanager.model.Habit;
import habitmanager.service.DemoDataGenerator;
import habitmanager.service.HabitManager;
import habitmanager.service.InsightEngine;
import habitmanager.service.InsightEngine.DayStatus;
import habitmanager.service.InsightEngine.OverallSummary;
import habitmanager.service.StreakCalculator;
import habitmanager.storage.FileStorage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Menu loop and all user input handling. Holds no business rules of its own:
 * everything is delegated to the service classes.
 */
public class ConsoleUI {

    private static final String DONE = Character.toString(0x2714);     // check mark
    private static final String NOT_DONE = Character.toString(0x2718); // cross mark
    private static final String NOT_TRACKED = "-";
    private static final int HISTORY_DAYS = 7;
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("MM-dd");

    private final HabitManager manager;
    private final FileStorage storage;
    private final Clock clock;
    private final BufferedReader in;
    private final PrintStream out;

    /** Thrown when standard input is closed (e.g. Ctrl+D / Ctrl+Z), so we can save and exit cleanly. */
    private static class InputClosedException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    public ConsoleUI(HabitManager manager, FileStorage storage, Clock clock, BufferedReader in, PrintStream out) {
        this.manager = manager;
        this.storage = storage;
        this.clock = clock;
        this.in = in;
        this.out = out;
    }

    public void run() {
        loadOnStartup();
        try {
            menuLoop();
        } catch (InputClosedException e) {
            out.println();
            out.println("Input closed.");
        }
        save();
        out.println("Data saved to " + storage.getFile() + ". Goodbye!");
    }

    // ---------------------------------------------------------------- menu

    private void menuLoop() {
        while (true) {
            printMenu();
            int choice = readInt("Choose an option: ", 0, 11);
            if (choice == 0) {
                return;
            }
            out.println();
            handleChoice(choice);
            out.println();
        }
    }

    private void printMenu() {
        out.println("===== HABIT MANAGER =====");
        out.println("1. View habits (today's status)");
        out.println("2. Add habit");
        out.println("3. Mark habit done today");
        out.println("4. Undo today's check in");
        out.println("5. Backfill a past date");
        out.println("6. Rename habit");
        out.println("7. Delete habit");
        out.println("8. View streaks");
        out.println("9. View insights");
        out.println("10. View history (last 7 days)");
        out.println("11. Load demo data");
        out.println("0. Save and exit");
    }

    private void handleChoice(int choice) {
        switch (choice) {
            case 1 -> viewHabits();
            case 2 -> addHabit();
            case 3 -> markDoneToday();
            case 4 -> undoToday();
            case 5 -> backfill();
            case 6 -> renameHabit();
            case 7 -> deleteHabit();
            case 8 -> viewStreaks();
            case 9 -> viewInsights();
            case 10 -> viewHistory();
            case 11 -> loadDemoData();
            default -> out.println("Unknown option.");
        }
    }

    // ---------------------------------------------------------------- habit management

    private void viewHabits() {
        if (printIfEmpty()) {
            return;
        }
        LocalDate today = today();
        out.println("Habits for " + today + ":");
        int width = nameColumnWidth();
        out.printf("    %-" + width + "s  %-5s  %-7s  %s%n", "Habit", "Today", "Streak", "Completion");
        List<Habit> habits = activeHabits();
        for (int i = 0; i < habits.size(); i++) {
            Habit habit = habits.get(i);
            out.printf("%2d. %-" + width + "s  %-5s  %-7s  %.1f%%%n",
                    i + 1,
                    habit.getName(),
                    habit.isDoneOn(today) ? DONE : NOT_DONE,
                    StreakCalculator.currentStreak(habit, today) + unitLetter(habit),
                    InsightEngine.completionPercentage(habit, today));
        }
    }

    private void addHabit() {
        Optional<String> name = readValidName("Name of the new habit (leave empty to cancel): ", null);
        name.ifPresent(n -> {
            Habit habit = manager.addHabit(n, today());
            save();
            out.println("Added \"" + habit.getName() + "\".");
        });
    }

    private void renameHabit() {
        chooseHabit("Which habit do you want to rename?").ifPresent(habit -> {
            String oldName = habit.getName();
            readValidName("New name (leave empty to cancel): ", habit).ifPresent(newName -> {
                manager.renameHabit(habit, newName);
                save();
                out.println("Renamed \"" + oldName + "\" to \"" + habit.getName() + "\".");
            });
        });
    }

    private void deleteHabit() {
        chooseHabit("Which habit do you want to delete?").ifPresent(habit -> {
            if (readYesNo("Delete \"" + habit.getName() + "\" and all its history? (y/n): ")) {
                manager.deleteHabit(habit);
                save();
                out.println("Deleted \"" + habit.getName() + "\".");
            } else {
                out.println("Nothing deleted.");
            }
        });
    }

    // ---------------------------------------------------------------- daily tracking

    private void markDoneToday() {
        chooseHabit("Which habit did you complete today?").ifPresent(habit -> {
            if (manager.checkIn(habit, today())) {
                save();
                out.println("Nice! \"" + habit.getName() + "\" marked done for today. Current streak: "
                        + StreakCalculator.currentStreak(habit, today()) + " day(s).");
            } else {
                out.println("\"" + habit.getName() + "\" is already marked done for today.");
            }
        });
    }

    private void undoToday() {
        chooseHabit("Which habit do you want to undo for today?").ifPresent(habit -> {
            if (manager.undoCheckIn(habit, today())) {
                save();
                out.println("Removed today's check in for \"" + habit.getName() + "\".");
            } else {
                out.println("\"" + habit.getName() + "\" was not marked done today, nothing to undo.");
            }
        });
    }

    private void backfill() {
        chooseHabit("Which habit do you want to backfill?").ifPresent(habit -> {
            LocalDate today = today();
            while (true) {
                Optional<LocalDate> date = readDate("Date to mark done (yyyy-MM-dd, leave empty to cancel): ");
                if (date.isEmpty()) {
                    return;
                }
                try {
                    if (manager.backfill(habit, date.get(), today)) {
                        save();
                        out.println("Marked \"" + habit.getName() + "\" done on " + date.get() + ".");
                    } else {
                        out.println("\"" + habit.getName() + "\" was already done on " + date.get() + ".");
                    }
                    return;
                } catch (IllegalArgumentException e) {
                    out.println(e.getMessage());
                }
            }
        });
    }

    // ---------------------------------------------------------------- streaks, insights, history

    private void viewStreaks() {
        if (printIfEmpty()) {
            return;
        }
        LocalDate today = today();
        int width = nameColumnWidth();
        out.printf("%-" + width + "s  %-8s  %s%n", "Habit", "Current", "Longest");
        for (Habit habit : activeHabits()) {
            out.printf("%-" + width + "s  %-8s  %s%n",
                    habit.getName(),
                    StreakCalculator.currentStreak(habit, today) + unitLetter(habit),
                    StreakCalculator.longestStreak(habit) + unitLetter(habit));
        }
    }

    private void viewInsights() {
        if (printIfEmpty()) {
            return;
        }
        LocalDate today = today();
        printSummary(InsightEngine.overallSummary(manager.listHabits(), today));
        for (Habit habit : activeHabits()) {
            out.println();
            printHabitInsights(habit, today);
        }
    }

    private void printSummary(OverallSummary summary) {
        LocalDate today = today();
        out.println("----- Overall summary -----");
        out.println("Done today: " + summary.doneToday() + " of " + summary.totalHabits());
        summary.strongest().ifPresent(h -> out.printf("Strongest habit: %s (%.1f%%)%n",
                h.getName(), InsightEngine.completionPercentage(h, today)));
        summary.weakest().ifPresent(h -> out.printf("Weakest habit: %s (%.1f%%)%n",
                h.getName(), InsightEngine.completionPercentage(h, today)));
        if (summary.needingAttention().isEmpty()) {
            out.println("Needs attention: none, every habit was done in the last "
                    + InsightEngine.ATTENTION_WINDOW_DAYS + " days.");
        } else {
            out.println("Needs attention (not done in the last " + InsightEngine.ATTENTION_WINDOW_DAYS
                    + " days): " + String.join(", ", summary.needingAttention().stream().map(Habit::getName).toList()));
        }
    }

    private void printHabitInsights(Habit habit, LocalDate today) {
        out.println("----- " + habit.getName() + " -----");
        out.printf("Completion: %.1f%% since %s%n", InsightEngine.completionPercentage(habit, today),
                habit.getCreatedDate());

        EnumMap<DayOfWeek, Double> rates = InsightEngine.weekdayRates(habit, today);
        StringBuilder row = new StringBuilder("Weekday rates: ");
        for (Map.Entry<DayOfWeek, Double> entry : rates.entrySet()) {
            row.append(String.format("%s %.0f%%  ", shortName(entry.getKey()), entry.getValue() * 100));
        }
        out.println(row.toString().trim());

        InsightEngine.bestWeekday(habit, today).ifPresent(day ->
                out.printf("Best weekday: %s (%.0f%%)%n", displayName(day), rates.get(day) * 100));
        InsightEngine.worstWeekday(habit, today).ifPresent(day ->
                out.printf("Worst weekday: %s (%.0f%%)%n", displayName(day), rates.get(day) * 100));
    }

    private void viewHistory() {
        chooseHabit("Which habit's history do you want to see?").ifPresent(habit -> {
            List<DayStatus> days = InsightEngine.history(habit, today(), HISTORY_DAYS);
            StringBuilder statusRow = new StringBuilder();
            StringBuilder dateRow = new StringBuilder();
            for (DayStatus day : days) {
                String mark = day.beforeCreation() ? NOT_TRACKED : (day.done() ? DONE : NOT_DONE);
                statusRow.append(String.format("%-7s", shortName(day.date().getDayOfWeek()) + " " + mark));
                dateRow.append(String.format("%-7s", day.date().format(SHORT_DATE)));
            }
            out.println("Last " + HISTORY_DAYS + " days for \"" + habit.getName() + "\":");
            out.println(statusRow.toString().stripTrailing());
            out.println(dateRow.toString().stripTrailing());
            out.println("(" + DONE + " done, " + NOT_DONE + " missed, " + NOT_TRACKED + " before the habit was created)");
        });
    }

    // ---------------------------------------------------------------- demo data

    private void loadDemoData() {
        out.println("This replaces ALL current habits with 3 sample habits created "
                + DemoDataGenerator.DAYS_OF_HISTORY + " days ago.");
        if (!readYesNo("Are you sure? (y/n): ")) {
            out.println("Demo data not loaded.");
            return;
        }
        manager.replaceAll(DemoDataGenerator.generate(today()));
        save();
        out.println("Demo data loaded: " + String.join(", ",
                manager.listHabits().stream().map(Habit::getName).toList()) + ".");
    }

    // ---------------------------------------------------------------- persistence

    private void loadOnStartup() {
        try {
            FileStorage.LoadResult result = storage.load();
            result.warnings().forEach(w -> out.println("Warning: " + w));
            manager.replaceAll(result.habits());
            out.println("Loaded " + manager.size() + " habit(s) from " + storage.getFile() + ".");
        } catch (IOException e) {
            out.println("Warning: could not read " + storage.getFile() + " (" + e.getMessage()
                    + "). Starting with no habits.");
        }
        out.println();
    }

    private void save() {
        try {
            storage.save(manager.listHabits());
        } catch (IOException e) {
            out.println("Error: could not save to " + storage.getFile() + " (" + e.getMessage() + ").");
        }
    }

    // ---------------------------------------------------------------- input helpers

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private String readLine(String prompt) {
        out.print(prompt);
        out.flush();
        try {
            String line = in.readLine();
            if (line == null) {
                throw new InputClosedException();
            }
            return line.trim();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private int readInt(String prompt, int min, int max) {
        while (true) {
            String line = readLine(prompt);
            try {
                int value = Integer.parseInt(line);
                if (value >= min && value <= max) {
                    return value;
                }
            } catch (NumberFormatException e) {
                // fall through to the reprompt below
            }
            out.println("Please enter a number from " + min + " to " + max + ".");
        }
    }

    private boolean readYesNo(String prompt) {
        while (true) {
            String answer = readLine(prompt).toLowerCase(Locale.ROOT);
            if (answer.equals("y") || answer.equals("yes")) {
                return true;
            }
            if (answer.equals("n") || answer.equals("no")) {
                return false;
            }
            out.println("Please answer y or n.");
        }
    }

    /** Returns empty if the user cancels with a blank line. */
    private Optional<LocalDate> readDate(String prompt) {
        while (true) {
            String line = readLine(prompt);
            if (line.isEmpty()) {
                return Optional.empty();
            }
            try {
                return Optional.of(LocalDate.parse(line));
            } catch (DateTimeParseException e) {
                out.println("\"" + line + "\" is not a valid date. Use the format yyyy-MM-dd, e.g. 2024-03-15.");
            }
        }
    }

    /** Asks until the name passes validation. Returns empty if the user cancels with a blank line. */
    private Optional<String> readValidName(String prompt, Habit habitBeingRenamed) {
        while (true) {
            String line = readLine(prompt);
            if (line.isEmpty()) {
                return Optional.empty();
            }
            try {
                return Optional.of(manager.validateName(line, habitBeingRenamed));
            } catch (IllegalArgumentException e) {
                out.println(e.getMessage());
            }
        }
    }

    /** Shows a numbered list and lets the user pick by number. 0 cancels. */
    private Optional<Habit> chooseHabit(String question) {
        if (printIfEmpty()) {
            return Optional.empty();
        }
        List<Habit> habits = activeHabits();
        out.println(question);
        for (int i = 0; i < habits.size(); i++) {
            out.println("  " + (i + 1) + ". " + habits.get(i).getName());
        }
        out.println("  0. Cancel");
        int choice = readInt("Habit number: ", 0, habits.size());
        return choice == 0 ? Optional.empty() : Optional.of(habits.get(choice - 1));
    }

    private boolean printIfEmpty() {
        if (activeHabits().isEmpty()) {
            out.println("You have no habits yet. Add one with option 2, or load demo data with option 11.");
            return true;
        }
        return false;
    }

    /** Archived habits (set from the web dashboard) are hidden from the console menus. */
    private List<Habit> activeHabits() {
        return manager.listHabits().stream().filter(h -> !h.isArchived()).toList();
    }

    /** "d" for day streaks, "w" for times-per-week habits. */
    private static String unitLetter(Habit habit) {
        return StreakCalculator.unit(habit).substring(0, 1);
    }

    private int nameColumnWidth() {
        int longest = activeHabits().stream().mapToInt(h -> h.getName().length()).max().orElse(0);
        return Math.max("Habit".length(), longest);
    }

    private static String shortName(DayOfWeek day) {
        return day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
    }

    private static String displayName(DayOfWeek day) {
        return day.getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }
}
