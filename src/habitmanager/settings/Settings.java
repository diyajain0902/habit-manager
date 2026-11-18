package habitmanager.settings;

import java.time.LocalTime;
import java.util.Objects;

/**
 * App-wide preferences.
 *
 * @param remindersEnabled   master switch for every notification
 * @param nudgeEnabled       evening "N habits still to do" nudge
 * @param nudgeTime          when the evening nudge fires
 * @param streakAlertEnabled "your streak ends tonight" alert
 * @param streakAlertTime    when the streak alert fires
 * @param dayEndsAt          when a new day starts, for night owls: with 03:00, a check in at
 *                           1 AM still counts for the previous day. Between 00:00 and 05:00.
 */
public record Settings(boolean remindersEnabled,
                       boolean nudgeEnabled,
                       LocalTime nudgeTime,
                       boolean streakAlertEnabled,
                       LocalTime streakAlertTime,
                       LocalTime dayEndsAt) {

    public static final LocalTime LATEST_DAY_END = LocalTime.of(5, 0);

    public Settings {
        Objects.requireNonNull(nudgeTime, "nudgeTime");
        Objects.requireNonNull(streakAlertTime, "streakAlertTime");
        Objects.requireNonNull(dayEndsAt, "dayEndsAt");
        if (dayEndsAt.isAfter(LATEST_DAY_END)) {
            throw new IllegalArgumentException("The day can end at 5:00 AM at the latest.");
        }
    }

    public static Settings defaults() {
        return new Settings(true, true, LocalTime.of(20, 0), true, LocalTime.of(21, 30), LocalTime.MIDNIGHT);
    }
}
