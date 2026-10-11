package ads.bench.compression;

/**
 * Growable output byte buffer shared by the compression candidates (extracted once
 * duplication appeared — the CycleTrace precedent). Deliberately NOT java.util: candidates
 * must not touch JDK containers per the fairness rules. Single-threaded use only.
 */
final class ByteBuf {
  private byte[] data;
  private int len;

  ByteBuf() {
    this(1024);
  }

  ByteBuf(int initialCapacity) {
    data = new byte[Math.max(16, initialCapacity)];
  }

  void write(int b) {
    ensure(1);
    data[len++] = (byte) b;
  }

  void write(byte[] src, int off, int n) {
    ensure(n);
    System.arraycopy(src, off, data, len, n);
    len += n;
  }

  private void ensure(int extra) {
    if (len + extra > data.length) {
      int ncap = Math.max(data.length * 2, len + extra);
      byte[] d = new byte[ncap];
      System.arraycopy(data, 0, d, 0, len);
      data = d;
    }
  }

  int size() {
    return len;
  }

  byte[] toBytes() {
    byte[] out = new byte[len];
    System.arraycopy(data, 0, out, 0, len);
    return out;
  }
}
