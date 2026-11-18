# Habit Manager

A cute, pastel habit tracker written in plain Core Java 17. It has no external libraries, no database and no frameworks.
Java runs a small web server on your own computer, and the dashboard opens in your browser.
Track daily habits on your own schedule, count towards daily targets, add notes, get reminders,
and see your streaks, completion rates and weekday patterns as graphs.
The original text menu is still available with `--console`.

## Requirements

- JDK 17 or newer (`javac -version` should print 17+)
- Any modern browser

## Build

Run this from the project folder. It works in PowerShell, cmd and bash.

```bash
javac -d out src/habitmanager/*.java src/habitmanager/model/*.java src/habitmanager/service/*.java src/habitmanager/storage/*.java src/habitmanager/settings/*.java src/habitmanager/reminders/*.java src/habitmanager/desktop/*.java src/habitmanager/ui/*.java src/habitmanager/web/*.java
```

## Run

```bash
java -cp out habitmanager.Main
```

The dashboard opens at <http://localhost:8080>. Run it from the project folder, because it serves the page from `web/`
and stores data in `habits.txt` (and `settings.properties`) there.

A bubble icon appears in the Windows system tray. Reminders pop up from it as Windows notifications, even when the browser
is closed. Right-click it to open the dashboard, mark a habit done, snooze the last reminder or quit.
Keep the app running for reminders to work.

To run it in the background without a console window, use `javaw` instead of `java`:

```bash
javaw -cp out habitmanager.Main
```

Or switch on **Start with Windows** in the Settings tab.

Options:

| Option | What it does |
|---|---|
| `--port 9000` | Use a different port if 8080 is busy |
| `--data myfile.txt` | Use a different data file (settings are kept next to it) |
| `--no-browser` | Don't open the browser automatically |
| `--no-tray` | No tray icon or Windows notifications; reminders only show in the open dashboard |
| `--console` | Use the original text menu instead of the dashboard |

For the console version on Windows, run `chcp 65001` first so the ✔ / ✘ symbols show correctly.

### "java is not recognized"?

Java isn't on your PATH. Either install a JDK (`winget install EclipseAdoptium.Temurin.17.JDK`, then reopen the terminal),
or add it for the current window:

- PowerShell: `$env:Path = "C:\path\to\jdk\bin;$env:Path"`
- cmd: `set PATH=C:\path\to\jdk\bin;%PATH%`

## The dashboard

- **Today**
  - Progress ring, hottest streak, and the habits that need some love.
  - One-click check-ins. Habits with a target show a ring like 3/8, with + and −.
  - The last 7 days as dots, a schedule chip and a reminder chip on each habit.
  - Drag ⠿ to reorder (or focus it and use the arrow keys).
  - 📝 adds a note for today, ✏️ edits the habit.
  - Archived habits sit in their own section, where you can restore or delete them.
- **Calendar**
  - A month view for each habit. Part-done days are half filled, rest days are striped, and days with notes have a dot.
  - Tap a day to mark it done or adjust its count, and to read or write its note.
- **Highlights**: graphs, all drawn by hand in SVG:
  - streak bars (current vs. best ever)
  - completion donuts
  - daily check-ins over the last 30 days (blue dots are perfect days)
  - weekday rhythm bar chart with best and toughest day
  - GitHub-style activity heatmap
- **Settings**
  - Turn all reminders on or off, plus the evening nudge and the streak alert (with times).
  - Send a test notification and allow browser notifications.
  - Choose when your day ends.
  - Start with Windows.

The add / edit dialog sets:

- name
- emoji and colour
- **how often**: every day, some days (e.g. Mon/Wed/Fri) or X times a week
- a **daily target** (e.g. 8 glasses; 1 means a simple check-in)
- a **reminder time**

Click **Load demo data** at the bottom of the page to try it with four sample habits that show off these features.

## Reminders

| Reminder | When |
|---|---|
| Habit reminder | At the habit's own time, if it's due today and not done yet |
| Evening nudge | At the nudge time (default 8:00 PM), if anything due today is still not done |
| Streak alert | At the alert time (default 9:30 PM), if a streak of 2+ days would break tonight |

- Each reminder is sent at most once a day.
- If the computer was asleep, a reminder up to 2 hours late is still sent. Older ones are skipped.
- Snoozed reminders come back after 10 minutes.
- Reminders also appear as banners in the open dashboard, with **Mark done** and **Snooze** buttons.

## Project structure

```
Habit Manager/
├── src/habitmanager/
│   ├── Main.java                     # entry point: web dashboard (default) or --console
│   ├── model/
│   │   ├── Habit.java                # name, created date, check ins, schedule, target, reminder, emoji, colour
│   │   ├── Schedule.java             # every day / some weekdays / X times a week
│   │   └── CheckIn.java              # one day's count and note
│   ├── service/
│   │   ├── HabitManager.java         # add / edit / delete / reorder / archive / check in / progress / notes
│   │   ├── HabitService.java         # applies a change and saves immediately; "day ends at"; reminders
│   │   ├── StreakCalculator.java     # schedule-aware current and longest streaks
│   │   ├── InsightEngine.java        # completion %, weekday rates, due today, summary, history
│   │   └── DemoDataGenerator.java    # sample habits
│   ├── storage/FileStorage.java      # load / save habits.txt (format 2, reads and upgrades format 1)
│   ├── settings/                     # Settings + settings.properties storage
│   ├── reminders/                    # ReminderEngine (pure logic), scheduler, NotificationCenter
│   ├── desktop/                      # system tray icon, Start with Windows
│   ├── ui/ConsoleUI.java             # text menu (--console)
│   └── web/                          # JDK built-in HTTP server (localhost only), JSON API, static files
├── web/                              # dashboard: index.html, styles.css, app.js, charts.js, bubble.svg (no JS libraries)
├── tests/
│   ├── StreakAndInsightTests.java    # original streak / insight / storage tests
│   └── ScheduleAndReminderTests.java # schedules, targets, notes, file format, settings, reminders
└── README.md
```

