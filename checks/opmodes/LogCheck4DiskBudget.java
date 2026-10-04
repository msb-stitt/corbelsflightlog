package io.github.mikestitt.corbelsflightlog.checks;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

import io.github.mikestitt.corbelsflightlog.FlightLog;
import io.github.mikestitt.corbelsflightlog.ftc.FtcFlightLog;

import java.io.File;

/**
 * Check 4: old logs are deleted once the folder passes its budget.
 *
 * <p>Sets the budget to 1 MB instead of the usual 2 GiB, then writes hard
 * enough to pass it. Run it several times: the file count should stop growing
 * and the oldest should disappear, while anything that is not a .wpilog is left
 * alone.
 *
 * <p>The budget is restored when this stops, so it cannot leave the robot with
 * a 1 MB cap.
 */
@TeleOp(name = "Log 4: disk budget", group = "LogCheck")
public class LogCheck4DiskBudget extends OpMode {

    private static final long TEST_BUDGET_BYTES = 1_000_000;

    private FlightLog log;
    private long previousBudget;
    private long loops;

    @Override
    public void init() {
        File folder = FtcFlightLog.logDirectory();
        telemetry.addData("Folder", folder);
        telemetry.addData("Budget now", FlightLog.maxDirectoryBytes / (1024 * 1024) + " MB");
        telemetry.addData("Files", count(folder));
        telemetry.addData("Total", bytes(folder) / 1024 + " KB");
        telemetry.addLine("Start sets the budget to 1 MB and writes fast.");
        telemetry.update();
    }

    @Override
    public void start() {
        previousBudget = FlightLog.maxDirectoryBytes;
        FlightLog.maxDirectoryBytes = TEST_BUDGET_BYTES;
        log = FtcFlightLog.open(this);
        loops = 0;
    }

    @Override
    public void loop() {
        loops++;
        // Values that change every loop, so nothing is skipped and the file grows.
        for (int i = 0; i < 40; i++) {
            log.recordOutput("filler/" + i, loops * (i + 1) + Math.random());
        }
        log.endLoop();

        File folder = FtcFlightLog.logDirectory();
        telemetry.addData("Log", log.status());
        telemetry.addData("Files", count(folder));
        telemetry.addData("Total", bytes(folder) / 1024 + " KB of "
                + TEST_BUDGET_BYTES / 1024 + " KB");
        telemetry.addLine("Run me a few times; the oldest logs should vanish.");
        telemetry.update();
    }

    @Override
    public void stop() {
        log.close();
        FlightLog.maxDirectoryBytes = previousBudget;   // never leave the cap small
    }

    private static int count(File folder) {
        File[] files = folder.listFiles((d, n) -> n.endsWith(".wpilog"));
        return files == null ? 0 : files.length;
    }

    private static long bytes(File folder) {
        File[] files = folder.listFiles((d, n) -> n.endsWith(".wpilog"));
        if (files == null) return 0;
        long total = 0;
        for (File f : files) total += f.length();
        return total;
    }
}
