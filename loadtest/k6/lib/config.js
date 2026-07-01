// Central config for the load harness, all overridable via -e VAR=value (or compose env).
// Defaults assume running INSIDE the compose network (host `api-gateway`); when running k6 from
// the host, pass -e BASE_URL=http://localhost:8080.

const env = (k, d) => (__ENV[k] !== undefined && __ENV[k] !== '' ? __ENV[k] : d);

export const CONFIG = {
  baseUrl: env('BASE_URL', 'http://api-gateway:8080'),
  // sustained OLTP write rate the ingest scenario drives (events/sec)
  targetEps: parseInt(env('TARGET_EPS', '500'), 10),
  // read-path concurrency
  readVus: parseInt(env('READ_VUS', '50'), 10),
  duration: env('DURATION', '5m'),
  rampUp: env('RAMP_UP', '30s'),
  // how many distinct accounts to authenticate and rotate through (spreads writes across user_ids)
  userPool: parseInt(env('USER_POOL', '200'), 10),
  password: env('USER_PASSWORD', 'password'),
  // when APP_SEED_USERS was set, use the bulk loadtest+{n}@ accounts; otherwise the 5 demo users
  useSeededUsers: env('USE_SEEDED_USERS', 'true') === 'true',
};

// SLO targets — kept in sync with loadtest/targets.json; used for k6 thresholds.
export const TARGETS = {
  readP95: 200,
  writeP95: 250,
  recommendationP95: 150,
  errorRateMax: 0.001,
};

const DEMO_USERS = ['alice', 'bob', 'carol', 'dave', 'erin'].map((n) => `${n}@streamflix.dev`);

// Build the list of emails to authenticate.
export function userEmails() {
  if (!CONFIG.useSeededUsers) return DEMO_USERS;
  const emails = [];
  for (let n = 1; n <= CONFIG.userPool; n++) emails.push(`loadtest+${n}@streamflix.dev`);
  return emails;
}
