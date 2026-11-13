package qcm.scan

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import qcm.core.Etat
import qcm.core.Notation
import qcm.core.Zone

/** Écran de vérification : le professeur contrôle (et corrige au doigt) la lecture avant d'enregistrer. */
class VerifActivity : ComponentActivity() {
    private lateinit var scan: Scan
    private lateinit var vue: ZoneView
    private lateinit var resume: TextView
    private lateinit var douteuses: Array<BooleanArray>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = Session.enCours
        if (s == null) { finish(); return }          // application relancée : le scan est perdu
        scan = s
        douteuses = Array(Zone.LIGNES) { i -> BooleanArray(Zone.COLONNES) { j -> s.lecture.etats[i][j] == Etat.DOUTEUSE } }
        vue = ZoneView(this, s.zone, s.lecture.cochees(), douteuses) { rafraichir() }
        resume = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 15f }
        val d = resources.displayMetrics.density
        val marge = (8 * d).toInt()

        val boutons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Button(context).apply { text = "Enregistrer"; setOnClickListener { enregistrer() } })
            addView(Button(context).apply { text = "Annuler"; setOnClickListener { finish() } })
        }
        val bas = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(marge, marge, marge, marge)
            addView(resume, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(boutons)
        }
        val racine = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(vue, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(bas)
        }
        setContentView(ScrollView(this).apply { addView(racine) })
        rafraichir()
    }

    private fun rafraichir() {
        val rep = Notation.reponses(vue.cochees)
        val cle = scan.contenu.cle
        val note = Notation.note(cle, rep)
        title = "${scan.contenu.nom} : $note/10  (${scan.lot.nom})"
        val t = SpannableStringBuilder("Lu  : ")
        for (i in rep.indices) {
            val debut = t.length
            t.append(rep[i]).append(' ')
            val couleur = when { rep[i] == cle[i] -> Color.rgb(20, 130, 50); rep[i] in 'A'..'D' || rep[i] == Notation.MULTIPLE -> Color.rgb(200, 30, 30); else -> Color.GRAY }
            t.setSpan(ForegroundColorSpan(couleur), debut, debut + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        t.append("\nClé : ").append(cle.toList().joinToString(" "))
        t.append("\n. = pas de réponse   * = plusieurs cases")
        val n = douteuses.sumOf { l -> l.count { it } }
        if (n > 0) {
            val debut = t.length
            t.append("\n$n case(s) douteuse(s), entourée(s) en orange : toucher une case pour la corriger.")
            t.setSpan(ForegroundColorSpan(Color.rgb(220, 110, 0)), debut, t.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else t.append("\nToucher une case pour corriger la lecture.")
        resume.text = t
    }

    private fun enregistrer() {
        val base = Base(this)
        val rep = Notation.reponses(vue.cochees)
        val note = Notation.note(scan.contenu.cle, rep)
        val faire = {
            base.enregistrer(scan.lot.id, scan.contenu.nom, rep, scan.contenu.cle, note)
            Toast.makeText(this, "${scan.contenu.nom} : $note/10 enregistré", Toast.LENGTH_SHORT).show()
            Session.enCours = null
            finish()
        }
        val ancienne = base.copie(scan.lot.id, scan.contenu.nom)
        if (ancienne == null) faire()
        else AlertDialog.Builder(this)
            .setTitle("Copie déjà scannée")
            .setMessage("${scan.contenu.nom} a déjà une copie enregistrée dans ce lot (${ancienne.note}/10, le ${dateTexte(ancienne.scan)}).\n\nLa remplacer par cette lecture ($note/10) ?")
            .setPositiveButton("Remplacer") { _, _ -> faire() }
            .setNegativeButton("Garder l'ancienne", null)
            .show()
    }
}
