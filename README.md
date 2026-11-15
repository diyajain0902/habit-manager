# Habit Manager

A console habit tracker written in plain Core Java 17. It has no external libraries, no database and no frameworks.
Track daily habits, backfill missed days, and see streaks, completion rates and weekday patterns.

## Requirements

- JDK 17 or newer (`javac -version` should print 17+)

## Project structure

```
Habit Manager/
├── src/habitmanager/
│   ├── Main.java                     # entry point, wires everything together
│   ├── model/Habit.java              # name, createdDate, completedDates (TreeSet)
│   ├── service/
│   │   ├── HabitManager.java         # add / rename / delete / find / check in / undo / backfill
│   │   ├── StreakCalculator.java     # current and longest streak
│   │   ├── InsightEngine.java        # completion %, weekday rates, summary, history
│   │   └── DemoDataGenerator.java    # sample habits for option 11
│   ├── storage/FileStorage.java      # load / save habits.txt
│   └── ui/ConsoleUI.java             # menu loop and input handling
├── tests/StreakAndInsightTests.java  # plain-Java tests (no JUnit)
└── README.md
```

## Build

Run these from the project root. The same commands work in PowerShell, cmd and bash.

```bash
javac -d out src/habitmanager/*.java src/habitmanager/model/*.java src/habitmanager/service/*.java src/habitmanager/storage/*.java src/habitmanager/ui/*.java
```

## Run

```bash
java -cp out habitmanager.Main
```

On Windows, switch the console to UTF-8 first so the ✔ / ✘ symbols show correctly:

```bash
chcp 65001
```

Data is stored in `habits.txt` in the directory you run the program from.

## Test

```bash
javac -cp out -d out tests/StreakAndInsightTests.java
```

```bash
java -cp out StreakAndInsightTests
```

Each check prints `PASS` or `FAIL` and a final count. The process exits with status 1 if anything fails.

## Menu

```
===== HABIT MANAGER =====
1. View habits (today's status)
2. Add habit
3. Mark habit done today
4. Undo today's check in
5. Backfill a past date
6. Rename habit
7. Delete habit
8. View streaks
9. View insights
10. View history (last 7 days)
11. Load demo data
0. Save and exit
```

Tip: choose **11** to load three sample habits with 30 days of history, then try the views.

## How things are calculated

- **Current streak** counts consecutive completed days backwards from today. If today is not done yet, it
  counts from yesterday, so an unbroken streak doesn't drop to 0 before you've had a chance to check in.
- **Longest streak** is the longest run of consecutive dates ever completed.
- **Completion %** is completed days / (days since creation, inclusive) × 100, rounded to 1 decimal.
- **Weekday rates** are completions on a weekday divided by how many times that weekday has occurred
  since the habit was created. Weekdays that haven't occurred yet are ignored.
- **Needs attention** means the habit has existed for at least 3 days and was not done today or in the 2 days before.

## Data file format

One habit per line, with ISO dates (`yyyy-MM-dd`):

```
name|createdDate|date1,date2,date3
Morning run|2024-03-01|2024-03-01,2024-03-02,2024-03-04
```

The file is created on first save. The program saves after every change and again on exit.
Malformed lines are skipped with a warning that shows the line number. The rest of the file still loads.
