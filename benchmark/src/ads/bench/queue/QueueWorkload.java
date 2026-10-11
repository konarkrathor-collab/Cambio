package ads.bench.queue;

import ads.bench.core.Rng;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * DATA (Phase 1): generates ONE deterministic, immutable operation log for the order-queue
 * benchmark, mirroring the {@code LadderWorkload} pattern.
 *
 * <p><b>Operation mix (and why):</b> per 1000 ops — 500 ENQUEUE, 450 DEQUEUE-head,
 * 40 CANCEL-by-id, 10 PEEK. Every order enters the queue exactly once, so enqueues must
 * dominate the mix; of each 500 arriving orders roughly 90% eventually leave via FIFO
 * dequeue (the matching loop), about 8% are cancelled pre-execution (4 cancels per 50
 * enqueues, inside the 5-20% cancel-rate band commonly cited for exchange order flow),
 * and the remaining ~2% accumulate as resting depth so the queue stays populated and all
 * paths stay exercised. PEEK models the matching engine's pre-trade best-order check.
 *
 * <p><b>Liveness tracking:</b> the generator mirrors the queue's FIFO order itself — an
 * arrival deque plus a swap-remove live list — so every CANCEL targets an order that is
 * really still queued, and DEQUEUE ops name no id at all (the candidate pops its own
 * head). Removed-but-not-yet-consumed arrivals are lazily skipped from the generator's
 * own deque, keeping generator cost amortized O(1) per op.
 */
public final class QueueWorkload {
  public static final byte ENQUEUE = 0;
  public static final byte DEQUEUE = 1;
  public static final byte CANCEL = 2;
  public static final byte PEEK = 3;

  public final int n;
  public final long seed;
  public final int finalDepth; // live orders at the end of generation
  public final byte[] op;
  public final long[] orderId; // target id for ENQUEUE / CANCEL; unused otherwise
  public final long[] volume;  // volume for ENQUEUE; unused otherwise

  private QueueWorkload(int n, long seed, int finalDepth, byte[] op, long[] orderId,
      long[] volume) {
    this.n = n;
    this.seed = seed;
    this.finalDepth = finalDepth;
    this.op = op;
    this.orderId = orderId;
    this.volume = volume;
  }

  public static QueueWorkload generate(int n, long seed) {
    Rng rng = new Rng(seed);
    byte[] op = new byte[n];
    long[] orderId = new long[n];
    long[] volume = new long[n];
    ArrayDeque<Long> arrival = new ArrayDeque<>();
    ArrayList<Long> live = new ArrayList<>();
    HashMap<Long, Integer> livePos = new HashMap<>();
    long nextId = 1;

    for (int i = 0; i < n; i++) {
      byte o;
      if (live.isEmpty()) {
        o = ENQUEUE; // never dequeue/peek an empty book in a live market
      } else {
        int roll = rng.nextInt(1000);
        if (roll < 500) o = ENQUEUE;
        else if (roll < 950) o = DEQUEUE;
        else if (roll < 990) o = CANCEL;
        else o = PEEK;
      }
      switch (o) {
        case ENQUEUE: {
          long id = nextId++;
          orderId[i] = id;
          volume[i] = 1 + rng.nextInt(1_000_000);
          arrival.addLast(id);
          livePos.put(id, live.size());
          live.add(id);
          break;
        }
        case DEQUEUE: {
          Long id;
          do {
            id = arrival.pollFirst();
          } while (id != null && !livePos.containsKey(id));
          if (id == null) {
            throw new IllegalStateException("generator liveness desync at op " + i);
          }
          removeLive(live, livePos, id);
          break;
        }
        case CANCEL: {
          int idx = rng.nextInt(live.size());
          long id = live.get(idx);
          removeLive(live, livePos, id);
          orderId[i] = id;
          break;
        }
        default: {
          break; // PEEK
        }
      }
      op[i] = o;
    }
    return new QueueWorkload(n, seed, live.size(), op, orderId, volume);
  }

  private static void removeLive(ArrayList<Long> live, HashMap<Long, Integer> livePos, long id) {
    int pos = livePos.remove(id);
    int last = live.size() - 1;
    if (pos != last) {
      long lastId = live.get(last);
      live.set(pos, lastId);
      livePos.put(lastId, pos);
    }
    live.remove(last);
  }

  /** Human-readable summary of the operation mix (for logs). */
  public String describe() {
    int enq = 0, deq = 0, can = 0, peek = 0;
    for (byte o : op) {
      switch (o) {
        case ENQUEUE: enq++; break;
        case DEQUEUE: deq++; break;
        case CANCEL: can++; break;
        default: peek++; break;
      }
    }
    return String.format("n=%d seed=%d | enqueue=%d dequeue=%d cancel=%d peek=%d | finalDepth=%d",
        n, seed, enq, deq, can, peek, finalDepth);
  }
}
