package io.github.mikestitt.corbelsflightlog.nt;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A NetworkTables 4 server, so AdvantageScope can connect to a robot the way
 * it connects to an FRC robot: {@code ws://<robot>:5810}.
 *
 * <p>Written in plain Java 8 with no libraries, to run on a REV Control Hub.
 * The robot's code sets values with {@link #set}, which stores them and
 * returns at once; each client has its own thread that sends them, so a slow
 * or lost connection never holds up the robot's loop.
 *
 * <p>FTC rule R704 forbids third-party telemetry over Wi-Fi at competitions.
 * Start a server only for practice.
 *
 * <p>Covered: the plain HTTP request AdvantageScope sends before connecting,
 * the 4.1, 4.0 and {@code rtt} subprotocols, {@code publish},
 * {@code unpublish}, {@code setproperties}, {@code subscribe} with
 * {@code prefix}, {@code topicsonly} and {@code periodic}, and
 * {@code unsubscribe}. Not covered: the {@code all} option (each send carries
 * the latest value), meta-topics such as {@code $clients}, saving
 * {@code persistent} topics, TLS and NetworkTables 3.
 */
public final class Nt4Server {

    /** The port FRC robots serve NetworkTables 4 on. */
    public static final int DEFAULT_PORT = 5810;

    static final String V41 = "v4.1.networktables.first.wpi.edu";
    static final String V40 = "networktables.first.wpi.edu";
    static final String RTT = "rtt.networktables.first.wpi.edu";

    /** How often a 4.1 or rtt connection is pinged. */
    static final long PING_MS = 1000;
    /** How long a 4.1 or rtt connection may send nothing before it is dropped. */
    static int idleTimeoutMs = 5000;
    private static final double DEFAULT_PERIOD_S = 0.1;
    private static final double MIN_PERIOD_S = 0.005;

    private final ServerSocket listener;
    private final Thread acceptor;
    private final long startNanos = System.nanoTime();
    private volatile boolean closed;

    // Everything below is guarded by `this`, and held only for work in memory.
    private final Map<String, Topic> topics = new LinkedHashMap<>();
    private final List<Client> clients = new ArrayList<>();
    private int nextTopicId = 1;

    private Nt4Server(ServerSocket listener) {
        this.listener = listener;
        this.acceptor = new Thread(this::acceptLoop, "nt4-accept");
        this.acceptor.setDaemon(true);
    }

    private static Nt4Server shared;

    /**
     * One server on {@link #DEFAULT_PORT} for the life of the program,
     * started on the first call. On a robot it outlives each OpMode, so
     * AdvantageScope stays connected from one run to the next.
     */
    public static synchronized Nt4Server shared() throws IOException {
        if (shared == null || shared.closed) shared = start(DEFAULT_PORT);
        return shared;
    }

    /** Starts a server on {@link #DEFAULT_PORT}, on every network interface. */
    public static Nt4Server start() throws IOException {
        return start(DEFAULT_PORT);
    }

    /** Starts a server on a port; 0 picks a free one, which {@link #port()} gives. */
    public static Nt4Server start(int port) throws IOException {
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(port));
        Nt4Server server = new Nt4Server(ss);
        server.acceptor.start();
        return server;
    }

    public int port() {
        return listener.getLocalPort();
    }

    /** How many WebSocket connections are open, rtt ones included. */
    public synchronized int connectionCount() {
        return clients.size();
    }

    /** The server's clock, in microseconds; every value it sends is stamped with it. */
    public long nowMicros() {
        return (System.nanoTime() - startNanos) / 1000 + 1;
    }

    /**
     * Sets a topic's value, creating the topic the first time. {@code type} is
     * an NT4 type string: {@code boolean}, {@code double}, {@code int},
     * {@code float}, {@code string}, {@code raw}, {@code double[]} and the
     * other arrays, {@code struct:Pose2d}, {@code structschema}, and so on.
     * The value is a {@code Boolean}, {@code Double}, {@code Long},
     * {@code Float}, {@code String}, {@code byte[]}, {@code boolean[]},
     * {@code double[]}, {@code long[]}, {@code float[]} or {@code String[]} to
     * match. A topic's type is fixed by its first value; a value of another
     * type is ignored. A value equal to the last is not sent again.
     */
    public void set(String name, String type, Object value) {
        if (closed || name == null || type == null || value == null) return;
        synchronized (this) {
            Topic t = topics.get(name);
            if (t == null) {
                t = createTopic(name, type, new LinkedHashMap<String, Object>());
                t.serverPublished = true;
            } else if (!t.type.equals(type)) {
                return;
            }
            t.serverPublished = true;
            if (t.value != null && Arrays.deepEquals(new Object[]{t.value}, new Object[]{value})) return;
            t.value = value;
            t.timestamp = nowMicros();
            t.seq++;
        }
        wakeAll();
    }

    /** Closes every connection and stops listening. */
    public void close() {
        closed = true;
        try {
            listener.close();
        } catch (IOException ignored) {
        }
        List<Client> all;
        synchronized (this) {
            all = new ArrayList<>(clients);
        }
        for (Client c : all) c.close();
    }

    // ------------------------------------------------------------ topics

    static final class Topic {
        final String name;
        final int id;
        final String type;
        final Map<String, Object> properties;
        Object value;
        long timestamp;
        long seq;
        boolean serverPublished;
        int publishers;

        Topic(String name, int id, String type, Map<String, Object> properties) {
            this.name = name;
            this.id = id;
            this.type = type;
            this.properties = properties;
        }

        boolean retained() {
            return Boolean.TRUE.equals(properties.get("retained"))
                    || Boolean.TRUE.equals(properties.get("persistent"));
        }
    }

    /** Must hold `this`. */
    private Topic createTopic(String name, String type, Map<String, Object> properties) {
        Topic t = new Topic(name, nextTopicId++, type, properties);
        topics.put(name, t);
        for (Client c : clients) {
            if (c.wantsTopic(name)) c.announce(t, null);
        }
        return t;
    }

    /** Must hold `this`. Removes a topic no one publishes any more. */
    private void dropIfUnused(Topic t) {
        if (t.serverPublished || t.publishers > 0 || t.retained()) return;
        topics.remove(t.name);
        for (Client c : clients) c.unannounce(t);
    }

    static int typeId(String type) {
        switch (type) {
            case "boolean": return 0;
            case "double": return 1;
            case "int": return 2;
            case "float": return 3;
            case "string":
            case "json": return 4;
            case "boolean[]": return 16;
            case "double[]": return 17;
            case "int[]": return 18;
            case "float[]": return 19;
            case "string[]": return 20;
            default: return 5;
        }
    }

    /** Appends {@code [id, time, typeId, value]}. False if the value does not fit the type. */
    static boolean writeValue(MsgPack.Writer w, int id, long timestamp, String type, Object v) {
        int typeId = typeId(type);
        if (!fits(typeId, v)) return false;
        w.arrayHeader(4).integer(id).integer(timestamp).integer(typeId);
        if (v instanceof Boolean) {
            w.bool((Boolean) v);
        } else if (v instanceof Double) {
            w.float64((Double) v);
        } else if (v instanceof Float) {
            w.float32((Float) v);
        } else if (v instanceof Long || v instanceof Integer) {
            w.integer(((Number) v).longValue());
        } else if (v instanceof String) {
            w.string((String) v);
        } else if (v instanceof byte[]) {
            w.bin((byte[]) v);
        } else if (v instanceof boolean[]) {
            boolean[] a = (boolean[]) v;
            w.arrayHeader(a.length);
            for (boolean b : a) w.bool(b);
        } else if (v instanceof double[]) {
            double[] a = (double[]) v;
            w.arrayHeader(a.length);
            for (double d : a) w.float64(d);
        } else if (v instanceof long[]) {
            long[] a = (long[]) v;
            w.arrayHeader(a.length);
            for (long l : a) w.integer(l);
        } else if (v instanceof float[]) {
            float[] a = (float[]) v;
            w.arrayHeader(a.length);
            for (float f : a) w.float32(f);
        } else {
            String[] a = (String[]) v;
            w.arrayHeader(a.length);
            for (String s : a) w.string(s);
        }
        return true;
    }

    private static boolean fits(int typeId, Object v) {
        switch (typeId) {
            case 0: return v instanceof Boolean;
            case 1: return v instanceof Double;
            case 2: return v instanceof Long || v instanceof Integer;
            case 3: return v instanceof Float;
            case 4: return v instanceof String;
            case 5: return v instanceof byte[];
            case 16: return v instanceof boolean[];
            case 17: return v instanceof double[];
            case 18: return v instanceof long[];
            case 19: return v instanceof float[];
            case 20: return v instanceof String[];
            default: return false;
        }
    }

    /** Converts a value a client sent to the form {@link #set} takes, or null. */
    static Object fromWire(int typeId, Object v) {
        try {
            switch (typeId) {
                case 0: return (Boolean) v;
                case 1: return ((Number) v).doubleValue();
                case 2: return ((Number) v).longValue();
                case 3: return ((Number) v).floatValue();
                case 4: return (String) v;
                case 5: return (byte[]) v;
                default: break;
            }
            List<?> list = (List<?>) v;
            int n = list.size();
            switch (typeId) {
                case 16: {
                    boolean[] a = new boolean[n];
                    for (int i = 0; i < n; i++) a[i] = (Boolean) list.get(i);
                    return a;
                }
                case 17: {
                    double[] a = new double[n];
                    for (int i = 0; i < n; i++) a[i] = ((Number) list.get(i)).doubleValue();
                    return a;
                }
                case 18: {
                    long[] a = new long[n];
                    for (int i = 0; i < n; i++) a[i] = ((Number) list.get(i)).longValue();
                    return a;
                }
                case 19: {
                    float[] a = new float[n];
                    for (int i = 0; i < n; i++) a[i] = ((Number) list.get(i)).floatValue();
                    return a;
                }
                case 20: {
                    String[] a = new String[n];
                    for (int i = 0; i < n; i++) a[i] = (String) list.get(i);
                    return a;
                }
                default: return null;
            }
        } catch (ClassCastException | NullPointerException e) {
            return null;
        }
    }

    // ------------------------------------------------------------ connections

    private void acceptLoop() {
        while (!closed) {
            Socket s;
            try {
                s = listener.accept();
            } catch (IOException e) {
                if (closed) return;
                continue;
            }
            Thread t = new Thread(() -> serve(s), "nt4-conn");
            t.setDaemon(true);
            t.start();
        }
    }

    private void serve(Socket socket) {
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(idleTimeoutMs);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            WebSocket.Request req = WebSocket.readRequest(in);
            if (!req.isUpgrade()) {
                answerHttp(out, req);
                socket.close();
                return;
            }
            String protocol = chooseProtocol(req.header("sec-websocket-protocol"));
            if (protocol == null || !req.path.startsWith("/nt/")) {
                byte[] body = "NetworkTables 4: no subprotocol in common\n".getBytes(MsgPack.UTF8);
                out.write(("HTTP/1.1 400 Bad Request\r\nContent-Length: " + body.length
                        + "\r\nConnection: close\r\n\r\n").getBytes(MsgPack.UTF8));
                out.write(body);
                out.flush();
                socket.close();
                return;
            }
            out.write(("HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + WebSocket.acceptKey(req.header("sec-websocket-key")) + "\r\n"
                    + "Sec-WebSocket-Protocol: " + protocol + "\r\n\r\n").getBytes(MsgPack.UTF8));
            out.flush();
            boolean pinged = !protocol.equals(V40);
            if (!pinged) socket.setSoTimeout(0);
            Client c = new Client(socket, out, protocol.equals(RTT), pinged);
            synchronized (this) {
                if (closed) {
                    socket.close();
                    return;
                }
                clients.add(c);
            }
            c.writer.start();
            c.readLoop(in);
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void answerHttp(OutputStream out, WebSocket.Request req) throws IOException {
        byte[] body = ("<html><body><p>NetworkTables 4 server, from corbelsflightlog. "
                + "Connect at ws://this-address:" + DEFAULT_PORT + "/nt/your-name.</p></body></html>\n")
                .getBytes(MsgPack.UTF8);
        out.write(("HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n").getBytes(MsgPack.UTF8));
        if (!"HEAD".equals(req.method)) out.write(body);
        out.flush();
    }

    static String chooseProtocol(String offered) {
        if (offered == null) return null;
        List<String> list = new ArrayList<>();
        for (String p : offered.split(",")) list.add(p.trim());
        if (list.contains(V41)) return V41;
        if (list.contains(V40)) return V40;
        if (list.contains(RTT)) return RTT;
        return null;
    }

    private void wakeAll() {
        List<Client> all;
        synchronized (this) {
            all = new ArrayList<>(clients);
        }
        for (Client c : all) c.wake();
    }

    private static final class Subscription {
        final List<String> names;
        final boolean prefix;
        final boolean topicsOnly;
        final double periodS;

        Subscription(List<String> names, boolean prefix, boolean topicsOnly, double periodS) {
            this.names = names;
            this.prefix = prefix;
            this.topicsOnly = topicsOnly;
            this.periodS = periodS;
        }

        boolean matches(String topic) {
            for (String n : names) {
                if (prefix ? topic.startsWith(n) : topic.equals(n)) return true;
            }
            return false;
        }
    }

    /** One WebSocket connection: its reader runs on the connection's thread, its writer on its own. */
    private final class Client {
        final Socket socket;
        final OutputStream out;
        final boolean rttOnly;
        final boolean pinged;
        final Thread writer;
        private final Object wakeLock = new Object();
        private boolean pending;
        private volatile boolean open = true;

        // Guarded by the server's lock.
        final Map<Long, Subscription> subscriptions = new HashMap<>();
        final Map<Integer, Topic> announced = new HashMap<>();
        final Map<Integer, Long> sentSeq = new HashMap<>();
        final Map<Long, Topic> publishing = new HashMap<>();
        final List<Map<String, Object>> textOut = new ArrayList<>();
        final MsgPack.Writer binaryOut = new MsgPack.Writer();

        Client(Socket socket, OutputStream out, boolean rttOnly, boolean pinged) {
            this.socket = socket;
            this.out = out;
            this.rttOnly = rttOnly;
            this.pinged = pinged;
            this.writer = new Thread(this::writeLoop, "nt4-send");
            this.writer.setDaemon(true);
        }

        void wake() {
            synchronized (wakeLock) {
                pending = true;
                wakeLock.notifyAll();
            }
        }

        // ---- under the server's lock

        boolean wantsTopic(String name) {
            for (Subscription s : subscriptions.values()) {
                if (s.matches(name)) return true;
            }
            return false;
        }

        boolean wantsValues(String name) {
            for (Subscription s : subscriptions.values()) {
                if (!s.topicsOnly && s.matches(name)) return true;
            }
            return false;
        }

        double periodS() {
            double p = Double.MAX_VALUE;
            for (Subscription s : subscriptions.values()) {
                if (!s.topicsOnly) p = Math.min(p, s.periodS);
            }
            return p == Double.MAX_VALUE ? DEFAULT_PERIOD_S : p;
        }

        void announce(Topic t, Long pubuid) {
            if (announced.containsKey(t.id) && pubuid == null) return;
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("name", t.name);
            params.put("id", t.id);
            params.put("type", t.type);
            if (pubuid != null) params.put("pubuid", pubuid);
            params.put("properties", new LinkedHashMap<>(t.properties));
            textOut.add(message("announce", params));
            announced.put(t.id, t);
        }

        void unannounce(Topic t) {
            if (announced.remove(t.id) == null) return;
            sentSeq.remove(t.id);
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("name", t.name);
            params.put("id", t.id);
            textOut.add(message("unannounce", params));
        }

        // ---- the reader, on the connection's thread

        void readLoop(InputStream in) {
            ByteArrayOutputStream partial = new ByteArrayOutputStream();
            int[] partialOpcode = new int[1];
            try {
                while (open) {
                    WebSocket.Message m;
                    try {
                        m = WebSocket.readMessage(in, partial, partialOpcode);
                    } catch (SocketTimeoutException idle) {
                        break;
                    }
                    switch (m.opcode) {
                        case WebSocket.TEXT:
                            if (!rttOnly) onText(new String(m.payload, MsgPack.UTF8));
                            break;
                        case WebSocket.BINARY:
                            onBinary(m.payload);
                            break;
                        case WebSocket.PING:
                            send(WebSocket.PONG, m.payload, m.payload.length);
                            break;
                        case WebSocket.CLOSE:
                            send(WebSocket.CLOSE, m.payload, Math.min(m.payload.length, 2));
                            open = false;
                            break;
                        default:
                            break;
                    }
                }
            } catch (IOException ignored) {
            } finally {
                close();
            }
        }

        @SuppressWarnings("unchecked")
        private void onText(String text) {
            Object parsed;
            try {
                parsed = Json.parse(text);
            } catch (Json.ParseException e) {
                return;
            }
            if (!(parsed instanceof List)) return;
            synchronized (Nt4Server.this) {
                for (Object item : (List<Object>) parsed) {
                    if (!(item instanceof Map)) continue;
                    Map<String, Object> msg = (Map<String, Object>) item;
                    Object method = msg.get("method");
                    Object params = msg.get("params");
                    if (!(method instanceof String) || !(params instanceof Map)) continue;
                    handle((String) method, (Map<String, Object>) params);
                }
            }
            wake();
        }

        /** Under the server's lock. */
        @SuppressWarnings("unchecked")
        private void handle(String method, Map<String, Object> p) {
            switch (method) {
                case "subscribe": {
                    Object uid = p.get("subuid");
                    Object names = p.get("topics");
                    if (!(uid instanceof Long) || !(names instanceof List)) return;
                    List<String> list = new ArrayList<>();
                    for (Object n : (List<Object>) names) {
                        if (n instanceof String) list.add((String) n);
                    }
                    Map<String, Object> opts = p.get("options") instanceof Map
                            ? (Map<String, Object>) p.get("options") : new HashMap<String, Object>();
                    double period = opts.get("periodic") instanceof Number
                            ? ((Number) opts.get("periodic")).doubleValue() : DEFAULT_PERIOD_S;
                    Subscription s = new Subscription(list,
                            Boolean.TRUE.equals(opts.get("prefix")),
                            Boolean.TRUE.equals(opts.get("topicsonly")),
                            Math.max(period, MIN_PERIOD_S));
                    subscriptions.put((Long) uid, s);
                    for (Topic t : topics.values()) {
                        if (s.matches(t.name)) {
                            announce(t, null);
                            // A new value subscription gets the current value, as a new client does.
                            if (!s.topicsOnly) sentSeq.remove(t.id);
                        }
                    }
                    return;
                }
                case "unsubscribe": {
                    Object uid = p.get("subuid");
                    if (uid instanceof Long) subscriptions.remove(uid);
                    return;
                }
                case "publish": {
                    Object name = p.get("name");
                    Object uid = p.get("pubuid");
                    Object type = p.get("type");
                    if (!(name instanceof String) || !(uid instanceof Long) || !(type instanceof String)) return;
                    Map<String, Object> props = p.get("properties") instanceof Map
                            ? new LinkedHashMap<>((Map<String, Object>) p.get("properties"))
                            : new LinkedHashMap<String, Object>();
                    Topic t = topics.get(name);
                    if (t == null) t = createTopic((String) name, (String) type, props);
                    t.publishers++;
                    publishing.put((Long) uid, t);
                    announce(t, (Long) uid);
                    return;
                }
                case "unpublish": {
                    Object uid = p.get("pubuid");
                    Topic t = uid instanceof Long ? publishing.remove(uid) : null;
                    if (t == null) return;
                    t.publishers--;
                    dropIfUnused(t);
                    return;
                }
                case "setproperties": {
                    Object name = p.get("name");
                    Object update = p.get("update");
                    Topic t = name instanceof String ? topics.get(name) : null;
                    if (t == null || !(update instanceof Map)) return;
                    Map<String, Object> changed = new LinkedHashMap<>();
                    for (Map.Entry<String, Object> e : ((Map<String, Object>) update).entrySet()) {
                        if (e.getValue() == null) {
                            t.properties.remove(e.getKey());
                        } else {
                            t.properties.put(e.getKey(), e.getValue());
                        }
                        changed.put(e.getKey(), e.getValue());
                    }
                    for (Client c : clients) {
                        if (!c.announced.containsKey(t.id)) continue;
                        Map<String, Object> params = new LinkedHashMap<>();
                        params.put("name", t.name);
                        params.put("ack", c == this);
                        params.put("update", changed);
                        c.textOut.add(message("properties", params));
                        if (c != this) c.wake();
                    }
                    dropIfUnused(t);
                    return;
                }
                default:
                    return;
            }
        }

        private void onBinary(byte[] payload) throws IOException {
            MsgPack.Reader r = new MsgPack.Reader(payload, 0, payload.length);
            boolean changed = false;
            try {
                while (r.hasMore()) {
                    Object o = r.value();
                    if (!(o instanceof List) || ((List<?>) o).size() != 4) continue;
                    List<?> m = (List<?>) o;
                    if (!(m.get(0) instanceof Long) || !(m.get(2) instanceof Long)) continue;
                    long id = (Long) m.get(0);
                    if (id == -1) {
                        MsgPack.Writer w = new MsgPack.Writer();
                        w.arrayHeader(4).integer(-1).integer(nowMicros()).integer((Long) m.get(2));
                        Object clientTime = m.get(3);
                        if (clientTime instanceof Long) {
                            w.integer((Long) clientTime);
                        } else if (clientTime instanceof Double) {
                            w.float64((Double) clientTime);
                        } else {
                            w.integer(0);
                        }
                        send(WebSocket.BINARY, w.toByteArray(), w.size());
                        continue;
                    }
                    if (rttOnly) continue;
                    synchronized (Nt4Server.this) {
                        Topic t = publishing.get(id);
                        if (t == null || typeId(t.type) != ((Long) m.get(2)).intValue()) continue;
                        Object v = fromWire(typeId(t.type), m.get(3));
                        if (v == null) continue;
                        t.value = v;
                        long ts = m.get(1) instanceof Long ? (Long) m.get(1) : 0;
                        t.timestamp = ts > 0 ? ts : nowMicros();
                        t.seq++;
                        changed = true;
                    }
                }
            } catch (MsgPack.FormatException ignored) {
            }
            if (changed) wakeAll();
        }

        // ---- the writer, on its own thread

        private void writeLoop() {
            long nextValues = 0;
            long nextPing = System.currentTimeMillis() + PING_MS;
            try {
                while (open) {
                    long now = System.currentTimeMillis();
                    long periodMs;
                    String text = null;
                    byte[] binary = null;
                    int binaryLen = 0;
                    synchronized (Nt4Server.this) {
                        periodMs = Math.max(1, (long) (periodS() * 1000));
                        if (!textOut.isEmpty()) {
                            text = Json.write(textOut);
                            textOut.clear();
                        }
                        if (!rttOnly && now >= nextValues) {
                            nextValues = now + periodMs;
                            binaryOut.reset();
                            for (Topic t : announced.values()) {
                                if (t.value == null || !wantsValues(t.name)) continue;
                                Long sent = sentSeq.get(t.id);
                                if (sent != null && sent == t.seq) continue;
                                if (writeValue(binaryOut, t.id, t.timestamp, t.type, t.value)) {
                                    sentSeq.put(t.id, t.seq);
                                }
                            }
                            if (binaryOut.size() > 0) {
                                binary = binaryOut.toByteArray();
                                binaryLen = binary.length;
                            }
                        }
                    }
                    if (text != null) {
                        byte[] b = text.getBytes(MsgPack.UTF8);
                        send(WebSocket.TEXT, b, b.length);
                    }
                    if (binary != null) send(WebSocket.BINARY, binary, binaryLen);
                    if (pinged && now >= nextPing) {
                        nextPing = now + PING_MS;
                        send(WebSocket.PING, new byte[0], 0);
                    }
                    long until = Math.min(rttOnly ? Long.MAX_VALUE : nextValues, pinged ? nextPing : Long.MAX_VALUE);
                    long wait = Math.max(1, until - System.currentTimeMillis());
                    synchronized (wakeLock) {
                        if (!pending) wakeLock.wait(Math.min(wait, 1000));
                        pending = false;
                    }
                }
            } catch (IOException | InterruptedException ignored) {
            } finally {
                close();
            }
        }

        private void send(int opcode, byte[] payload, int length) throws IOException {
            synchronized (out) {
                WebSocket.writeFrame(out, opcode, payload, length);
                out.flush();
            }
        }

        void close() {
            open = false;
            synchronized (Nt4Server.this) {
                if (clients.remove(this)) {
                    for (Topic t : new ArrayList<>(publishing.values())) {
                        t.publishers--;
                        dropIfUnused(t);
                    }
                    publishing.clear();
                }
            }
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            wake();
        }
    }

    private static Map<String, Object> message(String method, Map<String, Object> params) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("method", method);
        m.put("params", params);
        return m;
    }

    /** For tests: the topic names the server holds. */
    synchronized List<String> topicNames() {
        return new ArrayList<>(topics.keySet());
    }

    @Override
    public String toString() {
        return "Nt4Server on port " + port();
    }
}
