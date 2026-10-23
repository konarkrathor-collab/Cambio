package ads.engine;

/**
 * The shape of a B+ tree ladder, for the inspector view: how tall it is, how many internal
 * nodes and leaves it has, and how many entries it holds. Read-only snapshot (integers
 * only: the engine package has no floating point).
 */
public final class LadderShape {
  final int order;
  final int height;
  final int internalNodes;
  final int leaves;
  final int entries;

  LadderShape(int order, int height, int internalNodes, int leaves, int entries) {
    this.order = order;
    this.height = height;
    this.internalNodes = internalNodes;
    this.leaves = leaves;
    this.entries = entries;
  }

  /** Maximum keys per node. */
  public int order() {
    return order;
  }

  /** Levels from the root to the leaves (1 = the root is a leaf). */
  public int height() {
    return height;
  }

  public int internalNodes() {
    return internalNodes;
  }

  public int leaves() {
    return leaves;
  }

  /** Price levels stored (equals the ladder's size). */
  public int entries() {
    return entries;
  }
}
