// REST routing throughput: ramps arrival rate against the gateway's public read
// route and (optionally) the authenticated bets route, asserting p99 latency and
// a low error rate. Exercises path rewrite + identity forwarding + proxy.
//
// Run against the full stack (orchestration docker-compose):
//   k6 run -e BASE_URL=http://localhost:8080 -e TOKEN=<jwt> scenarios/gateway_routing.js
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const TOKEN = __ENV.TOKEN || '';

export const options = {
  scenarios: {
    routing: {
      executor: 'ramping-arrival-rate',
      startRate: 200,
      timeUnit: '1s',
      preAllocatedVUs: 300,
      maxVUs: 3000,
      stages: [
        { duration: '30s', target: 1000 },
        { duration: '60s', target: 5000 },
        { duration: '30s', target: 5000 },
      ],
    },
  },
  thresholds: {
    http_req_duration: ['p(99)<100'],
    http_req_failed: ['rate<0.001'],
  },
};

export default function () {
  // Public read route — no auth; exercises /api/v1/events -> odds-feed.
  const events = http.get(`${BASE_URL}/api/v1/events`);
  check(events, { 'events 200': (r) => r.status === 200 });

  if (TOKEN) {
    // Authenticated route — exercises JWT verify + identity forwarding + rewrite.
    const bets = http.get(`${BASE_URL}/api/v1/bets`, {
      headers: { Authorization: `Bearer ${TOKEN}` },
    });
    check(bets, { 'bets authorized': (r) => r.status === 200 || r.status === 404 });
  }
}
