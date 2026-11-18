# Habit Manager

A cute, pastel habit tracker written in plain Core Java 17. It has no external libraries, no database and no frameworks.
Java runs a small web server on your own computer, and the dashboard opens in your browser.
Track daily habits, backfill missed days, and see your streaks, completion rates and weekday patterns as graphs.
The original text menu is still available with `--console`.

## Requirements

- JDK 17 or newer (`javac -version` should print 17+)
- Any modern browser

## Build

Run this from the project folder. It works in PowerShell, cmd and bash.

```bash
javac -d out src/habitmanager/*.java src/habitmanager/model/*.java src/habitmanager/service/*.java src/habitmanager/storage/*.java src/habitmanager/ui/*.java src/habitmanager/web/*.java
```

## Run

```bash
java -cp out habitmanager.Main
```

The dashboard opens at <http://localhost:8080>. Keep the terminal open while you use it, and press `Ctrl+C` to stop.
Run it from the project folder, because it serves the page from `web/` and stores data in `habits.txt` there.

Options:

| Option | What it does |
|---|---|
| `--port 9000` | Use a different port if 8080 is busy |
| `--data myfile.txt` | Use a different data file |
| `--no-browser` | Don't open the browser automatically |
| `--console` | Use the original text menu instead of the dashboard |

For the console version on Windows, run `chcp 65001` first so the ✔ / ✘ symbols show correctly.

### "java is not recognized"?

Java isn't on your PATH. Either install a JDK (`winget install EclipseAdoptium.Temurin.17.JDK`, then reopen the terminal),
or add it for the current window:

- PowerShell: `$env:Path = "C:\path\to\jdk\bin;$env:Path"`
- cmd: `set PATH=C:\path\to\jdk\bin;%PATH%`

## The dashboard

- **Today**: progress ring, hottest streak, habits needing some love, one-click check-ins, the last 7 days as dots, rename and delete.
- **Calendar**: a month view for each habit. Tap a past day to mark it done, tap again to undo.
- **Highlights**: graphs, all drawn by hand in SVG:
  - streak bars (current vs. best ever)
  - completion donuts
  - daily check-ins over the last 30 days (pink dots are perfect days)
  - weekday rhythm bar chart with best and toughest day
  - GitHub-style activity heatmap

Click **Load demo data** at the bottom of the page to try it with three sample habits that have 30 days of history.

## Project structure

```
Habit Manager/
├── src/habitmanager/
│   ├── Main.java                     # entry point: web dashboard (default) or --console
│   ├── model/Habit.java              # name, createdDate, completedDates (TreeSet)
│   ├── service/
│   │   ├── HabitManager.java         # add / rename / delete / find / check in / undo / backfill / toggle
│   │   ├── HabitService.java         # applies a change and saves immediately (used by the web API)
│   │   ├── StreakCalculator.java     # current and longest streak
│   │   ├── InsightEngine.java        # completion %, weekday rates, summary, history
│   │   └── DemoDataGenerator.java    # sample habits
│   ├── storage/FileStorage.java      # load / save habits.txt
│   ├── ui/ConsoleUI.java             # text menu (--console)
│   └── web/
│       ├── WebServer.java            # JDK built-in HTTP server, localhost only
│       ├── ApiHandler.java           # JSON API under /api/
│       ├── StateSerializer.java      # habits -> JSON
│       ├── Json.java                 # tiny JSON writer
│       └── StaticFileHandler.java    # serves web/
├── web/                              # dashboard: index.html, styles.css, app.js (no JS libraries)
├── tests/StreakAndInsightTests.java  # plain-Java tests (no JUnit)
└── README.md
```

All the business rules stay in the Java service classes. The browser only draws the numbers the server sends.

## Test

```bash
javac -cp out -d out tests/StreakAndInsightTests.java
```

```bash
java -cp out StreakAndInsightTests
```

Each check prints `PASS` or `FAIL` and a final count. The process exits with status 1 if anything fails.

## API

All responses are JSON. Every successful call returns the full, recalculated state.
POST bodies are form-encoded and must include the header `X-Habit-Client: 1`, which blocks other websites from changing your data.

| Method | Path | Fields |
|---|---|---|
| GET | `/api/state` | |
| POST | `/api/habits/add` | `name` |
| POST | `/api/habits/rename` | `name`, `newName` |
| POST | `/api/habits/delete` | `name` |
| POST | `/api/checkin` | `name` |
| POST | `/api/undo` | `name` |
| POST | `/api/toggle` | `name`, `date` (yyyy-MM-dd) |
| POST | `/api/demo` | |

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

The file is created on first save, and every change is saved immediately.
Malformed lines are skipped with a warning that shows the line number. The rest of the file still loads.
The web dashboard and the console menu use the same file, so you can switch between them.
