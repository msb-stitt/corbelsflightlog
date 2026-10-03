package io.github.mikestitt.corbelsflightlog.nt;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The server side of RFC 6455 that NetworkTables 4 needs: reading an HTTP
 * request, the upgrade handshake, and reading and writing frames. Written
 * against {@code java.io} so it runs on Android API 24, which has no
 * {@code java.util.Base64}.
 */
final class WebSocket {

    static final int CONTINUATION = 0x0;
    static final int TEXT = 0x1;
    static final int BINARY = 0x2;
    static final int CLOSE = 0x8;
    static final int PING = 0x9;
    static final int PONG = 0xA;

    /** A message larger than this closes the connection. */
    static final int MAX_MESSAGE_BYTES = 16 * 1024 * 1024;

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private WebSocket() {
    }

    /** An HTTP request line and its headers, names in lower case. */
    static final class Request {
        final String method;
        final String path;
        final Map<String, String> headers;

        Request(String method, String path, Map<String, String> headers) {
            this.method = method;
            this.path = path;
            this.headers = headers;
        }

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        boolean isUpgrade() {
            String upgrade = header("upgrade");
            return upgrade != null && upgrade.equalsIgnoreCase("websocket") && header("sec-websocket-key") != null;
        }
    }

    /** Reads an HTTP request's head, up to and including the blank line. */
    static Request readRequest(InputStream in) throws IOException {
        String requestLine = line(in);
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) throw new IOException("not an HTTP request: " + requestLine);
        Map<String, String> headers = new LinkedHashMap<>();
        for (int count = 0; ; count++) {
            if (count > 100) throw new IOException("too many headers");
            String h = line(in);
            if (h.isEmpty()) break;
            int colon = h.indexOf(':');
            if (colon <= 0) continue;
            headers.put(h.substring(0, colon).trim().toLowerCase(Locale.ROOT), h.substring(colon + 1).trim());
        }
        return new Request(parts[0], parts[1], headers);
    }

    private static String line(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int c = in.read();
            if (c < 0) throw new EOFException("connection closed in the HTTP head");
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) throw new IOException("HTTP line too long");
        }
        return sb.toString();
    }

    /** The {@code Sec-WebSocket-Accept} value for a client's key. */
    static String acceptKey(String clientKey) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            return base64(sha1.digest((clientKey.trim() + GUID).getBytes(MsgPack.UTF8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is missing", e);
        }
    }

    private static final char[] B64 =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    static String base64(byte[] data) {
        StringBuilder sb = new StringBuilder((data.length + 2) / 3 * 4);
        for (int i = 0; i < data.length; i += 3) {
            int b0 = data[i] & 0xff;
            int b1 = i + 1 < data.length ? data[i + 1] & 0xff : 0;
            int b2 = i + 2 < data.length ? data[i + 2] & 0xff : 0;
            sb.append(B64[b0 >>> 2]);
            sb.append(B64[((b0 & 0x03) << 4) | (b1 >>> 4)]);
            sb.append(i + 1 < data.length ? B64[((b1 & 0x0f) << 2) | (b2 >>> 6)] : '=');
            sb.append(i + 2 < data.length ? B64[b2 & 0x3f] : '=');
        }
        return sb.toString();
    }

    /** One whole message, its continuation frames joined. */
    static final class Message {
        final int opcode;
        final byte[] payload;

        Message(int opcode, byte[] payload) {
            this.opcode = opcode;
            this.payload = payload;
        }
    }

    /**
     * Reads frames until a whole message is in hand. Control frames that
     * arrive between the parts of a fragmented message are returned on their
     * own, and the parts read so far are kept in {@code partial}.
     */
    static Message readMessage(InputStream in, ByteArrayOutputStream partial, int[] partialOpcode)
            throws IOException {
        while (true) {
            int b0 = readByte(in);
            int b1 = readByte(in);
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0f;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7f;
            if (len == 126) {
                len = (readByte(in) << 8) | readByte(in);
            } else if (len == 127) {
                len = 0;
                for (int k = 0; k < 8; k++) len = (len << 8) | readByte(in);
            }
            if (len < 0 || len > MAX_MESSAGE_BYTES || partial.size() + len > MAX_MESSAGE_BYTES) {
                throw new IOException("message larger than " + MAX_MESSAGE_BYTES + " bytes");
            }
            byte[] mask = new byte[4];
            if (masked) readFully(in, mask);
            byte[] payload = new byte[(int) len];
            readFully(in, payload);
            if (masked) {
                for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
            }
            if (opcode >= 0x8) return new Message(opcode, payload);
            if (opcode != CONTINUATION) {
                partial.reset();
                partialOpcode[0] = opcode;
            }
            if (fin && opcode != CONTINUATION) return new Message(opcode, payload);
            partial.write(payload, 0, payload.length);
            if (fin) {
                byte[] whole = partial.toByteArray();
                partial.reset();
                return new Message(partialOpcode[0], whole);
            }
        }
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new EOFException("connection closed");
        return b;
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new EOFException("connection closed");
            off += n;
        }
    }

    /** Writes one unmasked, final frame, as a server does. */
    static void writeFrame(OutputStream out, int opcode, byte[] payload, int length) throws IOException {
        byte[] head = new byte[10];
        int h = 0;
        head[h++] = (byte) (0x80 | opcode);
        if (length < 126) {
            head[h++] = (byte) length;
        } else if (length < 0x10000) {
            head[h++] = 126;
            head[h++] = (byte) (length >>> 8);
            head[h++] = (byte) length;
        } else {
            head[h++] = 127;
            for (int k = 7; k >= 0; k--) head[h++] = (byte) (k >= 4 ? 0 : length >>> (8 * k));
        }
        out.write(head, 0, h);
        out.write(payload, 0, length);
    }
}
