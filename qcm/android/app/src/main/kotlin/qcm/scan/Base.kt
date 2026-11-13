package qcm.scan

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import qcm.core.CleLot
import qcm.core.Copie

/**
 * Les clés des lots sont enregistrées chiffrées (AES-GCM) par une clé maîtresse gardée
 * dans l'Android Keystore : elle ne quitte jamais le téléphone et n'est pas sauvegardée.
 */
object Coffre {
    private const val ALIAS = "qcm_cle_maitresse"

    private fun cle(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return kg.generateKey()
    }

    fun proteger(clair: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, cle())
        return c.iv + c.doFinal(clair)                    // IV (12 octets) puis chiffré
    }

    fun ouvrir(b: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, cle(), GCMParameterSpec(128, b, 0, 12))
        return c.doFinal(b, 12, b.size - 12)
    }
}

class LotInfo(val id: String, val nom: String, val copies: Int)

class CopieInfo(val rowId: Long, val nom: String, val reponses: String, val cle: String, val note: Int, val scan: Long)

fun dateTexte(t: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(t))

class Base(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "qcm.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE lot(id TEXT PRIMARY KEY, nom TEXT NOT NULL, cle BLOB NOT NULL, cree INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE copie(_id INTEGER PRIMARY KEY AUTOINCREMENT, lot TEXT NOT NULL, nom TEXT NOT NULL, " +
            "reponses TEXT NOT NULL, cle TEXT NOT NULL, note INTEGER NOT NULL, scan INTEGER NOT NULL, UNIQUE(lot, nom))")
    }

    override fun onUpgrade(db: SQLiteDatabase, ancienne: Int, nouvelle: Int) {}

    fun lots(): List<LotInfo> {
        val l = ArrayList<LotInfo>()
        readableDatabase.rawQuery(
            "SELECT lot.id, lot.nom, (SELECT COUNT(*) FROM copie WHERE copie.lot = lot.id) FROM lot ORDER BY lot.cree DESC", null).use { c ->
            while (c.moveToNext()) l.add(LotInfo(c.getString(0), c.getString(1), c.getInt(2)))
        }
        return l
    }

    fun lot(id: String): LotInfo? = lots().firstOrNull { it.id == id }

    /** Clé secrète d'un lot (déchiffrée par le Keystore), ou null si le lot est inconnu. */
    fun cle(id: String): CleLot? {
        readableDatabase.rawQuery("SELECT nom, cle FROM lot WHERE id = ?", arrayOf(id)).use { c ->
            if (!c.moveToFirst()) return null
            return CleLot(Coffre.ouvrir(c.getBlob(1)), c.getString(0))
        }
    }

    /** Ajoute un lot. Renvoie false si cette clé était déjà connue (le nom est alors mis à jour s'il est fourni). */
    fun ajouterLot(cle: CleLot): Boolean {
        val id = cle.id
        val existant = lot(id)
        if (existant != null) {
            if (cle.nom.isNotBlank() && cle.nom != existant.nom)
                writableDatabase.update("lot", ContentValues().apply { put("nom", cle.nom) }, "id = ?", arrayOf(id))
            return false
        }
        writableDatabase.insertOrThrow("lot", null, ContentValues().apply {
            put("id", id); put("nom", cle.nom.ifBlank { "Lot $id" })
            put("cle", Coffre.proteger(cle.cle)); put("cree", System.currentTimeMillis())
        })
        return true
    }

    fun supprimerLot(id: String) {
        writableDatabase.delete("copie", "lot = ?", arrayOf(id))
        writableDatabase.delete("lot", "id = ?", arrayOf(id))
    }

    fun copies(lot: String): List<CopieInfo> {
        val l = ArrayList<CopieInfo>()
        readableDatabase.rawQuery(
            "SELECT _id, nom, reponses, cle, note, scan FROM copie WHERE lot = ? ORDER BY nom COLLATE LOCALIZED", arrayOf(lot)).use { c ->
            while (c.moveToNext()) l.add(CopieInfo(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4), c.getLong(5)))
        }
        return l
    }

    fun copie(lot: String, nom: String): CopieInfo? = copies(lot).firstOrNull { it.nom == nom }

    /** Enregistre une copie ; remplace celle du même élève dans le même lot s'il y en a une. */
    fun enregistrer(lot: String, nom: String, reponses: String, cle: String, note: Int) {
        writableDatabase.insertWithOnConflict("copie", null, ContentValues().apply {
            put("lot", lot); put("nom", nom); put("reponses", reponses); put("cle", cle)
            put("note", note); put("scan", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun supprimerCopie(rowId: Long) { writableDatabase.delete("copie", "_id = ?", arrayOf(rowId.toString())) }

    fun export(lot: LotInfo): List<Copie> =
        copies(lot.id).map { Copie(it.nom, it.reponses, it.cle, it.note, lot.nom, dateTexte(it.scan)) }
}
