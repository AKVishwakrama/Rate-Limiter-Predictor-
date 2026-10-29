export default function HealthStatus({ allowed, rejected }) {
  const total = allowed + rejected
  const rejectRate = total > 0 ? rejected / total : 0

  let color = '#8FA97A', label = 'Healthy'
  if (rejectRate > 0.3) { color = '#B5502E'; label = 'Critical — high rejection rate' }
  else if (rejectRate > 0.1) { color = '#D4A017'; label = 'Warning — elevated rejections' }

  return (
    <div className="bg-panel-900 border border-hairline rounded-lg p-4 flex items-center gap-3">
      <span className="w-3 h-3 rounded-full flex-shrink-0" style={{ background: color }} />
      <div>
        <p className="text-sm text-taupe-400">System health</p>
        <p className="text-lg font-display font-semibold" style={{ color }}>{label}</p>
      </div>
    </div>
  )
}