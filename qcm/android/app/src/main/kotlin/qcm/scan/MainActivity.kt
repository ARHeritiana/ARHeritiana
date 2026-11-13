package qcm.scan

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File
import java.security.SecureRandom
import qcm.core.CleLot
import qcm.core.LectureImpossible

/** Accueil : photographier une copie, liste des lots (un lot = une clé = un devoir). */
class MainActivity : ComponentActivity() {
    private lateinit var base: Base
    private lateinit var liste: ListView
    private var lots: List<LotInfo> = emptyList()

    private val photoUri: Uri by lazy {
        FileProvider.getUriForFile(this, "$packageName.fichiers", File(cacheDir, "photo.jpg"))
    }
    private val prendre = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) traiter(photoUri) }
    private val choisir = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) traiter(uri) }
    private val fichierCle = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val texte = try {
                contentResolver.openInputStream(uri)?.use { String(it.readBytes().copyOf(4096), Charsets.UTF_8).trimEnd('\u0000') }
            } catch (e: Exception) { null }
            importer(texte ?: "")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        base = Base(this)
        val d = resources.displayMetrics.density
        val m = (12 * d).toInt()
        liste = ListView(this)
        liste.setOnItemClickListener { _, _, pos, _ ->
            startActivity(Intent(this, LotActivity::class.java).putExtra("lot", lots[pos].id))
        }
        val racine = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(m, m, m, m)
            addView(Button(context).apply {
                text = "Photographier une copie"; textSize = 18f
                setOnClickListener { photographier() }
            })
            addView(Button(context).apply {
                text = "Choisir une photo existante"
                setOnClickListener { choisir.launch("image/*") }
            })
            addView(TextView(context).apply {
                text = "Lots (toucher un lot pour voir les notes et exporter)"
                setPadding(0, m, 0, m / 2)
            })
            addView(liste, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(Button(context).apply { text = "Importer une clé (coller le texte)"; setOnClickListener { coller() } })
            addView(Button(context).apply { text = "Importer une clé (fichier qcm_cle.txt)"; setOnClickListener { fichierCle.launch("*/*") } })
            addView(Button(context).apply { text = "Nouvelle clé (nouveau lot)"; setOnClickListener { nouvelle() } })
        }
        setContentView(racine)
    }

    override fun onResume() {
        super.onResume()
        rafraichir()
    }

    private fun rafraichir() {
        lots = base.lots()
        val lignes = if (lots.isEmpty()) listOf("Aucun lot : importer ou créer une clé pour commencer.")
        else lots.map { "${it.nom}   (${it.copies} copie${if (it.copies > 1) "s" else ""}, id ${it.id})" }
        liste.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, lignes)
        liste.isEnabled = lots.isNotEmpty()
    }

    private fun message(titre: String, texte: String) {
        AlertDialog.Builder(this).setTitle(titre).setMessage(texte).setPositiveButton("OK", null).show()
    }

    // ------------------------------------------------------------- scan

    private fun photographier() {
        if (lots.isEmpty()) { message("Aucune clé", "Importer ou créer d'abord la clé du lot."); return }
        try { prendre.launch(photoUri) }
        catch (e: ActivityNotFoundException) { message("Appareil photo", "Aucune application d'appareil photo n'est disponible.") }
    }

    private fun traiter(uri: Uri) {
        val attente = AlertDialog.Builder(this).setMessage("Lecture de la copie…").setCancelable(false).show()
        Thread {
            val resultat: Any = try {
                Photo.analyser(this, base, uri)
            } catch (e: LectureImpossible) {
                e.message ?: "Lecture impossible."
            } catch (e: Throwable) {
                "Erreur inattendue : ${e.javaClass.simpleName} ${e.message ?: ""}"
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                attente.dismiss()
                if (resultat is Scan) {
                    Session.enCours = resultat
                    startActivity(Intent(this, VerifActivity::class.java))
                } else message("Lecture impossible", resultat.toString())
            }
        }.start()
    }

    // -------------------------------------------------------------- clés

    private fun saisie(titre: String, indication: String, multiligne: Boolean, suite: (String) -> Unit) {
        val champ = EditText(this).apply {
            hint = indication
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                (if (multiligne) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
        }
        AlertDialog.Builder(this).setTitle(titre).setView(champ)
            .setPositiveButton("OK") { _, _ -> suite(champ.text.toString()) }
            .setNegativeButton("Annuler", null).show()
    }

    private fun coller() = saisie("Importer une clé", "QCMKEY1:…", true) { importer(it) }

    private fun importer(texte: String) {
        val cle = CleLot.lire(texte)
        if (cle == null) { message("Clé invalide", "Le texte attendu est la ligne « QCMKEY1:…:nom du lot » du fichier qcm_cle.txt."); return }
        val nouvelle = base.ajouterLot(cle)
        Toast.makeText(this, if (nouvelle) "Clé importée (id ${cle.id})" else "Cette clé était déjà dans le téléphone", Toast.LENGTH_LONG).show()
        rafraichir()
    }

    private fun nouvelle() = saisie("Nouveau lot", "Nom du lot, par ex. DS1-2026", false) { saisi ->
        val nom = saisi.replace(Regex("[\\r\\n]"), " ").trim()
        val cle = CleLot(ByteArray(32).also { SecureRandom().nextBytes(it) }, nom)
        base.ajouterLot(cle)
        rafraichir()
        AlertDialog.Builder(this).setTitle("Clé créée")
            .setMessage("Pour produire les sujets, la ligne de clé doit être placée dans un fichier qcm_cle.txt à côté de qcm_personnalise.tex.\n\n" +
                "Cette clé est SECRÈTE : ne l'envoyer qu'à soi-même. On peut la partager plus tard depuis l'écran du lot.")
            .setPositiveButton("Partager la clé maintenant") { _, _ -> partagerCle(this, cle) }
            .setNegativeButton("Plus tard", null).show()
    }
}

fun partagerCle(ctx: android.content.Context, cle: CleLot) {
    val envoi = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, "qcm_cle.txt (${cle.nom})")
        .putExtra(Intent.EXTRA_TEXT, cle.texte())
    ctx.startActivity(Intent.createChooser(envoi, "Envoyer la clé (secrète) vers…"))
}
