#!/usr/bin/env node
// -----------------------------------------------------------------------------
// One-time bulk catalog puller for the OLTP streaming load simulator.
//
// Sweeps the real YouTube Data API v3 (search.list -> videos.list) across a fixed
// set of genre queries, hydrates full metadata in batches of 50 (mirroring the
// batch pattern in video-service/.../youtube/YoutubeApiCatalogLoader.java), and
// writes a large cached catalog JSON. Load tests then run OFFLINE against this
// cached file, so the ~10k-unit/day API quota is spent once, not every run.
//
// Records match CatalogSeeder.SeedVideo plus description + releaseYear (both are
// existing `videos` columns): { youtubeId, title, genre, channel, durationSec,
// description, releaseYear }.
//
// Usage:
//   YOUTUBE_API_KEY=... node scripts/youtube-catalog-pull.mjs [--target 5000] \
//       [--max-units 9500] [--out <path>] [--per-query 200]
//
// Quota accounting (YouTube documented costs): search.list = 100 units/call,
// videos.list = 1 unit/call. A --max-units guard stops before exhausting quota.
// Progress + a resumable checkpoint are written so an interrupted pull continues.
// -----------------------------------------------------------------------------

import { readFileSync, writeFileSync, existsSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const REPO = resolve(__dirname, '..');

// ---- args -------------------------------------------------------------------
function arg(name, dflt) {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : dflt;
}
const TARGET = parseInt(arg('target', '5000'), 10);
const MAX_UNITS = parseInt(arg('max-units', '9500'), 10);
const PER_QUERY = parseInt(arg('per-query', '200'), 10); // cap videos collected per genre query
const OUT = resolve(REPO, arg('out', 'video-service/src/main/resources/catalog/youtube-catalog-large.json'));
const CHECKPOINT = resolve(REPO, 'scripts/.youtube-catalog-checkpoint.json');

const API_KEY = process.env.YOUTUBE_API_KEY;
if (!API_KEY) {
  console.error('ERROR: YOUTUBE_API_KEY is not set. Export it (see .env.example) and retry.');
  process.exit(1);
}

// Genre label -> search query. Labels align with the demo catalog's genre chips.
const GENRE_QUERIES = {
  Pop: 'official music video pop',
  Rock: 'official music video rock',
  'Hip-Hop': 'official music video hip hop',
  Electronic: 'official music video electronic edm',
  Jazz: 'jazz performance live',
  Classical: 'classical music orchestra',
  Country: 'official music video country',
  'R&B': 'official music video r&b soul',
  Latin: 'official music video latin',
  Indie: 'official music video indie',
  Metal: 'official music video metal',
  Reggae: 'official music video reggae',
};

// ---- quota-tracked fetch ----------------------------------------------------
let unitsSpent = 0;
async function api(path, params, cost) {
  if (unitsSpent + cost > MAX_UNITS) {
    throw new Error(`quota guard: would exceed --max-units ${MAX_UNITS} (spent ${unitsSpent})`);
  }
  const qs = new URLSearchParams({ ...params, key: API_KEY }).toString();
  const url = `https://www.googleapis.com/youtube/v3/${path}?${qs}`;
  const resp = await fetch(url);
  unitsSpent += cost;
  if (!resp.ok) {
    const body = await resp.text();
    throw new Error(`HTTP ${resp.status} on ${path}: ${body.slice(0, 300)}`);
  }
  return resp.json();
}

// ISO-8601 duration (PT#H#M#S) -> seconds
function isoDurationToSec(iso) {
  const m = /^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?$/.exec(iso || '');
  if (!m) return 0;
  return (parseInt(m[1] || 0, 10) * 3600) + (parseInt(m[2] || 0, 10) * 60) + parseInt(m[3] || 0, 10);
}

// ---- checkpoint (resume support) --------------------------------------------
function loadCheckpoint() {
  if (existsSync(CHECKPOINT)) {
    try { return JSON.parse(readFileSync(CHECKPOINT, 'utf8')); } catch { /* ignore */ }
  }
  return { idsByGenre: {}, records: [] };
}
function saveCheckpoint(state) {
  writeFileSync(CHECKPOINT, JSON.stringify(state));
}

// ---- phase 1: collect video IDs per genre via search.list -------------------
async function collectIds(state) {
  const genres = Object.entries(GENRE_QUERIES);
  const perGenreTarget = Math.min(PER_QUERY, Math.ceil(TARGET / genres.length));
  for (const [genre, query] of genres) {
    const seen = new Set(state.idsByGenre[genre] || []);
    let pageToken = '';
    while (seen.size < perGenreTarget) {
      let data;
      try {
        data = await api('search.list', {
          part: 'snippet', type: 'video', q: query, maxResults: '50',
          order: 'viewCount', ...(pageToken ? { pageToken } : {}),
        }, 100);
      } catch (e) {
        console.error(`[search:${genre}] stopping: ${e.message}`);
        return false; // hit quota / error — stop collecting, hydrate what we have
      }
      for (const it of data.items || []) {
        if (it.id?.videoId) seen.add(it.id.videoId);
      }
      state.idsByGenre[genre] = [...seen];
      saveCheckpoint(state);
      const totalIds = Object.values(state.idsByGenre).reduce((n, a) => n + a.length, 0);
      console.error(`[search:${genre}] ${seen.size}/${perGenreTarget} ids · total ${totalIds} · units ${unitsSpent}`);
      if (!data.nextPageToken) break;
      pageToken = data.nextPageToken;
    }
  }
  return true;
}

// ---- phase 2: hydrate metadata in batches of 50 via videos.list -------------
async function hydrate(state) {
  const already = new Set(state.records.map((r) => r.youtubeId));
  const genreOf = {};
  for (const [genre, ids] of Object.entries(state.idsByGenre)) {
    for (const id of ids) if (!genreOf[id]) genreOf[id] = genre;
  }
  const pending = Object.keys(genreOf).filter((id) => !already.has(id));
  for (let i = 0; i < pending.length; i += 50) {
    const batch = pending.slice(i, i + 50);
    let data;
    try {
      data = await api('videos.list', {
        part: 'snippet,contentDetails,statistics', id: batch.join(','), maxResults: '50',
      }, 1);
    } catch (e) {
      console.error(`[hydrate] stopping: ${e.message}`);
      break;
    }
    for (const it of data.items || []) {
      const sn = it.snippet || {};
      const durationSec = isoDurationToSec(it.contentDetails?.duration);
      if (!sn.title || durationSec <= 0) continue; // skip un-embeddable / bad rows
      state.records.push({
        youtubeId: it.id,
        title: sn.title.slice(0, 250),
        genre: genreOf[it.id],
        channel: (sn.channelTitle || 'Unknown').slice(0, 250),
        durationSec,
        description: (sn.description || '').slice(0, 500),
        releaseYear: sn.publishedAt ? parseInt(sn.publishedAt.slice(0, 4), 10) : 2015,
      });
    }
    saveCheckpoint(state);
    console.error(`[hydrate] ${state.records.length} records · units ${unitsSpent}`);
  }
}

// ---- main -------------------------------------------------------------------
const state = loadCheckpoint();
console.error(`Target ${TARGET} videos · max-units ${MAX_UNITS} · out ${OUT}`);
await collectIds(state);
await hydrate(state);

// de-dup + cap to target, then write the catalog
const byId = new Map();
for (const r of state.records) if (!byId.has(r.youtubeId)) byId.set(r.youtubeId, r);
const catalog = [...byId.values()].slice(0, TARGET);

mkdirSync(dirname(OUT), { recursive: true });
writeFileSync(OUT, JSON.stringify(catalog, null, 2));
console.error(`\nDONE: wrote ${catalog.length} videos to ${OUT} (spent ${unitsSpent} quota units).`);
if (catalog.length < TARGET) {
  console.error(`NOTE: got fewer than --target ${TARGET}; re-run to resume from checkpoint (quota permitting).`);
}
