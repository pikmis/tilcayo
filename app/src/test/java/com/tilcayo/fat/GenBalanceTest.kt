package com.tilcayo.fat

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Проверки «честности» генерации: сколько опасностей выпадает на отрезок пути,
 * остаётся ли проход и не ставятся ли несовместимые ловушки рядом.
 *
 * Печатает сводку по высотам, поэтому годится и как отчёт, и как регресс-тест.
 */
class GenBalanceTest {

    private fun lib() = ChunkLib.parse(File("src/main/res/raw/chunks.txt").readText())

    /** Опасность как прямоугольник, в который нельзя влетать. */
    private class H(val kind: Char, val y0: Float, val y1: Float, val x0: Float, val x1: Float)

    private fun hazards(sim: Sim): List<H> {
        val out = ArrayList<H>()
        for (s in sim.spikes) {
            val x0 = if (s.left) Phys.WALL else Phys.W - Phys.WALL - Phys.SPIKE_LEN
            out.add(H('S', s.y0, s.y1, x0, x0 + Phys.SPIKE_LEN))
        }
        for (s in sim.saws) {
            out.add(H('W', min(s.y0, s.y1) - 13f, max(s.y0, s.y1) + 13f, min(s.x0, s.x1) - 13f, max(s.x0, s.x1) + 13f))
        }
        for (o in sim.orbits) {
            out.add(H('O', o.cy - o.rad - 12f, o.cy + o.rad + 12f, o.cx - o.rad - 12f, o.cx + o.rad + 12f))
        }
        for (l in sim.lasers) out.add(H('L', l.y - 4f, l.y + 4f, Phys.WALL, Phys.W - Phys.WALL))
        for (p in sim.pillars) out.add(H(if (p.hot) 'H' else 'P', p.y0, p.y1, p.x0, p.x1))
        for (b in sim.bumpers) out.add(H('B', b.y - 17f, b.y + 17f, b.x - 17f, b.x + 17f))
        return out
    }

    /** Сколько опасностей попадает в скользящее окно высотой [win]. */
    private fun maxInWindow(haz: List<H>, from: Float, to: Float, win: Float, step: Float): Int {
        var worst = 0
        var y = -from
        while (y > -to) {
            var n = 0
            for (h in haz) if (h.y1 > y - win / 2 && h.y0 < y + win / 2) n++
            if (n > worst) worst = n
            y -= step
        }
        return worst
    }

    /** Есть ли на этой высоте щель шириной [gap] между опасностями. */
    private fun hasGap(haz: List<H>, y: Float, gap: Float): Boolean {
        val xs = haz.filter { it.y1 > y - 45f && it.y0 < y + 45f }
            .map { max(0f, it.x0) to min(Phys.W, it.x1) }
            .sortedBy { it.first }
        var cur = 0f
        for ((a, b) in xs) {
            if (a - cur >= gap) return true
            cur = max(cur, b)
        }
        return Phys.W - cur >= gap
    }

    private fun blockedShare(haz: List<H>, from: Float, to: Float, gap: Float): Float {
        // Пилы на рельсах двигаются: в статике они не «запирают» высоту, их учитывает max/240px.
        val stat = haz.filter { it.kind != 'W' }
        var bad = 0
        var all = 0
        var y = -from
        while (y > -to) {
            all++
            if (!hasGap(stat, y, gap)) bad++
            y -= 50f
        }
        return if (all == 0) 0f else bad * 100f / all
    }

    /** Пары «бампер рядом с режущей ловушкой» — отбрасывают игрока прямо в смерть. */
    private fun bumperTraps(haz: List<H>): Int {
        var n = 0
        val blades = haz.filter { it.kind == 'W' || it.kind == 'O' || it.kind == 'H' }
        for (b in haz) {
            if (b.kind != 'B') continue
            val bx = (b.x0 + b.x1) / 2
            val by = (b.y0 + b.y1) / 2
            for (s in blades) {
                val dx = max(0f, max(s.x0 - bx, bx - s.x1))
                val dy = max(0f, max(s.y0 - by, by - s.y1))
                if (hypot(dx, dy) < 58f) { n++; break }
            }
        }
        return n
    }

    private class Stats(val density: Float, val window: Int, val blocked: Float, val traps: Int, val kinds: Map<Char, Float>)

    private fun measure(library: List<ChunkTemplate>, seed: Int, from: Float, to: Float): Stats {
        val sim = Sim()
        sim.reset(1, 1f, 800f)
        val gen = Gen(sim, library, Random(seed * 31))
        gen.bossChance = 0f
        gen.fill(-to)
        val all = hazards(sim)
        val haz = all.filter { (it.y0 + it.y1) / 2 <= -from && (it.y0 + it.y1) / 2 > -to }
        val kinds = haz.groupingBy { it.kind }.eachCount().mapValues { it.value * 1000f / (to - from) }
        return Stats(
            haz.size * 1000f / (to - from),
            maxInWindow(haz, from, to, 240f, 40f),
            blockedShare(haz, from, to, 74f),
            bumperTraps(haz),
            kinds,
        )
    }

    @Test
    fun reportDensity() {
        val f = File("src/main/res/raw/chunks.txt")
        assumeTrue(f.exists())
        val library = lib()
        val ranges = listOf(0f to 2000f, 2000f to 4000f, 4000f to 6000f, 6000f to 8000f)
        val seeds = 6
        val out = StringBuilder()
        fun log(s: String) {
            println(s)
            out.append(s).append('\n')
        }
        log("SEGMENT     haz/1000  max/240px  blocked%  bumperTraps   S     W     O     L     P     B")
        var worstWindow = 0f
        var worstBlocked = 0f
        var worstTraps = 0f
        for (r in ranges) {
            var d = 0f; var w = 0; var b = 0f; var t = 0
            val kinds = HashMap<Char, Float>()
            for (seed in 1..seeds) {
                val s = measure(library, seed, r.first, r.second)
                d += s.density; w += s.window; b += s.blocked; t += s.traps
                for ((k, v) in s.kinds) kinds[k] = (kinds[k] ?: 0f) + v
            }
            fun k(c: Char) = (kinds[c] ?: 0f) / seeds
            worstWindow = maxOf(worstWindow, w.toFloat() / seeds)
            worstBlocked = maxOf(worstBlocked, b / seeds)
            worstTraps = maxOf(worstTraps, t.toFloat() / seeds)
            log(
                "%-10s %8.1f %10.1f %9.1f %12.1f %5.1f %5.1f %5.1f %5.1f %5.1f %5.1f".format(
                    "${r.first.toInt()}-${r.second.toInt()}",
                    d / seeds, w.toFloat() / seeds, b / seeds, t.toFloat() / seeds,
                    k('S'), k('W'), k('O'), k('L'), k('P'), k('B'),
                ),
            )
        }
        File("build/gen-report.txt").apply { parentFile.mkdirs() }.writeText(out.toString())

        // Непроходимых «стен» быть не должно: в окне одного прыжка не больше 5 опасностей.
        assert(worstWindow <= 5f) { "слишком плотная стена: $worstWindow опасностей в окне 240 px" }
        // Бампер не должен стоять вплотную к режущей ловушке.
        assert(worstTraps == 0f) { "бамперы рядом с пилами: $worstTraps шт." }
        // В любом месте обязана оставаться вертикальная щель между неподвижными опасностями.
        assert(worstBlocked <= 6f) { "перекрыто $worstBlocked% высоты" }
    }
}
