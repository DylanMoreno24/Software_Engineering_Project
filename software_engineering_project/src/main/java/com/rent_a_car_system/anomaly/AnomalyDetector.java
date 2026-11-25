package com.rent_a_car_system.anomaly;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Checks time logs and flags entries that look wrong.
 *
 * For each employee it builds a baseline (normal shift length and start time)
 * from their other shifts, then runs four checks:
 *   1. Missing clock-out
 *   2. Unusually long or short shift
 *   3. Odd clock-in time
 *   4. Too many hours in a week
 *
 * The detector never changes pay or logs. It only returns flags.
 */
public class AnomalyDetector {

    // ---------------------------------------------------------------
    // Thresholds (tune these without touching the checks)
    // ---------------------------------------------------------------

    /** Minimum completed shifts before an employee's baseline is trusted. */
    public static final int MIN_SHIFTS_FOR_BASELINE = 5;

    /** How many standard deviations away counts as "far outside normal". */
    public static final double STD_DEV_CUTOFF = 3.0;

    /** Floors so a very consistent employee isn't flagged for tiny changes. */
    public static final double MIN_LENGTH_STD_HOURS = 0.75;
    public static final double MIN_START_STD_HOURS = 1.0;

    /** A clock-in with no clock-out after this many hours is flagged. */
    public static final double MAX_OPEN_SHIFT_HOURS = 12.0;

    /** Hard limits that apply even to brand-new employees with no baseline. */
    public static final double MAX_SHIFT_HOURS = 14.0;
    public static final double MIN_SHIFT_MINUTES = 10.0;

    /** Weekly hours (Monday to Sunday) above this are flagged. */
    public static final double MAX_WEEKLY_HOURS = 60.0;

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("MMM d 'at' h:mm a", Locale.US);
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("MMM d", Locale.US);

    // ---------------------------------------------------------------
    // Input shape
    // ---------------------------------------------------------------

    /**
     * Minimal view of one time log. Convert Person 1's TimeLog objects into
     * these before calling detect(). A null clockOut means the employee is
     * still clocked in (or forgot to clock out).
     */
    public static class Shift {
        private final int logId;
        private final int employeeId;
        private final LocalDateTime clockIn;
        private final LocalDateTime clockOut;

        public Shift(int logId, int employeeId, LocalDateTime clockIn, LocalDateTime clockOut) {
            this.logId = logId;
            this.employeeId = employeeId;
            this.clockIn = clockIn;
            this.clockOut = clockOut;
        }

        public int getLogId() { return logId; }
        public int getEmployeeId() { return employeeId; }
        public LocalDateTime getClockIn() { return clockIn; }
        public LocalDateTime getClockOut() { return clockOut; }

        public boolean isComplete() { return clockOut != null; }

        /** Hours worked. Works for shifts that cross midnight. Null if still open. */
        public Double getHours() {
            if (clockOut == null) return null;
            return Duration.between(clockIn, clockOut).toMinutes() / 60.0;
        }

        /** Clock-in time as hours since midnight, e.g. 9:30 AM = 9.5. */
        public double getStartHour() {
            return clockIn.getHour() + clockIn.getMinute() / 60.0;
        }
    }

    // ---------------------------------------------------------------
    // Baseline (private helper)
    // ---------------------------------------------------------------

    private static class Baseline {
        double avgLengthHours;
        double stdLengthHours;
        double avgStartHour;
        double stdStartHours;
    }

    /**
     * Builds a baseline from an employee's completed shifts, leaving out the
     * shift being checked so an outlier can't hide inside its own average.
     * Returns null if there isn't enough history yet.
     */
    private Baseline buildBaseline(List<Shift> employeeShifts, int excludeLogId) {
        List<Shift> history = new ArrayList<>();
        for (Shift s : employeeShifts) {
            if (s.isComplete() && s.getLogId() != excludeLogId) {
                history.add(s);
            }
        }
        if (history.size() < MIN_SHIFTS_FOR_BASELINE) {
            return null;
        }

        Baseline b = new Baseline();

        // Shift length: plain mean and standard deviation.
        double sum = 0;
        for (Shift s : history) sum += s.getHours();
        b.avgLengthHours = sum / history.size();
        double sq = 0;
        for (Shift s : history) {
            double d = s.getHours() - b.avgLengthHours;
            sq += d * d;
        }
        b.stdLengthHours = Math.sqrt(sq / history.size());

        // Start time: circular mean, so 23:30 and 00:30 are one hour apart.
        double sinSum = 0, cosSum = 0;
        for (Shift s : history) {
            double angle = s.getStartHour() / 24.0 * 2 * Math.PI;
            sinSum += Math.sin(angle);
            cosSum += Math.cos(angle);
        }
        double meanAngle = Math.atan2(sinSum, cosSum);
        b.avgStartHour = ((meanAngle / (2 * Math.PI) * 24.0) + 24.0) % 24.0;
        double startSq = 0;
        for (Shift s : history) {
            double d = circularDiff(s.getStartHour(), b.avgStartHour);
            startSq += d * d;
        }
        b.stdStartHours = Math.sqrt(startSq / history.size());

        return b;
    }

    /** Shortest distance between two times of day, in hours (0 to 12). */
    private static double circularDiff(double a, double b) {
        double d = Math.abs(a - b) % 24.0;
        return Math.min(d, 24.0 - d);
    }

    // ---------------------------------------------------------------
    // Main entry point
    // ---------------------------------------------------------------

