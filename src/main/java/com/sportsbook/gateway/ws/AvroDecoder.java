package com.sportsbook.gateway.ws;

import java.io.IOException;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificRecord;

/**
 * Binary Avro decode for inbound Kafka payloads. V1 publishes without a Schema Registry (ADR-0014);
 * each consumer pins the same shared-protocol generated classes, so the writer/reader schema is
 * implicit. The gateway only reads (the events are produced by odds-feed / settlement).
 */
public final class AvroDecoder {

  public static <T extends SpecificRecord> T decode(byte[] payload, Class<T> type) {
    try {
      DatumReader<T> reader = new SpecificDatumReader<>(type);
      return reader.read(null, DecoderFactory.get().binaryDecoder(payload, null));
    } catch (IOException e) {
      throw new IllegalStateException("Failed to decode Avro record " + type.getName(), e);
    }
  }

  private AvroDecoder() {
    // Utility holder.
  }
}
