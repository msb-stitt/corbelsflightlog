package io.github.mikestitt.corbelsflightlog.ftc;

import android.content.Context;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.OpModeManagerImpl;
import com.qualcomm.robotcore.eventloop.opmode.OpModeManagerNotifier;
import com.qualcomm.robotcore.util.RobotLog;
import com.qualcomm.robotcore.util.WebHandlerManager;

import io.github.mikestitt.corbelsflightlog.FlightLog;
import io.github.mikestitt.corbelsflightlog.nt.Nt4Server;

import org.firstinspires.ftc.ftccommon.external.WebHandlerRegistrar;
import org.firstinspires.ftc.robotcore.internal.system.AppUtil;

import java.io.File;
import java.io.IOException;

/**
 * FlightLog for an FTC robot: files land in the Robot Controller's own storage,
 * are named after the OpMode, and are closed when the OpMode stops -- whether
 * or not your code remembers to.
 *
 * <pre>
 * private FlightLog log;
 *
 * public void start() { log = FtcFlightLog.open(this); }
 * public void loop()  { log.recordOutput("shooter/rpm", rpm); log.endLoop(); }
 * public void stop()  { log.close(); }   // optional: see below
 * </pre>
 *
 * <p><b>Closing.</b> This registers with the Robot Controller and closes the
 * open log when an OpMode stops, and again before the next one initialises. So
 * a log survives an OpMode that throws, or that never calls {@code close()}.
 * Calling {@code close()} yourself still works and is still worth doing: it
 * flushes immediately rather than at the end of the OpMode.
 *
 * <p><b>Where the files go.</b> {@link #logDirectory()} is a
 * {@code corbelsflightlog} folder at the top of the Robot Controller's storage
 * -- the SDK's {@code AppUtil.ROOT_FOLDER}, so {@code /sdcard/corbelsflightlog},
 * which is internal storage on a Control Hub, not a removable card. If that
 * folder can't be used, this falls back to a {@code corbelsflightlog} folder
 * in the app's private files directory, which always exists.
 *
 * <p><b>Getting them off the robot.</b> With the laptop on the robot's Wi-Fi,
 * open <a href="http://192.168.43.1:8080/corbelsflightlog">
 * 192.168.43.1:8080/corbelsflightlog</a> and click a file. {@code adb pull}
 * still works too.
 */
public final class FtcFlightLog implements OpModeManagerNotifier.Notifications {

    /** The single instance registered with the Robot Controller. */
    private static final FtcFlightLog INSTANCE = new FtcFlightLog();

    /** The log this class opened and may close. */
    private static FlightLog current;

    private FtcFlightLog() {
    }

    /**
     * The folder logs go in, under the Robot Controller's storage -- so
     * {@code /sdcard/corbelsflightlog} on a Control Hub. Named after the
     * library rather than something generic like "logs" so that its size is a
     * straight measure of what this library is using, and so it is easy to find
     * at the top level.
     */
    static final String FOLDER_NAME = "corbelsflightlog";

    /** Set by {@link #useDirectory}; overrides the usual choice when set. */
    private static volatile File override;

    /**
     * Log somewhere else -- a USB stick, or a temp folder in a test. Pass null
     * to go back to the usual choice.
     */
    public static void useDirectory(File folder) {
        override = folder;
    }

    /**
     * Where log files go: {@code /sdcard/corbelsflightlog} when that is usable,
     * otherwise a folder of that name in the app's own files directory. Never
     * returns null, never throws.
     */
    public static File logDirectory() {
        File chosen = override;
        if (usable(chosen)) return chosen;

        File first = null;
        File appFiles = null;
        try {
            first = AppUtil.ROOT_FOLDER;
        } catch (Throwable ignored) {
            // no app: a unit test, or an SDK that moved it
        }
        try {
            Context context = AppUtil.getDefContext();
            if (context != null) appFiles = context.getFilesDir();
        } catch (Throwable ignored) {
            // no app context either
        }
        return chooseDirectory(first, appFiles);
    }

