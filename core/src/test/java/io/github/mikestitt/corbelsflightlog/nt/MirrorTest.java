package io.github.mikestitt.corbelsflightlog.nt;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import io.github.mikestitt.corbelsflightlog.FlightLog;

/** A FlightLog mirrored onto a server sends what it records, as it records it. */
public class MirrorTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File savedDirectory;
    private Nt4Server server;
    private FlightLog log;

    @Before
    public void setUp() throws IOException {
        savedDirectory = FlightLog.directory;
        FlightLog.directory = tmp.getRoot();
        server = Nt4Server.start(0);
        log = FlightLog.open("Mirror");
        log.mirrorTo(server);
    }

    @After
    public void tearDown() {
        log.close();
        server.close();
        FlightLog.directory = savedDirectory;
    }

    private RawClient subscribed() throws IOException {
        RawClient c = RawClient.connect(server.port(), Nt4Server.V41);
        c.sendText("[{\"method\":\"subscribe\",\"params\":{\"topics\":[\"\"],\"subuid\":1,"
                + "\"options\":{\"prefix\":true,\"periodic\":0.02}}}]");
        return c;
    }

    private static Object valueOf(RawClient c, String name, String type) {
        Map<String, Object> a = c.announceOf(name);
        assertNotNull(name + " announced", a);
        assertEquals(name + " type", type, a.get("type"));
        List<Object> v = c.valuesFor((Long) a.get("id"));
        return v.isEmpty() ? null : v.get(v.size() - 1);
    }

    @Test
    public void everyRecordedTypeGoesLiveUnderItsLogNameAndNtType() throws IOException {
        log.recordOutput("d", 1.5);
        log.recordOutput("f", 2.5f);
        log.recordOutput("i", 7L);
        log.recordOutput("b", true);
        log.recordOutput("s", "hi");
        log.recordOutput("da", new double[]{1, 2});
        log.recordOutput("ia", new int[]{3, 4});
        log.recordOutput("ba", new boolean[]{true, false});
        log.recordOutput("sa", new String[]{"a"});
        log.recordOutput("raw", new byte[]{9});
        log.pose2d("p", 1, 2, 0.5);
        FlightLog.event("started");
        try (RawClient c = subscribed()) {
            c.readUntil(2000, "every value", () -> c.values.size() >= 12);
            assertEquals(1.5, valueOf(c, "/d", "double"));
            assertEquals(2.5f, valueOf(c, "/f", "float"));
            assertEquals(7L, valueOf(c, "/i", "int"));
            assertEquals(true, valueOf(c, "/b", "boolean"));
            assertEquals("hi", valueOf(c, "/s", "string"));
            assertEquals(Arrays.asList(1.0, 2.0), valueOf(c, "/da", "double[]"));
            assertEquals(Arrays.asList(3L, 4L), valueOf(c, "/ia", "int[]"));
            assertEquals(Arrays.asList(true, false), valueOf(c, "/ba", "boolean[]"));
            assertEquals(Arrays.asList("a"), valueOf(c, "/sa", "string[]"));
            assertArrayEquals(new byte[]{9}, (byte[]) valueOf(c, "/raw", "raw"));
            assertEquals("started", valueOf(c, "/Events", "string"));
            ByteBuffer pose = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
            pose.putDouble(1).putDouble(2).putDouble(0.5);
            assertArrayEquals(pose.array(), (byte[]) valueOf(c, "/p", "struct:Pose2d"));
        }
    }

    @Test
    public void theStructSchemasAreSentForAdvantageScope() throws IOException {
        try (RawClient c = subscribed()) {
            c.readUntil(2000, "the schemas", () -> c.announceOf("/.schema/struct:Pose3d") != null
                    && c.values.size() >= 10);
            assertEquals("Translation2d translation;Rotation2d rotation", new String(
                    (byte[]) valueOf(c, "/.schema/struct:Pose2d", "structschema"), MsgPack.UTF8));
            assertEquals("double x;double y", new String(
                    (byte[]) valueOf(c, "/.schema/struct:Translation2d", "structschema"), MsgPack.UTF8));
            assertEquals("double value", new String(
                    (byte[]) valueOf(c, "/.schema/struct:Rotation2d", "structschema"), MsgPack.UTF8));
        }
    }

    @Test
    public void aValueChangedWhileConnectedArrivesLive() throws IOException {
        log.recordOutput("x", 1.0);
        try (RawClient c = subscribed()) {
            c.readUntil(2000, "x = 1", () -> Double.valueOf(1.0).equals(lastX(c)));
            log.recordOutput("x", 2.0);
            c.readUntil(2000, "x = 2", () -> Double.valueOf(2.0).equals(lastX(c)));
        }
    }

    private static Object lastX(RawClient c) {
        Map<String, Object> a = c.announceOf("/x");
        if (a == null) return null;
        List<Object> v = c.valuesFor((Long) a.get("id"));
        return v.isEmpty() ? null : v.get(v.size() - 1);
    }

    @Test
    public void mirroringToNullStopsIt() throws IOException {
        log.mirrorTo(null);
        log.recordOutput("after", 1.0);
        try (RawClient c = subscribed()) {
            c.readFor(300);
            assertNull(c.announceOf("/after"));
        }
    }
}
