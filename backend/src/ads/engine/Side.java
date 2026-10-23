package ads.engine;

/** Order side. A BUY rests on the bid ladder and matches asks; a SELL mirrors it. */
public enum Side {
  BUY,
  SELL;

  public Side opposite() {
    return this == BUY ? SELL : BUY;
  }
}
