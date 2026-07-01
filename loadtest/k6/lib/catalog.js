import http from 'k6/http';
import { CONFIG } from './config.js';

// Fetch a sample of real video ids + genres from the (public) catalog so scenarios exercise rows
// that actually exist, regardless of catalog size. Called once in setup.
export function loadCatalog() {
  const res = http.get(`${CONFIG.baseUrl}/api/videos?page=0&size=1000`, {
    tags: { name: 'catalog_bootstrap', group: 'read' },
  });
  if (res.status !== 200) {
    throw new Error(`loadCatalog: GET /api/videos returned ${res.status}`);
  }
  const body = res.json();
  const ids = (body.content || []).map((v) => v.id);
  const genres = [...new Set((body.content || []).map((v) => v.genre))];
  if (ids.length === 0) throw new Error('loadCatalog: catalog is empty — seed it first');
  return { ids, genres, totalElements: body.totalElements };
}

export const pick = (arr) => arr[Math.floor(Math.random() * arr.length)];
