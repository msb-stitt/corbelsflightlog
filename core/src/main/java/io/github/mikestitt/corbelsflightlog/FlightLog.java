package io.github.mikestitt.corbelsflightlog;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

import io.github.mikestitt.corbelsflightlog.nt.Nt4Server;

/**
 * One WPILOG file per OpMode run, for AdvantageScope.
 *
 * <p>Files are named {@code <run>-<date>-<time>.wpilog} in {@link #directory}
 * ({@code logs} by default). On an FTC robot, corbelsflightlog-ftc points that at
 * {@code /sdcard/FIRST/logs}. Open the files with AdvantageScope.
 *
 * <p><b>Only changes are written.</b> Each channel remembers the last value it
 * wrote and skips a call whose value is identical. AdvantageScope holds a value
 * until the next record, so nothing is lost -- a boolean that's true for the
 * whole match is one record, not one per loop. The exception is
 * {@link #event}, which records every call, since two identical events are
 * two things that happened.
 *
 * <p><b>Never throws into the OpMode.</b> If the file can't be opened or a
 * write fails, logging switches itself off and {@link #status()} says why.
 *
 * <p><b>One type per channel.</b> A channel's type is fixed by its first
 * value. A later call with a different type is ignored, and a one-time note
 * is added to {@code /Events} so it's easy to spot.
 *
 * <p><b>Pose frame.</b> Pose methods take Pedro coordinates (inches, origin in
 * a field corner, radians) and write WPILib {@code Pose2d} structs in the
 * frame AdvantageScope's FTC fields use: meters, origin at field center,
 * turned by {@link #fieldQuarterTurns} -- VERIFY that turn for your season.
 */
public final class FlightLog {

    /** Where log files go. Public so tests can point it at a temp folder.
     *  On a robot, corbelsflightlog-ftc sets this to the Robot Controller's
     *  own storage. */
    public static File directory = new File("logs");

    /**
     * How much of {@link #directory} logs may occupy. At {@link #open}, the
     * oldest {@code .wpilog} files are deleted until the total is under this.
     * Without it the folder grows until the device fills and logging stops.
     */
    public static long maxDirectoryBytes = 2L * 1024 * 1024 * 1024;   // 2 GiB

    /**
     * Counter-clockwise quarter turns from Pedro's axes to AdvantageScope's FTC
     * axes. Display only -- nothing on the robot reads it.
     *
     * <p><b>1 is verified for BIOBUZZ (2026-2027).</b> To check it for another
     * season, log a circle around the middle of the field and look at the four
     * axis crossings: Pedro (96,72,90deg) should draw right of centre facing up,
     * (72,96,180deg) above centre facing left, (48,72,270deg) left of centre
     * facing down, (72,48,0deg) below centre facing right. Each crossing has a
     * distinct position and heading, so a wrong turn or a mirrored axis shows up
     * at once -- which the shape of the circle alone would not reveal.
     */
    public static int fieldQuarterTurns = 1;

    private static final double FIELD_CENTER_IN = 72.0;
    private static final double METERS_PER_INCH = 0.0254;
    private static final long FLUSH_INTERVAL_NS = 1_000_000_000L;
    private static final int POSE_BYTES = 24;

    /** WPILib's own struct definitions, exactly as WPILib 2026's
     *  {@code addStructSchema} writes them (no trailing semicolons), in
     *  dependency order. AdvantageScope resolves Pose2d's parts by these names,
     *  so neither names nor order may change. Checked against WPILib by
     *  tools/logcheck. */
    static final String[][] SCHEMAS = {
            {"Translation2d", "double x;double y"},
            {"Rotation2d", "double value"},
            {"Pose2d", "Translation2d translation;Rotation2d rotation"},
            {"Twist2d", "double dx;double dy;double dtheta"},
            {"ChassisSpeeds", "double vx;double vy;double omega"},
            {"MecanumDriveWheelSpeeds",
                    "double front_left;double front_right;double rear_left;double rear_right"},
            {"Translation3d", "double x;double y;double z"},
            {"Quaternion", "double w;double x;double y;double z"},
            {"Rotation3d", "Quaternion q"},
            {"Pose3d", "Translation3d translation;Rotation3d rotation"},
    };

