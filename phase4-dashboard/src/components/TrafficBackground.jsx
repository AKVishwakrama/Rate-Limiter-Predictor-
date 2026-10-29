import { useEffect, useRef } from 'react'

export default function TrafficBackground({ allowed = 0, rejected = 0 }) {
  const canvasRef = useRef(null)
  const particlesRef = useRef([])
  const statsRef = useRef({ allowed, rejected })

  useEffect(() => {
    statsRef.current = { allowed, rejected }
  }, [allowed, rejected])

  useEffect(() => {
    const canvas = canvasRef.current
    const ctx = canvas.getContext('2d')
    let animationId
    let width, height

    const resize = () => {
      width = canvas.width = window.innerWidth
      height = canvas.height = window.innerHeight
    }
    resize()
    window.addEventListener('resize', resize)

    const NODE_X = () => width * 0.5

    const spawnParticle = () => {
      const { allowed, rejected } = statsRef.current
      const total = allowed + rejected
      const isRejected = total > 0 && Math.random() < rejected / Math.max(1, total)
      return {
        x: -20,
        y: height * (0.15 + Math.random() * 0.7),
        speed: 1.0 + Math.random() * 1.8,
        radius: 1.3 + Math.random() * 2.2,
        rejected: isRejected,
        wobble: Math.random() * Math.PI * 2,
        opacity: 0.25 + Math.random() * 0.35,
      }
    }

    for (let i = 0; i < 35; i++) {
      const p = spawnParticle()
      p.x = Math.random() * width
      particlesRef.current.push(p)
    }

    const draw = () => {
      ctx.clearRect(0, 0, width, height)

      const gx = NODE_X(), gy = height / 2
      const gradient = ctx.createRadialGradient(gx, gy, 10, gx, gy, 220)
      gradient.addColorStop(0, 'rgba(201, 162, 39, 0.07)')
      gradient.addColorStop(1, 'rgba(201, 162, 39, 0)')
      ctx.fillStyle = gradient
      ctx.fillRect(0, 0, width, height)

      ctx.beginPath()
      ctx.arc(gx, gy, 26, 0, Math.PI * 2)
      ctx.strokeStyle = 'rgba(201, 162, 39, 0.3)'
      ctx.lineWidth = 1.5
      ctx.stroke()
      ctx.beginPath()
      ctx.arc(gx, gy, 42, 0, Math.PI * 2)
      ctx.strokeStyle = 'rgba(201, 162, 39, 0.12)'
      ctx.stroke()

      particlesRef.current.forEach((p) => {
        p.x += p.speed
        p.wobble += 0.02
        const yOffset = Math.sin(p.wobble) * 3

        ctx.beginPath()
        ctx.arc(p.x, p.y + yOffset, p.radius, 0, Math.PI * 2)
        ctx.fillStyle = p.rejected
          ? `rgba(181, 80, 46, ${p.opacity})`
          : `rgba(143, 169, 122, ${p.opacity})`
        ctx.fill()

        const nearGateway = Math.abs(p.x - gx) < 45
        if (p.rejected && nearGateway && p.speed > 0.05) {
          p.speed *= 0.85
        }

        if (p.x > width + 20 || (p.rejected && p.speed < 0.05 && p.x > gx)) {
          Object.assign(p, spawnParticle())
        }
      })

      animationId = requestAnimationFrame(draw)
    }

    draw()
    return () => {
      cancelAnimationFrame(animationId)
      window.removeEventListener('resize', resize)
    }
  }, [])

  return (
    <canvas
      ref={canvasRef}
      className="fixed inset-0 w-full h-full pointer-events-none"
      style={{ zIndex: 0 }}
    />
  )
}