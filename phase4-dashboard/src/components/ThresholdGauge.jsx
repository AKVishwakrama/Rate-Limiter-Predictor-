export default function ThresholdGauge({ current, max = 20 }) {
  const pct = Math.max(0, Math.min(1, (current ?? 0) / max))
  const angle = -90 + pct * 180
  const color = pct > 0.7 ? '#8FA97A' : pct > 0.3 ? '#D4A017' : '#B5502E'

  return (
    <div className="bg-panel-900 border border-hairline rounded-lg p-4">
      <p className="text-sm text-taupe-400 mb-2">Per-client threshold (live)</p>
      <div className="relative w-full flex justify-center">
        <svg width="160" height="90" viewBox="0 0 160 90">
          <path d="M 10 80 A 70 70 0 0 1 150 80" fill="none" stroke="#3D362C" strokeWidth="12" strokeLinecap="round" />
          <path
            d="M 10 80 A 70 70 0 0 1 150 80"
            fill="none" stroke={color} strokeWidth="12" strokeLinecap="round"
            strokeDasharray={`${pct * 220} 220`}
            style={{ transition: 'stroke-dasharray 0.4s ease, stroke 0.4s ease' }}
          />
          <line
            x1="80" y1="80"
            x2={80 + 55 * Math.cos((angle * Math.PI) / 180)}
            y2={80 + 55 * Math.sin((angle * Math.PI) / 180)}
            stroke="#EDE4D3" strokeWidth="2.5"
            style={{ transition: 'all 0.4s ease' }}
          />
          <circle cx="80" cy="80" r="4" fill="#EDE4D3" />
        </svg>
      </div>
      <p className="text-center text-2xl font-display font-semibold text-cream-100 -mt-2">{current ?? '—'}</p>
      <p className="text-center text-xs text-taupe-400">of {max} requests/sec per client</p>
    </div>
  )
}