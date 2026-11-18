package app.toque.ui.screens

import android.view.View
import android.widget.TextView
import app.toque.core.i18n.tr
import app.toque.core.machine.RootShell
import app.toque.core.update.Edition
import app.toque.core.update.Editions
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.Ui
import app.toque.update.EditionStorage
import app.toque.update.Updater

/** Same selector in both builds. No recipe/favorite methods or motor-controller behavior are changed. */
class EditionScreen(a: MainActivity) : Screen(a) {
    override val title = tr("Version de l’application")
    private lateinit var status: TextView
    private lateinit var button: View
    private var working = false
    private var portSuspended = false
    private val updater by lazy { Updater(a) }

    override fun build(): View {
        val root = Ui.vertical(c)
        root.setPadding(Ui.u(24), Ui.u(10), Ui.u(24), Ui.u(24))
        root.addView(Ui.text(c, tr("Version utilisée : %s", EditionStorage.edition.label), 24f, Ui.TEXT, Ui.MEDIUM))
        root.addView(Ui.text(c, tr("Compilation : %s", updater.installedVersion), 17f, Ui.TEXT2), Ui.lp(t = 10))
        root.addView(Ui.text(c, tr("Fork Maxime inclut vos fonctionnalités. Référence Wuki60 reprend une release officielle avec ce sélecteur et des données séparées."), 18f, Ui.TEXT2), Ui.lp(t = 18))
        root.addView(Ui.text(c, tr("Chaque version garde ses recettes, ses favoris et ses préférences. Au premier passage, la référence reprend seulement les réglages de machine, de sécurité et de langue. Synchronisez ses recettes Lidl depuis Réglages."), 18f, Ui.TEXT2), Ui.lp(t = 16))
        root.addView(Ui.text(c, tr("Les deux versions se téléchargent depuis votre dépôt. Le sélecteur reste disponible pour revenir à l’autre version."), 18f, Ui.TEXT2), Ui.lp(t = 16))
        status = Ui.text(c, "", 18f, Ui.TEXT2)
        root.addView(status, Ui.lp(t = 18))
        val target = if (EditionStorage.edition == Edition.FORK) Edition.REFERENCE else Edition.FORK
        button = Ui.button(c, tr("Utiliser %s", target.label), Ui.Style.PRIMARY, height = 60) { choose(target) }
        root.addView(button, Ui.lp(Ui.WRAP, Ui.WRAP, t = 18))
        return Ui.scroll(c, root)
    }

    override fun canLeave(): Boolean = !working

    private fun idle(): Boolean = Editions.canSwitch(a.machine.connected, a.snapshot.busy, a.prefs.guidedRecipe)

    private fun choose(target: Edition) {
        if (working) return
        if (!idle()) { a.toast(tr("Terminez la recette et attendez que le robot soit au repos."), Ui.WARN); return }
        working = true
        button.isEnabled = false
        status.text = tr("Recherche sur GitHub…")
        Thread {
            try {
                if (!RootShell.available()) throw Updater.Failure(tr("Accès root nécessaire pour changer de version."))
                val release = updater.latest(target)
                view.post {
                    // Release lookup does not suspend the machine. Confirm again on the main thread.
                    working = false
                    button.isEnabled = true
                    status.text = ""
                    a.confirm(tr("Passer à %s", target.label),
                        tr("La version %s sera installée puis Toque redémarrera. Les données des deux versions restent indépendantes et conservées.", release.version),
                        tr("Installer")) {
                        if (!idle()) { a.toast(tr("Terminez la recette et attendez que le robot soit au repos."), Ui.WARN); return@confirm }
                        working = true
                        button.isEnabled = false
                        Thread {
                            try {
                                val file = updater.download(release) { p -> view.post { status.text = tr("Téléchargement de la version %s… %d %%", release.version, p) } }
                                view.post {
                                    if (!idle()) { failed(tr("Terminez la recette et attendez que le robot soit au repos.")); return@post }
                                    a.machine.suspendPort { verdict ->
                                        if (!verdict.ok) { failed(verdict.message); return@suspendPort }
                                        portSuspended = true
                                        status.text = tr("Installation en cours…")
                                        Thread {
                                            try { updater.install(file) }
                                            catch (e: Exception) { view.post { failed(e.message ?: tr("Erreur")) } }
                                        }.start()
                                    }
                                }
                            } catch (e: Exception) { view.post { failed(e.message ?: tr("Erreur")) } }
                        }.start()
                    }
                }
            } catch (e: Exception) { view.post { failed(e.message ?: tr("Erreur")) } }
        }.start()
    }

    private fun failed(message: String) {
        if (portSuspended) { a.machine.resumePort(); portSuspended = false }
        working = false
        button.isEnabled = true
        status.text = message
        a.toast(message, Ui.WARN)
    }
}
