import { CONFIG, TARGETS } from './lib/config.js';
import { loginPool } from './lib/auth.js';
import { loadCatalog } from './lib/catalog.js';
import { browse } from './scenarios/browse.js';
import { ingest } from './scenarios/ingest.js';

// Mixed OLTP streaming profile: a constant-arrival-rate write stream at TARGET_EPS running
// alongside a concurrent read load. Thresholds encode the "lite Netflix-like" SLO bar — any
// breach is the measurable gap. Output goes to Prometheus (live Grafana) via
// -o experimental-prometheus-rw and to results/summary.json via handleSummary (for the gap report).

const eps = CONFIG.targetEps;

export const options = {
  discardResponseBodies: false,
  scenarios: {
    ingest_writes: {
      executor: 'constant-arrival-rate',
      exec: 'ingestExec',
      rate: eps,
      timeUnit: '1s',
      duration: CONFIG.duration,
      preAllocatedVUs: Math.max(20, Math.ceil(eps * 0.5)),
      maxVUs: Math.max(50, eps * 2),
      tags: { scenario: 'ingest' },
    },
    browse_reads: {
      executor: 'ramping-vus',
      exec: 'browseExec',
      startVUs: 0,
      stages: [
        { duration: CONFIG.rampUp, target: CONFIG.readVus },
        { duration: CONFIG.duration, target: CONFIG.readVus },
      ],
      tags: { scenario: 'browse' },
    },
  },
  thresholds: {
    'http_req_duration{group:read}': [`p(95)<${TARGETS.readP95}`],
    'http_req_duration{group:write}': [`p(95)<${TARGETS.writeP95}`],
    'http_req_duration{group:reco}': [`p(95)<${TARGETS.recommendationP95}`],
    'http_req_failed': [`rate<${TARGETS.errorRateMax}`],
    // sustained throughput SLO: accepted writes/sec must hold near the target
    'oltp_events': [`rate>${Math.floor(eps * 0.95)}`],
  },
};

export function setup() {
  const tokens = loginPool();
  const cat = loadCatalog();
  console.log(`setup: ${tokens.length} tokens · ${cat.ids.length} video ids · ` +
    `${cat.totalElements} catalog rows · target ${eps} eps`);
  return { tokens, ids: cat.ids, genres: cat.genres };
}

export function ingestExec(data) { ingest(data); }
export function browseExec(data) { browse(data); }

export function handleSummary(data) {
  return {
    stdout: shortSummary(data),
    '/results/summary.json': JSON.stringify(data, null, 2),
  };
}

function metric(data, name, sub) {
  const m = data.metrics[name];
  if (!m) return 'n/a';
  const v = m.values[sub];
  return v === undefined ? 'n/a' : v.toFixed(2);
}

function shortSummary(data) {
  const lines = [
    '',
    '=== StreamFlix OLTP streaming load — summary ===',
    `  read   p95:  ${metric(data, 'http_req_duration{group:read}', 'p(95)')} ms  (target <${TARGETS.readP95})`,
    `  write  p95:  ${metric(data, 'http_req_duration{group:write}', 'p(95)')} ms  (target <${TARGETS.writeP95})`,
    `  reco   p95:  ${metric(data, 'http_req_duration{group:reco}', 'p(95)')} ms  (target <${TARGETS.recommendationP95})`,
    `  errors rate: ${metric(data, 'http_req_failed', 'rate')}       (target <${TARGETS.errorRateMax})`,
    `  writes/sec:  ${metric(data, 'oltp_events', 'rate')}       (target ~${eps})`,
    '  full JSON -> loadtest/results/summary.json',
    '',
  ];
  return lines.join('\n');
}
