import { useEffect, useRef, useState } from 'react'
import {
  LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer,
} from 'recharts'
import TrafficBackground from './components/TrafficBackground'
import HealthStatus from './components/HealthStatus'
import ThresholdGauge from './components/ThresholdGauge'
import EventLog from './components/EventLog'

const GATEWAY_URL = 'http://localhost:8080'
const POLL_INTERVAL_MS = 1000
const MAX_POINTS = 60

const STRATEGIES = [
  { id: 'token-bucket', label: 'Token Bucket' },
  { id: 'sliding-window', label: 'Sliding Window' },
  { id: 'ml-adaptive', label: 'ML Adaptive' },
]

export default function App() {
  const [activeStrategy, setActiveStrategy] = useState(null)
  const [history, setHistory] = useState([])
  const [connectionError, setConnectionError] = useState(false)
  const [totals, setTotals] = useState({ allowed: 0, rejected: 0 })
  const secondsElapsed = useRef(0)

  useEffect(() => {
    const poll = async () => {
      try {
        const res = await fetch(`${GATEWAY_URL}/admin/metrics/live`)
        if (!res.ok) throw new Error(`HTTP ${res.status}`)
        const data = await res.json()

        setConnectionError(false)
        setActiveStrategy(data.activeStrategy)
        secondsElapsed.current += 1

        setTotals((prev) => ({
          allowed: prev.allowed + data.allowed,
          rejected: prev.rejected + data.rejected,
        }))

        setHistory((prev) => {
          const next = [...prev, {
            t: secondsElapsed.current,
            allowed: data.allowed,
            rejected: data.rejected,
            threshold: data.currentThreshold ?? null,
          }]
          return next.length > MAX_POINTS ? next.slice(next.length - MAX_POINTS) : next
        })
      } catch (e) {
        setConnectionError(true)
      }
    }

    poll()
    const interval = setInterval(poll, POLL_INTERVAL_MS)
    return () => clearInterval(interval)
  }, [])

  const switchStrategy = async (strategyId) => {
    try {
      await fetch(`${GATEWAY_URL}/admin/strategy`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ strategy: strategyId }),
      })
      setActiveStrategy(strategyId)
    } catch (e) {
      setConnectionError(true)
    }
  }

  const resetMetrics = async () => {
    await fetch(`${GATEWAY_URL}/admin/metrics/reset`, { method: 'POST' })
    setHistory([])
    setTotals({ allowed: 0, rejected: 0 })
    secondsElapsed.current = 0
  }

  const latest = history[history.length - 1]
  const successRate = totals.allowed + totals.rejected > 0
    ? ((totals.allowed / (totals.allowed + totals.rejected)) * 100).toFixed(1)
    : '—'

  return (
    <div className="min-h-screen relative text-cream-100 font-body">
      <div className="grain-overlay" />
      <TrafficBackground allowed={latest?.allowed ?? 0} rejected={latest?.rejected ?? 0} />

      <div className="relative z-10 max-w-5xl mx-auto p-8 space-y-6">
        <header>
          <h1 className="text-3xl font-display font-semibold text-cream-100">Adaptive Rate Limiter</h1>
          <div className="brass-rule my-3" />
          <p className="text-taupe-400 text-sm">
            Live traffic view — polling {GATEWAY_URL} every second
          </p>
        </header>

        {connectionError && (
          <div className="bg-rust-400/10 backdrop-blur border border-rust-400/40 text-rust-400 px-4 py-3 rounded-lg text-sm">
            Can't reach the gateway at {GATEWAY_URL}. Is it running?
            (<code>cd phase1-gateway && mvn spring-boot:run</code>)
          </div>
        )}

        <div className="flex flex-wrap items-center gap-3">
          {STRATEGIES.map((s) => (
            <button
              key={s.id}
              onClick={() => switchStrategy(s.id)}
              className={`px-4 py-2 rounded-lg text-sm font-medium transition-all border ${
                activeStrategy === s.id
                  ? 'bg-brass-400 border-brass-400 text-panel-950 font-semibold'
                  : 'bg-panel-900 border-hairline text-taupe-400 hover:border-brass-400/50 hover:text-cream-100'
              }`}
            >
              {s.label}
            </button>
          ))}
          <button
            onClick={resetMetrics}
            className="ml-auto px-4 py-2 rounded-lg text-sm font-medium bg-panel-900 border border-hairline text-taupe-400 hover:text-cream-100"
          >
            Reset metrics
          </button>
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
          <StatCard label="Active strategy" value={activeStrategy ?? '—'} />
          <StatCard label="Allowed (last sec)" value={latest?.allowed ?? '—'} accent="text-sage-400" />
          <StatCard label="Rejected (last sec)" value={latest?.rejected ?? '—'} accent="text-rust-400" />
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
          <HealthStatus allowed={totals.allowed} rejected={totals.rejected} />
          <div className="bg-panel-900 border border-hairline rounded-lg p-4">
            <p className="text-sm text-taupe-400">Success rate (session)</p>
            <p className="text-2xl font-display font-semibold mt-1 text-cream-100">{successRate}{successRate !== '—' && '%'}</p>
            <p className="text-xs text-taupe-400 mt-1">{totals.allowed} allowed / {totals.rejected} rejected</p>
          </div>
          {activeStrategy === 'ml-adaptive' ? (
            <ThresholdGauge current={latest?.threshold} max={20} />
          ) : (
            <div className="bg-panel-900 border border-hairline rounded-lg p-4 flex items-center justify-center text-center">
              <p className="text-xs text-taupe-400">Switch to ML Adaptive to see the live threshold gauge</p>
            </div>
          )}
        </div>

        <EventLog activeStrategy={activeStrategy} threshold={latest?.threshold} />

        <div className="bg-panel-900 border border-hairline rounded-lg p-4">
          <p className="text-sm text-taupe-400 mb-2">Allowed vs Rejected requests / second</p>
          <ResponsiveContainer width="100%" height={320}>
            <LineChart data={history}>
              <CartesianGrid strokeDasharray="3 3" stroke="#3D362C" />
              <XAxis dataKey="t" stroke="#A89A85" fontSize={12} />
              <YAxis stroke="#A89A85" fontSize={12} />
              <Tooltip
                contentStyle={{ background: '#26211C', border: '1px solid #3D362C', borderRadius: 8, color: '#EDE4D3' }}
              />
              <Legend />
              <Line type="monotone" dataKey="allowed" stroke="#8FA97A" dot={false} strokeWidth={2} />
              <Line type="monotone" dataKey="rejected" stroke="#B5502E" dot={false} strokeWidth={2} />
            </LineChart>
          </ResponsiveContainer>
        </div>

        <p className="text-xs text-taupe-400/70">
          This dashboard is a live view for demos/screenshots — it does NOT replace the
          Phase 2 simulator + analysis pipeline for actual paper results.
        </p>
      </div>
    </div>
  )
}

function StatCard({ label, value, accent = 'text-cream-100' }) {
  return (
    <div className="bg-panel-900 border border-hairline rounded-lg p-4">
      <p className="text-sm text-taupe-400">{label}</p>
      <p className={`text-2xl font-display font-semibold mt-1 ${accent}`}>{value}</p>
    </div>
  )
}