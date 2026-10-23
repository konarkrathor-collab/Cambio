package ads.test;

import ads.bench.compression.CompressorOps;
import ads.logbook.HuffmanCodec;

/** Presents the production {@link HuffmanCodec} as the benchmark's {@link CompressorOps}. */
final class CompressorAdapter implements CompressorOps {
  private final HuffmanCodec codec = new HuffmanCodec();

  @Override
  public byte[] encode(byte[] input) {
    return codec.encode(input);
  }

  @Override
  public byte[] decode(byte[] encoded) {
    return codec.decode(encoded);
  }
}
