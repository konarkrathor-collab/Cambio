package ads.bench.core;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * RESIDENT FOOTPRINT: bytes retained by a live structure, as opposed to
 * {@link AllocCounter}'s lifetime allocation churn. Walks the object graph reachable from
 * the structure through instance fields (statics excluded; each object counted once) and
 * sizes every object with HotSpot's layout rules for this JVM's flags (read at runtime):
 * object header 12 B with compressed class pointers (else 16), array header 16 B (else
 * 20 -&gt; 24 aligned), references 4 B with compressed oops (else 8), objects padded to
 * {@code ObjectAlignmentInBytes}. Field packing follows JDK 15+ layout: the 4-byte gap
 * after a 12-byte header is filled by a 4-byte (or smaller) field when one exists,
 * otherwise padded before 8-byte fields.
 *
 * <p>This is a model, so {@link #heapDelta} cross-checks it against the real heap. Harness
 * code: {@code java.util} use and reflection are allowed here (fairness rule 6); nothing
 * in this class is timed. Classes the module system closes to reflection (e.g. the JDK's
 * own TreeMap internals) make {@link #of} throw; callers report those as n/a.
 */
public final class DeepSize {
  private DeepSize() {
  }

  private static final boolean COMPRESSED_OOPS = vmFlag("UseCompressedOops", true);
  private static final boolean COMPRESSED_KLASS = vmFlag("UseCompressedClassPointers", true);
  private static final int ALIGN = (int) vmLong("ObjectAlignmentInBytes", 8);
  public static final int OBJECT_HEADER = COMPRESSED_KLASS ? 12 : 16;
  public static final int ARRAY_HEADER = COMPRESSED_KLASS ? 16 : 20;
  public static final int REF = COMPRESSED_OOPS ? 4 : 8;

  /** Result of one graph walk. */
  public static final class Size {
    public final long bytes;
    public final long objects;

    Size(long bytes, long objects) {
      this.bytes = bytes;
      this.objects = objects;
    }
  }

  public static String layout() {
    return "header=" + OBJECT_HEADER + "B arrayHeader=" + ARRAY_HEADER + "B ref=" + REF
        + "B align=" + ALIGN + "B";
  }

  /** Deep retained size of everything reachable from root through instance fields. */
  public static Size of(Object root) {
    IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
    ArrayDeque<Object> stack = new ArrayDeque<>();
    stack.push(root);
    seen.put(root, Boolean.TRUE);
    long bytes = 0;
    long objects = 0;
    while (!stack.isEmpty()) {
      Object o = stack.pop();
      Class<?> c = o.getClass();
      objects++;
      if (c.isArray()) {
        int len = Array.getLength(o);
        Class<?> ct = c.getComponentType();
        bytes += align(ARRAY_HEADER + (long) len * width(ct));
        if (!ct.isPrimitive()) {
          for (int i = 0; i < len; i++) {
            Object e = Array.get(o, i);
            if (e != null && seen.put(e, Boolean.TRUE) == null) stack.push(e);
          }
        }
        continue;
      }
      List<Field> fields = instanceFields(c);
      bytes += shallow(fields);
      for (Field f : fields) {
        if (f.getType().isPrimitive()) continue;
        try {
          Object e = f.get(o);
          if (e != null && seen.put(e, Boolean.TRUE) == null) stack.push(e);
        } catch (IllegalAccessException ex) {
          throw new IllegalStateException("cannot read " + f, ex);
        }
      }
    }
    return new Size(bytes, objects);
  }

  private static List<Field> instanceFields(Class<?> c) {
    List<Field> out = new ArrayList<>();
    for (Class<?> k = c; k != null; k = k.getSuperclass()) {
      for (Field f : k.getDeclaredFields()) {
        if (Modifier.isStatic(f.getModifiers())) continue;
        f.setAccessible(true); // throws InaccessibleObjectException for closed JDK modules
        out.add(f);
      }
    }
    return out;
  }

  private static long shallow(List<Field> fields) {
    long sum = 0;
    boolean hasWide = false;
    boolean hasNarrow = false;
    for (Field f : fields) {
      int w = width(f.getType());
      sum += w;
      if (w == 8) hasWide = true;
      else hasNarrow = true;
    }
    long size = OBJECT_HEADER + sum;
    if (OBJECT_HEADER % 8 != 0 && hasWide && !hasNarrow) {
      size += 4; // nothing fits the post-header gap: pad so 8-byte fields are aligned
    }
    return align(size);
  }

  private static int width(Class<?> t) {
    if (t == long.class || t == double.class) return 8;
    if (t == int.class || t == float.class) return 4;
    if (t == short.class || t == char.class) return 2;
    if (t == byte.class || t == boolean.class) return 1;
    return REF;
  }

  private static long align(long n) {
    return (n + ALIGN - 1) / ALIGN * ALIGN;
  }

  /** Live-heap bytes after repeated full GCs (G1 needs a few passes to settle). */
  public static long usedHeapAfterGc() {
    Runtime rt = Runtime.getRuntime();
    long best = Long.MAX_VALUE;
    for (int i = 0; i < 4; i++) {
      System.gc();
      best = Math.min(best, rt.totalMemory() - rt.freeMemory());
    }
    return best;
  }

  /**
   * Cross-check: live heap with {@code root} reachable, minus live heap after it is dropped.
   * Coarse (GC/TLAB granularity, tens of KB) — meaningful only for multi-MB structures.
   */
  public static long heapDelta(Object[] holder) {
    long with = usedHeapAfterGc();
    holder[0] = null;
    long without = usedHeapAfterGc();
    return with - without;
  }

  private static boolean vmFlag(String name, boolean dflt) {
    String v = vmOption(name);
    return v == null ? dflt : Boolean.parseBoolean(v);
  }

  private static long vmLong(String name, long dflt) {
    String v = vmOption(name);
    return v == null ? dflt : Long.parseLong(v);
  }

  private static String vmOption(String name) {
    try {
      com.sun.management.HotSpotDiagnosticMXBean b = ManagementFactory
          .getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
      return b == null ? null : b.getVMOption(name).getValue();
    } catch (RuntimeException e) {
      return null;
    }
  }
}
