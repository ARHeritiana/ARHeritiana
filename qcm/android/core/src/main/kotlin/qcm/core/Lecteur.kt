package qcm.core

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.decoder.Decoder
import com.google.zxing.qrcode.detector.Detector

/** Image en niveaux de gris (0 = noir, 255 = blanc), ligne par ligne. */
class ImageGrise(val w: Int, val h: Int, val px: ByteArray) {
    init { require(px.size == w * h) }
    fun at(x: Int, y: Int): Int = px[y * w + x].toInt() and 255

    /** Réduction par moyenne de blocs f x f. */
    fun reduire(f: Int): ImageGrise {
        if (f <= 1) return this
        val nw = w / f; val nh = h / f
        val out = ByteArray(nw * nh)
        for (y in 0 until nh) for (x in 0 until nw) {
            var s = 0
            for (dy in 0 until f) { val o = (y * f + dy) * w + x * f; for (dx in 0 until f) s += px[o + dx].toInt() and 255 }
            out[y * nw + x] = (s / (f * f)).toByte()
        }
        return ImageGrise(nw, nh, out)
    }
}

class LectureImpossible(message: String) : Exception(message)

enum class Etat { VIDE, COCHEE, DOUTEUSE }

/**
 * Résultat de la lecture d'une photo.
 * @param qr texte brut du QR (encore chiffré)
 * @param taux taux de noircissement de chaque case, [ligne][colonne], entre 0 et 1
 * @param etats décision pour chaque case (DOUTEUSE = à vérifier par le professeur)
 * @param versImage zone (mm) -> pixels de la photo
 * @param echelle résolution de la photo au niveau de la zone, en pixels par millimètre
 */
class Lecture(val qr: String, val taux: Array<DoubleArray>, val etats: Array<Array<Etat>>,
              val versImage: Homographie, val echelle: Double) {
    /** Cases considérées comme cochées (les douteuses penchent du côté le plus proche). */
    fun cochees(): Array<BooleanArray> = Array(Zone.LIGNES) { i ->
        BooleanArray(Zone.COLONNES) { j ->
            etats[i][j] == Etat.COCHEE || (etats[i][j] == Etat.DOUTEUSE && taux[i][j] >= (Lecteur.SEUIL_VIDE + Lecteur.SEUIL_COCHEE) / 2)
        }
    }
    val douteuses: Int get() = etats.sumOf { l -> l.count { it == Etat.DOUTEUSE } }
}

/**
 * Lecture d'une feuille photographiée :
 *  1. le QR est localisé et lu (ZXing) : il donne une première position approximative de la zone ;
 *  2. les 4 carrés noirs sont cherchés près de leur position prévue : ils donnent la
 *     transformation précise zone -> photo (corrige la perspective et la rotation) ;
 *  3. l'intérieur de chaque case est échantillonné : proportion de points sombres.
 */
object Lecteur {
    /** En dessous : case vide. Au-dessus de SEUIL_COCHEE : cochée. Entre les deux : douteuse. */
    const val SEUIL_VIDE = 0.04
    const val SEUIL_COCHEE = 0.10
    /** Part du côté de la case réellement analysée (on évite le cadre imprimé). */
    private const val PART_INTERIEURE = 0.62
    /** Un point est « sombre » s'il est plus foncé que blanc - CONTRASTE x (blanc - noir). */
    private const val CONTRASTE = 0.30
    private const val ECHELLE_MIN = 3.5     // pixels par mm : en dessous, photo trop petite

