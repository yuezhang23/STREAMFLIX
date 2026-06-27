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

function VideoCard({ video, rec, onAction, onPlay }) {
  if (!video) return null
  return (
    <div className="card">
      <div className="thumb" onClick={() => onPlay(video)}>
        <img src={video.thumbnailUrl} alt={video.title} />
        <span className="play-badge">▶</span>
      </div>
      <div className="card-body">
        <div className="title">{video.title}</div>
        <div className="genre">{video.channel || video.genre} · {video.genre}</div>
        {rec?.reason && <div className="reason">{rec.reason}</div>}
        <div className="actions">
          <button onClick={() => onPlay(video)}>▶ Watch</button>
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

function PlayerModal({ video, onClose }) {
  if (!video) return null
  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <div className="modal-head">
          <span>{video.title}</span>
          <button onClick={onClose}>✕</button>
        </div>
        {video.youtubeId ? (
          <iframe
            title={video.title}
            src={`https://www.youtube.com/embed/${video.youtubeId}?autoplay=1`}
            allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture"
            allowFullScreen
          />
        ) : (
          <div className="no-embed">No YouTube id for this title.</div>
        )}
      </div>
    </div>
  )
}

function Rail({ title, items, byId, recById, onAction, onPlay }) {
  if (!items?.length) return null
  return (
    <section>
      <h2>{title}</h2>
      <div className="rail">
        {items.map((it) => (
          <VideoCard key={it.videoId} video={byId[it.videoId]} rec={recById?.[it.videoId]} onAction={onAction} onPlay={onPlay} />
        ))}
      </div>
    </section>
  )
}

// YouTube-style chip bar. "For You" is the categorized homepage; each genre chip filters the feed.
// Genres the recommender has personalized for this user (from watch/rating history) are highlighted
// with a ★ and surfaced first — they "emerge" as the user's data grows.
function ChipBar({ tags, active, onSelect }) {
  return (
    <div className="chips">
      <button className={`chip ${active === 'For You' ? 'active' : ''}`} onClick={() => onSelect('For You')}>
        For You
      </button>
      {tags.map((t) => (
        <button
          key={t.genre}
          className={`chip ${active === t.genre ? 'active' : ''} ${t.personalized ? 'personalized' : ''}`}
          onClick={() => onSelect(t.genre)}
          title={t.personalized ? `Recommended for you · affinity ${t.score}` : 'Popular category'}
        >
          {t.personalized ? '★ ' : ''}{t.genre}
        </button>
      ))}
    </div>
  )
}

export default function App() {
  const [auth, setAuth] = useState(loadAuth())
  const [videos, setVideos] = useState([])
  const [recs, setRecs] = useState([])
  const [trending, setTrending] = useState([])
  const [tags, setTags] = useState([])
  const [activeTab, setActiveTab] = useState('For You')
  const [overview, setOverview] = useState(null)
  const [status, setStatus] = useState('')
  const [playing, setPlaying] = useState(null)

  const byId = Object.fromEntries(videos.map((v) => [v.id, v]))
  const recById = Object.fromEntries(recs.map((r) => [r.videoId, r]))

  // group the catalog by genre, ordered by the recommendation-tags ranking
  const videosByGenre = {}
  for (const v of videos) (videosByGenre[v.genre] ||= []).push(v)
  const genreOrder = tags.length
    ? tags.map((t) => t.genre).filter((g) => videosByGenre[g])
    : Object.keys(videosByGenre)

  const refresh = useCallback(async () => {
    if (!auth) return
    const [cat, rec, trend, tg, ov] = await Promise.all([
      api.listVideos(200),
      api.recommendations(auth.userId, auth.token).catch(() => []),
      api.trending(auth.token).catch(() => []),
      api.recommendedTags(auth.userId, auth.token).catch(() => []),
      api.overview(auth.token).catch(() => null),
    ])
    setVideos(cat.content || [])
    setRecs(rec || [])
    setTrending(trend || [])
    setTags(tg || [])
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
      // give the reco consumer a moment to fold the event in, then refresh recs + tags so the
      // chip bar re-ranks as the user's data grows
      setTimeout(async () => {
        const [rec, tg] = await Promise.all([
          api.recommendations(auth.userId, auth.token).catch(() => []),
          api.recommendedTags(auth.userId, auth.token).catch(() => []),
        ])
        setRecs(rec || [])
        setTags(tg || [])
        setStatus('Recommendations updated')
      }, 800)
    } catch (e) {
      setStatus('Action failed: ' + e.message)
    }
  }

  // Opening the player counts as a watch event (fed into the pipeline) and shows the embed.
  const onPlay = (video) => {
    setPlaying(video)
    onAction('watch', video.id)
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

      <ChipBar tags={tags} active={activeTab} onSelect={setActiveTab} />

      {activeTab === 'For You' ? (
        <>
          <Rail title="Recommended for you" items={recs} byId={byId} recById={recById} onAction={onAction} onPlay={onPlay} />
          <Rail title="Trending now" items={trending} byId={byId} onAction={onAction} onPlay={onPlay} />
          {genreOrder.map((g) => (
            <Rail
              key={g}
              title={g}
              items={(videosByGenre[g] || []).map((v) => ({ videoId: v.id }))}
              byId={byId}
              onAction={onAction}
              onPlay={onPlay}
            />
          ))}
        </>
      ) : (
        <section>
          <h2>{activeTab}</h2>
          <div className="grid">
            {(videosByGenre[activeTab] || []).map((v) => (
              <VideoCard key={v.id} video={v} onAction={onAction} onPlay={onPlay} />
            ))}
          </div>
        </section>
      )}

      <PlayerModal video={playing} onClose={() => setPlaying(null)} />
    </div>
  )
}
