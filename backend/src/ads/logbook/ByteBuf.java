package ads.logbook;

/**
 * Growable output byte buffer for the Huffman codec, ported from the benchmark's
 * {@code ByteBuf} with its unused methods dropped. Deliberately no JDK collections.
 * Single-threaded use only.
 */
final class ByteBuf {
  private byte[] data;
  private int len;

  ByteBuf(int initialCapacity) {
    data = new byte[Math.max(16, initialCapacity)];
  }

  void write(int b) {
    ensure(1);
    data[len++] = (byte) b;
  }

  private void ensure(int extra) {
    if (len + extra > data.length) {
      int ncap = Math.max(data.length * 2, len + extra);
      byte[] d = new byte[ncap];
      System.arraycopy(data, 0, d, 0, len);
      data = d;
    }
  }

  byte[] toBytes() {
    byte[] out = new byte[len];
    System.arraycopy(data, 0, out, 0, len);
    return out;
  }
}
