// Thin API client. All calls go through the gateway under the same-origin /api prefix.

const TOKEN_KEY = 'streamflix.auth'

export function loadAuth() {
  const raw = localStorage.getItem(TOKEN_KEY)
  return raw ? JSON.parse(raw) : null
}

export function saveAuth(auth) {
  localStorage.setItem(TOKEN_KEY, JSON.stringify(auth))
}

export function clearAuth() {
  localStorage.removeItem(TOKEN_KEY)
}

async function request(path, { method = 'GET', body, token } = {}) {
  const headers = { 'Content-Type': 'application/json' }
  if (token) headers['Authorization'] = `Bearer ${token}`
  const res = await fetch(`/api${path}`, {
    method,
    headers,
    body: body ? JSON.stringify(body) : undefined,
  })
  if (!res.ok) {
    const text = await res.text().catch(() => '')
    throw new Error(`${res.status} ${res.statusText} ${text}`)
  }
  const ct = res.headers.get('content-type') || ''
  return ct.includes('application/json') ? res.json() : null
}

export const api = {
  login: (email, password) =>
    request('/users/login', { method: 'POST', body: { email, password } }),
  register: (email, password, displayName) =>
    request('/users/register', { method: 'POST', body: { email, password, displayName } }),
  listVideos: (size = 200) => request(`/videos?page=0&size=${size}`),
  recommendations: (userId, token) => request(`/recommendations/${userId}`, { token }),
  recommendedTags: (userId, token) => request(`/videos/tags/${userId}`, { token }),
  trending: (token) => request('/analytics/trending?limit=10', { token }),
  overview: (token) => request('/analytics/overview', { token }),
  watch: (id, token) =>
    request(`/videos/${id}/watch`, { method: 'POST', body: { watchedSec: 600 }, token }),
  like: (id, token) => request(`/videos/${id}/like`, { method: 'POST', token }),
  rate: (id, rating, token) =>
    request(`/videos/${id}/rate`, { method: 'POST', body: { rating }, token }),
  runEtl: (token) => request('/analytics/etl/run', { method: 'POST', token }),
}
