package ads.logbook;

/**
 * MSB-first bit writer built on {@link ByteBuf}, ported unchanged from the benchmark's
 * {@code BitOut}. No JDK collections. Single-threaded use only.
 */
final class BitOut {
  private final ByteBuf buf;
  private int acc;
  private int nbits;

  BitOut(ByteBuf buf) {
    this.buf = buf;
  }

  void writeBit(int bit) {
    acc = (acc << 1) | (bit & 1);
    nbits++;
    if (nbits == 8) {
      buf.write(acc & 0xFF);
      acc = 0;
      nbits = 0;
    }
  }

  /** Writes the low {@code count} bits of {@code value}, most significant first (count <= 62). */
  void writeBits(long value, int count) {
    for (int i = count - 1; i >= 0; i--) {
      writeBit((int) ((value >>> i) & 1L));
    }
  }

  /** Pads the final partial byte with zero bits. Must be called exactly once at the end. */
  void flush() {
    if (nbits > 0) {
      buf.write((acc << (8 - nbits)) & 0xFF);
      acc = 0;
      nbits = 0;
    }
  }
}