    fun lire(img: ImageGrise): Lecture {
        val (qr, approx, motifs) = lireQr(img)
            ?: throw LectureImpossible("QR code introuvable ou illisible : reprendre la photo (feuille entière, nette, bien éclairée).")
        // Les 2 carrés de droite sont tout près du QR : sa position suffit à les prévoir.
        val reperes = arrayOfNulls<Repere>(4)
        for (i in 1..2) reperes[i] = chercherRepere(img, approx, Zone.REPERES[i], 9.0)
        // Les 2 carrés de gauche sont à 15 cm du QR : on affine d'abord la prévision avec les
        // carrés de droite et les 2 motifs gauches du QR, et on cherche dans une fenêtre plus large.
        var approxGauche = approx
        if (reperes[1] != null && reperes[2] != null) {
            Homographie.depuis4Points(
                arrayOf(Zone.REPERES[1], Zone.REPERES[2], motifs[0].first, motifs[1].first),
                arrayOf(doubleArrayOf(reperes[1]!!.x, reperes[1]!!.y), doubleArrayOf(reperes[2]!!.x, reperes[2]!!.y),
                    motifs[0].second, motifs[1].second))?.let { approxGauche = it }
        }
        for (i in intArrayOf(0, 3)) reperes[i] = chercherRepere(img, approxGauche, Zone.REPERES[i], 16.0)
        val manquants = reperes.count { it == null }
        if (manquants > 0) throw LectureImpossible(
            "Carré(s) noir(s) de repérage introuvable(s) ($manquants sur 4) : les 4 coins de la zone de réponses doivent être visibles.")
        val h = Homographie.depuis4Points(Zone.REPERES, Array(4) { doubleArrayOf(reperes[it]!!.x, reperes[it]!!.y) })
            ?: throw LectureImpossible("Repérage incohérent : reprendre la photo.")
        // contrôle : la transformation finale doit retrouver les motifs du QR (à 2 mm près)
        for ((mm, pix) in motifs) {
            val ecart = Math.hypot(h.x(mm[0], mm[1]) - pix[0], h.y(mm[0], mm[1]) - pix[1])
            if (ecart > 2.0 * h.echelle(mm[0], mm[1])) throw LectureImpossible("Repérage incohérent : reprendre la photo, feuille bien à plat.")
        }
        val echelle = h.echelle(Zone.W / 2, Zone.H / 2)
        if (echelle < ECHELLE_MIN) throw LectureImpossible("Photo trop éloignée ou trop petite : rapprocher l'appareil.")
        val noir = reperes.map { it!!.gris }.average()

        val taux = Array(Zone.LIGNES) { DoubleArray(Zone.COLONNES) }
        val etats = Array(Zone.LIGNES) { i ->
            Array(Zone.COLONNES) { j ->
                val t = tauxCase(img, h, Zone.caseX(j), Zone.caseY(i), noir, echelle)
                taux[i][j] = t
                when { t < SEUIL_VIDE -> Etat.VIDE; t >= SEUIL_COCHEE -> Etat.COCHEE; else -> Etat.DOUTEUSE }
            }
        }
        return Lecture(qr, taux, etats, h, echelle)
    }

    /** Lit seulement le texte d'un QR présent dans l'image (import d'une clé, par ex.). */
    fun lireTexteQr(img: ImageGrise): String? = lireQr(img)?.texte

    /** Image redressée de la zone (pxParMm pixels par millimètre), pour l'écran de vérification. */
    fun redresser(img: ImageGrise, h: Homographie, pxParMm: Double): ImageGrise {
        val w = Math.round(Zone.W * pxParMm).toInt(); val ht = Math.round(Zone.H * pxParMm).toInt()
        val out = ByteArray(w * ht)
        for (y in 0 until ht) for (x in 0 until w) {
            val mx = (x + 0.5) / pxParMm; val my = (y + 0.5) / pxParMm
            out[y * w + x] = gris(img, h.x(mx, my), h.y(mx, my), 255).toByte()
        }
        return ImageGrise(w, ht, out)
    }

    // ------------------------------------------------------------------ QR

    /** motifs : (position en mm dans la zone, position en pixels) des motifs bas gauche et haut gauche du QR, puis haut droit. */
    private data class Qr(val texte: String, val approx: Homographie, val motifs: List<Pair<DoubleArray, DoubleArray>>)

    private fun lireQr(img: ImageGrise): Qr? {
        val grand = maxOf(img.w, img.h)
        val f0 = maxOf(1, Math.ceil(grand / 1800.0).toInt())
        val facteurs = (listOf(f0, f0 + 1, maxOf(1, f0 - 1), f0 + 2, 1)).distinct()
        for (f in facteurs) {
            val r = lireQrA(img.reduire(f), f.toDouble())
            if (r != null) return r
        }
        return null
    }

