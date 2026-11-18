package habitmanager.reminders;

import habitmanager.service.HabitService;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Checks for due reminders every 30 seconds on a background thread and publishes them. */
public class ReminderScheduler {

    private static final long CHECK_EVERY_SECONDS = 30;

    private final HabitService service;
    private final NotificationCenter center;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "habit-reminders");
        thread.setDaemon(true);
        return thread;
    });

    public ReminderScheduler(HabitService service, NotificationCenter center) {
        this.service = service;
        this.center = center;
    }

    public void start() {
        executor.scheduleAtFixedRate(this::checkNow, 5, CHECK_EVERY_SECONDS, TimeUnit.SECONDS);
    }

    public void stop() {
        executor.shutdownNow();
    }

    /** Publishes every reminder that is due. Never throws, so one bad tick cannot stop the schedule. */
    public void checkNow() {
        try {
            for (ReminderEngine.Reminder reminder : service.dueReminders()) {
                center.publish(reminder, service.now());
            }
        } catch (RuntimeException e) {
            System.out.println("Warning: reminder check failed: " + e.getMessage());
        }
    }
}
