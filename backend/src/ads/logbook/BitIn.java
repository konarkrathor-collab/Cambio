package ads.logbook;

/**
 * MSB-first bit reader mirroring {@link BitOut}, ported from the benchmark's {@code BitIn}.
 * Reads past the end return 0 bits, as before, but are now recorded so the decoder can
 * reject a truncated stream instead of silently decoding the zero padding (backend/BUGS.md,
 * B2). No JDK collections. Single-threaded use only.
 */
final class BitIn {
  private final byte[] data;
  private int pos;
  private int acc;
  private int nbits;
  private boolean overrun;

  BitIn(byte[] data) {
    this.data = data;
  }

  int readBit() {
    if (nbits == 0) {
      if (pos < data.length) {
        acc = data[pos++] & 0xFF;
      } else {
        acc = 0;
        overrun = true;
      }
      nbits = 8;
    }
    nbits--;
    return (acc >>> nbits) & 1;
  }

  /** Reads {@code count} bits (count <= 62) most significant first. */
  long readBits(int count) {
    long v = 0;
    for (int i = 0; i < count; i++) {
      v = (v << 1) | readBit();
    }
    return v;
  }

  /** True once any bit was read from beyond the end of the data. */
  boolean overrun() {
    return overrun;
  }
}
