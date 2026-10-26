package legalatlas.core

import kotlin.math.abs

/** Координаты шестиугольника в осевой системе (q, r). */
data class Hex(val q: Int, val r: Int) {
    fun neighbors() = DIRECTIONS.map { Hex(q + it.q, r + it.r) }
    fun distance(o: Hex) = (abs(q - o.q) + abs(q + r - o.q - o.r) + abs(r - o.r)) / 2

    companion object {
        val DIRECTIONS = listOf(Hex(1, 0), Hex(1, -1), Hex(0, -1), Hex(-1, 0), Hex(-1, 1), Hex(0, 1))
    }
}

/**
 * Раскладывает области одного мира в соты так, чтобы смысловые соседи
 * по возможности оказались рядом. Порядок детерминированный: карта не
 * «прыгает» между запусками, а новые области пристраиваются к старым.
 */
object HexLayout {
    fun layout(topics: List<Topic>): Map<String, Hex> {
        val placed = linkedMapOf<String, Hex>()
        val ids = topics.map { it.id }.toSet()
        val links = topics.associate { t -> t.id to t.neighbors.filter { it in ids }.toMutableSet() }
        // Связи делаем симметричными: если A считает B соседом, то и B — A.
        for ((id, ns) in links) for (n in ns) links[n]?.add(id)

        for (topic in topics) {
            if (placed.isEmpty()) {
                placed[topic.id] = Hex(0, 0)
                continue
            }
            val occupied = placed.values.toSet()
            val anchors = links[topic.id].orEmpty().mapNotNull { placed[it] }.ifEmpty { occupied.toList() }
            val candidates = anchors.flatMap { it.neighbors() }.filter { it !in occupied }.distinct()
            // Больше соседей рядом, затем ближе к центру, затем фиксированный порядок.
            val best = candidates.maxWith(
                compareBy<Hex>({ c -> c.neighbors().count { it in anchors } }, { -it.distance(Hex(0, 0)) }, { -it.r }, { it.q }),
            )
            placed[topic.id] = best
        }
        return placed
    }
}
