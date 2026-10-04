package io.github.mikestitt.corbelsflightlog.checks;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The checks that need a Control Hub but no person: run with the hub joined to
 * this computer by USB, and exits 1 on any failure.
 *
 * <pre>
 * ./gradlew :checks:usbGate
 * </pre>
 *
 * <p>It forwards ports 5810 and 8002 over {@code adb}, starts
 * {@link LogCheck9NtApi} through Panels' socket ({@link PanelsControl}), checks
 * it with {@link NtApiCheck}, stops it and runs it again, then pulls the second
 * run's log and checks it holds every value the client received. The robot
 * project needs {@code opmodes/} copied into its TeamCode, and Panels.
 *
 * <p>{@code adb} is the system property {@code adb}, which Gradle sets from the
 * Android SDK it builds with.
 */
public final class UsbGate {

    private static final String OPMODE = "Log 9: NetworkTables API";

    private UsbGate() {
    }

    public static void main(String[] args) throws Exception {
        String adb = System.getProperty("adb", "adb");
        NtApiCheck check = new NtApiCheck();
        try {
            gate(adb, check);
        } catch (Exception e) {
            check.expect(false, "the gate stopped: " + e);
        } finally {
            System.exit(check.report());
        }
    }

    private static void gate(String adb, NtApiCheck check) throws Exception {
        String state = adb(adb, "get-state").trim();
        check.expect("device".equals(state), "one Control Hub on adb (" + state + ")");
        if (!"device".equals(state)) return;
        for (int port : new int[]{5810, PanelsControl.PORT}) {
            adb(adb, "forward", "tcp:" + port, "tcp:" + port);
        }
        // The Robot Controller app takes about 40 s to bring Panels up after
        // the hub boots or the app is installed.
        boolean up = PanelsControl.waitForHealth("127.0.0.1", 180_000);
        check.expect(up, "Panels answers on port " + PanelsControl.PORT);
        if (!up) return;

        try (PanelsControl panels = PanelsControl.connect("127.0.0.1")) {
            boolean listed = panels.lists(OPMODE);
            check.expect(listed, "the robot has \"" + OPMODE + "\": checks/opmodes copied into TeamCode");
            if (!listed) return;
            panels.stop();
        }

        if (!start(check, 1) || !check.connect("127.0.0.1", 5810)) return;
        check.hierarchy();
        boolean checked = check.checkRun(1);
        stop(check, 1);
        if (!checked) return;

        if (!start(check, 2)) return;
        checked = check.checkRun(2);
        String onRobot = check.logFile();
        stop(check, 2);
        if (!checked) return;

        check.expect(onRobot != null, "run 2 said where its log is: " + onRobot);
        if (onRobot == null) return;
        boolean closed = check.waitForClosed(onRobot, 30_000);
        check.expect(closed, "run 2 closed its log, said on " + LogCheck9NtApi.CLOSED);
        if (!closed) return;
        // Read through adb, the file lags what the app wrote by some seconds,
        // closed or not: a pull at once was cut short in one run of two.
        File local = Files.createTempFile("logcheck9-", ".wpilog").toFile();
        local.deleteOnExit();
        for (int i = 0; i < 15; i++) {
            adb(adb, "pull", onRobot, local.getPath());
            if (NtApiCheck.logHolds(local.getPath(), check.received)) break;
            Thread.sleep(2000);
        }
        check.compareLog(local.getPath(), check.received);
    }

    private static boolean start(NtApiCheck check, int run) throws IOException {
        try (PanelsControl panels = PanelsControl.connect("127.0.0.1")) {
            boolean ran = panels.run(OPMODE);
            check.expect(ran, "run " + run + " started through Panels (" + panels.state() + ")");
            return ran;
        }
    }

    private static void stop(NtApiCheck check, int run) throws IOException {
        try (PanelsControl panels = PanelsControl.connect("127.0.0.1")) {
            check.expect(panels.stop(), "run " + run + " stopped through Panels (" + panels.state() + ")");
        }
    }

    /** Runs adb and returns what it printed; fails if it exits nonzero. */
    private static String adb(String adb, String... args) throws IOException, InterruptedException {
        String[] command = new String[args.length + 1];
        command[0] = adb;
        System.arraycopy(args, 0, command, 1, args.length);
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) {
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        }
        String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) throw new IOException(String.join(" ", command) + " failed: " + text);
        return text;
    }
}
