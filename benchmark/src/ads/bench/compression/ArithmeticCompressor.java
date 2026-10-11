package ads.bench.compression;

/**
 * CANDIDATE (doc 05 §6 — the optional 4th, included precisely because doc 05's own
 * argument concedes that arithmetic/ANS "compress better than Huffman in principle"):
 * order-0 arithmetic coding with 32-bit low/high registers and the classic bit-level
 * renormalization (E1/E2/E3) from the Witten-Neal-Cleary formulation. Header: [4-byte
 * big-endian length][256 × 4-byte frequencies]; the decoder uses the EXACT same frequency
 * table — no requantization, hence no quantization bugs. Measured beside Huffman, this
 * quantifies the "fractional bits vs integer-length codes" claim on real data; the ratio
 * gain is then weighed against implementation risk, which is doc 05 §6's actual argument
 * for keeping Huffman.
 */
public final class ArithmeticCompressor implements CompressorOps {
  private static final long HALF = 0x80000000L;
  private static final long QUARTER = 0x40000000L;
  private static final long THREE_QUARTERS = 0xC0000000L;

  @Override
  public byte[] encode(byte[] input) {
    int n = input.length;
    if (n == 0) return new byte[0];
    int[] freq = new int[256];
    for (int i = 0; i < n; i++) {
      freq[input[i] & 0xFF]++;
    }
    int[] cum = new int[257];
    for (int s = 0; s < 256; s++) {
      cum[s + 1] = cum[s] + freq[s];
    }
    long total = cum[256];

    ByteBuf raw = new ByteBuf(n + 1100);
    raw.write((n >>> 24) & 0xFF);
    raw.write((n >>> 16) & 0xFF);
    raw.write((n >>> 8) & 0xFF);
    raw.write(n & 0xFF);
    for (int s = 0; s < 256; s++) {
      int v = freq[s];
      raw.write((v >>> 24) & 0xFF);
      raw.write((v >>> 16) & 0xFF);
      raw.write((v >>> 8) & 0xFF);
      raw.write(v & 0xFF);
    }
    BitOut bits = new BitOut(raw);

    long low = 0;
    long high = 0xFFFFFFFFL;
    int pending = 0;
    for (int i = 0; i < n; i++) {
      int s = input[i] & 0xFF;
      long range = high - low + 1;
      high = low + (range * cum[s + 1]) / total - 1;
      low = low + (range * cum[s]) / total;
      while (true) {
        if (high < HALF) {
          emitWithPending(bits, 0, pending);
          pending = 0;
        } else if (low >= HALF) {
          emitWithPending(bits, 1, pending);
          pending = 0;
          low -= HALF;
          high -= HALF;
        } else if (low >= QUARTER && high < THREE_QUARTERS) {
          pending++;
          low -= QUARTER;
          high -= QUARTER;
        } else {
          break;
        }
        low = (low << 1) & 0xFFFFFFFFL;
        high = ((high << 1) | 1L) & 0xFFFFFFFFL;
      }
    }
    pending++;
    if (low < QUARTER) {
      emitWithPending(bits, 0, pending);
    } else {
      emitWithPending(bits, 1, pending);
    }
    bits.flush();
    return raw.toBytes();
  }

  /** Emits {@code bit}, then {@code pending} copies of its complement (the E3 debt). */
  private static void emitWithPending(BitOut bits, int bit, int pending) {
    bits.writeBit(bit);
    for (int i = 0; i < pending; i++) {
      bits.writeBit(1 - bit);
    }
  }
  @Override
  public byte[] decode(byte[] encoded) {
    if (encoded.length == 0) return new byte[0];
    BitIn in = new BitIn(encoded);
    long origLen = in.readBits(32);
    int[] freq = new int[256];
    for (int s = 0; s < 256; s++) {
      freq[s] = (int) in.readBits(32);
    }
    if (origLen == 0) return new byte[0];
    int[] cum = new int[257];
    for (int s = 0; s < 256; s++) {
      cum[s + 1] = cum[s] + freq[s];
    }
    long total = cum[256];
    long low = 0;
    long high = 0xFFFFFFFFL;
    long value = in.readBits(32);
    ByteBuf out = new ByteBuf((int) origLen + 16);
    for (long o = 0; o < origLen; o++) {
      long range = high - low + 1;
      long scaled = ((value - low + 1) * total - 1) / range;
      int s = findSymbol(scaled, cum);
      out.write(s);
      high = low + (range * cum[s + 1]) / total - 1;
      low = low + (range * cum[s]) / total;
      while (true) {
        if (high < HALF) {
          // E1: nothing to subtract
        } else if (low >= HALF) {
          value -= HALF;
          low -= HALF;
          high -= HALF;
        } else if (low >= QUARTER && high < THREE_QUARTERS) {
          value -= QUARTER;
          low -= QUARTER;
          high -= QUARTER;
        } else {
          break;
        }
        low = (low << 1) & 0xFFFFFFFFL;
        high = ((high << 1) | 1L) & 0xFFFFFFFFL;
        value = ((value << 1) | in.readBit()) & 0xFFFFFFFFL;
      }
    }
    return out.toBytes();
  }

  /** Largest s with cum[s] &lt;= scaled (scaled &lt; total guarantees s &lt;= 255). */
  private static int findSymbol(long scaled, int[] cum) {
    int lo = 0;
    int hi = 256;
    while (lo < hi) {
      int mid = (lo + hi + 1) >>> 1;
      if (cum[mid] <= scaled) lo = mid;
      else hi = mid - 1;
    }
    return Math.min(lo, 255);
  }
}
