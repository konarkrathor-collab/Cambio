package ads.api;

import java.util.Map;

/**
 * Where the engine thread sends live events (BOOK_UPDATE, TRADE, LOOPHOLE_ALERT,
 * RATE_TICK). Implementations must never block the caller.
 */
public interface EventSink {
  EventSink NONE = (type, data) -> { };

  void publish(String type, Map<String, Object> data);
}
