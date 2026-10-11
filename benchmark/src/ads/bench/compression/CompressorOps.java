package ads.bench.compression;

/**
 * CONTRACT (Phase 0): the trade-telemetry batch compressor interface every candidate must
 * satisfy. Mirrors doc 05 §6: completed trade logs are batch-compressed for archival, and
 * decompression correctness matters more than squeezing out the last few percent of ratio.
 *
 * <p>Semantics (FROZEN):
 * <ul>
 *   <li>{@code encode(input)}: returns the compressed form of the batch. Any format is
 *       allowed — the ONLY requirement is that {@code decode(encode(input))} reproduces
 *       {@code input} byte-for-byte. That round-trip identity is the entire correctness
 *       gate for this module (stronger and simpler than an oracle-agreement pattern, and
 *       exactly the property audit logs need).</li>
 *   <li>{@code decode(encoded)}: exact inverse of {@code encode}.</li>
 *   <li>Empty input must round-trip (empty output or a decodable empty-payload form).</li>
 * </ul>
 */
public interface CompressorOps {
  byte[] encode(byte[] input);

  byte[] decode(byte[] encoded);
}
