package app.toque.ui.screens

import android.view.View
import android.widget.TextView
import app.toque.Prefs
import app.toque.core.i18n.tr
import app.toque.recipes.LidlSync
import app.toque.recipes.OnlineSync
import app.toque.ui.Icon
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.Ui

class LidlRecipesScreen(a: MainActivity) : Screen(a) {
    override val title = tr("Recettes Lidl")
    private lateinit var syncText: TextView
    private var syncing = false

    override fun build(): View {
        val col = Ui.vertical(c)
        col.setPadding(Ui.u(24), Ui.u(6), Ui.u(24), Ui.u(24))
        val lidl = Ui.card(c, 24f)
        col.addView(lidl)
        lidl.addView(Ui.text(c, tr("Toque télécharge lui-même les recettes officielles du Monsieur Cuisine connect sur monsieur-cuisine.com (sans compte)."),
            16f, Ui.TEXT2).apply { setLineSpacing(0f, 1.15f) })
        syncText = Ui.text(c, "", 16f, Ui.TEXT)
        lidl.addView(syncText, Ui.lp(t = 10))
        val b1 = Ui.horizontal(c)
        b1.addView(Ui.button(c, tr("Synchroniser"), Ui.Style.PRIMARY, Icon.Kind.SYNC, height = 58) { sync(online = true) }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
        lidl.addView(b1, Ui.lp(t = 12))
        lidl.addView(Ui.text(c, tr("Autre source : les recettes déjà présentes dans l'appli d'origine du robot (remplacent celles du site)."),
            14f, Ui.TEXT3).apply { setLineSpacing(0f, 1.1f) }, Ui.lp(t = 12))
        val b2 = Ui.horizontal(c)
        b2.addView(Ui.button(c, tr("Importer de l'appli d'origine"), Ui.Style.SECONDARY, height = 52) { sync(online = false) }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
        b2.addView(Ui.button(c, tr("Ouvrir l'appli d'origine"), Ui.Style.GHOST, Icon.Kind.LINK, height = 52) { a.openVendorApp() })
        lidl.addView(b2, Ui.lp(t = 6))
        lidl.addView(settingsToggle(c, tr("Synchro automatique chaque nuit"), a.prefs.autoSync) { a.prefs.autoSync = it }, Ui.lp(t = 10))

        return Ui.scroll(c, col)
    }

    private fun sync(online: Boolean) {
        if (syncing) return
        if (!online && a.prefs.mode != Prefs.Mode.HARDWARE) {
            a.toast(tr("Disponible une fois Toque installé sur le robot (mode Machine réelle)"), Ui.WARN)
            return
        }
        syncing = true
        syncText.text = tr("Synchronisation en cours… (quelques minutes)")
        Thread {
            val r = if (online) OnlineSync(a, a.prefs, a.app.recipes, a.app.log).sync { msg -> view.post { syncText.text = msg } }
            else LidlSync(a, a.prefs, a.app.recipes, a.app.log).importNow { msg -> view.post { syncText.text = msg } }
            view.post {
                syncing = false
                refreshSync()
                a.toast(r.message, if (r.ok) Ui.OK else Ui.WARN)
            }
        }.start()
    }

    private fun refreshSync() {
        val last = a.prefs.lastSyncResult
        syncText.text = (if (last.isEmpty()) tr("Jamais synchronisé") else tr("Dernière synchro le %s", last)) +
            "\n" + tr("%d recettes dans Toque", a.app.recipes.count)
    }

    override fun onShow() {
        if (!syncing) refreshSync()
    }
}
