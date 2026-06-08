package com.sportsbook.gateway.routing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.ServerRequest;

/**
 * Ensures a W3C {@code traceparent} header reaches the downstream service (ADR-0007 distributed
 * tracing). An inbound {@code traceparent} is left untouched (the proxy forwards it, continuing the
 * trace); otherwise, if the gateway has an active span, one is synthesised from it so the trace
 * starts at the edge. Tracer is optional so the gateway still runs if tracing is disabled.
 */
@Component
public class TraceForwarding {

  private static final String TRACEPARENT = "traceparent";
  private static final String SAMPLED_FLAGS = "01";

  private final ObjectProvider<Tracer> tracer;

  public TraceForwarding(ObjectProvider<Tracer> tracer) {
    this.tracer = tracer;
  }

  public ServerRequest apply(ServerRequest request) {
    if (request.headers().firstHeader(TRACEPARENT) != null) {
      return request;
    }
    String traceparent = currentTraceparent();
    if (traceparent == null) {
      return request;
    }
    return ServerRequest.from(request).header(TRACEPARENT, traceparent).build();
  }

  private String currentTraceparent() {
    Tracer activeTracer = tracer.getIfAvailable();
    if (activeTracer == null) {
      return null;
    }
    Span span = activeTracer.currentSpan();
    if (span == null) {
      return null;
    }
    TraceContext context = span.context();
    return "00-" + context.traceId() + "-" + context.spanId() + "-" + SAMPLED_FLAGS;
  }
}