    private fun lireQrA(img: ImageGrise, f: Double): Qr? {
        if (img.w < 50 || img.h < 50) return null
        return try {
            val src = PlanarYUVLuminanceSource(img.px, img.w, img.h, 0, 0, img.w, img.h, false)
            val hints = mapOf(DecodeHintType.TRY_HARDER to true)
            val det = Detector(BinaryBitmap(HybridBinarizer(src)).blackMatrix).detect(hints)
            val texte = Decoder().decode(det.bits, hints).text
            // points ZXing : bas gauche, haut gauche, haut droit (centres des 3 grands motifs),
            // puis éventuellement le petit motif d'alignement.
            val n = det.bits.width.toDouble()                  // nombre de modules par côté
            val p = det.points
            if (p.size < 3) return null
            fun mm(mx: Double, my: Double) = doubleArrayOf(
                Zone.QRX - Zone.QR / 2 + mx * Zone.QR / n, Zone.QRY - Zone.QR / 2 + my * Zone.QR / n)
            fun pix(i: Int) = doubleArrayOf((p[i].x + 0.5) * f, (p[i].y + 0.5) * f)
            val src4 = arrayOf(mm(3.5, n - 3.5), mm(3.5, 3.5), mm(n - 3.5, 3.5),
                if (p.size >= 4) mm(n - 6.5, n - 6.5) else mm(n - 3.5, n - 3.5))
            val bl = pix(0); val tl = pix(1); val tr = pix(2)
            val dst4 = arrayOf(bl, tl, tr,
                if (p.size >= 4) pix(3) else doubleArrayOf(tr[0] + bl[0] - tl[0], tr[1] + bl[1] - tl[1]))
            val h = Homographie.depuis4Points(src4, dst4) ?: return null
            Qr(texte, h, listOf(src4[0] to bl, src4[1] to tl, src4[2] to tr))
        } catch (e: Exception) {      // NotFoundException, FormatException, ChecksumException...
            null
        }
    }

    // ------------------------------------------------------------- repères

    private class Repere(val x: Double, val y: Double, val gris: Double)

    /** Cherche un carré noir de côté Zone.FID autour de sa position prévue par [approx]. */
    private fun chercherRepere(img: ImageGrise, approx: Homographie, centre: DoubleArray, rayonMm: Double): Repere? {
        val px = approx.x(centre[0], centre[1]); val py = approx.y(centre[0], centre[1])
        val s = approx.echelle(centre[0], centre[1])
        if (s.isNaN() || s < 1.0) return null
        val rayon = (rayonMm * s).toInt()
        val x0 = maxOf(0, (px - rayon).toInt()); val x1 = minOf(img.w - 1, (px + rayon).toInt())
        val y0 = maxOf(0, (py - rayon).toInt()); val y1 = minOf(img.h - 1, (py + rayon).toInt())
        val w = x1 - x0 + 1; val h = y1 - y0 + 1
        if (w < 8 || h < 8) return null

        // seuil d'Otsu sur la fenêtre
        val hist = IntArray(256)
        for (y in y0..y1) for (x in x0..x1) hist[img.at(x, y)]++
        val total = w * h
        var somme = 0.0; for (i in 0..255) somme += i * hist[i].toDouble()
        var sB = 0.0; var nB = 0; var meilleur = -1.0; var seuil = 127
        var moyB = 0.0; var moyF = 0.0
        for (t in 0..255) {
            nB += hist[t]; if (nB == 0) continue
            val nF = total - nB; if (nF == 0) break
            sB += t * hist[t].toDouble()
            val mB = sB / nB; val mF = (somme - sB) / nF
            val v = nB.toDouble() * nF * (mB - mF) * (mB - mF)
            if (v > meilleur) { meilleur = v; seuil = t; moyB = mB; moyF = mF }
        }
        if (moyF - moyB < 25) return null                 // pas de contraste : rien de noir ici

        // composantes connexes sombres
        val vu = BooleanArray(total)
        val pile = IntArray(total)
        val aireAttendue = Zone.FID * s * Zone.FID * s
        var choix: Repere? = null; var distChoix = Double.MAX_VALUE
        for (dep in 0 until total) {
            if (vu[dep] || img.at(x0 + dep % w, y0 + dep / w) > seuil) continue
            var n = 0; var top = 0
            var sx = 0.0; var sy = 0.0; var sxx = 0.0; var syy = 0.0; var sg = 0.0
            var bord = false
            pile[top++] = dep; vu[dep] = true
            while (top > 0) {
                val c = pile[--top]; val cx = c % w; val cy = c / w
                n++; sx += cx; sy += cy; sxx += cx.toDouble() * cx; syy += cy.toDouble() * cy
                sg += img.at(x0 + cx, y0 + cy)
                if (cx == 0 || cy == 0 || cx == w - 1 || cy == h - 1) bord = true
                if (cx > 0) { val v = c - 1; if (!vu[v] && img.at(x0 + cx - 1, y0 + cy) <= seuil) { vu[v] = true; pile[top++] = v } }
                if (cx < w - 1) { val v = c + 1; if (!vu[v] && img.at(x0 + cx + 1, y0 + cy) <= seuil) { vu[v] = true; pile[top++] = v } }
                if (cy > 0) { val v = c - w; if (!vu[v] && img.at(x0 + cx, y0 + cy - 1) <= seuil) { vu[v] = true; pile[top++] = v } }
                if (cy < h - 1) { val v = c + w; if (!vu[v] && img.at(x0 + cx, y0 + cy + 1) <= seuil) { vu[v] = true; pile[top++] = v } }
            }
            if (bord || n < 0.45 * aireAttendue || n > 1.9 * aireAttendue) continue
            val mx = sx / n; val my = sy / n
            // carré plein : moment d'inertie = aire^2 / 6 (invariant par rotation) ;
            // une forme creuse ou allongée donne un rapport nettement plus grand.
            val inertie = (sxx / n - mx * mx) + (syy / n - my * my)
            val rapport = inertie / (n / 6.0)
            if (rapport < 0.8 || rapport > 1.3) continue
            val gx = x0 + mx + 0.5; val gy = y0 + my + 0.5
            val d = Math.hypot(gx - px, gy - py)
            if (d < distChoix) { distChoix = d; choix = Repere(gx, gy, sg / n) }
        }
        return choix
    }

