package ads.vault;

/**
 * Appendix A {@code Account{traderId, cash, marginLimit}}. Amounts are in the engine's
 * notional unit (price tick x quantity). Only the {@link Vault} changes {@code cash}.
 */
public final class Account {
  final long traderId;
  long cash;
  final long marginLimit;
  Account ledgerNext; // Vault ledger bucket chain link

  Account(long traderId, long cash, long marginLimit) {
    this.traderId = traderId;
    this.cash = cash;
    this.marginLimit = marginLimit;
  }

  public long traderId() {
    return traderId;
  }

  public long cash() {
    return cash;
  }

  public long marginLimit() {
    return marginLimit;
  }
}
