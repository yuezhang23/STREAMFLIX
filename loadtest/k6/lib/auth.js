import http from 'k6/http';
import { CONFIG, userEmails } from './config.js';

// Authenticate the whole user pool once (in setup) and return their bearer tokens. k6 VUs then
// rotate through this token pool so OLTP writes spread across many user_ids — realistic for
// interaction-table contention rather than hammering one row.
export function loginPool() {
  const tokens = [];
  const emails = userEmails();
  for (const email of emails) {
    const res = http.post(
      `${CONFIG.baseUrl}/api/users/login`,
      JSON.stringify({ email, password: CONFIG.password }),
      { headers: { 'Content-Type': 'application/json' }, tags: { name: 'login', group: 'auth' } },
    );
    if (res.status === 200) {
      const token = res.json('token');
      if (token) tokens.push(token);
    }
  }
  if (tokens.length === 0) {
    throw new Error(
      `loginPool: authenticated 0/${emails.length} users at ${CONFIG.baseUrl}. ` +
      `If using seeded users, ensure APP_SEED_USERS covers USER_POOL (${CONFIG.userPool}).`,
    );
  }
  return tokens;
}