    // --------------------------------------------------------------- cases

    private fun gris(img: ImageGrise, x: Double, y: Double, defaut: Int): Int {
        val xi = Math.floor(x).toInt(); val yi = Math.floor(y).toInt()
        return if (xi < 0 || yi < 0 || xi >= img.w || yi >= img.h) defaut else img.at(xi, yi)
    }

    /** Proportion de points sombres à l'intérieur de la case centrée en (cx, cy) mm. */
    private fun tauxCase(img: ImageGrise, h: Homographie, cx: Double, cy: Double, noir: Double, echelle: Double): Double {
        // blanc local = 85e centile des gris autour de la case (insensible aux ombres douces)
        val hist = IntArray(256); var nb = 0
        val nv = maxOf(12, Math.ceil(9.0 * echelle / 2).toInt())
        for (a in 0 until nv) for (b in 0 until nv) {
            val g = gris(img, h.x(cx - 4.5 + 9.0 * (a + 0.5) / nv, cy - 4.5 + 9.0 * (b + 0.5) / nv),
                h.y(cx - 4.5 + 9.0 * (a + 0.5) / nv, cy - 4.5 + 9.0 * (b + 0.5) / nv), -1)
            if (g >= 0) { hist[g]++; nb++ }
        }
        if (nb == 0) return 0.0
        var cumul = 0; var blanc = 255
        for (g in 255 downTo 0) { cumul += hist[g]; if (cumul >= 0.15 * nb) { blanc = g; break } }
        val seuil = blanc - CONTRASTE * (blanc - noir)

        val cote = Zone.CASE * PART_INTERIEURE
        val n = maxOf(10, Math.ceil(cote * echelle).toInt())
        var sombres = 0
        for (a in 0 until n) for (b in 0 until n) {
            val mx = cx - cote / 2 + cote * (a + 0.5) / n; val my = cy - cote / 2 + cote * (b + 0.5) / n
            if (gris(img, h.x(mx, my), h.y(mx, my), 255) < seuil) sombres++
        }
        return sombres.toDouble() / (n * n)
    }
}
