package anomaly;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Runs the detector on the planted test data and prints the numbers
 * promised in the slides: how many planted errors were caught and how many
 * false alarms occurred.
 *
 * Run it as a normal Java program (it has a main method).
 */
public class EvaluationRunner {

    public static void main(String[] args) {
        TestDataGenerator data = new TestDataGenerator();
        AnomalyDetector detector = new AnomalyDetector();
        List<Flag> flags = detector.detect(data.shifts, TestDataGenerator.NOW);

        // Every log ID that received at least one flag.
        Set<Integer> flaggedLogIds = new LinkedHashSet<>();
        for (Flag f : flags) {
            flaggedLogIds.add(f.getLogId());
        }

        int planted = data.plantedLogIds.size();
        int caught = 0;
        for (int id : data.plantedLogIds) {
            if (flaggedLogIds.contains(id)) caught++;
        }

        Set<Integer> falseAlarms = new LinkedHashSet<>(flaggedLogIds);
        falseAlarms.removeAll(data.plantedLogIds);

        int totalShifts = data.shifts.size();
        int cleanShifts = totalShifts - planted;

        System.out.println("=== Anomaly Detection Evaluation ===");
        System.out.println("Total shifts checked:    " + totalShifts);
        System.out.println("Planted bad records:     " + planted);
        System.out.println("Planted errors caught:   " + caught + " of " + planted);
        System.out.println("False alarms:            " + falseAlarms.size() + " of " + cleanShifts + " clean shifts");
        System.out.println();

        System.out.println("--- Flags raised ---");
        for (Flag f : flags) {
            String tag = data.plantedLogIds.contains(f.getLogId()) ? "[planted]    " : "[FALSE ALARM]";
            System.out.println(tag + " " + f);
        }

        if (caught < planted) {
            System.out.println();
            System.out.println("--- Missed ---");
            for (int id : data.plantedLogIds) {
                if (!flaggedLogIds.contains(id)) {
                    System.out.println("Log " + id + " was planted but not flagged.");
                }
            }
        }
    }
}