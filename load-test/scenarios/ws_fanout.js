// WebSocket fan-out: 10k concurrent STOMP subscribers on /ws/v1/odds, measuring
// the lag from SUBSCRIBE to the first pushed odds MESSAGE (target p99 < 1s).
//
// Run against the full stack (orchestration docker-compose) with odds-feed's
// mock generator emitting odds changes for EVENT_ID:
//   k6 run -e WS_URL=ws://localhost:8080/ws/v1/odds -e EVENT_ID=<eventId> scenarios/ws_fanout.js
import ws from 'k6/ws';
import { check } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const pushLag = new Trend('push_lag', true);
const messages = new Counter('odds_messages_received');

const WS_URL = __ENV.WS_URL || 'ws://localhost:8080/ws/v1/odds';
const EVENT_ID = __ENV.EVENT_ID || 'demo-event';
const HOLD_MS = Number(__ENV.HOLD_MS || 120000);

export const options = {
  scenarios: {
    fanout: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 2000 },
        { duration: '45s', target: 10000 },
        { duration: '60s', target: 10000 },
      ],
    },
  },
  // The headline SLO: live odds reach every subscriber within a second.
  thresholds: {
    push_lag: ['p(99)<1000'],
  },
};

export default function () {
  const res = ws.connect(WS_URL, {}, (socket) => {
    let subscribedAt = 0;

    socket.on('open', () => {
      socket.send('CONNECT\naccept-version:1.2\nheart-beat:0,0\n\n\0');
    });

    socket.on('message', (frame) => {
      if (frame.startsWith('CONNECTED')) {
        subscribedAt = Date.now();
        socket.send(`SUBSCRIBE\nid:sub-0\ndestination:/topic/odds/${EVENT_ID}\n\n\0`);
      } else if (frame.startsWith('MESSAGE')) {
        if (subscribedAt > 0) {
          pushLag.add(Date.now() - subscribedAt);
          subscribedAt = 0; // first push after subscribe is the one we time
        }
        messages.add(1);
      }
    });

    socket.setTimeout(() => socket.close(), HOLD_MS);
  });

  check(res, { 'ws handshake 101': (r) => r && r.status === 101 });
}
