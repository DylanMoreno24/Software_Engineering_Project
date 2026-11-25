package anomaly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** One test per check, plus the edge cases from the plan. */
public class AnomalyDetectorTest {

    private AnomalyDetector detector;
    private TestDataGenerator data;
    private List<Flag> flags;

    @BeforeEach
    void setUp() {
        detector = new AnomalyDetector();
        data = new TestDataGenerator();
        flags = detector.detect(data.shifts, TestDataGenerator.NOW);
    }

    private boolean hasFlag(int logId, Flag.AnomalyType type) {
        for (Flag f : flags) {
            if (f.getLogId() == logId && f.getType() == type) return true;
        }
        return false;
    }

    // ---- The four checks ----

    @Test
    void missingClockOutIsFlagged() {
        assertTrue(hasFlag(data.missingClockOutLogId, Flag.AnomalyType.MISSING_CLOCK_OUT));
    }

    @Test
    void sixteenHourShiftIsFlagged() {
        assertTrue(hasFlag(data.longShiftLogId, Flag.AnomalyType.UNUSUAL_SHIFT_LENGTH));
    }

    @Test
    void threeMinuteShiftIsFlagged() {
        assertTrue(hasFlag(data.shortShiftLogId, Flag.AnomalyType.UNUSUAL_SHIFT_LENGTH));
    }

    @Test
    void threeAmClockInIsFlaggedForNineAmWorker() {
        assertTrue(hasFlag(data.oddStartLogId, Flag.AnomalyType.ODD_CLOCK_IN_TIME));
    }

    @Test
    void overSixtyHourWeekIsFlagged() {
        assertTrue(hasFlag(data.heavyWeekLogId, Flag.AnomalyType.EXCESS_WEEKLY_HOURS));
    }

    // ---- Things that should NOT be flagged ----

    @Test
    void nightShiftWorkerIsNotFlagged() {
        for (Flag f : flags) {
            assertFalse(shiftOwner(f.getLogId()) == TestDataGenerator.NIGHT_EMPLOYEE,
                    "Night shift worker should not be flagged: " + f);
        }
    }

    @Test
    void normalDayWorkerIsNotFlagged() {
        for (Flag f : flags) {
            assertFalse(shiftOwner(f.getLogId()) == TestDataGenerator.DAY_EMPLOYEE,
                    "Normal day worker should not be flagged: " + f);
        }
    }

    // ---- Reason messages ----

    @Test
    void reasonsArePlainEnglishAndNotEmpty() {
        for (Flag f : flags) {
            assertNotNull(f.getReason());
            assertFalse(f.getReason().trim().isEmpty());
        }
    }

    @Test
    void longShiftReasonMentionsAverage() {
        for (Flag f : flags) {
            if (f.getLogId() == data.longShiftLogId) {
                assertTrue(f.getReason().contains("Average is"));
            }
        }
    }

    // ---- Edge cases ----

    @Test
    void shiftCrossingMidnightCountsCorrectHours() {
        AnomalyDetector.Shift s = new AnomalyDetector.Shift(1, 1,
                LocalDateTime.of(2026, 10, 1, 22, 0),
                LocalDateTime.of(2026, 10, 2, 6, 0));
        assertEquals(8.0, s.getHours(), 0.001);
    }

    @Test
    void brandNewEmployeeWithNormalShiftsIsNotFlagged() {
        for (Flag f : flags) {
            assertFalse(shiftOwner(f.getLogId()) == TestDataGenerator.NEW_EMPLOYEE,
                    "New employee with no history should not be flagged: " + f);
        }
    }

    @Test
    void brandNewEmployeeStillCaughtByHardLimits() {
        List<AnomalyDetector.Shift> single = new ArrayList<>();
        single.add(new AnomalyDetector.Shift(1, 99,
                LocalDateTime.of(2026, 10, 1, 9, 0),
                LocalDateTime.of(2026, 10, 2, 1, 0))); // 16 hours, no history
        List<Flag> result = detector.detect(single, TestDataGenerator.NOW);
        assertEquals(1, result.size());
        assertEquals(Flag.AnomalyType.UNUSUAL_SHIFT_LENGTH, result.get(0).getType());
    }

    @Test
    void recentOpenShiftIsNotFlaggedAsMissingClockOut() {
        List<AnomalyDetector.Shift> single = new ArrayList<>();
        single.add(new AnomalyDetector.Shift(1, 99,
                TestDataGenerator.NOW.minusHours(3), null)); // clocked in 3 hours ago
        assertTrue(detector.detect(single, TestDataGenerator.NOW).isEmpty());
    }

    // ---- FlagService: resolving flags ----

    @Test
    void dismissedFlagIsNotReflaggedOnNextRun() {
        FlagService service = new FlagService(detector);
        service.runDetection(data.shifts, TestDataGenerator.NOW);
        int pendingBefore = service.getFlaggedLogs().size();
        assertTrue(pendingBefore > 0);

        assertTrue(service.resolveFlag(data.longShiftLogId, "dismiss", null));

        int addedOnSecondRun = service.runDetection(data.shifts, TestDataGenerator.NOW);
        assertEquals(0, addedOnSecondRun);
        for (Flag f : service.getFlaggedLogs()) {
            assertFalse(f.getLogId() == data.longShiftLogId);
        }
    }

    @Test
    void correctRequiresValidTimes() {
        FlagService service = new FlagService(detector);
        service.runDetection(data.shifts, TestDataGenerator.NOW);

        LocalDateTime in = LocalDateTime.of(2026, 10, 5, 9, 0);
        // clock-out before clock-in is rejected
        assertFalse(service.resolveFlag(data.missingClockOutLogId, "correct",
                new LocalDateTime[] { in, in.minusHours(1) }));
        // missing times are rejected
        assertFalse(service.resolveFlag(data.missingClockOutLogId, "correct", null));
        // valid times are accepted
        assertTrue(service.resolveFlag(data.missingClockOutLogId, "correct",
                new LocalDateTime[] { in, in.plusHours(8) }));
    }

    @Test
    void unknownActionIsRejected() {
        FlagService service = new FlagService(detector);
        service.runDetection(data.shifts, TestDataGenerator.NOW);
        assertFalse(service.resolveFlag(data.longShiftLogId, "delete", null));
    }

    // ---- helper ----

    private int shiftOwner(int logId) {
        for (AnomalyDetector.Shift s : data.shifts) {
            if (s.getLogId() == logId) return s.getEmployeeId();
        }
        return -1;
    }
}