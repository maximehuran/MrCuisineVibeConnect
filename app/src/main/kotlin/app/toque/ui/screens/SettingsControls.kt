package app.toque.ui.screens

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import app.toque.core.i18n.tr
import app.toque.ui.Ui

internal fun settingsToggle(c: Context, label: String, initial: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
    val row = Ui.horizontal(c)
    row.addView(Ui.text(c, label, 18f, Ui.TEXT), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
    var state = initial
    val sw = Ui.text(c, "", 16f, Ui.TEXT, Ui.MEDIUM).apply { gravity = Gravity.CENTER }
    fun paint() {
        sw.text = if (state) tr("Oui") else tr("Non")
        sw.setTextColor(if (state) 0xFF1A0E05.toInt() else Ui.TEXT2)
        sw.background = if (state) Ui.round(Ui.ACCENT, 24f) else Ui.round(Ui.SURFACE3, 24f)
    }
    paint()
    sw.setOnClickListener { state = !state; paint(); onChange(state) }
    row.addView(sw, LinearLayout.LayoutParams(Ui.u(84), Ui.u(46)))
    return row
}
