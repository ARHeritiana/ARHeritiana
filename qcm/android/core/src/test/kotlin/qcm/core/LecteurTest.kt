package qcm.core

import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Photos simulées à partir des vraies pages PDF (coches variées, perspective, rotation,
 * ombre, flou, bruit, JPEG) : voir qcm/android/outils/simule.py.
 */
class LecteurTest {
    private val cle = CleLot.lire("QCMKEY1:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8:TEST")!!

    private fun charger(nom: String): ImageGrise {
        val im = ImageIO.read(javaClass.getResourceAsStream("/photos/$nom"))
        val g = BufferedImage(im.width, im.height, BufferedImage.TYPE_BYTE_GRAY)
        g.graphics.drawImage(im, 0, 0, null)
        val px = ByteArray(im.width * im.height)
        g.raster.getDataElements(0, 0, im.width, im.height, px)
        return ImageGrise(im.width, im.height, px)
    }

    private fun verifier(photo: String, nom: String, cleRep: String, reponses: String, note: Int): Lecture {
        val l = Lecteur.lire(charger(photo))
        val c = QrQcm.dechiffrer(cle.cle, l.qr)
        assertNotNull(c, "déchiffrement du QR de $photo")
        assertEquals(nom, c.nom)
        assertEquals(cleRep, c.cle)
        val taux = l.taux.joinToString(" | ") { r -> r.joinToString(" ") { "%.2f".format(it) } }
        println("$photo : ${"%.1f".format(l.echelle)} px/mm, douteuses=${l.douteuses}, taux = $taux")
        val rep = Notation.reponses(l.cochees())
        assertEquals(reponses, rep, "réponses lues sur $photo")
        assertEquals(note, Notation.note(c.cle, rep))
        assertEquals(0, l.douteuses, "cases douteuses sur $photo")
        return l
    }

    @Test fun photoStylesVaries() { verifier("photo1.jpg", "Alain Térieur", "ADABACCDDC", "ADABACCDDA", 9) }

    @Test fun photoTourneePetiteAvecDoubleEtVide() {
        verifier("photo2.jpg", "Sacha Touille", "CBCCCACDCA", "CB*C.ACACA", 7)
    }

    @Test fun photoVideTeteEnBas() {
        val l = verifier("photo3.jpg", "Teddy Fissile", "AAABACCACA", "..........", 0)
        assertTrue(l.taux.all { r -> r.all { it < 0.01 } })
    }

    @Test fun corrigeDonneDixSurDix() { verifier("photo4.jpg", "Alain Térieur", "ADABACCDDC", "ADABACCDDC", 10) }

    @Test fun imageSansQr() {
        assertFailsWith<LectureImpossible> { Lecteur.lire(ImageGrise(400, 300, ByteArray(120000) { -56 })) }
    }

    @Test fun redressement() {
        val img = charger("photo1.jpg")
        val l = Lecteur.lire(img)
        val z = Lecteur.redresser(img, l.versImage, 6.0)
        assertEquals(1068, z.w); assertEquals(252, z.h)
        // les 4 carrés de repérage sont noirs, le centre d'une case vide est clair
        for (r in Zone.REPERES) assertTrue(z.at((r[0] * 6).toInt(), (r[1] * 6).toInt()) < 90)
        val vide = z.at((Zone.caseX(1) * 6).toInt(), (Zone.caseY(0) * 6).toInt())
        val plein = z.at((Zone.caseX(3) * 6).toInt(), (Zone.caseY(1) * 6).toInt())
        assertTrue(vide > plein + 40, "vide=$vide plein=$plein")
    }
}