    /**
     * Picks the log folder given the Robot Controller's storage folder and the
     * app's own files folder, either of which may be null or unusable. Split
     * out from {@link #logDirectory} so it can be tested off a robot.
     */
    static File chooseDirectory(File firstFolder, File appFilesFolder) {
        File preferred = firstFolder == null ? null : new File(firstFolder, FOLDER_NAME);
        if (usable(preferred)) return preferred;
        File fallback = appFilesFolder == null ? null : new File(appFilesFolder, FOLDER_NAME);
        if (usable(fallback)) return fallback;
        return preferred != null ? preferred : new File(FOLDER_NAME);
    }

    /** True if the folder exists or can be created, and can be written to. */
    static boolean usable(File folder) {
        if (folder == null) return false;
        try {
            if (!folder.isDirectory() && !folder.mkdirs()) return false;
            return folder.canWrite();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Opens a log named after the OpMode's class. Never throws. */
    public static FlightLog open(OpMode opMode) {
        return open(opMode == null ? "OpMode" : opMode.getClass().getSimpleName());
    }

    /**
     * Opens a log as {@link #open(OpMode)} does, and sends every value it
     * records live to AdvantageScope as well, over NetworkTables 4 at
     * {@code 192.168.43.1:5810}. The server starts on the first call and runs
     * until the Robot Controller app stops.
     *
     * <p>For practice only. FTC rule R704 forbids third-party telemetry over
     * Wi-Fi at competitions, so a competition OpMode calls {@link #open}.
     * If the server cannot start, the log still records and the reason goes
     * to the robot's log.
     */
    public static FlightLog openLive(OpMode opMode) {
        FlightLog log = open(opMode);
        try {
            log.mirrorTo(Nt4Server.shared());
        } catch (IOException | RuntimeException e) {
            RobotLog.ee("corbelsflightlog", e, "could not start the NetworkTables server");
        }
        return log;
    }

    /** Opens a log with the given run name, in {@link #logDirectory()}. */
    public static synchronized FlightLog open(String runName) {
        closeCurrent();
        FlightLog.directory = logDirectory();
        current = FlightLog.open(runName);
        return current;
    }

    /** The listener the Robot Controller calls. Package-private for tests. */
    static OpModeManagerNotifier.Notifications listener() {
        return INSTANCE;
    }

    /** Closes the log this class opened, if it is still open. */
    public static synchronized void closeCurrent() {
        if (current != null) {
            current.close();
            current = null;
        }
    }

    // ------------------------------------------------------------ robot hooks

    /**
     * Called by the Robot Controller as it starts. Registers the OpMode
     * listener that closes logs, and the pages that serve them.
     */
    @WebHandlerRegistrar
    public static void register(Context context, WebHandlerManager manager) {
        try {
            OpModeManagerImpl opModeManager =
                    OpModeManagerImpl.getOpModeManagerOfActivity(AppUtil.getInstance().getActivity());
            if (opModeManager != null) {
                opModeManager.unregisterListener(INSTANCE);
                opModeManager.registerListener(INSTANCE);
            }
            LogWebHandlers.register(manager);
            RobotLog.ii("corbelsflightlog", "logs at http://192.168.43.1:8080"
                    + LogWebHandlers.INDEX_PATH + ", files in " + logDirectory());
        } catch (Throwable t) {
            // A logger must never stop the Robot Controller from starting.
            RobotLog.ee("corbelsflightlog", t, "could not register");
        }
    }

    /** Closes a log left open by an OpMode that was force-stopped. */
    @Override
    public void onOpModePreInit(OpMode opMode) {
        closeCurrent();
    }

    @Override
    public void onOpModePreStart(OpMode opMode) {
    }

    /** Closes the log when the OpMode ends, however it ended. */
    @Override
    public void onOpModePostStop(OpMode opMode) {
        closeCurrent();
    }
}
