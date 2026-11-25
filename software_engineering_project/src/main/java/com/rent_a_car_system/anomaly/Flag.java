package com.rent_a_car_system.anomaly;

/**
 * One flagged time log entry. Holds what the detector found and
 * where the flag stands in the admin review process.
 */
public class Flag {

    /** The four kinds of anomaly from the slides. */
    public enum AnomalyType {
        MISSING_CLOCK_OUT,
        UNUSUAL_SHIFT_LENGTH,
        ODD_CLOCK_IN_TIME,
        EXCESS_WEEKLY_HOURS
    }

    /** Review status. New flags start as NEEDS_REVIEW. */
    public enum FlagStatus {
        NEEDS_REVIEW,
        CONFIRMED,
        DISMISSED,
        CORRECTED
    }

    private final int logId;
    private final int employeeId;
    private final AnomalyType type;
    private final String reason;
    private FlagStatus status;

    /** Creates a new flag with status NEEDS_REVIEW. */
    public Flag(int logId, int employeeId, AnomalyType type, String reason) {
        this(logId, employeeId, type, reason, FlagStatus.NEEDS_REVIEW);
    }

    /** Used when loading an existing flag back from the database. */
    public Flag(int logId, int employeeId, AnomalyType type, String reason, FlagStatus status) {
        this.logId = logId;
        this.employeeId = employeeId;
        this.type = type;
        this.reason = reason;
        this.status = status;
    }

    public int getLogId() {
        return logId;
    }

    public int getEmployeeId() {
        return employeeId;
    }

    public AnomalyType getType() {
        return type;
    }

    /** Plain-English explanation, e.g. "Shift was 14.5 hours. Average is 8.1." */
    public String getReason() {
        return reason;
    }

    public FlagStatus getStatus() {
        return status;
    }

    /** Only the admin review flow (resolveFlag) should call this. */
    public void setStatus(FlagStatus status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return "Flag{log=" + logId + ", employee=" + employeeId
                + ", type=" + type + ", status=" + status
                + ", reason=\"" + reason + "\"}";
    }
}