    /** Opens the file's output stream. Replaced by tests to simulate storage
     *  failures; package-private so robot code can't see it. */
    interface Opener {
        OutputStream open(File file) throws IOException;
    }

    /** Test hook: how files are opened. */
    static Opener opener = FileOutputStream::new;

    /** Test hook: the clock, in nanoseconds. */
    static LongSupplier clock = System::nanoTime;

    /** Test hook: wall-clock milliseconds, for file names. */
    static LongSupplier wallClock = System::currentTimeMillis;

    /** The log most recently opened, for {@link #event}. */
    private static volatile FlightLog current;

    /** One named channel: its entry ID, type, and the last value written. */
    private static final class Channel {
        final int id;
        final String type;
        boolean written;
        long bits;          // last scalar, as raw bits (double, float, int64, boolean)
        double x, y, h;     // last pose, in Pedro units, before conversion
        Object last;        // last String, or a copy of the last array

        Channel(int id, String type) {
            this.id = id;
            this.type = type;
        }
    }

    private WpiLogWriter writer;      // null once disabled
    private final File file;
    private String problem;           // why it's disabled, if it is
    private final long startNs;
    private long lastFlushNs;
    private final Map<String, Channel> channels = new HashMap<>();
    private final Set<String> typeWarnings = new HashSet<>();
    private final byte[] poseBuffer = new byte[POSE_BYTES];
    private final byte[] structBuffer = new byte[56];
    private Nt4Server live;          // null unless mirrored

    private FlightLog(WpiLogWriter writer, File file, String problem) {
        this.writer = writer;
        this.file = file;
        this.problem = problem;
        this.startNs = clock.getAsLong();
        this.lastFlushNs = startNs;
    }

    /** A log that records nothing. Safe to call every method on. */
    public static FlightLog disabled(String reason) {
        return new FlightLog(null, null, reason);
    }

