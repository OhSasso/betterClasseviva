package com.example.betterclasseviva

import android.content.Context
import android.widget.TextView
import com.github.mikephil.charting.components.MarkerView
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.utils.MPPointF

/** "MM-DD" -> "DD/MM" */
fun formatData(d: String): String {
    val p = d.split("-")
    return if (p.size == 2) "${p[1]}/${p[0]}" else d
}

/** Chiave di ordinamento per anno scolastico: settembre..dicembre prima di gennaio..giugno. */
fun chiaveData(d: String): Int {
    val p = d.split("-")
    val m = p.getOrNull(0)?.toIntOrNull() ?: return 0
    val g = p.getOrNull(1)?.toIntOrNull() ?: 0
    return (if (m < 9) m + 12 else m) * 100 + g
}

/** Tooltip mostrato toccando il grafico: media in quel momento + voto che l'ha generata. */
class VotoMarker(
    context: Context,
    private val voti: List<Pair<String, Voto>>
) : MarkerView(context, R.layout.marker_voto) {

    private val titolo: TextView = findViewById(R.id.markerTitle)
    private val sotto: TextView = findViewById(R.id.markerSub)

    override fun refreshContent(e: Entry, highlight: Highlight) {
        titolo.text = String.format("Media %.2f", e.y)
        val (materia, voto) = voti.getOrNull(e.x.toInt()) ?: return super.refreshContent(e, highlight)
        sotto.text = "${voto.grade} · ${materia.replaceFirstChar { it.uppercase() }} · ${formatData(voto.date)}"
        super.refreshContent(e, highlight)
    }

    override fun getOffset(): MPPointF = MPPointF(-(width / 2f), -height.toFloat() - 14f)
}
