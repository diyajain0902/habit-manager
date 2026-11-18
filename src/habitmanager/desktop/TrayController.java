package habitmanager.desktop;

import habitmanager.model.Habit;
import habitmanager.reminders.NotificationCenter;
import habitmanager.reminders.ReminderEngine;
import habitmanager.service.HabitService;
import habitmanager.service.InsightEngine;

import java.awt.AWTException;
import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.awt.Menu;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Timer;
import java.util.TimerTask;

/**
 * The bubble icon in the Windows system tray. It shows reminders as Windows notifications
 * (even when the browser is closed) and offers a menu: open the dashboard, mark a habit
 * done, snooze the last reminder, or quit.
 */
public final class TrayController implements NotificationCenter.Notifier {

    private static final int SNOOZE_MINUTES = 10;
    private static final long REFRESH_EVERY_MS = 5 * 60 * 1000;

    private final HabitService service;
    private final Runnable openDashboard;
    private final TrayIcon icon;
    private final Menu markDoneMenu = new Menu("Mark done");
    private final MenuItem snoozeItem = new MenuItem("Snooze last reminder (" + SNOOZE_MINUTES + " min)");
    private volatile String lastReminderHabit;

    private TrayController(HabitService service, Runnable openDashboard) {
        this.service = service;
        this.openDashboard = openDashboard;

        MenuItem open = new MenuItem("Open dashboard");
        open.addActionListener(e -> openDashboard.run());
        snoozeItem.setEnabled(false);
        snoozeItem.addActionListener(e -> snoozeLast());
        MenuItem quit = new MenuItem("Quit Habit Manager");
        quit.addActionListener(e -> System.exit(0)); // the shutdown hook saves

        PopupMenu menu = new PopupMenu();
        menu.add(open);
        menu.addSeparator();
        menu.add(markDoneMenu);
        menu.add(snoozeItem);
        menu.addSeparator();
        menu.add(quit);

        icon = new TrayIcon(BubbleIcon.create(64), "Habit Manager", menu);
        icon.setImageAutoSize(true);
        icon.addActionListener(e -> openDashboard.run()); // double-click the icon or click a notification
        refreshMenu();
    }

    /** Adds the tray icon if this computer has a system tray. Returns empty otherwise. */
    public static Optional<TrayController> install(HabitService service, Runnable openDashboard) {
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            return Optional.empty();
        }
        try {
            TrayController controller = new TrayController(service, openDashboard);
            SystemTray.getSystemTray().add(controller.icon);
            service.addChangeListener(controller::refreshMenuLater);
            controller.startPeriodicRefresh();
            return Optional.of(controller);
        } catch (AWTException | RuntimeException e) {
            System.out.println("Tray icon not available (" + e.getMessage() + "). Reminders will show in the browser only.");
            return Optional.empty();
        }
    }

    @Override
    public void show(NotificationCenter.Notice notice) {
        if (notice.kind() == ReminderEngine.Kind.HABIT && notice.habitName() != null) {
            lastReminderHabit = notice.habitName();
        }
        EventQueue.invokeLater(() -> {
            icon.displayMessage(notice.title(), notice.message(), TrayIcon.MessageType.NONE);
            refreshMenu();
        });
    }

    private void refreshMenuLater() {
        EventQueue.invokeLater(this::refreshMenu);
    }

    /** Rebuilds the "Mark done" submenu and the tooltip from the current habits. */
    private void refreshMenu() {
        LocalDate today = service.today();
        List<Habit> due = service.read(habits -> habits.stream().filter(h -> InsightEngine.isDueOn(h, today)).toList());
        List<String> pending = service.read(habits -> due.stream().filter(h -> !h.isDoneOn(today)).map(Habit::getName).toList());

        markDoneMenu.removeAll();
        if (pending.isEmpty()) {
            MenuItem allDone = new MenuItem("All done for today!");
            allDone.setEnabled(false);
            markDoneMenu.add(allDone);
        }
        for (String name : pending) {
            MenuItem item = new MenuItem(name);
            item.addActionListener(e -> markDone(name));
            markDoneMenu.add(item);
        }
        snoozeItem.setEnabled(lastReminderHabit != null && pending.contains(lastReminderHabit));
        int done = due.size() - pending.size();
        icon.setToolTip("Habit Manager - " + done + " of " + due.size() + " done today");
    }

    private void markDone(String name) {
        try {
            service.checkIn(name);
            icon.displayMessage("Nice!", name + " is done for today.", TrayIcon.MessageType.NONE);
        } catch (RuntimeException e) {
            icon.displayMessage("Could not check in", e.getMessage(), TrayIcon.MessageType.WARNING);
        }
    }

    private void snoozeLast() {
        String name = lastReminderHabit;
        if (name == null) {
            return;
        }
        try {
            service.snooze(name, SNOOZE_MINUTES);
            snoozeItem.setEnabled(false);
        } catch (RuntimeException e) {
            icon.displayMessage("Could not snooze", e.getMessage(), TrayIcon.MessageType.WARNING);
        }
    }

    /** Keeps the menu right after midnight, when nothing else changes. */
    private void startPeriodicRefresh() {
        Timer timer = new Timer("tray-refresh", true);
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                refreshMenuLater();
            }
        }, REFRESH_EVERY_MS, REFRESH_EVERY_MS);
    }
}