All the business rules stay in the Java classes. The browser only draws the numbers the server sends.

## Test

```bash
javac -cp out -d out tests/StreakAndInsightTests.java tests/ScheduleAndReminderTests.java
```

```bash
java -cp out StreakAndInsightTests
```

```bash
java -cp out ScheduleAndReminderTests
```

Each check prints `PASS` or `FAIL` and a final count. The process exits with status 1 if anything fails.

## API

All responses are JSON. Changes return the full, recalculated state.
POST bodies are form-encoded and must include the header `X-Habit-Client: 1`, which blocks other websites from changing your data.

| Method | Path | Fields |
|---|---|---|
| GET | `/api/state` | |
| GET | `/api/settings` | |
| GET | `/api/notifications` | `after` (id) |
| POST | `/api/habits/add` | `name` + details |
| POST | `/api/habits/update` | `name`, `newName` + details |
| POST | `/api/habits/rename` | `name`, `newName` |
| POST | `/api/habits/delete` | `name` |
| POST | `/api/habits/archive` | `name`, `archived` (true/false) |
| POST | `/api/habits/move` | `name`, `index` |
| POST | `/api/checkin` | `name` |
| POST | `/api/undo` | `name` |
| POST | `/api/toggle` | `name`, `date` |
| POST | `/api/progress` | `name`, `delta`, optional `date` |
| POST | `/api/note` | `name`, `note`, optional `date` |
| POST | `/api/demo` | |
| POST | `/api/settings` | `remindersEnabled`, `nudgeEnabled`, `nudgeTime`, `streakAlertEnabled`, `streakAlertTime`, `dayEndsAt`, `startAtLogin` |
| POST | `/api/notifications/snooze` | `name`, `minutes` |
| POST | `/api/notifications/test` | |

Details:
- `schedule` (`DAILY`, `WEEKDAYS` or `WEEKLY`)
- `days` (e.g. `MON,WED,FRI`)
- `timesPerWeek`
- `target`
- `reminder` (`HH:mm` or empty)
- `color` (0–5 or empty)
- `emoji`

Dates are `yyyy-MM-dd`.

## How things are calculated

- **Current streak** counts backwards from today. If today isn't done yet, it starts from the previous day, so an
  unbroken streak doesn't drop to 0 before you've had a chance to check in.
  - *Some days* habits only count scheduled days. Rest days neither add to nor break the streak.
  - *X times a week* habits count weeks (Mon–Sun) in which the goal was met. The current week is still open until the goal is met.
- **Longest streak** is the longest such run ever.
- **Completion %** is done / expected × 100, rounded to 1 decimal. Today is included.
  - Every day: days since creation.
  - Some days: scheduled days only.
  - X times a week: the weekly goal, capped by how many days of that week the habit existed.
- **Daily targets**: a day is done when its count reaches the target. Raising a target keeps past completed days completed.
- **Weekday rates** are completions on a weekday divided by how many times that weekday has occurred since the
  habit was created. Weekdays that haven't occurred yet, and rest days, are left out.
- **Due today** decides what counts in "x of y done today":
  - every daily habit
  - *some days* habits on their days
  - *X times a week* habits until this week's goal is met
- **Needs attention**:
  - Every day: missed today and the 2 days before.
  - Some days: missed the last 3 scheduled days.
  - X times a week: nothing in the last 7 days.
  - New habits aren't flagged.
- **Day ends at** (Settings, midnight to 5 AM) shifts what "today" means. With 3:00 AM, a check-in at 1 AM counts
  for the previous day.

## Data files

`habits.txt` uses format 2: a header, then one habit per line as `key=value` fields. Text is URL-encoded, so names and
notes can contain any characters.

```
# habitmanager format 2
name=Drink+water|created=2024-03-01|schedule=DAILY|target=8|reminder=10:00|color=4|emoji=%F0%9F%92%A7|archived=false|checkins=2024-03-01:8,2024-03-02:3:with+lemon
```

Each check-in is `date:count` or `date:count:note`. Unknown keys are ignored.

Files in the original format (`name|createdDate|date1,date2`) still load. The first time the app saves, it keeps a copy
as `habits.txt.v1.bak` and upgrades the file.

The file is created on first save, and every change is saved immediately.
Malformed lines are skipped with a warning that shows the line number. The rest of the file still loads.
The web dashboard and the console menu use the same file, so you can switch between them.

`settings.properties` holds the Settings tab values. A missing or broken value falls back to the default.
