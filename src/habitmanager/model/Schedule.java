package habitmanager.model;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * When a habit is meant to be done:
 * <ul>
 *   <li>DAILY - every day</li>
 *   <li>WEEKDAYS - only on chosen days of the week, e.g. Mon, Wed, Fri</li>
 *   <li>WEEKLY - a number of times per week, on any days</li>
 * </ul>
 * Immutable. Encoded in the data file as "DAILY", "WEEKDAYS:MON,WED,FRI" or "WEEKLY:3".
 */
public final class Schedule {

    public enum Type { DAILY, WEEKDAYS, WEEKLY }

    private static final Schedule DAILY = new Schedule(Type.DAILY, EnumSet.allOf(DayOfWeek.class), 7);

    private final Type type;
    private final Set<DayOfWeek> days;
    private final int timesPerWeek;

    private Schedule(Type type, Set<DayOfWeek> days, int timesPerWeek) {
        this.type = type;
        this.days = Collections.unmodifiableSet(EnumSet.copyOf(days));
        this.timesPerWeek = timesPerWeek;
    }

    public static Schedule daily() {
        return DAILY;
    }

    /** Specific days of the week. Choosing all seven days is the same as daily. */
    public static Schedule onDays(Collection<DayOfWeek> chosenDays) {
        if (chosenDays == null || chosenDays.isEmpty()) {
            throw new IllegalArgumentException("Pick at least one day of the week.");
        }
        EnumSet<DayOfWeek> set = EnumSet.copyOf(chosenDays);
        return set.size() == 7 ? DAILY : new Schedule(Type.WEEKDAYS, set, set.size());
    }

    /** A number of times per week (1 to 7). Seven times a week is the same as daily. */
    public static Schedule timesPerWeek(int times) {
        if (times < 1 || times > 7) {
            throw new IllegalArgumentException("Times per week must be between 1 and 7.");
        }
        return times == 7 ? DAILY : new Schedule(Type.WEEKLY, EnumSet.allOf(DayOfWeek.class), times);
    }

    public Type getType() {
        return type;
    }

    public boolean isDaily() {
        return type == Type.DAILY;
    }

    public boolean isWeekly() {
        return type == Type.WEEKLY;
    }

    /** The days of the week this habit can be done on (all seven for DAILY and WEEKLY). */
    public Set<DayOfWeek> getDays() {
        return days;
    }

    /** Weekly goal: 7 for DAILY, the number of chosen days for WEEKDAYS, the target for WEEKLY. */
    public int getTimesPerWeek() {
        return timesPerWeek;
    }

    /** True if the habit is expected on this date's weekday. WEEKLY habits can be done any day. */
    public boolean isScheduledOn(LocalDate date) {
        return days.contains(date.getDayOfWeek());
    }

    /** Human-friendly label, e.g. "Every day", "Mon, Wed, Fri" or "3x a week". */
    public String describe() {
        return switch (type) {
            case DAILY -> "Every day";
            case WEEKLY -> timesPerWeek + "x a week";
            case WEEKDAYS -> days.stream().map(Schedule::shortName).collect(Collectors.joining(", "));
        };
    }

    public String encode() {
        return switch (type) {
            case DAILY -> "DAILY";
            case WEEKLY -> "WEEKLY:" + timesPerWeek;
            case WEEKDAYS -> "WEEKDAYS:" + days.stream().map(Schedule::code).collect(Collectors.joining(","));
        };
    }

    public static Schedule parse(String text) {
        String value = text.trim().toUpperCase(Locale.ROOT);
        if (value.equals("DAILY")) {
            return DAILY;
        }
        if (value.startsWith("WEEKLY:")) {
            return timesPerWeek(Integer.parseInt(value.substring("WEEKLY:".length())));
        }
        if (value.startsWith("WEEKDAYS:")) {
            return onDays(parseDays(value.substring("WEEKDAYS:".length())));
        }
        throw new IllegalArgumentException("unknown schedule \"" + text + "\"");
    }

    /** Parses a comma separated list such as "MON,WED,FRI". Blank entries are ignored. */
    public static Set<DayOfWeek> parseDays(String list) {
        EnumSet<DayOfWeek> chosen = EnumSet.noneOf(DayOfWeek.class);
        for (String code : list.split(",")) {
            if (!code.isBlank()) {
                chosen.add(dayFromCode(code.trim()));
            }
        }
        return chosen;
    }

    /** Accepts "MON", "MONDAY", "mon" etc. */
    public static DayOfWeek dayFromCode(String code) {
        String upper = code.trim().toUpperCase(Locale.ROOT);
        for (DayOfWeek day : DayOfWeek.values()) {
            if (day.name().equals(upper) || code(day).equals(upper)) {
                return day;
            }
        }
        throw new IllegalArgumentException("unknown day \"" + code + "\"");
    }

    public static String code(DayOfWeek day) {
        return day.name().substring(0, 3);
    }

    private static String shortName(DayOfWeek day) {
        String code = code(day);
        return code.charAt(0) + code.substring(1).toLowerCase(Locale.ROOT);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Schedule s && s.type == type && s.days.equals(days) && s.timesPerWeek == timesPerWeek;
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, days, timesPerWeek);
    }

    @Override
    public String toString() {
        return encode();
    }
}
