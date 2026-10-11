package ads.bench.compression;

/**
 * CANDIDATE (doc 05 §6, THE PROJECT PICK): static (order-0) Huffman coding with canonical
 * codes. Per batch: byte frequencies → Huffman tree (min-heap) → canonical code assignment;
 * the encoded stream is [4-byte big-endian original length][256 one-byte code lengths]
 * [MSB-first packed body]. The decoder rebuilds identical canonical codes from the length
 * table alone — no tree is transmitted. Provably optimal WITHIN integer-length prefix
 * codes, and simple enough to be byte-perfect — which is exactly why doc 05 picks it, and
 * exactly what the round-trip gate holds it to.
 */
public final class HuffmanCompressor implements CompressorOps {
  private static final int MAX_LEN = 62; // codes travel through longs; defended in computeLengths

  @Override
  public byte[] encode(byte[] input) {
    int n = input.length;
    if (n == 0) return new byte[0];
    int[] freq = new int[256];
    for (int i = 0; i < n; i++) {
      freq[input[i] & 0xFF]++;
    }

    // --- Huffman tree: leaves are symbols 0..255, internal nodes follow in f[]/left[]/right[]
    int[] f = new int[512];
    int[] left = new int[512];
    int[] right = new int[512];
    int[] heap = new int[257];
    int hsize = 0;
    for (int s = 0; s < 256; s++) {
      if (freq[s] > 0) {
        f[s] = freq[s];
        left[s] = -1;
        right[s] = -1;
        heapPush(heap, hsize++, s, f);
      }
    }
    int nodes = 256;
    while (hsize > 1) {
      int a = heapPop(heap, --hsize, f);
      int b = heapPop(heap, --hsize, f);
      f[nodes] = f[a] + f[b];
      left[nodes] = a;
      right[nodes] = b;
      heapPush(heap, hsize++, nodes, f);
      nodes++;
    }
    int root = heap[0];
    int[] lengths = new int[256];
    computeLengths(root, 0, left, right, lengths);

    // --- canonical code assignment (decoder rebuilds this from lengths alone)
    int maxLen = 0;
    for (int s = 0; s < 256; s++) {
      if (lengths[s] > maxLen) maxLen = lengths[s];
    }
    int[] count = new int[MAX_LEN + 1];
    for (int s = 0; s < 256; s++) {
      if (lengths[s] > 0) count[lengths[s]]++;
    }
    long[] firstCode = new long[MAX_LEN + 1];
    long code = 0;
    for (int len = 1; len <= maxLen; len++) {
      code = (code + count[len - 1]) << 1;
      firstCode[len] = code;
    }
    long[] nextCode = new long[MAX_LEN + 1];
    System.arraycopy(firstCode, 0, nextCode, 0, nextCode.length);
    long[] codes = new long[256];
    for (int s = 0; s < 256; s++) {
      int len = lengths[s];
      if (len > 0) codes[s] = nextCode[len]++;
    }

    // --- stream: header then MSB-first packed body
    ByteBuf raw = new ByteBuf(n / 2 + 320);
    raw.write((n >>> 24) & 0xFF);
    raw.write((n >>> 16) & 0xFF);
    raw.write((n >>> 8) & 0xFF);
    raw.write(n & 0xFF);
    for (int s = 0; s < 256; s++) {
      raw.write(lengths[s]);
    }
    BitOut bits = new BitOut(raw);
    for (int i = 0; i < n; i++) {
      int b = input[i] & 0xFF;
      bits.writeBits(codes[b], lengths[b]);
    }
    bits.flush();
    return raw.toBytes();
  }

  private static void heapPush(int[] heap, int size, int v, int[] f) {
    heap[size] = v;
    int i = size;
    while (i > 0) {
      int parent = (i - 1) >>> 1;
      if (f[heap[parent]] <= f[heap[i]]) break;
      int t = heap[parent];
      heap[parent] = heap[i];
      heap[i] = t;
      i = parent;
    }
  }

  /** Removes the minimum; {@code newSize} is the heap size AFTER removal. */
  private static int heapPop(int[] heap, int newSize, int[] f) {
    int top = heap[0];
    heap[0] = heap[newSize];
    int i = 0;
    while (true) {
      int l = 2 * i + 1;
      int r = 2 * i + 2;
      int sm = i;
      if (l < newSize && f[heap[l]] < f[heap[sm]]) sm = l;
      if (r < newSize && f[heap[r]] < f[heap[sm]]) sm = r;
      if (sm == i) break;
      int t = heap[sm];
      heap[sm] = heap[i];
      heap[i] = t;
      i = sm;
    }
    return top;
  }

  private static void computeLengths(int node, int depth, int[] left, int[] right,
      int[] lengths) {
    if (left[node] == -1) {
      if (depth > MAX_LEN) {
        throw new IllegalStateException("pathological frequencies: code length > " + MAX_LEN);
      }
      lengths[node] = Math.max(1, depth); // single-symbol input: root is a leaf at depth 0
      return;
    }
    computeLengths(left[node], depth + 1, left, right, lengths);
    computeLengths(right[node], depth + 1, left, right, lengths);
  }
  @Override
  public byte[] decode(byte[] encoded) {
    if (encoded.length == 0) return new byte[0];
    BitIn in = new BitIn(encoded);
    long origLen = in.readBits(32);
    int[] lengths = new int[256];
    int maxLen = 0;
    for (int s = 0; s < 256; s++) {
      lengths[s] = (int) in.readBits(8);
      if (lengths[s] > maxLen) maxLen = lengths[s];
    }
    if (origLen == 0) return new byte[0];
    int[] count = new int[MAX_LEN + 1];
    for (int s = 0; s < 256; s++) {
      if (lengths[s] > 0) count[lengths[s]]++;
    }
    long[] firstCode = new long[MAX_LEN + 1];
    long code = 0;
    for (int len = 1; len <= maxLen; len++) {
      code = (code + count[len - 1]) << 1;
      firstCode[len] = code;
    }
    int[] firstIndex = new int[MAX_LEN + 1];
    int total = 0;
    for (int len = 1; len <= maxLen; len++) {
      firstIndex[len] = total;
      total += count[len];
    }
    int[] symbols = new int[total];
    int[] cursor = new int[MAX_LEN + 1];
    System.arraycopy(firstIndex, 0, cursor, 0, cursor.length);
    for (int s = 0; s < 256; s++) {
      int len = lengths[s];
      if (len > 0) symbols[cursor[len]++] = s;
    }
    ByteBuf out = new ByteBuf((int) origLen + 16);
    for (long o = 0; o < origLen; o++) {
      long acc = 0;
      int sym = -1;
      for (int len = 1; len <= maxLen; len++) {
        acc = (acc << 1) | in.readBit();
        long idx = acc - firstCode[len];
        if (idx >= 0 && idx < count[len]) {
          sym = symbols[firstIndex[len] + (int) idx];
          break;
        }
      }
      if (sym < 0) {
        throw new IllegalStateException("corrupt Huffman stream");
      }
      out.write(sym);
    }
    return out.toBytes();
  }
}
