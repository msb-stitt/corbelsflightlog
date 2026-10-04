package io.github.mikestitt.corbelsflightlog.checks;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Starts and stops an OpMode the way Panels' OpModes Control does, by sending
 * its commands over Panels' own WebSocket, so no browser, layout or screen
 * position is involved.
 *
 * <p>Read in the sources of {@code com.bylazar:panels} 1.0.5 and
 * {@code opmodecontrol} 1.0.3, which {@code fullpanels} 1.0.13 uses: the socket
 * is on port 8002, {@code /health} answers {@code OK}, and every message is
 * {@code {pluginID, messageID, data}}. To plugin
 * {@code com.bylazar.opmodecontrol}, {@code initOpMode} with the OpMode's name,
 * {@code startActiveOpMode} and {@code stopActiveOpMode} call the SDK's
 * {@code OpModeManagerImpl}; it answers {@code activeOpMode} with
 * {@code INIT}, {@code RUNNING} or {@code STOPPED} at each change.
 */
final class PanelsControl implements AutoCloseable {

    static final int PORT = 8002;
    private static final String PLUGIN = "com.bylazar.opmodecontrol";
    private static final Pattern STATUS = Pattern.compile("\"status\":\"([A-Z]+)\"");
    private static final Pattern NAME = Pattern.compile("\"name\":\"((?:[^\"\\\\]|\\\\.)*)\"");

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final Random random = new Random();
    private String opModes = "";
    private String status = "";
    private String active = "";

    private PanelsControl(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new BufferedInputStream(socket.getInputStream());
        this.out = socket.getOutputStream();
    }

    /** True once Panels' socket answers its health check, waiting up to {@code ms}. */
    static boolean waitForHealth(String host, long ms) throws InterruptedException {
        long until = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < until) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(
                        "http://" + host + ":" + PORT + "/health").openConnection();
                c.setConnectTimeout(2000);
                c.setReadTimeout(2000);
                if (c.getResponseCode() == 200) return true;
            } catch (IOException e) {
                // not up yet
            }
            Thread.sleep(1000);
        }
        return false;
    }

    static PanelsControl connect(String host) throws IOException {
        Socket s = new Socket(host, PORT);
        s.setSoTimeout(5000);
        s.getOutputStream().write(("GET / HTTP/1.1\r\nHost: " + host + "\r\nUpgrade: websocket\r\n"
                + "Connection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        PanelsControl p = new PanelsControl(s);
        String status = p.line();
        if (!status.contains(" 101")) throw new IOException("Panels did not upgrade: " + status);
        while (!p.line().isEmpty()) {
            // the rest of the response head
        }
        // What Panels sends a new client, so a state reported later is news.
        p.read(1000);
        return p;
    }

    /**
     * True if Panels lists an OpMode of that name, waiting up to 5 s for the list.
     *
     * <p>Connect afresh for each command: a client left unread while a check
     * runs has had its socket closed by Panels.
     */
    boolean lists(String name) throws IOException {
        long until = System.currentTimeMillis() + 5000;
        while (opModes.isEmpty() && System.currentTimeMillis() < until) read(100);
        return opModes.contains("\"name\":\"" + name + "\"");
    }

    /** Initialises then starts the OpMode, and says whether each state was reported in 30 s. */
    boolean run(String name) throws IOException {
        send("initOpMode", "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
        if (!await("INIT", name, 30_000)) return false;
        send("startActiveOpMode", "null");
        return await("RUNNING", name, 30_000);
    }

    /** Stops the active OpMode, and says whether STOPPED was reported in 30 s. */
    boolean stop() throws IOException {
        send("stopActiveOpMode", "null");
        return await("STOPPED", null, 30_000);
    }

    /** The last state Panels reported, for a failure message. */
    String state() {
        return status + " " + active;
    }

    private boolean await(String want, String name, long ms) throws IOException {
        long until = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < until) {
            if (want.equals(status) && (name == null || name.equals(active))) return true;
            read(100);
        }
        return false;
    }

    private void send(String type, String data) throws IOException {
        frame(0x1, ("{\"pluginID\":\"" + PLUGIN + "\",\"messageID\":\"" + type + "\",\"data\":"
                + data + "}").getBytes(StandardCharsets.UTF_8));
    }

    /** Reads messages for up to {@code ms}, keeping the plugin's latest list and state. */
    private void read(long ms) throws IOException {
        long until = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < until) {
            String m = message();
            if (m == null || !m.contains("\"pluginID\":\"" + PLUGIN + "\"")) continue;
            if (m.contains("\"messageID\":\"opModesList\"")) opModes = m;
            if (m.contains("\"messageID\":\"activeOpMode\"")) {
                Matcher s = STATUS.matcher(m);
                Matcher n = NAME.matcher(m);
                status = s.find() ? s.group(1) : "";
                active = n.find() ? n.group(1) : "";
            }
        }
    }

    /** One text message, or null after 50 ms with nothing. Answers pings. */
    private String message() throws IOException {
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        while (true) {
            int b0;
            socket.setSoTimeout(50);
            try {
                b0 = in.read();
            } catch (SocketTimeoutException e) {
                if (text.size() == 0) return null;
                continue;
            }
            if (b0 < 0) throw new IOException("Panels closed the socket");
            socket.setSoTimeout(5000);
            int b1 = in.read();
            long len = b1 & 0x7f;
            if (len == 126) len = (in.read() << 8) | in.read();
            if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) len = (len << 8) | in.read();
            }
            byte[] payload = new byte[(int) len];
            for (int got = 0; got < len; ) {
                int r = in.read(payload, got, (int) len - got);
                if (r < 0) throw new IOException("Panels closed the socket");
                got += r;
            }
            int opcode = b0 & 0x0f;
            if (opcode == 0x9) {
                frame(0xA, payload);
            } else if (opcode == 0x8) {
                throw new IOException("Panels closed the socket");
            } else if (opcode == 0x1 || opcode == 0x0) {
                text.write(payload);
                if ((b0 & 0x80) != 0) return new String(text.toByteArray(), StandardCharsets.UTF_8);
            }
        }
    }

    /** Sends one frame, masked as a client's must be. */
    private void frame(int opcode, byte[] payload) throws IOException {
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        f.write(0x80 | opcode);
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

    private String line() throws IOException {
        StringBuilder s = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0 && c != '\n') {
            if (c != '\r') s.append((char) c);
        }
        return s.toString();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
