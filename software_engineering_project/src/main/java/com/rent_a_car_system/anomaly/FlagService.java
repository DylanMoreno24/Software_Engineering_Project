package com.rent_a_car_system.anomaly;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The layer Person 2's admin screens call.
 *
 *   getFlaggedLogs()  ->  get_flagged_logs()
 *   resolveFlag(...)  ->  resolve_flag(log_id, action, corrected_times)
 *
 * NOTE: flags are currently kept in memory so the feature works and can be
 * tested on its own. The spots to swap in real database calls (with Person 1's
 * DAO) are marked TODO.
 */
public class FlagService {

    private final AnomalyDetector detector;

    // Key is "logId:type" so the same problem on the same log is never added twice.
    private final Map<String, Flag> flags = new LinkedHashMap<>();

    public FlagService(AnomalyDetector detector) {
        this.detector = detector;
    }

    /**
     * Runs the detector and stores any flags that are new.
     * Flags already on file (including ones the admin already resolved) are
     * not re-added, so a dismissed flag does not come back.
     *
     * @return how many new flags were added
     */
    public int runDetection(List<AnomalyDetector.Shift> shifts, LocalDateTime now) {
        int added = 0;
        for (Flag f : detector.detect(shifts, now)) {
            String key = f.getLogId() + ":" + f.getType();
            if (!flags.containsKey(key)) {
                flags.put(key, f);
                added++;
                // TODO: save the new flag to the database (Flag table or TimeLog columns)
            }
        }
        return added;
    }

    /** Flags still waiting for the administrator (status NEEDS_REVIEW). */
    public List<Flag> getFlaggedLogs() {
        // TODO: load from the database instead of memory
        List<Flag> pending = new ArrayList<>();
        for (Flag f : flags.values()) {
            if (f.getStatus() == Flag.FlagStatus.NEEDS_REVIEW) {
                pending.add(f);
            }
        }
        return pending;
    }

    /**
     * Applies the administrator's decision to every pending flag on a log.
     *
     * @param action         "confirm", "dismiss", or "correct"
     * @param correctedTimes for "correct" only: {newClockIn, newClockOut}; ignored otherwise
     * @return true if at least one flag was updated, false if the log had no
     *         pending flags or the input was invalid
     */
    public boolean resolveFlag(int logId, String action, LocalDateTime[] correctedTimes) {
        if (action == null) return false;

        Flag.FlagStatus newStatus;
        switch (action.toLowerCase()) {
            case "confirm":
                newStatus = Flag.FlagStatus.CONFIRMED;
                break;
            case "dismiss":
                newStatus = Flag.FlagStatus.DISMISSED;
                break;
            case "correct":
                if (correctedTimes == null || correctedTimes.length != 2
                        || correctedTimes[0] == null || correctedTimes[1] == null
                        || !correctedTimes[1].isAfter(correctedTimes[0])) {
                    return false; // need a valid clock-in and a later clock-out
                }
                newStatus = Flag.FlagStatus.CORRECTED;
                // TODO: call Person 1's function to update the TimeLog with the
                // corrected times. Pay is only ever changed here, by the admin.
                break;
            default:
                return false;
        }

        boolean updated = false;
        for (Flag f : flags.values()) {
            if (f.getLogId() == logId && f.getStatus() == Flag.FlagStatus.NEEDS_REVIEW) {
                f.setStatus(newStatus);
                updated = true;
                // TODO: save the new status to the database
            }
        }
        return updated;
    }
}