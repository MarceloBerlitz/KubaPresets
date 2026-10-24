package audio.kuba.presets

import org.json.JSONArray
import org.json.JSONObject

class TopCollection(val usagePage: Int, val usage: Int) {
    val input = sortedSetOf<Int>()
    val output = sortedSetOf<Int>()
    val feature = sortedSetOf<Int>()
}

/** Resumo do HID Report Descriptor (equivalente ao `device.collections` do WebHID). */
class HidInfo(
    val collections: List<TopCollection>,
    val usesReportIds: Boolean,
    val featureBytes: Map<Int, Int>,
) {
    fun collectionsJson(): JSONArray {
        fun reports(ids: Set<Int>) = JSONArray().also { a ->
            ids.forEach { a.put(JSONObject().put("reportId", it).put("items", JSONArray())) }
        }
        val arr = JSONArray()
        for (c in collections) {
            arr.put(
                JSONObject()
                    .put("usagePage", c.usagePage).put("usage", c.usage).put("type", 1)
                    .put("children", JSONArray())
                    .put("inputReports", reports(c.input))
                    .put("outputReports", reports(c.output))
                    .put("featureReports", reports(c.feature))
            )
        }
        return arr
    }

    companion object {
        fun empty() = HidInfo(emptyList(), false, emptyMap())

        fun parse(d: ByteArray): HidInfo {
            val cols = mutableListOf<TopCollection>()
            val featBits = HashMap<Int, Int>()
            var usagePage = 0
            var usage = -1
            var reportId = 0
            var size = 0
            var count = 0
            var depth = 0
            var anyId = false
            var cur: TopCollection? = null
            var i = 0
            while (i < d.size) {
                val p = d[i].toInt() and 0xFF
                if (p == 0xFE) { // long item
                    i += 3 + (if (i + 1 < d.size) d[i + 1].toInt() and 0xFF else 0)
                    continue
                }
                val sc = p and 3
                val n = if (sc == 3) 4 else sc
                if (i + n > d.size - 1 && n > 0) break
                var v = 0
                for (k in 0 until n) v = v or ((d[i + 1 + k].toInt() and 0xFF) shl (8 * k))
                val type = (p shr 2) and 3
                val tag = p shr 4
                when (type) {
                    1 -> when (tag) {
                        0 -> usagePage = v
                        7 -> size = v
                        8 -> { reportId = v; anyId = true }
                        9 -> count = v
                    }
                    2 -> if (tag == 0 && usage < 0) {
                        if (n == 4) { usagePage = v ushr 16; usage = v and 0xFFFF } else usage = v
                    }
                    0 -> {
                        when (tag) {
                            8 -> cur?.input?.add(reportId)
                            9 -> cur?.output?.add(reportId)
                            11 -> {
                                cur?.feature?.add(reportId)
                                featBits[reportId] = (featBits[reportId] ?: 0) + size * count
                            }
                            10 -> {
                                if (depth == 0) {
                                    cur = TopCollection(usagePage, maxOf(usage, 0)).also { cols.add(it) }
                                }
                                depth++
                            }
                            12 -> { depth--; if (depth <= 0) { depth = 0; cur = null } }
                        }
                        usage = -1
                    }
                }
                i += 1 + n
            }
            return HidInfo(cols, anyId, featBits.mapValues { (it.value + 7) / 8 })
        }
    }
}
