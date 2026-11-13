package qcm.core

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ChiffreTest {
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    @Test fun blocRfc8439() {
        val k = unhex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
        assertEquals(
            "10f1e7e4d13b5915500fdd1fa32071c4c7d1f4c733c068030422aa9ac3d46c4e" +
                "d2826446079faa0914c2d705d98b02a2b5129cd1de164eb9cbd083e8a2503c4e",
            Chiffre.hex(Chiffre.bloc(k, 1, unhex("000000090000004a00000000"))))
    }

    @Test fun poly1305Rfc8439() {
        val tag = Chiffre.poly1305(unhex("85d6be7857556d337f4452fe42d506a80103808afb0db2fd4abff6af4149f51b"),
            "Cryptographic Forum Research Group".toByteArray())
        assertEquals("a8061dc1305136c6c22b8baf0c0127a9", Chiffre.hex(tag))
    }

    @Test fun aeadRfc8439() {
        val key = unhex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
        val nonce = unhex("070000004041424344454647"); val aad = unhex("50515253c0c1c2c3c4c5c6c7")
        val clair = "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.".toByteArray()
        val ct = Chiffre.aeadChiffrer(key, nonce, aad, clair)
        assertEquals("1ae10b594f09e26a7e902ecbd0600691", Chiffre.hex(ct.copyOfRange(ct.size - 16, ct.size)))
        assertContentEquals(clair, Chiffre.aeadDechiffrer(key, nonce, aad, ct))
        ct[3] = (ct[3] + 1).toByte()
        assertNull(Chiffre.aeadDechiffrer(key, nonce, aad, ct))
    }

    /** Comparaison avec l'implémentation ChaCha20-Poly1305 du JDK, sur des tailles variées. */
    @Test fun identiqueAuJdk() {
        val rnd = java.util.Random(42)
        for (n in listOf(0, 1, 15, 16, 17, 31, 63, 64, 65, 200)) {
            val key = ByteArray(32).also { rnd.nextBytes(it) }
            val nonce = ByteArray(12).also { rnd.nextBytes(it) }
            val aad = ByteArray(rnd.nextInt(20)).also { rnd.nextBytes(it) }
            val clair = ByteArray(n).also { rnd.nextBytes(it) }
            val c = Cipher.getInstance("ChaCha20-Poly1305")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
            c.updateAAD(aad)
            assertContentEquals(c.doFinal(clair), Chiffre.aeadChiffrer(key, nonce, aad, clair), "taille $n")
        }
    }

    @Test fun base64() {
        for (n in 0..40) {
            val b = ByteArray(n) { (it * 37 + n).toByte() }
            val t = Chiffre.b64(b)
            assertEquals(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b), t)
            assertContentEquals(b, Chiffre.b64dec(t))
        }
        assertNull(Chiffre.b64dec("a b"))
    }

    /** QR réellement produit par qcm_personnalise.tex (LuaLaTeX) avec la clé de test. */
    @Test fun qrProduitParLua() {
        val cle = CleLot.lire("# commentaire\nQCMKEY1:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8:TEST\n")
        assertNotNull(cle)
        assertEquals("TEST", cle.nom)
        val qr = "ATejbAuYp7sAmg8kqZFZzfZV7Nq6ICoIeJPA3GBO6G0HRap8AKKan8wHhXKI8UT62g"
        assertEquals(cle.id, QrQcm.idCle(qr))
        assertEquals(ContenuQr("Alain Térieur", "ADABACCDDC"), QrQcm.dechiffrer(cle.cle, qr))
        // un seul caractère modifié : refusé
        val faux = qr.substring(0, 30) + (if (qr[30] == 'A') 'B' else 'A') + qr.substring(31)
        assertNull(QrQcm.dechiffrer(cle.cle, faux))
        // autre clé : refusé
        assertNull(QrQcm.dechiffrer(ByteArray(32) { 7 }, qr))
        assertNull(QrQcm.dechiffrer(cle.cle, "Alain Terieur|A B A C B B D A C D"))
        assertEquals(cle.texte(), CleLot.lire(cle.texte())!!.texte())
        assertNull(CleLot.lire("QCMKEY1:abc:x"))
    }
}
