package io.github.mikestitt.corbelsflightlog.nt;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The MessagePack subset NetworkTables 4 value messages use. The spec says this
 * subset "is small enough that a custom encoder/decoder can be easily
 * implemented", and so it is: nil, booleans, integers, 32 and 64 bit floats,
 * strings, binary and arrays, plus maps on reading so a message carrying one is
 * skipped rather than misread.
 */
final class MsgPack {

    static final Charset UTF8 = Charset.forName("UTF-8");

    private MsgPack() {
    }

    /** A growable byte buffer that values are appended to. */
    static final class Writer {
        private byte[] buf = new byte[256];
        private int n;

        int size() {
            return n;
        }

        byte[] toByteArray() {
            return Arrays.copyOf(buf, n);
        }

        void reset() {
            n = 0;
        }

        private void room(int more) {
            if (n + more > buf.length) buf = Arrays.copyOf(buf, Math.max(buf.length * 2, n + more));
        }

        private void b(int v) {
            room(1);
            buf[n++] = (byte) v;
        }

        private void be(long v, int bytes) {
            room(bytes);
            for (int k = bytes - 1; k >= 0; k--) buf[n++] = (byte) (v >>> (8 * k));
        }

        Writer arrayHeader(int size) {
            if (size < 16) {
                b(0x90 | size);
            } else if (size < 0x10000) {
                b(0xdc);
                be(size, 2);
            } else {
                b(0xdd);
                be(size, 4);
            }
            return this;
        }

        Writer nil() {
            b(0xc0);
            return this;
        }

        Writer bool(boolean v) {
            b(v ? 0xc3 : 0xc2);
            return this;
        }

        Writer integer(long v) {
            if (v >= 0) {
                if (v < 128) {
                    b((int) v);
                } else if (v < 0x100) {
                    b(0xcc);
                    be(v, 1);
                } else if (v < 0x10000) {
                    b(0xcd);
                    be(v, 2);
                } else if (v < 0x100000000L) {
                    b(0xce);
                    be(v, 4);
                } else {
                    b(0xcf);
                    be(v, 8);
                }
            } else {
                if (v >= -32) {
                    b((int) v & 0xff);
                } else if (v >= Byte.MIN_VALUE) {
                    b(0xd0);
                    be(v, 1);
                } else if (v >= Short.MIN_VALUE) {
                    b(0xd1);
                    be(v, 2);
                } else if (v >= Integer.MIN_VALUE) {
                    b(0xd2);
                    be(v, 4);
                } else {
                    b(0xd3);
                    be(v, 8);
                }
            }
            return this;
        }

        Writer float64(double v) {
            b(0xcb);
            be(Double.doubleToRawLongBits(v), 8);
            return this;
        }

        Writer float32(float v) {
            b(0xca);
            be(Float.floatToRawIntBits(v) & 0xffffffffL, 4);
            return this;
        }

        Writer string(String s) {
            byte[] bytes = s.getBytes(UTF8);
            int len = bytes.length;
            if (len < 32) {
                b(0xa0 | len);
            } else if (len < 0x100) {
                b(0xd9);
                be(len, 1);
            } else if (len < 0x10000) {
                b(0xda);
                be(len, 2);
            } else {
                b(0xdb);
                be(len, 4);
            }
            raw(bytes);
            return this;
        }

        Writer bin(byte[] bytes) {
            int len = bytes.length;
            if (len < 0x100) {
                b(0xc4);
                be(len, 1);
            } else if (len < 0x10000) {
                b(0xc5);
                be(len, 2);
            } else {
                b(0xc6);
                be(len, 4);
            }
            raw(bytes);
            return this;
        }

        private void raw(byte[] bytes) {
            room(bytes.length);
            System.arraycopy(bytes, 0, buf, n, bytes.length);
            n += bytes.length;
        }
    }

    /** Thrown for bytes that are not the MessagePack this reads. */
    static final class FormatException extends Exception {
        FormatException(String message) {
            super(message);
        }
    }

    /**
     * Reads values one after another from a byte array. Integers come back as
     * {@code Long}, 32 bit floats as {@code Float}, 64 bit floats as
     * {@code Double}, strings as {@code String}, binary as {@code byte[]},
     * arrays as {@code List<Object>} and maps as {@code Map<Object, Object>}.
     */
    static final class Reader {
        private final byte[] buf;
        private int i;
        private final int end;

        Reader(byte[] buf, int offset, int length) {
            this.buf = buf;
            this.i = offset;
            this.end = offset + length;
        }

        boolean hasMore() {
            return i < end;
        }

        private int u8() throws FormatException {
            if (i >= end) throw new FormatException("ran out of bytes");
            return buf[i++] & 0xff;
        }

        private long be(int bytes) throws FormatException {
            long v = 0;
            for (int k = 0; k < bytes; k++) v = (v << 8) | u8();
            return v;
        }

        private byte[] take(long len) throws FormatException {
            if (len < 0 || len > end - i) throw new FormatException("a length runs past the end");
            byte[] out = Arrays.copyOfRange(buf, i, i + (int) len);
            i += (int) len;
            return out;
        }

        Object value() throws FormatException {
            int t = u8();
            if (t <= 0x7f) return (long) t;
            if (t >= 0xe0) return (long) (byte) t;
            if ((t & 0xf0) == 0x90) return array(t & 0x0f);
            if ((t & 0xf0) == 0x80) return map(t & 0x0f);
            if ((t & 0xe0) == 0xa0) return new String(take(t & 0x1f), UTF8);
            switch (t) {
                case 0xc0: return null;
                case 0xc2: return Boolean.FALSE;
                case 0xc3: return Boolean.TRUE;
                case 0xc4: return take(be(1));
                case 0xc5: return take(be(2));
                case 0xc6: return take(be(4));
                case 0xca: return Float.intBitsToFloat((int) be(4));
                case 0xcb: return Double.longBitsToDouble(be(8));
                case 0xcc: return be(1);
                case 0xcd: return be(2);
                case 0xce: return be(4);
                case 0xcf: return be(8);
                case 0xd0: return (long) (byte) be(1);
                case 0xd1: return (long) (short) be(2);
                case 0xd2: return (long) (int) be(4);
                case 0xd3: return be(8);
                case 0xd9: return new String(take(be(1)), UTF8);
                case 0xda: return new String(take(be(2)), UTF8);
                case 0xdb: return new String(take(be(4)), UTF8);
                case 0xdc: return array((int) be(2));
                case 0xdd: return array((int) be(4));
                case 0xde: return map((int) be(2));
                case 0xdf: return map((int) be(4));
                default:
                    throw new FormatException(String.format("type byte 0x%02x is not read here", t));
            }
        }

        private List<Object> array(int size) throws FormatException {
            if (size < 0 || size > end - i) throw new FormatException("an array runs past the end");
            List<Object> list = new ArrayList<>(size);
            for (int k = 0; k < size; k++) list.add(value());
            return list;
        }

        private Map<Object, Object> map(int size) throws FormatException {
            if (size < 0 || size > end - i) throw new FormatException("a map runs past the end");
            Map<Object, Object> map = new LinkedHashMap<>();
            for (int k = 0; k < size; k++) map.put(value(), value());
            return map;
        }
    }
}
