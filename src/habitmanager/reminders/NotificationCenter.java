package habitmanager.reminders;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Hands each reminder to every display (the Windows tray, and the dashboard which polls
 * {@link #after}) and keeps the most recent ones in memory.
 */
public class NotificationCenter {

    /** Anything that can show a notice to the user. */
    public interface Notifier {
        void show(Notice notice);
    }

    /** A reminder that was sent, with an id the dashboard uses to fetch only new ones. */
    public record Notice(long id, LocalDateTime time, ReminderEngine.Kind kind,
                         String habitName, String title, String message) {
    }

    private static final int KEEP = 50;

    private final Deque<Notice> recent = new ArrayDeque<>();
    private final List<Notifier> notifiers = new CopyOnWriteArrayList<>();
    private long nextId = 1;

    public void addNotifier(Notifier notifier) {
        notifiers.add(notifier);
    }

    public Notice publish(ReminderEngine.Reminder reminder, LocalDateTime now) {
        Notice notice;
        synchronized (this) {
            notice = new Notice(nextId++, now, reminder.kind(), reminder.habitName(), reminder.title(), reminder.message());
            recent.addLast(notice);
            while (recent.size() > KEEP) {
                recent.removeFirst();
            }
        }
        for (Notifier notifier : notifiers) {
            try {
                notifier.show(notice);
            } catch (RuntimeException e) {
                System.out.println("Warning: could not show a notification: " + e.getMessage());
            }
        }
        return notice;
    }

    /** Notices newer than the given id, oldest first. */
    public synchronized List<Notice> after(long id) {
        return recent.stream().filter(n -> n.id() > id).toList();
    }

    /** The id of the newest notice, or 0 if none were sent yet. */
    public synchronized long latestId() {
        return nextId - 1;
    }

    public synchronized List<Notice> all() {
        return new ArrayList<>(recent);
    }
}