    /** Opens a new file named after {@code runName} ({@code "OpMode"} if
     *  null). Never throws. */
    public static FlightLog open(String runName) {
        File file = null;
        if (runName == null) runName = "OpMode";
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) {
                return disabled("can't create " + directory);
            }
            prune(directory, maxDirectoryBytes);
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date(wallClock.getAsLong()));
            String base = runName.replaceAll("[^A-Za-z0-9_-]", "_") + "-" + stamp;
            file = new File(directory, base + ".wpilog");
            for (int n = 2; file.exists(); n++) {        // Control Hub clocks can be wrong
                file = new File(directory, base + "-" + n + ".wpilog");
            }
            WpiLogWriter w = new WpiLogWriter(
                    new BufferedOutputStream(opener.open(file), 1 << 16),
                    "Corbels FlightLog");
            FlightLog log = new FlightLog(w, file, null);
            for (String[] s : SCHEMAS) {
                int id = w.start("/.schema/struct:" + s[0], "structschema", "", 0);
                w.appendString(id, s[1], 0);
            }
            current = log;
            return log;
        } catch (IOException | RuntimeException e) {
            return disabled("can't open " + (file != null ? file : directory) + ": " + e);
        }
    }

    public boolean isRecording() {
        return writer != null;
    }

    /** File name while recording, otherwise why not -- for the Driver Station. */
    public String status() {
        return writer != null ? file.getName() : "off (" + problem + ")";
    }

    /**
     * Sends every value this log records to {@code server} as well, under the
     * same name and type, so AdvantageScope shows live what the file holds
     * afterwards. Sends the struct schemas at once. Only values recorded from
     * now on are sent, and nothing is sent once the log stops recording.
     * {@code null} stops mirroring.
     */
    public void mirrorTo(Nt4Server server) {
        live = server;
        if (server == null) return;
        for (String[] schema : SCHEMAS) {
            server.set("/.schema/struct:" + schema[0], "structschema",
                    schema[1].getBytes(StandardCharsets.UTF_8));
        }
    }

    // ------------------------------------------------------------ scalars

    /** A {@code double}. NaN and infinities are skipped (a gap in the graph). */
    public void recordOutput(String key, double value) {
        if (writer == null || !isFinite(value)) return;
        long bits = Double.doubleToRawLongBits(value + 0.0);   // + 0.0: -0.0 == 0.0
        try {
            Channel ch = channel(key, "double");
            if (ch == null || (ch.written && ch.bits == bits)) return;
            writer.appendDouble(ch.id, value + 0.0, now());
            mirror(key, "double", value + 0.0);
            remember(ch, bits);
        } catch (IOException e) {
            fail(e);
        }
    }

    /**
     * A {@code float}, stored as a 4-byte float so {@code 0.1f} reads as 0.1.
     * Safe as an overload: for {@code recordOutput("x", 5)} Java prefers the
     * {@code long} form, so an {@code int} stays an integer.
     */
    public void recordOutput(String key, float value) {
        if (writer == null || Float.isNaN(value) || Float.isInfinite(value)) return;
        long bits = Float.floatToRawIntBits(value + 0.0f);
        try {
            Channel ch = channel(key, "float");
            if (ch == null || (ch.written && ch.bits == bits)) return;
            writer.appendFloat(ch.id, value + 0.0f, now());
            mirror(key, "float", value + 0.0f);
            remember(ch, bits);
        } catch (IOException e) {
            fail(e);
        }
    }

    /** An integer: counts, encoder ticks, states as numbers. */
    public void recordOutput(String key, long value) {
        if (writer == null) return;
        try {
            Channel ch = channel(key, "int64");
            if (ch == null || (ch.written && ch.bits == value)) return;
            writer.appendInt64(ch.id, value, now());
            mirror(key, "int", value);
            remember(ch, value);
        } catch (IOException e) {
            fail(e);
        }
    }

    /** A true/false: limit switches, "has game piece", "at speed". */
    public void recordOutput(String key, boolean value) {
        if (writer == null) return;
        long bits = value ? 1 : 0;
        try {
            Channel ch = channel(key, "boolean");
            if (ch == null || (ch.written && ch.bits == bits)) return;
            writer.appendBoolean(ch.id, value, now());
            mirror(key, "boolean", value);
            remember(ch, bits);
        } catch (IOException e) {
            fail(e);
        }
    }

    /** Text, e.g. a state name. {@code null} is written as "null". */
    public void recordOutput(String key, String value) {
        if (writer == null) return;
        String v = String.valueOf(value);
        try {
            Channel ch = channel(key, "string");
            if (ch == null || (ch.written && v.equals(ch.last))) return;
            writer.appendString(ch.id, v, now());
            mirror(key, "string", v);
            ch.last = v;
            ch.written = true;
        } catch (IOException e) {
            fail(e);
        }
    }

    // ------------------------------------------------------------ poses

    /** A Pedro pose, drawn on AdvantageScope's 2D field. */
    public void pose(String key, double xIn, double yIn, double headingRad) {
        if (writer == null || !isFinite(xIn) || !isFinite(yIn) || !isFinite(headingRad)) return;
        try {
            Channel ch = channel(key, "struct:Pose2d");
            if (ch == null) return;
            if (ch.written && same(ch.x, xIn) && same(ch.y, yIn) && same(ch.h, headingRad)) return;
            encodePose(poseBuffer, 0, xIn, yIn, headingRad);
            writer.appendRaw(ch.id, poseBuffer, POSE_BYTES, now());
            mirror(key, "struct:Pose2d", Arrays.copyOf(poseBuffer, POSE_BYTES));
            ch.x = xIn;
            ch.y = yIn;
            ch.h = headingRad;
            ch.written = true;
        } catch (IOException e) {
            fail(e);
        }
    }

    /**
     * A Pedro pose lifted off the floor, drawn on AdvantageScope's 3D field.
     *
     * <p>Takes the same Pedro coordinates as {@link #pose} -- inches, corner
     * origin, radians -- plus a height in inches, and applies the same
     * {@link #fieldQuarterTurns} rotation. Use this rather than converting by
     * hand: a 3D pose built from raw metres will sit at a different place on
     * the field than the 2D one, which is exactly the bug it replaces.
     */
    public void pose(String key, double xIn, double yIn, double heightIn, double headingRad) {
        if (writer == null || !isFinite(xIn) || !isFinite(yIn)
                || !isFinite(heightIn) || !isFinite(headingRad)) {
            return;
        }
        encodePose(poseBuffer, 0, xIn, yIn, headingRad);
        double fx = readDouble(poseBuffer, 0);
        double fy = readDouble(poseBuffer, 8);
        double heading = readDouble(poseBuffer, 16);
        pose3d(key, fx, fy, heightIn * METERS_PER_INCH,
                Math.cos(heading / 2), 0, 0, Math.sin(heading / 2));
    }

    /**
     * Several Pedro poses -- a path, a list of targets -- as one
     * {@code Pose2d[]}, which AdvantageScope draws as a trajectory. The array
     * is {@code {x0, y0, heading0, x1, y1, heading1, ...}} in Pedro inches and
     * radians. An empty array clears it. Skipped if any value is non-finite.
     */
    public void poses(String key, double[] xyh) {
        if (writer == null || xyh.length % 3 != 0) return;
        for (double v : xyh) {
            if (!isFinite(v)) return;
        }
        try {
            Channel ch = channel(key, "struct:Pose2d[]");
            if (ch == null || (ch.written && Arrays.equals(xyh, (double[]) ch.last))) return;
            int n = xyh.length / 3;
            byte[] buf = new byte[POSE_BYTES * n];
            for (int i = 0; i < n; i++) {
                encodePose(buf, POSE_BYTES * i, xyh[3 * i], xyh[3 * i + 1], xyh[3 * i + 2]);
            }
            writer.appendRaw(ch.id, buf, buf.length, now());
            mirror(key, "struct:Pose2d[]", buf);
            ch.last = xyh.clone();
            ch.written = true;
        } catch (IOException e) {
            fail(e);
        }
    }

    /** Several strings as one value. */
    public void recordOutput(String key, String[] values) {
        if (writer == null || values == null) return;
        try {
            Channel ch = channel(key, "string[]");
            if (ch == null || (ch.written && Arrays.equals(values, (String[]) ch.last))) return;
            writer.appendStringArray(ch.id, values, now());
            mirror(key, "string[]", values.clone());
            ch.last = values.clone();
            ch.written = true;
        } catch (IOException e) {
            fail(e);
        }
    }

    /** Bytes, stored as they are -- the escape hatch for anything without a
     *  type of its own. */
    public void recordOutput(String key, byte[] value) {
        if (writer == null || value == null) return;
        try {
            Channel ch = channel(key, "raw");
            if (ch == null || (ch.written && Arrays.equals(value, (byte[]) ch.last))) return;
            writer.appendRaw(ch.id, value, value.length, now());
            mirror(key, "raw", value.clone());
            ch.last = value.clone();
            ch.written = true;
        } catch (IOException e) {
            fail(e);
        }
    }

    // ------------------------------------------------------------ structs
    //
    // WPILib struct types, which AdvantageScope draws directly: a pose on the
    // 2D or 3D field, speeds and wheel speeds in its tables and graphs. Values
    // are in WPILib's units -- metres, radians, metres per second -- and are
    // written as given, with no field-frame conversion. (pose(...) above is the
    // exception: it converts from Pedro's inches and corner origin.)
    //
    // Each is skipped when any component is non-finite, and written only when
    // the encoded value changes.

    /** A point, in metres. */
    public void translation2d(String key, double x, double y) {
        if (!finite(x) || !finite(y)) return;
        putDouble(structBuffer, 0, x);
        putDouble(structBuffer, 8, y);
        writeStruct(key, "Translation2d", 16);
    }

    /** An angle, in radians. */
    public void rotation2d(String key, double radians) {
        if (!finite(radians)) return;
        putDouble(structBuffer, 0, radians);
        writeStruct(key, "Rotation2d", 8);
    }

    /** A pose already in WPILib's frame: metres and radians, no conversion. */
    public void pose2d(String key, double x, double y, double radians) {
        if (!finite(x) || !finite(y) || !finite(radians)) return;
        putDouble(structBuffer, 0, x);
        putDouble(structBuffer, 8, y);
        putDouble(structBuffer, 16, radians);
        writeStruct(key, "Pose2d", 24);
    }

    /** A movement in the robot's own frame: metres and radians. */
    public void twist2d(String key, double dx, double dy, double dtheta) {
        if (!finite(dx) || !finite(dy) || !finite(dtheta)) return;
        putDouble(structBuffer, 0, dx);
        putDouble(structBuffer, 8, dy);
        putDouble(structBuffer, 16, dtheta);
        writeStruct(key, "Twist2d", 24);
    }

    /** How fast the robot is moving: metres per second and radians per second. */
    public void chassisSpeeds(String key, double vx, double vy, double omega) {
        if (!finite(vx) || !finite(vy) || !finite(omega)) return;
        putDouble(structBuffer, 0, vx);
        putDouble(structBuffer, 8, vy);
        putDouble(structBuffer, 16, omega);
        writeStruct(key, "ChassisSpeeds", 24);
    }

    /** Four wheels at once -- speeds, or powers, whichever you pass. */
    public void mecanumWheelSpeeds(String key, double frontLeft, double frontRight,
                                   double rearLeft, double rearRight) {
        if (!finite(frontLeft) || !finite(frontRight) || !finite(rearLeft) || !finite(rearRight)) return;
        putDouble(structBuffer, 0, frontLeft);
        putDouble(structBuffer, 8, frontRight);
        putDouble(structBuffer, 16, rearLeft);
        putDouble(structBuffer, 24, rearRight);
        writeStruct(key, "MecanumDriveWheelSpeeds", 32);
    }

    /** A point in space, in metres. */
    public void translation3d(String key, double x, double y, double z) {
        if (!finite(x) || !finite(y) || !finite(z)) return;
        putDouble(structBuffer, 0, x);
        putDouble(structBuffer, 8, y);
        putDouble(structBuffer, 16, z);
        writeStruct(key, "Translation3d", 24);
    }

    /** A rotation as a quaternion. */
    public void quaternion(String key, double w, double x, double y, double z) {
        if (!finite(w) || !finite(x) || !finite(y) || !finite(z)) return;
        putDouble(structBuffer, 0, w);
        putDouble(structBuffer, 8, x);
        putDouble(structBuffer, 16, y);
        putDouble(structBuffer, 24, z);
        writeStruct(key, "Quaternion", 32);
    }

    /** A 3D rotation. WPILib stores it as a quaternion, not as angles. */
    public void rotation3d(String key, double w, double x, double y, double z) {
        if (!finite(w) || !finite(x) || !finite(y) || !finite(z)) return;
        putDouble(structBuffer, 0, w);
        putDouble(structBuffer, 8, x);
        putDouble(structBuffer, 16, y);
        putDouble(structBuffer, 24, z);
        writeStruct(key, "Rotation3d", 32);
    }

    /** A pose in space: metres, and a rotation as a quaternion. */
    public void pose3d(String key, double x, double y, double z,
                       double qw, double qx, double qy, double qz) {
        if (!finite(x) || !finite(y) || !finite(z)
                || !finite(qw) || !finite(qx) || !finite(qy) || !finite(qz)) return;
        putDouble(structBuffer, 0, x);
        putDouble(structBuffer, 8, y);
        putDouble(structBuffer, 16, z);
        putDouble(structBuffer, 24, qw);
        putDouble(structBuffer, 32, qx);
        putDouble(structBuffer, 40, qy);
        putDouble(structBuffer, 48, qz);
        writeStruct(key, "Pose3d", 56);
    }

    /**
     * A pose in space from yaw, pitch and roll, in radians -- the form the FTC
     * SDK reports orientation in. Converted to the quaternion WPILib stores,
     * applying roll, then pitch, then yaw.
     */
    public void pose3d(String key, double x, double y, double z,
                       double yawRad, double pitchRad, double rollRad) {
        if (!finite(yawRad) || !finite(pitchRad) || !finite(rollRad)) return;
        double cy = Math.cos(yawRad * 0.5);
        double sy = Math.sin(yawRad * 0.5);
        double cp = Math.cos(pitchRad * 0.5);
        double sp = Math.sin(pitchRad * 0.5);
        double cr = Math.cos(rollRad * 0.5);
        double sr = Math.sin(rollRad * 0.5);
        pose3d(key, x, y, z,
                cr * cp * cy + sr * sp * sy,
                sr * cp * cy - cr * sp * sy,
                cr * sp * cy + sr * cp * sy,
                cr * cp * sy - sr * sp * cy);
    }

    /** Writes structBuffer[0, length) as struct:type, when it has changed. */
    private void writeStruct(String key, String type, int length) {
        if (writer == null) return;
        try {
            Channel ch = channel(key, "struct:" + type);
            if (ch == null) return;
            byte[] last = (byte[]) ch.last;
            if (ch.written && last != null && regionEquals(last, structBuffer, length)) return;
            writer.appendRaw(ch.id, structBuffer, length, now());
            mirror(key, "struct:" + type, Arrays.copyOf(structBuffer, length));
            if (last == null || last.length != length) {
                last = new byte[length];
                ch.last = last;
            }
            System.arraycopy(structBuffer, 0, last, 0, length);
            ch.written = true;
        } catch (IOException e) {
            fail(e);
        }
    }

    private static boolean regionEquals(byte[] a, byte[] b, int length) {
        if (a.length != length) return false;
        for (int i = 0; i < length; i++) {
            if (a[i] != b[i]) return false;
        }
        return true;
    }

    private static boolean finite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    // ------------------------------------------------------------ arrays

    /** Several doubles as one value, e.g. four motor powers. */
    public void recordOutput(String key, double[] values) {
        if (writer == null) return;
        try {
            Channel ch = channel(key, "double[]");
            if (ch == null || (ch.written && Arrays.equals(values, (double[]) ch.last))) return;
            writer.appendDoubleArray(ch.id, values, now());
            mirror(key, "double[]", values.clone());
            ch.last = values.clone();
            ch.written = true;
        } catch (IOException e) {
            fail(e);
        }
    }

    /** Several integers as one value, e.g. four encoder positions. */
    public void recordOutput(String key, long[] values) {
        if (writer == null) return;
        try {
            Channel ch = channel(key, "int64[]");
            if (ch == null || (ch.written && Arrays.equals(values, (long[]) ch.last))) return;
            writer.appendInt64Array(ch.id, values, now());
            mirror(key, "int[]", values.clone());
            ch.last = values.clone();
            ch.written = true;
        } catch (IOException e) {
            fail(e);
        }
    }

    /** Same as {@link #recordOutput(String, long[])}, for {@code int[]}. */
    public void recordOutput(String key, int[] values) {
        if (writer == null) return;
        try {
            Channel ch = channel(key, "int64[]");
            if (ch == null) return;
            long[] prev = (long[]) ch.last;
            if (ch.written && prev.length == values.length) {
                boolean same = true;
                for (int i = 0; i < values.length && same; i++) same = prev[i] == values[i];
                if (same) return;
            }
            long[] wide = new long[values.length];
            for (int i = 0; i < values.length; i++) wide[i] = values[i];
            writer.appendInt64Array(ch.id, wide, now());
            mirror(key, "int[]", wide);
            ch.last = wide;
            ch.written = true;
        } catch (IOException e) {
            fail(e);
        }
    }

    /** Several true/falses as one value, e.g. which of three slots are full. */
    public void recordOutput(String key, boolean[] values) {
        if (writer == null) return;
        try {
            Channel ch = channel(key, "boolean[]");
            if (ch == null || (ch.written && Arrays.equals(values, (boolean[]) ch.last))) return;
            writer.appendBooleanArray(ch.id, values, now());
            mirror(key, "boolean[]", values.clone());
            ch.last = values.clone();
            ch.written = true;
        } catch (IOException e) {
            fail(e);
        }
    }

    // ------------------------------------------------------------ events

    /**
     * Records a one-off event in the current log's {@code /Events} channel,
     * with its exact time. Every call is recorded, even a repeat. Callable from
     * anywhere -- OpModes, subsystems, commands. Does nothing if no log is open.
     */
    public static void event(String message) {
        FlightLog log = current;
        if (log == null || log.writer == null) return;
        log.appendEvent(String.valueOf(message));
    }

    private void appendEvent(String message) {
        try {
            Channel ch = channel("Events", "string");
            if (ch == null) return;
            writer.appendString(ch.id, message, now());
            mirror("Events", "string", message);
        } catch (IOException e) {
            fail(e);
        }
    }

    // ------------------------------------------------------------ lifecycle

    /** Call once per loop. Flushes to storage about once a second. */
    public void endLoop() {
        if (writer == null) return;
        long t = clock.getAsLong();
        if (t - lastFlushNs < FLUSH_INTERVAL_NS) return;
        lastFlushNs = t;
        try {
            writer.flush();
        } catch (IOException e) {
            fail(e);
        }
    }

    /** Flushes and closes the file. Safe to call more than once. */
    public void close() {
        if (current == this) current = null;
        if (writer == null) return;
        try {
            writer.close();
        } catch (IOException e) {
            // nothing more to do; the file keeps whatever reached storage
        }
        writer = null;
        problem = "closed";
    }

    // ------------------------------------------------------------ internals

    /** The channel for {@code key}, created on first use. {@code null} if the
     *  key already holds a different type. */
    private Channel channel(String key, String type) throws IOException {
        Channel ch = channels.get(key);
        if (ch == null) {
            ch = new Channel(writer.start("/" + key, type, "", now()), type);
            channels.put(key, ch);
            return ch;
        }
        if (ch.type.equals(type)) return ch;
        if (typeWarnings.add(key)) {
            appendEvent("log.data(\"" + key + "\"): first logged as " + ch.type
                    + ", then given " + type + " -- later values ignored");
        }
        return null;
    }

    /** Sends a value just written to the mirror, if there is one. NT4 calls
     *  WPILOG's {@code int64} {@code int}. */
    private void mirror(String key, String ntType, Object value) {
        Nt4Server server = live;
        if (server != null) server.set("/" + key, ntType, value);
    }

    private static void remember(Channel ch, long bits) {
        ch.bits = bits;
        ch.written = true;
    }

    private void encodePose(byte[] buf, int offset, double xIn, double yIn, double headingRad) {
        double x = (xIn - FIELD_CENTER_IN) * METERS_PER_INCH;
        double y = (yIn - FIELD_CENTER_IN) * METERS_PER_INCH;
        int turns = Math.floorMod(fieldQuarterTurns, 4);
        double fx;
        double fy;
        switch (turns) {
            case 1:  fx = -y; fy = x;  break;
            case 2:  fx = -x; fy = -y; break;
            case 3:  fx = y;  fy = -x; break;
            default: fx = x;  fy = y;  break;
        }
        double heading = (headingRad + turns * Math.PI / 2) % (2 * Math.PI);
        if (heading < 0) heading += 2 * Math.PI;
        putDouble(buf, offset, fx + 0.0);   // + 0.0 turns -0.0 into 0.0
        putDouble(buf, offset + 8, fy + 0.0);
        putDouble(buf, offset + 16, heading);
    }

    private static double readDouble(byte[] buf, int offset) {
        long bits = 0;
        for (int i = 7; i >= 0; i--) {
            bits = (bits << 8) | (buf[offset + i] & 0xFFL);
        }
        return Double.longBitsToDouble(bits);
    }

    private long now() {
        return (clock.getAsLong() - startNs) / 1000L;
    }

    private void fail(IOException e) {
        problem = "write failed: " + e;
        try {
            writer.close();
        } catch (IOException ignored) {
            // already failing
        }
        writer = null;
    }

    private static void putDouble(byte[] buf, int offset, double value) {
        long bits = Double.doubleToRawLongBits(value);
        for (int i = 0; i < 8; i++) {
            buf[offset + i] = (byte) (bits >>> (8 * i));
        }
    }

    private static boolean same(double a, double b) {
        return Double.doubleToRawLongBits(a + 0.0) == Double.doubleToRawLongBits(b + 0.0);
    }

    /** Deletes oldest .wpilog files until the folder is under {@code capBytes}. */
    private static void prune(File folder, long capBytes) {
        File[] files = folder.listFiles((d, n) -> n.endsWith(".wpilog"));
        if (files == null || files.length == 0) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= capBytes) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : files) {
            if (total <= capBytes) return;
            long size = f.length();
            if (f.delete()) total -= size;
        }
    }

    private static boolean isFinite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }
}
