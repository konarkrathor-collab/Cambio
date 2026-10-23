package ads.engine;

/** Receives every trade as the engine executes it, in execution order (e.g. the Logbook). */
public interface TradeSink {
  void onTrade(Trade trade);
}
