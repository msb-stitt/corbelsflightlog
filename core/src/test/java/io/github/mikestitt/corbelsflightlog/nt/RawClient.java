package io.github.mikestitt.corbelsflightlog.nt;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * A WebSocket client written for these tests, so they check the server
 * against the RFC and the NT4 spec rather than against our own server code.
 * It masks every frame, as a real client must.
 */
final class RawClient implements AutoCloseable {

    final Socket socket;
    final InputStream in;
    final OutputStream out;
    final String protocol;
    final List<Map<String, Object>> texts = new ArrayList<>();
    final List<List<Object>> values = new ArrayList<>();
    int pings;
    private final Random random = new Random(1);
    private final ByteArrayOutputStream partial = new ByteArrayOutputStream();
    private final int[] partialOpcode = new int[1];

    private RawClient(Socket socket, InputStream in, String protocol) throws IOException {
        this.socket = socket;
        this.in = in;
        this.out = socket.getOutputStream();
        this.protocol = protocol;
    }

    /** Connects to /nt/test offering the protocols given; the one chosen is {@link #protocol}. */
    static RawClient connect(int port, String... protocols) throws IOException {
        Socket s = new Socket("127.0.0.1", port);
        s.setSoTimeout(3000);
        String key = "dGhlIHNhbXBsZSBub25jZQ==";
        s.getOutputStream().write(("GET /nt/test HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\n"
                + "Connection: Upgrade\r\nSec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n"
                + "Sec-WebSocket-Protocol: " + String.join(", ", protocols) + "\r\n\r\n").getBytes(MsgPack.UTF8));
        InputStream in = new BufferedInputStream(s.getInputStream());
        WebSocket.Request head = readResponse(in);
        if (!head.method.endsWith("101")) throw new IOException("not upgraded: " + head.method);
        if (!"s3pPLMBiTxaQ9kYGzzhZRbK+xOo=".equals(head.header("sec-websocket-accept"))) {
            throw new IOException("wrong accept key: " + head.header("sec-websocket-accept"));
        }
        return new RawClient(s, in, head.header("sec-websocket-protocol"));
    }

    /** Reads a response head, with the status line's "HTTP/1.1 101" in {@code method}. */
    static WebSocket.Request readResponse(InputStream in) throws IOException {
        WebSocket.Request r = WebSocket.readRequest(in);
        return new WebSocket.Request(r.method + " " + r.path, r.path, r.headers);
    }

    void sendText(String json) throws IOException {
        frame(WebSocket.TEXT, json.getBytes(MsgPack.UTF8));
    }

    void sendBinary(MsgPack.Writer w) throws IOException {
        frame(WebSocket.BINARY, w.toByteArray());
    }

    void frame(int opcode, byte[] payload) throws IOException {
        frame(opcode, payload, true);
    }

    void frame(int opcode, byte[] payload, boolean fin) throws IOException {
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        f.write((fin ? 0x80 : 0) | opcode);
        int len = payload.length;
        if (len < 126) {
            f.write(0x80 | len);
        } else {
            f.write(0x80 | 126);
            f.write(len >>> 8);
            f.write(len);
        }
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        f.write(mask, 0, 4);
        for (int i = 0; i < len; i++) f.write(payload[i] ^ mask[i & 3]);
        out.write(f.toByteArray());
        out.flush();
    }

    /** Reads one message and files it; false on a timeout. Answers pings. */
    @SuppressWarnings("unchecked")
    boolean readOne() throws IOException {
        WebSocket.Message m;
        try {
            m = WebSocket.readMessage(in, partial, partialOpcode);
        } catch (SocketTimeoutException e) {
            return false;
        }
        if (m.opcode == WebSocket.TEXT) {
            try {
                for (Object o : (List<Object>) Json.parse(new String(m.payload, MsgPack.UTF8))) {
                    texts.add((Map<String, Object>) o);
                }
            } catch (Json.ParseException e) {
                throw new IOException("server sent text that is not JSON", e);
            }
        } else if (m.opcode == WebSocket.BINARY) {
            MsgPack.Reader r = new MsgPack.Reader(m.payload, 0, m.payload.length);
            try {
                while (r.hasMore()) values.add((List<Object>) r.value());
            } catch (MsgPack.FormatException e) {
                throw new IOException("server sent bytes that are not MessagePack", e);
            }
        } else if (m.opcode == WebSocket.PING) {
            pings++;
            frame(WebSocket.PONG, m.payload);
        } else if (m.opcode == WebSocket.CLOSE) {
            throw new IOException("closed by the server");
        }
        return true;
    }

    interface Condition {
        boolean met();
    }

    /** Reads until the condition holds, or fails after the time given. */
    void readUntil(long ms, String what, Condition c) throws IOException {
        long end = System.currentTimeMillis() + ms;
        socket.setSoTimeout(50);
        while (!c.met()) {
            if (System.currentTimeMillis() > end) throw new AssertionError("timed out waiting for " + what
                    + "; texts " + texts + ", values " + values);
            readOne();
        }
    }

    /** Reads whatever arrives in the time given. */
    void readFor(long ms) throws IOException {
        long end = System.currentTimeMillis() + ms;
        socket.setSoTimeout(20);
        while (System.currentTimeMillis() < end) readOne();
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> announceOf(String name) {
        for (Map<String, Object> t : texts) {
            if ("announce".equals(t.get("method"))
                    && name.equals(((Map<String, Object>) t.get("params")).get("name"))) {
                return (Map<String, Object>) t.get("params");
            }
        }
        return null;
    }

    int countMethod(String method) {
        int n = 0;
        for (Map<String, Object> t : texts) {
            if (method.equals(t.get("method"))) n++;
        }
        return n;
    }

    /** The values received for a topic id, in order. */
    List<Object> valuesFor(long id) {
        List<Object> out = new ArrayList<>();
        for (List<Object> v : values) {
            if (((Long) v.get(0)) == id) out.add(v.get(3));
        }
        return out;
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
