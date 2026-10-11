package ads.bench.compression;

/**
 * CANDIDATE (doc 05 §6, scored 3.60): LZW over 16-bit FIXED-width codes with the
 * dictionary frozen at 65,536 entries. The classic LZW correctness hazard is the
 * synchronized 9→12-bit code-width growth between encoder and decoder (they lag by one
 * entry); fixed-width codes remove that hazard entirely at a modest ratio cost — an
 * implementation simplification, documented rather than hidden. The dictionary is an
 * open-addressing hash on the 24-bit key (prefixCode&lt;&lt;8 | nextByte) sized for load
 * &le; 0.5; when full it freezes (no reset) and both sides stop adding at the same count.
 * No java.util.
 *
 * <p><b>NOTE on a bug the round-trip gate caught here</b> (found and fixed Run-4-style):
 * the first version read the stored code BEFORE confirming the slot matched the key —
 * empty slots carry 0 in the values array, so every lookup "hit" entry 0 and the encoder
 * emitted a single code for an entire 256 KB batch (decoded length: 1; FAIL row retained
 * in the raw CSV). The fix: test {@code encKeys[slot] == key} to distinguish a hit from an
 * empty slot. This is first-hand evidence for doc 05 §6's implementation-risk argument —
 * even textbook LZW hides a silent-garbage initialization bug that only a byte-for-byte
 * round-trip gate exposes, and the gate caught it before any number was reported.
 */
public final class LzwCompressor implements CompressorOps {
  private static final int MAX_ENTRIES = 65536;
  private static final int TABLE_SIZE = 1 << 17;
  private static final int MASK = TABLE_SIZE - 1;
  private static final int EMPTY = -1;

  // reused state (single-threaded instance)
  private final int[] encKeys = new int[TABLE_SIZE];
  private final int[] encVals = new int[TABLE_SIZE];
  private final int[] decPrefix = new int[MAX_ENTRIES];
  private final byte[] decSuffix = new byte[MAX_ENTRIES];
  private final byte[] decFirst = new byte[MAX_ENTRIES];
  private final byte[] reversal = new byte[MAX_ENTRIES];

  private static int hash(int key) {
    int h = key * 0x9E3779B1;
    h ^= h >>> 15;
    return h & MASK;
  }

  /** Slot where {@code key} lives, or the first empty slot on its probe path. */
  private int findSlot(int key) {
    int slot = hash(key);
    while (encKeys[slot] != EMPTY && encKeys[slot] != key) {
      slot = (slot + 1) & MASK;
    }
    return slot;
  }

  @Override
  public byte[] encode(byte[] input) {
    int n = input.length;
    if (n == 0) return new byte[0];
    for (int i = 0; i < TABLE_SIZE; i++) {
      encKeys[i] = EMPTY;
    }
    int nextCode = 256;
    ByteBuf out = new ByteBuf(n / 2 + 64);
    int prefix = input[0] & 0xFF;
    for (int i = 1; i < n; i++) {
      int b = input[i] & 0xFF;
      int key = (prefix << 8) | b;
      int slot = findSlot(key);
      if (encKeys[slot] == key) {
        prefix = encVals[slot]; // hit (empty slots are distinguished by the key test)
      } else {
        writeCode(out, prefix);
        if (nextCode < MAX_ENTRIES) {
          encKeys[slot] = key;
          encVals[slot] = nextCode++;
        }
        prefix = b;
      }
    }
    writeCode(out, prefix);
    return out.toBytes();
  }

  private static void writeCode(ByteBuf out, int code) {
    out.write(code >>> 8);
    out.write(code & 0xFF);
  }
  @Override
  public byte[] decode(byte[] encoded) {
    int n = encoded.length;
    if (n == 0) return new byte[0];
    int codeCount = n / 2;
    ByteBuf out = new ByteBuf(n * 2);
    for (int c = 0; c < 256; c++) {
      decPrefix[c] = -1;
      decSuffix[c] = (byte) c;
      decFirst[c] = (byte) c;
    }
    int nextCode = 256;
    int p = 0;
    int prev = readCode(encoded, p);
    p += 2;
    emitEntry(prev, out);
    for (int k = 1; k < codeCount; k++) {
      int code = readCode(encoded, p);
      p += 2;
      byte first;
      if (code < nextCode) {
        emitEntry(code, out);
        first = decFirst[code];
      } else if (code == nextCode) {
        // KwKwK case: the encoder referenced the entry it added after its previous emit
        first = decFirst[prev];
        emitEntry(prev, out);
        out.write(first);
      } else {
        throw new IllegalStateException("corrupt LZW stream: code " + code + " > next " + nextCode);
      }
      if (nextCode < MAX_ENTRIES) {
        decPrefix[nextCode] = prev;
        decSuffix[nextCode] = first;
        decFirst[nextCode] = decFirst[prev];
        nextCode++;
      }
      prev = code;
    }
    return out.toBytes();
  }

  private static int readCode(byte[] data, int p) {
    return ((data[p] & 0xFF) << 8) | (data[p + 1] & 0xFF);
  }

  /** Writes the string of {@code code} to out, walking the suffix chain back to a root. */
  private void emitEntry(int code, ByteBuf out) {
    int top = 0;
    int c = code;
    while (c >= 256) {
      reversal[top++] = decSuffix[c];
      c = decPrefix[c];
    }
    reversal[top++] = decSuffix[c]; // root symbol (c < 256)
    for (int i = top - 1; i >= 0; i--) {
      out.write(reversal[i]);
    }
  }
}