    /**
     * Runs every check on every shift and returns the flags found.
     *
     * @param shifts all time logs to examine
     * @param now    the current time (used for the missing clock-out check)
     */
    public List<Flag> detect(List<Shift> shifts, LocalDateTime now) {
        List<Flag> flags = new ArrayList<>();

        Map<Integer, List<Shift>> byEmployee = new LinkedHashMap<>();
        for (Shift s : shifts) {
            byEmployee.computeIfAbsent(s.getEmployeeId(), k -> new ArrayList<>()).add(s);
        }

        for (List<Shift> employeeShifts : byEmployee.values()) {
            employeeShifts.sort(Comparator.comparing(Shift::getClockIn));

            for (Shift s : employeeShifts) {
                Flag f;
                f = checkMissingClockOut(s, now);
                if (f != null) flags.add(f);

                f = checkShiftLength(s, buildBaseline(employeeShifts, s.getLogId()));
                if (f != null) flags.add(f);

                f = checkStartTime(s, buildBaseline(employeeShifts, s.getLogId()));
                if (f != null) flags.add(f);
            }

            flags.addAll(checkWeeklyHours(employeeShifts));
        }

        return flags;
    }

    // ---------------------------------------------------------------
    // Check 1: missing clock-out
    // ---------------------------------------------------------------

    private Flag checkMissingClockOut(Shift s, LocalDateTime now) {
        if (s.isComplete()) return null;
        double hoursOpen = Duration.between(s.getClockIn(), now).toMinutes() / 60.0;
        if (hoursOpen <= MAX_OPEN_SHIFT_HOURS) return null;

        String reason = String.format(Locale.US,
                "No clock-out recorded. Clocked in %s and still open after %.0f hours.",
                s.getClockIn().format(WHEN), hoursOpen);
        return new Flag(s.getLogId(), s.getEmployeeId(), Flag.AnomalyType.MISSING_CLOCK_OUT, reason);
    }

    // ---------------------------------------------------------------
    // Check 2: unusually long or short shift
    // ---------------------------------------------------------------

    private Flag checkShiftLength(Shift s, Baseline b) {
        if (!s.isComplete()) return null;
        double hours = s.getHours();
        String reason = null;

        if (hours < 0) {
            reason = "Clock-out is before clock-in. This entry needs to be corrected.";
        } else if (hours * 60.0 < MIN_SHIFT_MINUTES) {
            reason = "Shift was " + describe(hours) + "."
                    + averageNote(b);
        } else if (hours > MAX_SHIFT_HOURS) {
            reason = String.format(Locale.US, "Shift was %.1f hours.", hours)
                    + averageNote(b)
                    + String.format(Locale.US, " Longer than the %.0f-hour limit.", MAX_SHIFT_HOURS);
        } else if (b != null) {
            double std = Math.max(b.stdLengthHours, MIN_LENGTH_STD_HOURS);
            double z = Math.abs(hours - b.avgLengthHours) / std;
            if (z > STD_DEV_CUTOFF) {
                reason = "Shift was " + describe(hours) + "." + averageNote(b);
            }
        }

        if (reason == null) return null;
        return new Flag(s.getLogId(), s.getEmployeeId(), Flag.AnomalyType.UNUSUAL_SHIFT_LENGTH, reason);
    }

    private String averageNote(Baseline b) {
        if (b == null) return "";
        return String.format(Locale.US, " Average is %.1f hours.", b.avgLengthHours);
    }

    private String describe(double hours) {
        if (hours < 1.0) {
            return Math.round(hours * 60.0) + " minutes";
        }
        return String.format(Locale.US, "%.1f hours", hours);
    }

    // ---------------------------------------------------------------
    // Check 3: odd clock-in time
    // ---------------------------------------------------------------

    private Flag checkStartTime(Shift s, Baseline b) {
        if (b == null) return null;
        double std = Math.max(b.stdStartHours, MIN_START_STD_HOURS);
        double diff = circularDiff(s.getStartHour(), b.avgStartHour);
        if (diff / std <= STD_DEV_CUTOFF) return null;

        String reason = "Clocked in at " + s.getClockIn().format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
                + ". Usual start time is around " + formatHour(b.avgStartHour) + ".";
        return new Flag(s.getLogId(), s.getEmployeeId(), Flag.AnomalyType.ODD_CLOCK_IN_TIME, reason);
    }

    private String formatHour(double hour) {
        int h = (int) hour;
        int m = (int) Math.round((hour - h) * 60.0);
        if (m == 60) { h = (h + 1) % 24; m = 0; }
        return LocalDateTime.of(2000, 1, 1, h, m).format(DateTimeFormatter.ofPattern("h:mm a", Locale.US));
    }

    // ---------------------------------------------------------------
    // Check 4: too many hours in a week (Monday to Sunday)
    // ---------------------------------------------------------------

    /** Flags the shift that pushes a week over the limit. One flag per week. */
    private List<Flag> checkWeeklyHours(List<Shift> employeeShifts) {
        List<Flag> flags = new ArrayList<>();
        Map<LocalDate, Double> totals = new LinkedHashMap<>();
        Set<LocalDate> alreadyFlagged = new HashSet<>();

        for (Shift s : employeeShifts) { // already sorted by clock-in
            if (!s.isComplete()) continue;
            LocalDate week = s.getClockIn().toLocalDate()
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            double total = totals.merge(week, s.getHours(), Double::sum);

            if (total > MAX_WEEKLY_HOURS && alreadyFlagged.add(week)) {
                String reason = String.format(Locale.US,
                        "Week of %s reached %.1f hours. Limit is %.0f. Possible overtime or a data error.",
                        week.format(DAY), total, MAX_WEEKLY_HOURS);
                flags.add(new Flag(s.getLogId(), s.getEmployeeId(),
                        Flag.AnomalyType.EXCESS_WEEKLY_HOURS, reason));
            }
        }
        return flags;
    }
}
