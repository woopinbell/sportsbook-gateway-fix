package com.sportsbook.gateway.ws;

import com.sportsbook.protocol.event.BetSettled;
import com.sportsbook.protocol.event.BetVoided;
import com.sportsbook.protocol.event.Money;
import java.time.Instant;

/**
 * Client-facing bet-status push (camelCase JSON, ADR-0004) delivered to the owning user's {@code
 * /user/queue/bets}. Unifies the two terminal outcomes: SETTLED (with a WON/LOST/PUSH/VOID result
 * and the credited amount) and VOIDED (with a void reason and the refunded stake).
 */
public record BetStatusUpdate(
    String betId,
    String userId,
    String eventId,
    String status,
    String result,
    MoneyView amount,
    String reason,
    Instant updatedAt) {

  /** Minor-units amount + ISO currency, mirroring the Money event record. */
  public record MoneyView(long amount, String currency) {
    static MoneyView from(Money money) {
      return new MoneyView(money.getAmount(), money.getCurrency());
    }
  }

  static BetStatusUpdate settled(BetSettled event) {
    return new BetStatusUpdate(
        event.getBetId(),
        event.getUserId(),
        event.getEventId(),
        "SETTLED",
        event.getResult().name(),
        MoneyView.from(event.getPayout()),
        null,
        event.getSettledAt());
  }

  static BetStatusUpdate voided(BetVoided event) {
    return new BetStatusUpdate(
        event.getBetId(),
        event.getUserId(),
        event.getEventId(),
        "VOIDED",
        null,
        MoneyView.from(event.getRefund()),
        event.getReason().name(),
        event.getVoidedAt());
  }
}
