import { useEffect, useRef, useState } from 'react'

export default function EventLog({ activeStrategy, threshold }) {
  const [events, setEvents] = useState([])
  const prevStrategy = useRef(null)
  const prevThreshold = useRef(null)

  const pushEvent = (text, tone = 'info') => {
    setEvents((prev) => [
      { id: Date.now() + Math.random(), text, tone, time: new Date().toLocaleTimeString() },
      ...prev.slice(0, 19),
    ])
  }

  useEffect(() => {
    if (activeStrategy && prevStrategy.current && activeStrategy !== prevStrategy.current) {
      pushEvent(`Strategy switched: ${prevStrategy.current} → ${activeStrategy}`, 'switch')
    }
    prevStrategy.current = activeStrategy
  }, [activeStrategy])

  useEffect(() => {
    if (threshold != null && prevThreshold.current != null && threshold !== prevThreshold.current) {
      const direction = threshold < prevThreshold.current ? 'lowered' : 'raised'
      pushEvent(`Adaptive threshold ${direction}: ${prevThreshold.current} → ${threshold}`, 'threshold')
    }
    prevThreshold.current = threshold
  }, [threshold])

  const toneColor = { switch: '#C9A227', threshold: '#D4A017', info: '#A89A85' }

  return (
    <div className="bg-panel-900 border border-hairline rounded-lg p-4">
      <p className="text-sm text-taupe-400 mb-3">Live event log</p>
      <div className="space-y-1.5 max-h-40 overflow-y-auto pr-1">
        {events.length === 0 && (
          <p className="text-xs text-taupe-400/60">No events yet — switch strategies or wait for adaptive threshold changes.</p>
        )}
        {events.map((e) => (
          <div key={e.id} className="text-xs flex gap-2">
            <span className="text-taupe-400/60 flex-shrink-0">{e.time}</span>
            <span style={{ color: toneColor[e.tone] }}>{e.text}</span>
          </div>
        ))}
      </div>
    </div>
  )
}