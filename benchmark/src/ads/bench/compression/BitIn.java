package ads.bench.compression;

/**
 * MSB-first bit reader mirroring {@link BitOut}. Reads past the end return 0 bits (which
 * is safe for both consumers: the Huffman decoder stops after its recorded original
 * length, and the arithmetic decoder stops after its recorded symbol count — trailing pad
 * bits are never interpreted). No java.util. Single-threaded use only.
 */
final class BitIn {
  private final byte[] data;
  private int pos;
  private int acc;
  private int nbits;

  BitIn(byte[] data) {
    this.data = data;
  }

  int readBit() {
    if (nbits == 0) {
      acc = pos < data.length ? data[pos++] & 0xFF : 0;
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
}
