package ads.bench.compression;

/**
 * CANDIDATE (doc 05 §6, scored 2.55): byte-oriented run-length encoding in its standard
 * PackBits form — literal runs (header 0..127 = next N+1 raw bytes) interleaved with
 * repeat runs (header 129..255 = repeat next byte 257-header times). PackBits is the
 * canonical RLE for arbitrary byte streams; a naive count-byte-pair RLE would 2x INFLATE
 * any non-run text and make the comparison uninformative. Log text has only occasional
 * runs (digit sequences, repeated delimiters), so the expected result is a ratio near or
 * slightly above 1 — RLE as the honest floor, not a strawman.
 */
public final class RleCompressor implements CompressorOps {
  @Override
  public byte[] encode(byte[] input) {
    if (input.length == 0) return new byte[0];
    ByteBuf out = new ByteBuf(input.length + 64);
    int i = 0;
    int n = input.length;
    while (i < n) {
      // measure run of equal bytes at i (max 128)
      int run = 1;
      while (i + run < n && input[i + run] == input[i] && run < 128) {
        run++;
      }
      if (run >= 2) {
        out.write(1 - run); // header 129..255 -> repeat (1-run)&0xFF times
        out.write(input[i]);
        i += run;
      } else {
        // literal run: collect until the next real run begins (max 128)
        int start = i;
        i++;
        while (i < n && i - start < 128) {
          if (i + 1 < n && input[i] == input[i + 1]) break; // a repeat run starts here
          i++;
        }
        int lit = i - start;
        out.write(lit - 1); // header 0..127 -> lit literal bytes
        out.write(input, start, lit);
      }
    }
    return out.toBytes();
  }

  @Override
  public byte[] decode(byte[] encoded) {
    ByteBuf out = new ByteBuf(Math.max(64, encoded.length * 2));
    int i = 0;
    int n = encoded.length;
    while (i < n) {
      int header = encoded[i++];
      if (header >= 0) {
        int lit = header + 1;
        out.write(encoded, i, lit);
        i += lit;
      } else {
        int rep = 1 - header;
        byte b = encoded[i++];
        for (int r = 0; r < rep; r++) {
          out.write(b);
        }
      }
    }
    return out.toBytes();
  }
}
