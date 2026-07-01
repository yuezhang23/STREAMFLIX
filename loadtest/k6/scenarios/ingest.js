import http from 'k6/http';
import { Counter } from 'k6/metrics';
import { CONFIG } from '../lib/config.js';
import { pick } from '../lib/catalog.js';

// Sustained OLTP write stream. Each iteration emits one behavior event (watch/view/rate/like),
// mirroring the weights in TrafficSimulator.emitRandomEvent (60/20/13/7). Writes hit the real
// gateway -> video-service -> Postgres INSERT + Kafka publish path. Tagged group:write for
// write-path p95 thresholds. `oltp_events` counts confirmed-accepted (202) writes for events/sec.
export const oltpEvents = new Counter('oltp_events');

export function ingest(data) {
  const token = pick(data.tokens);
  const id = pick(data.ids);
  const authHeaders = {
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
  };

  const r = Math.random();
  let res;
  if (r < 0.6) {
    const watchedSec = 60 + Math.floor(Math.random() * 540);
    res = http.post(`${CONFIG.baseUrl}/api/videos/${id}/watch`, JSON.stringify({ watchedSec }), {
      ...authHeaders, tags: { name: 'watch', group: 'write' },
    });
  } else if (r < 0.8) {
    res = http.post(`${CONFIG.baseUrl}/api/videos/${id}/view`, null, {
      ...authHeaders, tags: { name: 'view', group: 'write' },
    });
  } else if (r < 0.93) {
    const rating = 3 + Math.floor(Math.random() * 3); // 3..5
    res = http.post(`${CONFIG.baseUrl}/api/videos/${id}/rate`, JSON.stringify({ rating }), {
      ...authHeaders, tags: { name: 'rate', group: 'write' },
    });
  } else {
    res = http.post(`${CONFIG.baseUrl}/api/videos/${id}/like`, null, {
      ...authHeaders, tags: { name: 'like', group: 'write' },
    });
  }
  if (res.status === 202) oltpEvents.add(1);
}
