package com.sportsbook.gateway.ws;

import com.sportsbook.protocol.event.OddsChanged;
import java.time.Instant;

/**
 * Client-facing odds push (camelCase JSON, ADR-0004) broadcast on {@code /topic/odds/{eventId}}.
 * Decimal odds stay plain strings on the wire, matching the {@link OddsChanged} contract.
 */
public record OddsUpdate(
    String eventId,
    String marketId,
    String selectionId,
    String previousOdds,
    String newOdds,
    Instant changedAt) {

  static OddsUpdate from(OddsChanged event) {
    return new OddsUpdate(
        event.getEventId(),
        event.getMarketId(),
        event.getSelectionId(),
        event.getPreviousOdds(),
        event.getNewOdds(),
        event.getChangedAt());
  }
}
