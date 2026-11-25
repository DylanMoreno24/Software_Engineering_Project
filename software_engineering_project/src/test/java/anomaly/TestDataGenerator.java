package anomaly;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Builds sample employees and shifts, including deliberately planted bad
 * records. Uses a fixed random seed so every run produces the same data.
 *
 * Employees:
 *   1  day worker, normal            (should NOT be flagged)
 *   2  night worker, 10 PM to 6 AM   (should NOT be flagged)
 *   3  planted: missing clock-out
 *   4  planted: 16-hour shift
 *   5  planted: 3-minute shift
 *   6  planted: 3 AM clock-in
 *   7  planted: 66.5-hour week
 *   8  brand-new, only 3 normal shifts (should NOT be flagged)
 */
public class TestDataGenerator {

    /** "Current time" used by the tests, after all the sample shifts. */
    public static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 15, 12, 0);

    public static final int DAY_EMPLOYEE = 1;
    public static final int NIGHT_EMPLOYEE = 2;
    public static final int MISSING_CLOCK_OUT_EMPLOYEE = 3;
    public static final int LONG_SHIFT_EMPLOYEE = 4;
    public static final int SHORT_SHIFT_EMPLOYEE = 5;
    public static final int ODD_START_EMPLOYEE = 6;
    public static final int HEAVY_WEEK_EMPLOYEE = 7;
    public static final int NEW_EMPLOYEE = 8;

    public final List<AnomalyDetector.Shift> shifts = new ArrayList<>();
    public final Set<Integer> plantedLogIds = new LinkedHashSet<>();

    public int missingClockOutLogId;
    public int longShiftLogId;
    public int shortShiftLogId;
    public int oddStartLogId;
    public int heavyWeekLogId; // the shift that pushes the week over the limit

    private int nextLogId = 1;
    private final Random random = new Random(42);

    // Monday, four weeks of normal history, then the planted week starts Oct 5.
    private static final LocalDate FIRST_MONDAY = LocalDate.of(2026, 9, 7);
    private static final LocalDate PLANTED_MONDAY = LocalDate.of(2026, 10, 5);

    public TestDataGenerator() {
        // Normal employees: 20 weekday shifts each.
        addRegularShifts(DAY_EMPLOYEE, 9);
        addRegularShifts(NIGHT_EMPLOYEE, 22);

        // Employees with history plus one planted problem.
        addRegularShifts(MISSING_CLOCK_OUT_EMPLOYEE, 9);
        missingClockOutLogId = addShift(MISSING_CLOCK_OUT_EMPLOYEE,
                PLANTED_MONDAY.atTime(9, 0), null);
        plantedLogIds.add(missingClockOutLogId);

        addRegularShifts(LONG_SHIFT_EMPLOYEE, 9);
        longShiftLogId = addShift(LONG_SHIFT_EMPLOYEE,
                PLANTED_MONDAY.plusDays(1).atTime(9, 0),
                PLANTED_MONDAY.plusDays(2).atTime(1, 0)); // 16 hours, crosses midnight
        plantedLogIds.add(longShiftLogId);

        addRegularShifts(SHORT_SHIFT_EMPLOYEE, 9);
        shortShiftLogId = addShift(SHORT_SHIFT_EMPLOYEE,
                PLANTED_MONDAY.plusDays(1).atTime(9, 0),
                PLANTED_MONDAY.plusDays(1).atTime(9, 3)); // 3 minutes
        plantedLogIds.add(shortShiftLogId);

        addRegularShifts(ODD_START_EMPLOYEE, 9);
        oddStartLogId = addShift(ODD_START_EMPLOYEE,
                PLANTED_MONDAY.plusDays(1).atTime(3, 0),
                PLANTED_MONDAY.plusDays(1).atTime(11, 0)); // 8 hours, but starts at 3 AM
        plantedLogIds.add(oddStartLogId);

        // Heavy week: seven 9.5-hour shifts, Monday to Sunday = 66.5 hours.
        addRegularShifts(HEAVY_WEEK_EMPLOYEE, 9);
        for (int d = 0; d < 7; d++) {
            LocalDateTime in = PLANTED_MONDAY.plusDays(d).atTime(9, 0)
                    .plusMinutes(random.nextInt(41) - 20);
            int id = addShift(HEAVY_WEEK_EMPLOYEE, in, in.plusMinutes(570));
            if (d == 6) {
                heavyWeekLogId = id; // the 7th shift is the one that crosses 60 hours
                plantedLogIds.add(id);
            }
        }

        // Brand-new employee: only three normal shifts, no real baseline yet.
        for (int d = 0; d < 3; d++) {
            LocalDateTime in = PLANTED_MONDAY.plusDays(d).atTime(9, 0);
            addShift(NEW_EMPLOYEE, in, in.plusHours(8));
        }
    }

    /** 20 weekday shifts (4 weeks x Mon-Fri), about 8 hours each, with small natural variation. */
    private void addRegularShifts(int employeeId, int startHour) {
        for (int week = 0; week < 4; week++) {
            for (int day = 0; day < 5; day++) {
                LocalDate date = FIRST_MONDAY.plusWeeks(week).plusDays(day);
                LocalDateTime in = date.atTime(startHour, 0).plusMinutes(random.nextInt(41) - 20);
                LocalDateTime out = in.plusMinutes(480 + random.nextInt(61) - 30);
                addShift(employeeId, in, out);
            }
        }
    }

    private int addShift(int employeeId, LocalDateTime in, LocalDateTime out) {
        int id = nextLogId++;
        shifts.add(new AnomalyDetector.Shift(id, employeeId, in, out));
        return id;
    }
}