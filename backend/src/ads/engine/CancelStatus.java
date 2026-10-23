package ads.engine;

/** Outcome of a cancel: only resting orders can be cancelled; anything else is NOT_FOUND. */
public enum CancelStatus {
  CANCELLED,
  NOT_FOUND
}
