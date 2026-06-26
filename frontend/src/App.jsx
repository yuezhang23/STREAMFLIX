import { useEffect, useState, useCallback } from 'react'
import { api, loadAuth, saveAuth, clearAuth } from './api.js'

function Login({ onLogin }) {
  const [email, setEmail] = useState('alice@streamflix.dev')
  const [password, setPassword] = useState('password')
  const [error, setError] = useState(null)

  const submit = async (e) => {
    e.preventDefault()
    setError(null)
    try {
      const auth = await api.login(email, password)
      saveAuth(auth)
      onLogin(auth)
    } catch (err) {
      setError('Login failed — try alice@streamflix.dev / password')
    }
  }

  return (
    <div className="login">
      <h1 className="brand">STREAMFLIX</h1>
      <form onSubmit={submit}>
        <input value={email} onChange={(e) => setEmail(e.target.value)} placeholder="email" />
        <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder="password" />
        <button type="submit">Sign in</button>
        {error && <p className="error">{error}</p>}
        <p className="hint">Seeded users: alice / bob / carol / dave / erin @streamflix.dev — password "password"</p>
      </form>
    </div>
  )
}

function VideoCard({ video, rec, onAction }) {
  if (!video) return null
  return (
    <div className="card">
      <img src={video.thumbnailUrl} alt={video.title} />
      <div className="card-body">
        <div className="title">{video.title}</div>
        <div className="genre">{video.genre} · {video.releaseYear}</div>
        {rec?.reason && <div className="reason">{rec.reason}</div>}
        <div className="actions">
          <button onClick={() => onAction('watch', video.id)}>▶ Watch</button>
          <button onClick={() => onAction('like', video.id)}>♥</button>
          <select defaultValue="" onChange={(e) => e.target.value && onAction('rate', video.id, Number(e.target.value))}>
            <option value="">Rate</option>
            {[1, 2, 3, 4, 5].map((n) => <option key={n} value={n}>{n}★</option>)}
          </select>
        </div>
      </div>
    </div>
  )
}

function Rail({ title, items, byId, recById, onAction }) {
  if (!items?.length) return null
  return (
    <section>
      <h2>{title}</h2>
      <div className="rail">
        {items.map((it) => (
          <VideoCard key={it.videoId} video={byId[it.videoId]} rec={recById?.[it.videoId]} onAction={onAction} />
        ))}
      </div>
    </section>
  )
}

export default function App() {
  const [auth, setAuth] = useState(loadAuth())
  const [videos, setVideos] = useState([])
  const [recs, setRecs] = useState([])
  const [trending, setTrending] = useState([])
  const [overview, setOverview] = useState(null)
  const [status, setStatus] = useState('')

  const byId = Object.fromEntries(videos.map((v) => [v.id, v]))
  const recById = Object.fromEntries(recs.map((r) => [r.videoId, r]))

  const refresh = useCallback(async () => {
    if (!auth) return
    const [cat, rec, trend, ov] = await Promise.all([
      api.listVideos(50),
      api.recommendations(auth.userId, auth.token).catch(() => []),
      api.trending(auth.token).catch(() => []),
      api.overview(auth.token).catch(() => null),
    ])
    setVideos(cat.content || [])
    setRecs(rec || [])
    setTrending(trend || [])
    setOverview(ov)
  }, [auth])

  useEffect(() => { refresh() }, [refresh])

  if (!auth) return <Login onLogin={setAuth} />

  const onAction = async (type, id, rating) => {
    setStatus(`Sending ${type}…`)
    try {
      if (type === 'watch') await api.watch(id, auth.token)
      if (type === 'like') await api.like(id, auth.token)
      if (type === 'rate') await api.rate(id, rating, auth.token)
      setStatus(`${type} recorded — recomputing recommendations…`)
      // give the reco consumer a moment to fold the event in, then refresh
      setTimeout(async () => {
        const rec = await api.recommendations(auth.userId, auth.token).catch(() => [])
        setRecs(rec || [])
        setStatus('Recommendations updated')
      }, 800)
    } catch (e) {
      setStatus('Action failed: ' + e.message)
    }
  }

  const runEtl = async () => {
    setStatus('Running OLAP ETL…')
    await api.runEtl(auth.token).catch(() => {})
    const [trend, ov] = await Promise.all([
      api.trending(auth.token).catch(() => []),
      api.overview(auth.token).catch(() => null),
    ])
    setTrending(trend || [])
    setOverview(ov)
    setStatus('Analytics refreshed')
  }

  const logout = () => { clearAuth(); setAuth(null) }

  return (
    <div className="app">
      <header>
        <span className="brand">STREAMFLIX</span>
        <div className="header-actions">
          {overview && (
            <span className="stats">
              {overview.totalEvents} views · {overview.distinctUsers} users · {overview.totalWatchHours}h watched
            </span>
          )}
          <button onClick={runEtl}>Run ETL</button>
          <button onClick={refresh}>Refresh</button>
          <span className="user">{auth.displayName}</span>
          <button onClick={logout}>Logout</button>
        </div>
      </header>

      {status && <div className="status">{status}</div>}

      <Rail title="Recommended for you" items={recs} byId={byId} recById={recById} onAction={onAction} />
      <Rail title="Trending now" items={trending} byId={byId} onAction={onAction} />

      <section>
        <h2>Browse all</h2>
        <div className="grid">
          {videos.map((v) => <VideoCard key={v.id} video={v} onAction={onAction} />)}
        </div>
      </section>
    </div>
  )
}
