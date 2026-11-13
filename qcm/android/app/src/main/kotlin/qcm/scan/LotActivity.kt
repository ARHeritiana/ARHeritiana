package qcm.scan

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File
import qcm.core.Export

/** Un lot : liste des copies corrigées, export du tableau (CSV / XLSX), partage de la clé. */
class LotActivity : ComponentActivity() {
    private lateinit var base: Base
    private lateinit var lot: LotInfo
    private lateinit var liste: ListView
    private lateinit var bilan: TextView
    private var copies: List<CopieInfo> = emptyList()
    private var aEnregistrer: File? = null

    private val enregistrerSous = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data
        val f = aEnregistrer
        if (r.resultCode == RESULT_OK && uri != null && f != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(f.readBytes()) }
                Toast.makeText(this, "Fichier enregistré", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Échec de l'enregistrement : ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        base = Base(this)
        val l = base.lot(intent.getStringExtra("lot") ?: "")
        if (l == null) { finish(); return }
        lot = l
        title = lot.nom
        val m = (12 * resources.displayMetrics.density).toInt()
        bilan = TextView(this)
        liste = ListView(this)
        liste.setOnItemLongClickListener { _, _, pos, _ ->
            val c = copies.getOrNull(pos) ?: return@setOnItemLongClickListener true
            AlertDialog.Builder(this).setTitle("Supprimer cette copie ?")
                .setMessage("${c.nom} (${c.note}/10)")
                .setPositiveButton("Supprimer") { _, _ -> base.supprimerCopie(c.rowId); rafraichir() }
                .setNegativeButton("Annuler", null).show()
            true
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(m, m, m, m)
            addView(bilan)
            addView(liste, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(Button(context).apply { text = "Exporter le tableau (Excel .xlsx)"; setOnClickListener { exporter(true) } })
            addView(Button(context).apply { text = "Exporter le tableau (.csv)"; setOnClickListener { exporter(false) } })
            addView(Button(context).apply { text = "Partager la clé du lot"; setOnClickListener { cle() } })
            addView(Button(context).apply { text = "Supprimer le lot"; setOnClickListener { supprimer() } })
        })
    }

    override fun onResume() {
        super.onResume()
        if (::lot.isInitialized) rafraichir()
    }

    private fun rafraichir() {
        copies = base.copies(lot.id)
        val moyenne = if (copies.isEmpty()) "" else "   moyenne %.2f/10".format(copies.map { it.note }.average())
        bilan.text = "${copies.size} copie(s)$moyenne   (id de clé ${lot.id})\nAppui long sur une copie pour la supprimer."
        liste.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1,
            copies.map { "${it.nom}   ${it.note}/10\n${it.reponses.toList().joinToString(" ")}" })
    }

    private fun exporter(xlsx: Boolean) {
        val donnees = base.export(lot)
        if (donnees.isEmpty()) { Toast.makeText(this, "Aucune copie dans ce lot", Toast.LENGTH_SHORT).show(); return }
        val nomFichier = "notes_" + lot.nom.replace(Regex("[^A-Za-z0-9_-]+"), "_").ifBlank { lot.id } + (if (xlsx) ".xlsx" else ".csv")
        val type = if (xlsx) "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" else "text/csv"
        val f = File(File(cacheDir, "export").apply { mkdirs() }, nomFichier)
        if (xlsx) f.outputStream().use { Export.xlsx(donnees, it) }
        else f.writeText(Export.csv(donnees), Charsets.UTF_8)
        AlertDialog.Builder(this).setTitle(nomFichier)
            .setItems(arrayOf("Partager (e-mail, Drive, Bluetooth…)", "Enregistrer dans le téléphone")) { _, choix ->
                if (choix == 0) {
                    val uri = FileProvider.getUriForFile(this, "$packageName.fichiers", f)
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(type)
                        .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, nomFichier)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Envoyer le tableau vers…"))
                } else {
                    aEnregistrer = f
                    enregistrerSous.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(type).putExtra(Intent.EXTRA_TITLE, nomFichier))
                }
            }.show()
    }

    private fun cle() {
        AlertDialog.Builder(this).setTitle("Clé secrète")
            .setMessage("Quiconque possède cette clé peut lire les réponses dans les QR codes de ce lot. Ne l'envoyer qu'à soi-même (pour le fichier qcm_cle.txt ou un autre téléphone).")
            .setPositiveButton("Partager") { _, _ -> base.cle(lot.id)?.let { partagerCle(this, it) } }
            .setNegativeButton("Annuler", null).show()
    }

    private fun supprimer() {
        AlertDialog.Builder(this).setTitle("Supprimer le lot « ${lot.nom} » ?")
            .setMessage("La clé et les ${copies.size} copie(s) enregistrée(s) seront effacées de ce téléphone. Sans la clé (fichier qcm_cle.txt), les copies de ce lot ne pourront plus être lues.")
            .setPositiveButton("Supprimer") { _, _ -> base.supprimerLot(lot.id); finish() }
            .setNegativeButton("Annuler", null).show()
    }
}
