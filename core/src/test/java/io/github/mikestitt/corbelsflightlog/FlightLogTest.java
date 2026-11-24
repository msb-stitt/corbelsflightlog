package io.github.mikestitt.corbelsflightlog;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import edu.wpi.first.util.datalog.DataLogRecord;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Behavioural tests of {@link FlightLog}: file lifecycle, every value type,
 * change-only writing, pose conversion, events, and every failure path.
 * Files are read back with WPILib's own reader (see {@link RecordedLog}).
 */
public class FlightLogTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File savedDirectory;
    private int savedTurns;
    private TestStreams.FakeClock clock;

    @Before
    public void setUp() {
        savedDirectory = FlightLog.directory;
        savedTurns = FlightLog.fieldQuarterTurns;
        FlightLog.directory = tmp.getRoot();
        clock = new TestStreams.FakeClock(5_000_000_000L);
        FlightLog.clock = clock;
        FlightLog.wallClock = () -> WALL_MS;
    }

    @After
    public void tearDown() {
        FlightLog.directory = savedDirectory;
        FlightLog.fieldQuarterTurns = savedTurns;
        FlightLog.clock = System::nanoTime;
        FlightLog.wallClock = System::currentTimeMillis;
        FlightLog.opener = java.io.FileOutputStream::new;
    }

    // ------------------------------------------------------------ helpers

    /** Opens a log, runs {@code body}, closes it, and reads the file back. */
    private RecordedLog record(Body body) throws IOException {
        FlightLog log = FlightLog.open("Test");
        assertTrue(log.status(), log.isRecording());
        body.run(log);
        log.close();
        return RecordedLog.of(onlyFile());
    }

    private interface Body {
        void run(FlightLog log) throws IOException;
    }

    private File onlyFile() {
        File[] files = FlightLog.directory.listFiles((d, n) -> n.endsWith(".wpilog"));
        assertEquals("expected exactly one log file", 1, files.length);
        return files[0];
    }

    private static final double EPS = 1e-12;

    /** A fixed wall-clock time, so file names are deterministic. */
    private static final long WALL_MS = 1_790_000_000_000L;

    private static String stamp() {
        return new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(new java.util.Date(WALL_MS));
    }
    private static final double M_PER_IN = 0.0254;

    // ------------------------------------------------------------ lifecycle

    @Test
    public void openCreatesANamedFileWithWpilibSchemas() throws IOException {
        RecordedLog log = record(l -> { });
        File f = onlyFile();
        assertEquals("Test-" + stamp() + ".wpilog", f.getName());
        assertEquals("Corbels FlightLog", log.reader.getExtraHeader());
        // Exactly WPILib 2026's addStructSchema output, in dependency order.
        assertEquals(Arrays.asList("/.schema/struct:Translation2d", "/.schema/struct:Rotation2d",
                "/.schema/struct:Pose2d", "/.schema/struct:Twist2d", "/.schema/struct:ChassisSpeeds",
                "/.schema/struct:MecanumDriveWheelSpeeds", "/.schema/struct:Translation3d",
                "/.schema/struct:Quaternion", "/.schema/struct:Rotation3d", "/.schema/struct:Pose3d"),
                new java.util.ArrayList<>(log.entries.keySet()));
        assertEquals(Collections.singletonList("double x;double y"), log.strings("/.schema/struct:Translation2d"));
        assertEquals(Collections.singletonList("double value"), log.strings("/.schema/struct:Rotation2d"));
        assertEquals(Collections.singletonList("Translation2d translation;Rotation2d rotation"),
                log.strings("/.schema/struct:Pose2d"));
        for (RecordedLog.Entry e : log.entries.values()) {
            assertEquals("structschema", e.type);
            assertEquals(0, e.startTimestamp);
        }
    }

    @Test
    public void runNamesAreSanitizedForFileNames() throws IOException {
        FlightLog log = FlightLog.open("Auto: Red/Left 2");
        log.close();
        assertTrue(onlyFile().getName(), onlyFile().getName().startsWith("Auto__Red_Left_2-"));
    }

    @Test
    public void aSecondRunInTheSameSecondGetsASuffix() throws IOException {
        FlightLog a = FlightLog.open("Run");
        FlightLog b = FlightLog.open("Run");
        FlightLog c = FlightLog.open("Run");
        assertEquals("Run-" + stamp() + ".wpilog", a.status());
        assertEquals("Run-" + stamp() + "-2.wpilog", b.status());
        assertEquals("Run-" + stamp() + "-3.wpilog", c.status());
        a.close();
        b.close();
        c.close();
        assertEquals(3, tmp.getRoot().listFiles().length);
    }

    @Test
    public void statusShowsTheFileWhileRecordingAndWhyNotOtherwise() throws IOException {
        FlightLog log = FlightLog.open("Status");
        assertTrue(log.status(), log.status().startsWith("Status-") && log.status().endsWith(".wpilog"));
        log.close();
        assertFalse(log.isRecording());
        assertEquals("off (closed)", log.status());
        assertEquals("off (because)", FlightLog.disabled("because").status());
    }

    @Test
    public void missingDirectoriesAreCreated() throws IOException {
        FlightLog.directory = new File(tmp.getRoot(), "a/b/c");
        FlightLog log = FlightLog.open("Deep");
        assertTrue(log.status(), log.isRecording());
        log.close();
        assertTrue(new File(tmp.getRoot(), "a/b/c").isDirectory());
    }

    @Test
    public void anUncreatableDirectoryDisablesLoggingWithAReason() throws IOException {
        File blocker = tmp.newFile("not-a-directory");
        FlightLog.directory = new File(blocker, "logs");     // parent is a file
        FlightLog log = FlightLog.open("Blocked");
        assertFalse(log.isRecording());
        assertTrue(log.status(), log.status().startsWith("off (can't create "));
    }

    @Test
    public void aNullRunNameFallsBackToOpMode() throws IOException {
        FlightLog log = FlightLog.open(null);
        assertEquals("OpMode-" + stamp() + ".wpilog", log.status());
        log.close();
    }

    @Test
    public void beingRefusedAccessToTheLogFolderDisablesLoggingWithAReason() {
        FlightLog.directory = new File(tmp.getRoot(), "locked") {
            @Override
            public boolean isDirectory() {
                throw new SecurityException("simulated: no storage permission");
            }
        };
        FlightLog log = FlightLog.open("Locked");
        assertFalse(log.isRecording());
        assertTrue(log.status(), log.status().contains("locked") && log.status().contains("no storage permission"));
    }

    @Test
    public void anOpenFailureDisablesLoggingWithAReason() {
        FlightLog.opener = f -> {
            throw new IOException("simulated: read-only storage");
        };
        FlightLog log = FlightLog.open("ReadOnly");
        assertFalse(log.isRecording());
        assertTrue(log.status(), log.status().contains("read-only storage"));
    }

    @Test
    public void aRuntimeExceptionWhileOpeningAlsoDisablesInsteadOfThrowing() {
        FlightLog.opener = f -> {
            throw new SecurityException("simulated: permission denied");
        };
        FlightLog log = FlightLog.open("Denied");
        assertFalse(log.isRecording());
        assertTrue(log.status(), log.status().contains("permission denied"));
    }

    @Test
    public void deadStorageIsDetectedAtTheFirstFlush() {
        // The 64 KB write buffer absorbs the header and schemas, so open()
        // succeeds; the first flush reaches the storage, fails, and logging
        // turns itself off.
        FlightLog.opener = f -> new TestStreams.Failing(0);
        FlightLog log = FlightLog.open("Dead");
        assertTrue(log.isRecording());
        clock.advanceMillis(1_001);
        log.endLoop();
        assertFalse(log.isRecording());
        assertTrue(log.status(), log.status().contains("write failed") && log.status().contains("storage full"));
    }

    @Test
    public void closeIsSafeTwiceAndSwallowsCloseErrors() throws IOException {
        TestStreams.Failing out = new TestStreams.Failing(Long.MAX_VALUE);
        out.failClose = true;
        FlightLog.opener = f -> out;
        FlightLog log = FlightLog.open("CloseFails");
        log.close();
        log.close();
        assertEquals(1, out.closes);
        assertEquals("off (closed)", log.status());
    }

    @Test
    public void aDisabledLogAcceptsEveryCallAndRecordsNothing() {
        FlightLog log = FlightLog.disabled("test");
        log.recordOutput("d", 1.0);
        log.recordOutput("f", 1.0f);
        log.recordOutput("i", 1);
        log.recordOutput("b", true);
        log.recordOutput("s", "x");
        log.pose("p", 1, 2, 3);
        log.poses("pp", new double[]{1, 2, 3});
        log.recordOutput("da", new double[]{1});
        log.recordOutput("ia", new long[]{1});
        log.recordOutput("ja", new int[]{1});
        log.recordOutput("ba", new boolean[]{true});
        log.endLoop();
        log.close();
        assertFalse(log.isRecording());
        assertEquals(0, tmp.getRoot().listFiles().length);
    }

    // ------------------------------------------------------------ timestamps

    @Test
    public void useClockSetsTheClockALogReadsAndUseWallClockPutsItBack() throws IOException {
        TestStreams.FakeClock simulated = new TestStreams.FakeClock(9_000_000_000L);
        FlightLog.useClock(simulated);
        RecordedLog log = record(l -> {
            simulated.advanceMillis(3);
            l.recordOutput("a", 1.0);
        });
        assertEquals(Arrays.asList(3_000L), log.timestamps("/a"));
        FlightLog.useWallClock();
        long before = System.nanoTime();
        long now = FlightLog.clock.getAsLong();
        assertTrue(now >= before && now - before < 1_000_000_000L);
    }

    @Test
    public void timestampsAreMicrosecondsSinceOpenFromTheClock() throws IOException {
        RecordedLog log = record(l -> {
            clock.advanceMicros(250);
            l.recordOutput("a", 1.0);
            clock.advanceMillis(2);
            l.recordOutput("a", 2.0);
        });
        assertEquals(Arrays.asList(250L, 2_250L), log.timestamps("/a"));
        assertEquals("entry started when first used", 250, log.entry("/a").startTimestamp);
    }

    // ------------------------------------------------------------ change-only: scalars

    @Test
    public void doublesAreWrittenOnlyWhenTheyChange() throws IOException {
        RecordedLog log = record(l -> {
            for (int i = 0; i < 100; i++) l.recordOutput("const", 5.0);
            for (double v : new double[]{1, 1, 2, 2, 1}) l.recordOutput("steps", v);
        });
        assertEquals(1, log.count("/const"));
        assertEquals(Arrays.asList(1.0, 2.0, 1.0), log.doubles("/steps"));
        assertEquals("double", log.entry("/const").type);
    }

    @Test
    public void nonFiniteDoublesAreSkippedAndMinusZeroEqualsZero() throws IOException {
        RecordedLog log = record(l -> {
            l.recordOutput("d", Double.NaN);
            l.recordOutput("d", Double.POSITIVE_INFINITY);
            l.recordOutput("d", Double.NEGATIVE_INFINITY);
            l.recordOutput("d", 0.0);
            l.recordOutput("d", -0.0);
            l.recordOutput("d", Double.NaN);
            l.recordOutput("d", 3.0);
        });
        List<Double> got = log.doubles("/d");
        assertEquals(Arrays.asList(0.0, 3.0), got);
        assertEquals("never writes -0.0", Double.doubleToRawLongBits(0.0),
                Double.doubleToRawLongBits(got.get(0)));
    }

    @Test
    public void floatsAreStoredAsFloatsOnlyWhenChanged() throws IOException {
        RecordedLog log = record(l -> {
            l.recordOutput("f", 0.1f);
            l.recordOutput("f", 0.1f);
            l.recordOutput("f", -0.0f);
            l.recordOutput("f", 0.0f);
            l.recordOutput("f", Float.NaN);
            l.recordOutput("f", Float.POSITIVE_INFINITY);
            l.recordOutput("f", Float.NEGATIVE_INFINITY);
            l.recordOutput("f", 2.5f);
        });
        RecordedLog.Entry e = log.entry("/f");
        assertEquals("float", e.type);
        assertEquals(3, e.records.size());
        assertEquals(0.1f, e.records.get(0).getFloat(), 0f);
        assertEquals(Float.floatToRawIntBits(0.0f), Float.floatToRawIntBits(e.records.get(1).getFloat()));
        assertEquals(2.5f, e.records.get(2).getFloat(), 0f);
    }

    @Test
    public void integersAreInt64OnlyWhenChanged() throws IOException {
        RecordedLog log = record(l -> {
            l.recordOutput("i", 7);
            l.recordOutput("i", 7);
            l.recordOutput("i", Long.MIN_VALUE);
            l.recordOutput("i", Long.MAX_VALUE);
            l.recordOutput("i", Long.MAX_VALUE);
        });
        RecordedLog.Entry e = log.entry("/i");
        assertEquals("int64", e.type);
        assertEquals(3, e.records.size());
        assertEquals(Long.MIN_VALUE, e.records.get(1).getInteger());
    }

    @Test
    public void booleansAreWrittenOnEachFlip() throws IOException {
        RecordedLog log = record(l -> {
            for (boolean b : new boolean[]{false, false, true, true, true, false}) l.recordOutput("b", b);
        });
        RecordedLog.Entry e = log.entry("/b");
        assertEquals("boolean", e.type);
        assertEquals(3, e.records.size());
        assertFalse(e.records.get(0).getBoolean());
        assertTrue(e.records.get(1).getBoolean());
        assertFalse(e.records.get(2).getBoolean());
    }

    @Test
    public void textIsWrittenOnlyWhenChangedAndNullBecomesTheWordNull() throws IOException {
        RecordedLog log = record(l -> {
            l.recordOutput("s", "INTAKING");
            l.recordOutput("s", "INTAKING");
            l.recordOutput("s", "SHOOTING");
            l.recordOutput("s", (String) null);
            l.recordOutput("s", (String) null);
            l.recordOutput("s", "null");
        });
        assertEquals(Arrays.asList("INTAKING", "SHOOTING", "null"), log.strings("/s"));
        assertEquals("string", log.entry("/s").type);
    }

    // ------------------------------------------------------------ poses

    /** Pedro inches/radians to AdvantageScope's FTC frame, computed independently. */
    private static double[] expectedPose(double xIn, double yIn, double headingRad, int turns) {
        double x = (xIn - 72) * M_PER_IN;
        double y = (yIn - 72) * M_PER_IN;
        double a = Math.floorMod(turns, 4) * Math.PI / 2;
        double fx = x * Math.cos(a) - y * Math.sin(a);   // rotate the point by a
        double fy = x * Math.sin(a) + y * Math.cos(a);
        double h = headingRad + a;
        h = h - 2 * Math.PI * Math.floor(h / (2 * Math.PI));
        return new double[]{fx, fy, h};
    }

    private static void assertPose(double[] expected, double[] actual) {
        assertEquals("x", expected[0], actual[0], EPS);
        assertEquals("y", expected[1], actual[1], EPS);
        assertEquals("heading", expected[2], actual[2], EPS);
    }

    @Test
    public void posesConvertCorrectlyForEveryQuarterTurn() throws IOException {
        double[][] pedro = {{0, 0, 0}, {72, 72, 0}, {144, 0, Math.PI / 2}, {10, 130, -1.0},
                {36, 72, 7.0}};
        int[] turnsList = {0, 1, 2, 3, -1, 5};
        for (int turns : turnsList) {
            FlightLog.directory = tmp.newFolder("turns" + turns);
            FlightLog.fieldQuarterTurns = turns;
            RecordedLog log = record(l -> {
                for (int i = 0; i < pedro.length; i++) l.pose("p" + i, pedro[i][0], pedro[i][1], pedro[i][2]);
            });
            for (int i = 0; i < pedro.length; i++) {
                RecordedLog.Entry e = log.entry("/p" + i);
                assertEquals("struct:Pose2d", e.type);
                assertEquals(24, e.last().getSize());
                double[] got = RecordedLog.pose(e.last());
                assertPose(expectedPose(pedro[i][0], pedro[i][1], pedro[i][2], turns), got);
                assertTrue("heading in [0, 2pi)", got[2] >= 0 && got[2] < 2 * Math.PI);
            }
        }
    }

    @Test
    public void fieldCenterConvertsToExactZeroNotMinusZero() throws IOException {
        for (int turns = 0; turns < 4; turns++) {
            FlightLog.directory = tmp.newFolder("center" + turns);
            FlightLog.fieldQuarterTurns = turns;
            RecordedLog log = record(l -> l.pose("c", 72, 72, 0));
            double[] got = RecordedLog.pose(log.entry("/c").last());
            assertEquals(Double.doubleToRawLongBits(0.0), Double.doubleToRawLongBits(got[0]));
            assertEquals(Double.doubleToRawLongBits(0.0), Double.doubleToRawLongBits(got[1]));
        }
    }

    @Test
    public void posesAreWrittenOnlyWhenAnyComponentChanges() throws IOException {
        RecordedLog log = record(l -> {
            l.pose("p", 1, 2, 3);
            l.pose("p", 1, 2, 3);
            l.pose("p", 1.5, 2, 3);
            l.pose("p", 1.5, 2.5, 3);
            l.pose("p", 1.5, 2.5, 3.5);
            l.pose("p", 1.5, 2.5, 3.5);
            l.pose("p", 1.5, 2.5, -0.0);
            l.pose("p", 1.5, 2.5, 0.0);
        });
        assertEquals(5, log.count("/p"));
    }

    @Test
    public void nonFinitePosesAreSkipped() throws IOException {
        RecordedLog log = record(l -> {
            l.pose("p", Double.NaN, 0, 0);
            l.pose("p", 0, Double.POSITIVE_INFINITY, 0);
            l.pose("p", 0, 0, Double.NEGATIVE_INFINITY);
            l.pose("p", 10, 20, 0);
        });
        assertEquals(1, log.count("/p"));
    }

    @Test
    public void poseArraysAreTrajectoriesWrittenOnlyWhenChanged() throws IOException {
        FlightLog.fieldQuarterTurns = 1;
        double[] path = {0, 0, 0, 24, 0, 0, 24, 24, Math.PI / 2};
        RecordedLog log = record(l -> {
            l.poses("path", path);
            l.poses("path", path.clone());                   // same values
            l.poses("path", new double[]{0, 0, 0});          // changed
            l.poses("path", new double[0]);                  // cleared
            l.poses("path", new double[0]);
            l.poses("path", new double[]{1, 2});             // not a multiple of 3: ignored
            l.poses("path", new double[]{1, 2, Double.NaN}); // non-finite: ignored
        });
        RecordedLog.Entry e = log.entry("/path");
        assertEquals("struct:Pose2d[]", e.type);
        assertEquals(3, e.records.size());
        double[] first = RecordedLog.pose(e.records.get(0));
        assertEquals(9, first.length);
        for (int i = 0; i < 3; i++) {
            assertPose(expectedPose(path[3 * i], path[3 * i + 1], path[3 * i + 2], 1),
                    Arrays.copyOfRange(first, 3 * i, 3 * i + 3));
        }
        assertEquals(24, e.records.get(1).getSize());
        assertEquals(0, e.records.get(2).getSize());
    }

    @Test
    public void poseArraysAreCopiedSoLaterMutationIsDetected() throws IOException {
        double[] path = {1, 1, 0};
        RecordedLog log = record(l -> {
            l.poses("p", path);
            path[0] = 2;                                   // caller reuses its array
            l.poses("p", path);
        });
        assertEquals(2, log.count("/p"));
    }

    // ------------------------------------------------------------ arrays

    @Test
    public void doubleArraysAreWrittenOnlyWhenChangedAndCopied() throws IOException {
        double[] powers = {0.5, 0.5, -0.5, -0.5};
        RecordedLog log = record(l -> {
            l.recordOutput("m", powers);
            l.recordOutput("m", powers);
            powers[0] = 1.0;
            l.recordOutput("m", powers);
            l.recordOutput("m", new double[]{1.0});             // length change
            l.recordOutput("m", new double[0]);
        });
        RecordedLog.Entry e = log.entry("/m");
        assertEquals("double[]", e.type);
        assertEquals(4, e.records.size());
        assertArrayEquals(new double[]{0.5, 0.5, -0.5, -0.5}, e.records.get(0).getDoubleArray(), 0);
        assertArrayEquals(new double[]{1.0, 0.5, -0.5, -0.5}, e.records.get(1).getDoubleArray(), 0);
    }

    @Test
    public void longArraysAreWrittenOnlyWhenChangedAndCopied() throws IOException {
        long[] ticks = {100, -200};
        RecordedLog log = record(l -> {
            l.recordOutput("t", ticks);
            l.recordOutput("t", ticks);
            ticks[1] = -201;
            l.recordOutput("t", ticks);
        });
        RecordedLog.Entry e = log.entry("/t");
        assertEquals("int64[]", e.type);
        assertEquals(2, e.records.size());
        assertArrayEquals(new long[]{100, -201}, e.records.get(1).getIntegerArray());
    }

    @Test
    public void intArraysAreWidenedToInt64AndCompareElementByElement() throws IOException {
        int[] ticks = {Integer.MIN_VALUE, 0, Integer.MAX_VALUE};
        RecordedLog log = record(l -> {
            l.recordOutput("t", ticks);
            l.recordOutput("t", ticks.clone());                   // same values, new array
            l.recordOutput("t", new int[]{Integer.MIN_VALUE, 1, Integer.MAX_VALUE});  // same length, changed
            l.recordOutput("t", new int[]{1});                    // length change
            l.recordOutput("t", new long[]{1});                   // long[] with equal values: same channel, no write
        });
        RecordedLog.Entry e = log.entry("/t");
        assertEquals("int64[]", e.type);
        assertEquals(3, e.records.size());
        assertArrayEquals(new long[]{Integer.MIN_VALUE, 0, Integer.MAX_VALUE}, e.records.get(0).getIntegerArray());
        assertArrayEquals(new long[]{1}, e.records.get(2).getIntegerArray());
    }

    @Test
    public void intArrayAfterLongArrayComparesAgainstTheWidenedCopy() throws IOException {
        RecordedLog log = record(l -> {
            l.recordOutput("t", new long[]{5, 6});
            l.recordOutput("t", new int[]{5, 6});                 // equal: no write
            l.recordOutput("t", new int[]{5, 7});                 // changed
        });
        assertEquals(2, log.count("/t"));
    }

    @Test
    public void booleanArraysAreWrittenOnlyWhenChangedAndCopied() throws IOException {
        boolean[] slots = {true, false, false};
        RecordedLog log = record(l -> {
            l.recordOutput("s", slots);
            l.recordOutput("s", slots);
            slots[2] = true;
            l.recordOutput("s", slots);
        });
        RecordedLog.Entry e = log.entry("/s");
        assertEquals("boolean[]", e.type);
        assertEquals(2, e.records.size());
        assertArrayEquals(new boolean[]{true, false, true}, e.records.get(1).getBooleanArray());
    }

    // ------------------------------------------------------------ one type per key

    @Test
    public void aKeyKeepsItsFirstTypeAndWarnsOnceInEvents() throws IOException {
        RecordedLog log = record(l -> {
            l.recordOutput("k", 5);
            l.recordOutput("k", 1.5);           // refused
            l.recordOutput("k", "x");             // refused, no second warning
            l.recordOutput("k", true);
            l.recordOutput("k", 2.5f);
            l.pose("k", 1, 2, 3);
            l.poses("k", new double[]{1, 2, 3});
            l.recordOutput("k", new double[]{1});
            l.recordOutput("k", new long[]{1});
            l.recordOutput("k", new int[]{1});
            l.recordOutput("k", new boolean[]{true});
            l.recordOutput("k", 6);            // same type: accepted
        });
        RecordedLog.Entry e = log.entry("/k");
        assertEquals("int64", e.type);
        assertEquals(2, e.records.size());
        List<String> events = log.strings("/Events");
        assertEquals(1, events.size());
        assertTrue(events.get(0), events.get(0).contains("\"k\"") && events.get(0).contains("int64")
                && events.get(0).contains("double"));
    }

    @Test
    public void everyMethodRefusesAKeyOwnedByAnotherType() throws IOException {
        RecordedLog log = record(l -> {
            l.recordOutput("d", 1.0);               // "d" is a double; every other type is refused
            l.recordOutput("d", 1f);
            l.recordOutput("d", 1);
            l.recordOutput("d", true);
            l.recordOutput("d", "x");
            l.pose("d", 1, 2, 3);
            l.poses("d", new double[]{1, 2, 3});
            l.recordOutput("d", new double[]{1});
            l.recordOutput("d", new long[]{1});
            l.recordOutput("d", new int[]{1});
            l.recordOutput("d", new boolean[]{true});
        });
        assertEquals("double", log.entry("/d").type);
        assertEquals(1, log.count("/d"));
        assertEquals("one warning, however many refusals", 1, log.count("/Events"));
    }

    @Test
    public void misusingTheEventsKeyDoesNotRecurse() throws IOException {
        RecordedLog log = record(l -> {
            l.recordOutput("Events", 1.0);            // student claims "Events" as a number
            FlightLog.event("dropped");         // events now can't be written
            FlightLog.event("dropped again");
        });
        assertEquals("double", log.entry("/Events").type);
        assertEquals(1, log.count("/Events"));
    }

    // ------------------------------------------------------------ events

    @Test
    public void everyEventIsRecordedEvenRepeats() throws IOException {
        RecordedLog log = record(l -> {
            FlightLog.event("same");
            FlightLog.event("same");
            clock.advanceMillis(5);
            FlightLog.event("same");
            FlightLog.event(null);
        });
        assertEquals(Arrays.asList("same", "same", "same", "null"), log.strings("/Events"));
        assertEquals("string", log.entry("/Events").type);
        assertEquals(5_000L, (long) log.timestamps("/Events").get(2));
    }

    @Test
    public void eventsWithNoOpenLogAreIgnored() {
        FlightLog.event("nobody listening");      // must not throw
        FlightLog log = FlightLog.open("Later");
        log.close();
        FlightLog.event("after close");           // must not throw
    }

    @Test
    public void eventsGoToTheMostRecentlyOpenedLog() throws IOException {
        FlightLog.directory = tmp.newFolder("a");
        FlightLog a = FlightLog.open("A");
        FlightLog.directory = tmp.newFolder("b");
        FlightLog b = FlightLog.open("B");
        FlightLog.event("to b");
        a.close();                                 // closing a non-current log keeps b current
        FlightLog.event("still to b");
        b.close();
        FlightLog.event("to nobody");
        RecordedLog readB = RecordedLog.of(new File(tmp.getRoot(), "b").listFiles()[0]);
        RecordedLog readA = RecordedLog.of(new File(tmp.getRoot(), "a").listFiles()[0]);
        assertEquals(Arrays.asList("to b", "still to b"), readB.strings("/Events"));
        assertFalse(readA.has("/Events"));
    }

    // ------------------------------------------------------------ flushing

    @Test
    public void endLoopFlushesAboutOnceASecond() throws IOException {
        TestStreams.Recording out = new TestStreams.Recording();
        FlightLog.opener = f -> out;
        FlightLog log = FlightLog.open("Flush");
        int base = out.flushes;
        log.endLoop();
        clock.advanceMillis(999);
        log.endLoop();
        assertEquals("not yet", base, out.flushes);
        clock.advanceMillis(1);
        log.endLoop();
        assertEquals("at one second", base + 1, out.flushes);
        clock.advanceMillis(500);
        log.endLoop();
        assertEquals(base + 1, out.flushes);
        clock.advanceMillis(500);
        log.endLoop();
        assertEquals(base + 2, out.flushes);
        log.close();
    }

    // ------------------------------------------------------------ write failures

    /**
     * Swaps the log's writer for one over storage that fails after
     * {@code bytesAllowed} more bytes, unbuffered, so the NEXT write fails
     * inside whichever method makes it. (Through the real 64 KB buffer, a
     * small write wouldn't reach storage at all.) White-box by design: it's
     * the only way to reach each method's own error handling.
     */
    private static TestStreams.Failing breakStorage(FlightLog log, long bytesAllowed) throws Exception {
        TestStreams.Failing out = new TestStreams.Failing(12 + bytesAllowed);   // 12: the new header
        java.lang.reflect.Field f = FlightLog.class.getDeclaredField("writer");
        f.setAccessible(true);
        f.set(log, new WpiLogWriter(out, ""));
        return out;
    }

    private static void assertDisabledByWriteFailure(FlightLog log) {
        assertFalse(log.isRecording());
        assertTrue(log.status(), log.status().startsWith("off (write failed: "));
    }

    /** Calls one write method on {@code log} with a value different each round. */
    private static void write(FlightLog log, String kind, int round) {
        double v = round + 1;
        switch (kind) {
            case "number": log.recordOutput("k", v); break;
            case "float32": log.recordOutput("k", (float) v); break;
            case "integer": log.recordOutput("k", round + 1); break;
            case "bool": log.recordOutput("k", round % 2 == 0); break;
            case "text": log.recordOutput("k", "v" + round); break;
            case "pose": log.pose("k", v, v, v); break;
            case "poses": log.poses("k", new double[]{v, v, v}); break;
            case "numbers": log.recordOutput("k", new double[]{v}); break;
            case "integers": log.recordOutput("k", new long[]{round + 1}); break;
            case "ints": log.recordOutput("k", new int[]{round + 1}); break;
            case "bools": log.recordOutput("k", new boolean[]{round % 2 == 0}); break;
            default: FlightLog.event("e" + round); break;
        }
    }

    private static final String[] KINDS = {"number", "float32", "integer", "bool", "text", "pose",
            "poses", "numbers", "integers", "ints", "bools", "event"};

    @Test
    public void everyWriteMethodTurnsLoggingOffWhenStartingItsChannelFails() throws Exception {
        for (String kind : KINDS) {
            FlightLog log = FlightLog.open("Start-" + kind);
            breakStorage(log, 0);                    // the channel's Start record fails
            write(log, kind, 0);
            assertDisabledByWriteFailure(log);
            log.close();
        }
    }

    @Test
    public void everyWriteMethodTurnsLoggingOffWhenAppendingFails() throws Exception {
        for (String kind : KINDS) {
            FlightLog log = FlightLog.open("Append-" + kind);
            write(log, kind, 0);                     // channel started while storage is fine
            breakStorage(log, 0);
            write(log, kind, 1);                     // changed value: the append fails
            assertDisabledByWriteFailure(log);
            // Everything afterwards is a harmless no-op.
            write(log, kind, 2);
            log.endLoop();
            log.close();
            // The reason survives close(), so the Driver Station keeps showing
            // WHY logging stopped rather than just "closed".
            assertDisabledByWriteFailure(log);
        }
    }

    @Test
    public void aFailingFlushTurnsLoggingOff() {
        TestStreams.Failing out = new TestStreams.Failing(Long.MAX_VALUE);
        out.failFlush = true;
        FlightLog.opener = f -> out;
        FlightLog log = FlightLog.open("FlushFails");
        clock.advanceMillis(1_000);
        log.endLoop();
        assertDisabledByWriteFailure(log);
    }

    @Test
    public void aFailureWhileClosingAfterAWriteFailureIsSwallowed() throws Exception {
        FlightLog log = FlightLog.open("CloseAfterFail");
        breakStorage(log, 0).failClose = true;
        log.recordOutput("k", 1.0);                       // write fails, then close fails too
        assertDisabledByWriteFailure(log);
    }

    @Test
    public void eventsAfterTheCurrentLogFailedAreIgnored() throws Exception {
        FlightLog log = FlightLog.open("FailedCurrent");
        breakStorage(log, 0);
        log.recordOutput("k", 1.0);
        assertDisabledByWriteFailure(log);
        FlightLog.event("into the void");            // still current, but no writer: no throw
        log.close();
    }

    /** A string larger than the 64 KB write buffer. */
    private static String pad() {
        char[] c = new char[70_000];
        Arrays.fill(c, 'x');
        return new String(c);
    }

    @Test
    public void aFileIsReadableUpToTheLastFlushAfterAFailure() throws IOException {
        // A real file whose stream dies after the first flush: the data up to
        // then must still be a valid log for WPILib's reader.
        File[] target = new File[1];
        long[] limit = {Long.MAX_VALUE};
        FlightLog.opener = f -> {
            target[0] = f;
            java.io.FileOutputStream real = new java.io.FileOutputStream(f);
            return new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    if (limit[0]-- <= 0) throw new IOException("simulated");
                    real.write(b);
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    if (limit[0] < len) throw new IOException("simulated");
                    limit[0] -= len;
                    real.write(b, off, len);
                }

                @Override
                public void flush() throws IOException {
                    real.flush();
                }

                @Override
                public void close() throws IOException {
                    real.close();
                }
            };
        };
        FlightLog log = FlightLog.open("Partial");
        log.recordOutput("kept", 1.0);
        clock.advanceMillis(1_000);
        log.endLoop();                                  // flushed: "kept" is on storage
        limit[0] = 0;
        log.recordOutput("lost", pad());                        // fails
        assertDisabledByWriteFailure(log);
        RecordedLog read = RecordedLog.of(target[0]);
        assertEquals(Collections.singletonList(1.0), read.doubles("/kept"));
    }

    // ------------------------------------------------------------ the recorded order

    @Test
    public void recordsAppearInCallOrderWithMonotonicTimestamps() throws IOException {
        RecordedLog log = record(l -> {
            for (int i = 0; i < 50; i++) {
                clock.advanceMicros(100);
                l.recordOutput("loop", i);
                l.recordOutput("half", (double) (i / 2));
                if (i % 10 == 0) FlightLog.event("tick " + i);
            }
        });
        long last = -1;
        for (DataLogRecord r : log.all) {
            assertTrue("timestamps never go backwards", r.getTimestamp() >= last);
            last = r.getTimestamp();
        }
        assertEquals(50, log.count("/loop"));
        assertEquals(25, log.count("/half"));
        assertEquals(5, log.count("/Events"));
    }
}
