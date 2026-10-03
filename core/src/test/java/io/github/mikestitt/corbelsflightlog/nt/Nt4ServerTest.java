package io.github.mikestitt.corbelsflightlog.nt;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class Nt4ServerTest {

    private Nt4Server server;

    @Before
    public void start() throws IOException {
        server = Nt4Server.start(0);
    }

    @After
    public void stop() {
        server.close();
        Nt4Server.idleTimeoutMs = 5000;
    }

    private static final String ALL = "[{\"method\":\"subscribe\",\"params\":{\"topics\":[\"\"],\"subuid\":1,"
            + "\"options\":{\"prefix\":true,\"periodic\":0.02}}}]";

    @Test
    public void aPlainHttpRequestIsAnsweredOkForAdvantageScopesProbe() throws IOException {
        try (Socket s = new Socket("127.0.0.1", server.port())) {
            s.setSoTimeout(3000);
            s.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(MsgPack.UTF8));
            InputStream in = new BufferedInputStream(s.getInputStream());
            WebSocket.Request head = RawClient.readResponse(in);
            assertEquals("HTTP/1.1 200", head.method);
            assertEquals("*", head.header("access-control-allow-origin"));
        }
    }

    @Test
    public void theHandshakePicksFourOneThenFourZeroThenRtt() throws IOException {
        try (RawClient c = RawClient.connect(server.port(), Nt4Server.V41, Nt4Server.V40)) {
            assertEquals(Nt4Server.V41, c.protocol);
        }
        try (RawClient c = RawClient.connect(server.port(), Nt4Server.V40)) {
            assertEquals(Nt4Server.V40, c.protocol);
        }
        try (RawClient c = RawClient.connect(server.port(), Nt4Server.RTT)) {
            assertEquals(Nt4Server.RTT, c.protocol);
        }
    }

    @Test
    public void aClientOfferingNoNt4ProtocolIsRefused() throws IOException {
        try {
            RawClient.connect(server.port(), "chat").close();
            fail("connected with no NT4 subprotocol");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("not upgraded"));
        }
    }

    @Test
    public void aSubscriberHearsAnnounceWithPropertiesThenTheValue() throws IOException {
        server.set("/x", "double", 1.5);
        try (RawClient c = RawClient.connect(server.port(), Nt4Server.V41)) {
            c.sendText(ALL);
            c.readUntil(2000, "the value of /x", () -> c.announceOf("/x") != null && !c.values.isEmpty());
            Map<String, Object> a = c.announceOf("/x");
            assertEquals("double", a.get("type"));
            assertNotNull("announce carries properties", a.get("properties"));
            List<Object> v = c.values.get(0);
            assertEquals(a.get("id"), v.get(0));
            assertEquals(1L, v.get(2));
            assertEquals(1.5, v.get(3));
        }
    }

    @Test
    public void everyTypeArrivesAsTheSpecEncodesIt() throws IOException {
        server.set("/b", "boolean", true);
        server.set("/i", "int", 7L);
        server.set("/f", "float", 2.5f);
        server.set("/s", "string", "hi");
        server.set("/r", "struct:Pose2d", new byte[]{1, 2, 3});
        server.set("/da", "double[]", new double[]{1, 2});
        server.set("/sa", "string[]", new String[]{"a", "b"});
        try (RawClient c = RawClient.connect(server.port(), Nt4Server.V41)) {
            c.sendText(ALL);
            c.readUntil(2000, "seven values", () -> c.values.size() >= 7);
            assertEquals(true, only(c, "/b"));
            assertEquals(7L, only(c, "/i"));
            assertEquals(2.5f, only(c, "/f"));
            assertEquals("hi", only(c, "/s"));
            assertArrayEquals(new byte[]{1, 2, 3}, (byte[]) only(c, "/r"));
            assertEquals(java.util.Arrays.asList(1.0, 2.0), only(c, "/da"));
            assertEquals(java.util.Arrays.asList("a", "b"), only(c, "/sa"));
        }
    }

    private static Object only(RawClient c, String name) {
        long id = (Long) c.announceOf(name).get("id");
        List<Object> v = c.valuesFor(id);
        assertEquals(name + " values", 1, v.size());
        return v.get(0);
    }

    @Test
    public void aTopicCreatedAfterSubscribingIsAnnouncedAndSent() throws IOException {
        try (RawClient c = RawClient.connect(server.port(), Nt4Server.V41)) {
            c.sendText(ALL);
            c.readFor(100);
            server.set("/late", "string", "here");
            c.readUntil(2000, "/late", () -> !c.values.isEmpty());
            assertNotNull(c.announceOf("/late"));
            assertEquals("here", c.values.get(0).get(3));
        }
    }

    @Test
    public void onlyChangedValuesAreSentAgain() throws IOException {
        server.set("/a", "double", 1.0);
        server.set("/b", "double", 2.0);
        try (RawClient c = RawClient.connect(server.port(), Nt4Server.V41)) {
            c.sendText(ALL);
            c.readUntil(2000, "two values", () -> c.values.size() >= 2);
            server.set("/a", "double", 3.0);
            c.readUntil(2000, "the new /a", () -> c.values.size() >= 3);
            c.readFor(200);
            assertEquals(3, c.values.size());
            assertEquals(3.0, c.values.get(2).get(3));
        }
    }

    @Test
    public void topicsOnlyAnnouncesAndSendsNoValues() throws IOException {
        server.set("/x", "double", 1.0);
        try (RawClient c = RawClient.connect(server.port(), Nt4Server.V41)) {
            c.sendText("[{\"method\":\"subscribe\",\"params\":{\"topics\":[\"\"],\"subuid\":1,"
                    + "\"options\":{\"prefix\":true,\"topicsonly\":true}}}]");
            c.readUntil(2000, "the announce", () -> c.announceOf("/x") != null);
            c.readFor(300);
            assertTrue("values: " + c.values, c.values.isEmpty());
        }
    }

    @Test
    public void withoutPrefixOnlyTheExactNameIsSubscribed() throws IOException {
        server.set("/x", "double", 1.0);
        server.set("/xy", "double", 2.0);
        try (RawClient c = RawClient.connect(server.port(), Nt4Server.V41)) {
            c.sendText("[{\"method\":\"subscribe\",\"params\":{\"topics\":[\"/x\"],\"subuid\":1,\"options\":{}}}]");
            c.readUntil(2000, "/x", () -> !c.values.isEmpty());
            c.readFor(300);
            assertNull(c.announceOf("/xy"));
            assertEquals(1, c.values.size());
        }
    }

    @Test
    public void aPrefixSubscriptionLeavesOtherTopicsOut() throws IOException {
        server.set("/sim/a", "double", 1.0);
        server.set("/other", "double", 2.0);
        try (RawClient c = RawClient.connect(server.port(), Nt4Server.V41)) {
            c.sendText("[{\"method\":\"subscribe\",\"params\":{\"topics\":[\"/sim/\"],\"subuid\":1,"
                    + "\"options\":{\"prefix\":true}}}]");
            c.readUntil(2000, "/sim/a", () -> !c.values.isEmpty());
            c.readFor(300);
            assertNull(c.announceOf("/other"));
        }
    }

    @Test
    public void unsubscribingStopsTheValues() throws IOException {
        server.set("/x", "double", 1.0);
        try (RawClient c = RawClient.connect(server.port(), Nt4Server.V41)) {
            c.sendText(ALL);
            c.readUntil(2000, "/x", () -> !c.values.isEmpty());
            c.sendText("[{\"method\":\"unsubscribe\",\"params\":{\"subuid\":1}}]");
            c.readFor(200);
            int before = c.values.size();
            server.set("/x", "double", 2.0);
            c.readFor(300);
            assertEquals(before, c.values.size());
        }
    }

    private static MsgPack.Writer rtt(long clientTime) {
        return new MsgPack.Writer().arrayHeader(4).integer(-1).integer(0).integer(2).integer(clientTime);
    }

    @Test
    public void timeSyncIsAnsweredOnTheMainSocket() throws IOException {
        long before = server.nowMicros();
        try (RawClient c = RawClient.connect(server.port(), Nt4Server.V41)) {
            c.sendBinary(rtt(123456789L));
            c.readUntil(2000, "the time reply", () -> !c.values.isEmpty());
            List<Object> v = c.values.get(0);
            assertEquals(-1L, v.get(0));
            assertTrue("server time " + v.get(1), (Long) v.get(1) >= before);
            assertEquals(123456789L, v.get(3));
        }
    }

    @Test
    public void timeSyncIsAnsweredOnTheRttSocketWhichIgnoresTheRest() throws IOException {
        server.set("/x", "double", 1.0);
        try (RawClient c = RawClient.connect(server.port(), Nt4Server.RTT)) {
            c.sendText(ALL);
            c.sendBinary(rtt(42));
            c.readUntil(2000, "the time reply", () -> !c.values.isEmpty());
            c.readFor(300);
            assertEquals(1, c.values.size());
            assertEquals(42L, c.values.get(0).get(3));
            assertTrue(c.texts.isEmpty());
        }
    }

    @Test
    public void aFourOneConnectionIsPingedAndAPingIsAnswered() throws IOException {
        try (RawClient c = RawClient.connect(server.port(), Nt4Server.V41)) {
            c.readUntil(3000, "a ping", () -> c.pings > 0);
            c.frame(WebSocket.PING, new byte[]{9, 8});
            c.socket.setSoTimeout(2000);
            WebSocket.Message m = WebSocket.readMessage(c.in, new java.io.ByteArrayOutputStream(), new int[1]);
            while (m.opcode != WebSocket.PONG) {
                m = WebSocket.readMessage(c.in, new java.io.ByteArrayOutputStream(), new int[1]);
            }
            assertArrayEquals(new byte[]{9, 8}, m.payload);
        }
    }

    @Test
    public void aSilentFourOneClientIsDropped() throws Exception {
        Nt4Server.idleTimeoutMs = 300;
        try (RawClient c = RawClient.connect(server.port(), Nt4Server.V41)) {
            waitFor(() -> server.connectionCount() == 1);
            // Read nothing and so send no pong: the server hears nothing.
            waitFor(() -> server.connectionCount() == 0);
        }
    }

    @Test
    public void aClientThatPublishesIsAnnouncedWithItsPubuidAndOthersHearIt() throws IOException {
        try (RawClient pub = RawClient.connect(server.port(), Nt4Server.V41);
             RawClient sub = RawClient.connect(server.port(), Nt4Server.V41)) {
            sub.sendText(ALL);
            pub.sendText("[{\"method\":\"publish\",\"params\":{\"name\":\"/in\",\"pubuid\":5,"
                    + "\"type\":\"double\",\"properties\":{}}}]");
            pub.readUntil(2000, "the publisher's announce", () -> pub.announceOf("/in") != null);
            assertEquals(5L, pub.announceOf("/in").get("pubuid"));
            pub.sendBinary(new MsgPack.Writer().arrayHeader(4).integer(5).integer(1000).integer(1).float64(4.25));
            sub.readUntil(2000, "the published value", () -> !sub.values.isEmpty());
            assertEquals(4.25, sub.values.get(0).get(3));
            assertEquals(1000L, sub.values.get(0).get(1));
        }
    }

    @Test
    public void unpublishingTheLastPublisherUnannouncesTheTopic() throws IOException {
        try (RawClient pub = RawClient.connect(server.port(), Nt4Server.V41);
             RawClient sub = RawClient.connect(server.port(), Nt4Server.V41)) {
            sub.sendText(ALL);
            sub.readFor(100);
            pub.sendText("[{\"method\":\"publish\",\"params\":{\"name\":\"/in\",\"pubuid\":5,"
                    + "\"type\":\"double\",\"properties\":{}}}]");
            sub.readUntil(2000, "the announce", () -> sub.announceOf("/in") != null);
            pub.sendText("[{\"method\":\"unpublish\",\"params\":{\"pubuid\":5}}]");
            sub.readUntil(2000, "the unannounce", () -> sub.countMethod("unannounce") == 1);
            assertFalse(server.topicNames().contains("/in"));
        }
    }

    @Test
    public void aRetainedTopicOutlivesItsPublisher() throws IOException {
        try (RawClient pub = RawClient.connect(server.port(), Nt4Server.V41)) {
            pub.sendText("[{\"method\":\"publish\",\"params\":{\"name\":\"/in\",\"pubuid\":5,"
                    + "\"type\":\"double\",\"properties\":{}}}]");
            pub.sendText("[{\"method\":\"setproperties\",\"params\":{\"name\":\"/in\","
                    + "\"update\":{\"retained\":true}}}]");
            pub.readUntil(2000, "the properties ack", () -> pub.countMethod("properties") == 1);
            Map<?, ?> props = (Map<?, ?>) pub.texts.get(pub.texts.size() - 1).get("params");
            assertEquals(true, props.get("ack"));
            pub.sendText("[{\"method\":\"unpublish\",\"params\":{\"pubuid\":5}}]");
            pub.readFor(200);
            assertTrue(server.topicNames().contains("/in"));
        }
    }

    @Test
    public void aDisconnectedClientsTopicsGoAndTheServerCarriesOn() throws Exception {
        RawClient pub = RawClient.connect(server.port(), Nt4Server.V41);
        pub.sendText("[{\"method\":\"publish\",\"params\":{\"name\":\"/in\",\"pubuid\":5,"
                + "\"type\":\"double\",\"properties\":{}}}]");
        pub.readUntil(2000, "the announce", () -> pub.announceOf("/in") != null);
        pub.close();
        waitFor(() -> server.connectionCount() == 0);
        assertFalse(server.topicNames().contains("/in"));
        server.set("/x", "double", 1.0);
        try (RawClient c = RawClient.connect(server.port(), Nt4Server.V41)) {
            c.sendText(ALL);
            c.readUntil(2000, "/x", () -> !c.values.isEmpty());
        }
    }

    @Test
    public void aMessageSplitIntoFramesIsJoined() throws IOException {
        server.set("/x", "double", 1.0);
        try (RawClient c = RawClient.connect(server.port(), Nt4Server.V41)) {
            byte[] all = ALL.getBytes(MsgPack.UTF8);
            int half = all.length / 2;
            c.frame(WebSocket.TEXT, java.util.Arrays.copyOfRange(all, 0, half), false);
            c.frame(WebSocket.PING, new byte[0]);
            c.frame(WebSocket.CONTINUATION, java.util.Arrays.copyOfRange(all, half, all.length), true);
            c.readUntil(2000, "/x", () -> !c.values.isEmpty());
        }
    }

    @Test
    public void textThatIsNotJsonIsIgnored() throws IOException {
        server.set("/x", "double", 1.0);
        try (RawClient c = RawClient.connect(server.port(), Nt4Server.V41)) {
            c.sendText("not json");
            c.sendText(ALL);
            c.readUntil(2000, "/x", () -> !c.values.isEmpty());
        }
    }

    @Test
    public void aValueOfAnotherTypeIsIgnored() throws IOException {
        server.set("/x", "double", 1.0);
        server.set("/x", "string", "no");
        try (RawClient c = RawClient.connect(server.port(), Nt4Server.V41)) {
            c.sendText(ALL);
            c.readUntil(2000, "/x", () -> !c.values.isEmpty());
            assertEquals(1.0, c.values.get(0).get(3));
        }
    }

    @Test
    public void settingIsQuickWhenAClientDoesNotRead() throws IOException {
        try (RawClient c = RawClient.connect(server.port(), Nt4Server.V41)) {
            c.sendText(ALL);
            byte[] big = new byte[60000];
            long start = System.nanoTime();
            for (int i = 0; i < 2000; i++) {
                big[0] = (byte) i;
                server.set("/big" + (i % 50), "raw", big.clone());
            }
            long ms = (System.nanoTime() - start) / 1_000_000;
            assertTrue("2000 sets took " + ms + " ms", ms < 1000);
        }
    }

    interface Check {
        boolean ok();
    }

    private static void waitFor(Check c) throws InterruptedException {
        long end = System.currentTimeMillis() + 3000;
        while (!c.ok()) {
            if (System.currentTimeMillis() > end) fail("timed out");
            Thread.sleep(20);
        }
    }
}
