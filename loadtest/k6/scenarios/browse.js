import http from 'k6/http';
import { check } from 'k6';
import { CONFIG } from '../lib/config.js';
import { pick } from '../lib/catalog.js';

// Read-heavy path: catalog paging, search, genre tags, and the (cache-backed) recommendation feed.
// Every request is tagged group:read so thresholds can isolate read-path p95. The recommendation
// call is additionally tagged group:reco for its own tighter SLO.
export function browse(data) {
  const token = pick(data.tokens);
  const authHeaders = { headers: { Authorization: `Bearer ${token}` } };

  const page = Math.floor(Math.random() * 10);
  http.get(`${CONFIG.baseUrl}/api/videos?page=${page}&size=24`, {
    tags: { name: 'list', group: 'read' },
  });

  const genre = pick(data.genres);
  http.get(`${CONFIG.baseUrl}/api/videos/search?q=${encodeURIComponent(genre)}&size=24`, {
    tags: { name: 'search', group: 'read' },
  });

  const userId = 1 + Math.floor(Math.random() * 5); // tags/reco accept a path user id
  http.get(`${CONFIG.baseUrl}/api/videos/tags/${userId}`, {
    tags: { name: 'tags', group: 'read' },
  });

  const reco = http.get(`${CONFIG.baseUrl}/api/recommendations/${userId}`, {
    ...authHeaders,
    tags: { name: 'recommendations', group: 'reco' },
  });
  check(reco, { 'reco 200': (r) => r.status === 200 });
}
