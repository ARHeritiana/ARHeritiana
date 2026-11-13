package qcm.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import qcm.core.ContenuQr
import qcm.core.ImageGrise
import qcm.core.Lecteur
import qcm.core.Lecture
import qcm.core.LectureImpossible
import qcm.core.QrQcm

/** Résultat d'une photo, en attente de vérification par le professeur. */
class Scan(val lot: LotInfo, val contenu: ContenuQr, val lecture: Lecture, val zone: Bitmap)

/** Passage du scan en cours de MainActivity à VerifActivity (trop gros pour un Intent). */
object Session { var enCours: Scan? = null }

object Photo {
    const val PX_PAR_MM = 6.0       // résolution de l'image redressée affichée

    /** Charge une photo en niveaux de gris (plus grand côté limité à 4096 pixels). */
    fun charger(ctx: Context, uri: Uri): ImageGrise {
        val dim = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, dim) }
        if (dim.outWidth <= 0) throw LectureImpossible("Image illisible.")
        var ech = 1
        while (maxOf(dim.outWidth, dim.outHeight) / ech > 4096) ech *= 2
        while (true) {
            try { return decoder(ctx, uri, ech) }
            catch (e: OutOfMemoryError) { if (ech >= 8) throw LectureImpossible("Mémoire insuffisante pour lire la photo."); ech *= 2 }
        }
    }

    private fun decoder(ctx: Context, uri: Uri, ech: Int): ImageGrise {
        val o = BitmapFactory.Options().apply { inSampleSize = ech; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            ?: throw LectureImpossible("Image illisible.")
        val w = bmp.width; val h = bmp.height
        val px = ByteArray(w * h)
        val ligne = IntArray(w)
        for (y in 0 until h) {
            bmp.getPixels(ligne, 0, w, 0, y, w, 1)
            val o0 = y * w
            for (x in 0 until w) {
                val c = ligne[x]
                px[o0 + x] = ((((c shr 16) and 255) * 77 + ((c shr 8) and 255) * 150 + (c and 255) * 29) shr 8).toByte()
            }
        }
        bmp.recycle()
        return ImageGrise(w, h, px)
    }

    fun versBitmap(g: ImageGrise): Bitmap {
        val px = IntArray(g.w * g.h) { val v = g.px[it].toInt() and 255; (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        return Bitmap.createBitmap(px, g.w, g.h, Bitmap.Config.ARGB_8888)
    }

    /** Lit la photo : QR, déchiffrement avec la clé du lot, cases cochées. */
    fun analyser(ctx: Context, base: Base, uri: Uri): Scan {
        val img = charger(ctx, uri)
        val lecture = Lecteur.lire(img)
        val id = QrQcm.idCle(lecture.qr)
            ?: throw LectureImpossible("Ce QR code n'est pas celui d'un QCM chiffré.")
        val lot = base.lot(id)
            ?: throw LectureImpossible("Cette copie appartient à un lot dont la clé n'est pas dans ce téléphone (id $id). Importer d'abord la clé du lot.")
        val contenu = QrQcm.dechiffrer(base.cle(id)!!.cle, lecture.qr)
            ?: throw LectureImpossible("QR code modifié ou falsifié : authentification refusée.")
        val zone = versBitmap(Lecteur.redresser(img, lecture.versImage, PX_PAR_MM))
        return Scan(lot, contenu, lecture, zone)
    }
}
