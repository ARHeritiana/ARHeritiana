package qcm.core

import java.math.BigInteger

/**
 * Chiffrement authentifié du QR : ChaCha20-Poly1305 (RFC 8439), en Kotlin pur
 * (aucune dépendance à la version d'Android). Doit rester identique à qcm_chiffre.lua.
 *
 * Texte du QR = base64url( 01 | id clé (3 o.) | nonce (12 o.) | chiffré | tag (16 o.) )
 * Clair       = 3 octets (10 réponses, 2 bits chacune) puis le nom en UTF-8.
 */
object Chiffre {
    const val VERSION = 1

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8) or
            ((b[o + 2].toInt() and 255) shl 16) or ((b[o + 3].toInt() and 255) shl 24)

    /** Bloc ChaCha20 : clé 32 octets, compteur, nonce 12 octets -> 64 octets. */
    fun bloc(key: ByteArray, counter: Int, nonce: ByteArray): ByteArray {
        require(key.size == 32 && nonce.size == 12)
        val s = IntArray(16)
        s[0] = 0x61707865; s[1] = 0x3320646e; s[2] = 0x79622d32; s[3] = 0x6b206574
        for (i in 0 until 8) s[4 + i] = le32(key, 4 * i)
        s[12] = counter
        for (i in 0 until 3) s[13 + i] = le32(nonce, 4 * i)
        val x = s.copyOf()
        fun q(a: Int, b: Int, c: Int, d: Int) {
            x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] xor x[a], 16)
            x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] xor x[c], 12)
            x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] xor x[a], 8)
            x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] xor x[c], 7)
        }
        repeat(10) {
            q(0, 4, 8, 12); q(1, 5, 9, 13); q(2, 6, 10, 14); q(3, 7, 11, 15)
            q(0, 5, 10, 15); q(1, 6, 11, 12); q(2, 7, 8, 13); q(3, 4, 9, 14)
        }
        val out = ByteArray(64)
        for (i in 0 until 16) {
            val v = x[i] + s[i]
            out[4 * i] = v.toByte(); out[4 * i + 1] = (v ushr 8).toByte()
            out[4 * i + 2] = (v ushr 16).toByte(); out[4 * i + 3] = (v ushr 24).toByte()
        }
        return out
    }

    fun chacha20(key: ByteArray, counter: Int, nonce: ByteArray, data: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        var c = counter
        var i = 0
        while (i < data.size) {
            val ks = bloc(key, c++, nonce)
            for (j in 0 until minOf(64, data.size - i)) out[i + j] = (data[i + j].toInt() xor ks[j].toInt()).toByte()
            i += 64
        }
        return out
    }

    private fun leBig(b: ByteArray, from: Int, to: Int): BigInteger =
        BigInteger(1, b.copyOfRange(from, to).reversedArray())

    /** Poly1305 (clé à usage unique de 32 octets) -> tag de 16 octets. */
    fun poly1305(key: ByteArray, msg: ByteArray): ByteArray {
        val p = BigInteger.ONE.shiftLeft(130).subtract(BigInteger.valueOf(5))
        val r = leBig(key, 0, 16).and(BigInteger("0ffffffc0ffffffc0ffffffc0fffffff", 16))
        val s = leBig(key, 16, 32)
        var acc = BigInteger.ZERO
        var i = 0
        while (i < msg.size) {
            val fin = minOf(i + 16, msg.size)
            val n = leBig(msg, i, fin).add(BigInteger.ONE.shiftLeft(8 * (fin - i)))
            acc = acc.add(n).multiply(r).mod(p)
            i += 16
        }
        val t = acc.add(s).mod(BigInteger.ONE.shiftLeft(128)).toByteArray().reversedArray()
        return ByteArray(16) { if (it < t.size) t[it] else 0 }   // petit-boutiste, complété par des 0
    }

    private fun le64(n: Int) = ByteArray(8) { if (it < 4) (n ushr (8 * it)).toByte() else 0 }
    private fun pad16(n: Int) = ByteArray((16 - n % 16) % 16)

    private fun tagAead(key: ByteArray, nonce: ByteArray, aad: ByteArray, ct: ByteArray): ByteArray {
        val otk = bloc(key, 0, nonce).copyOf(32)
        return poly1305(otk, aad + pad16(aad.size) + ct + pad16(ct.size) + le64(aad.size) + le64(ct.size))
    }

    /** Renvoie chiffré + tag (16 derniers octets). */
    fun aeadChiffrer(key: ByteArray, nonce: ByteArray, aad: ByteArray, clair: ByteArray): ByteArray {
        val ct = chacha20(key, 1, nonce, clair)
        return ct + tagAead(key, nonce, aad, ct)
    }

    /** Renvoie le clair, ou null si le tag est faux (QR modifié ou mauvaise clé). */
    fun aeadDechiffrer(key: ByteArray, nonce: ByteArray, aad: ByteArray, ctEtTag: ByteArray): ByteArray? {
        if (ctEtTag.size < 16) return null
        val ct = ctEtTag.copyOf(ctEtTag.size - 16)
        val tag = ctEtTag.copyOfRange(ctEtTag.size - 16, ctEtTag.size)
        val t = tagAead(key, nonce, aad, ct)
        var diff = 0
        for (i in 0 until 16) diff = diff or (t[i].toInt() xor tag[i].toInt())
        return if (diff == 0) chacha20(key, 1, nonce, ct) else null
    }

    /** Identifiant public de la clé (3 octets) : ne révèle rien de la clé. */
    fun idCle(key: ByteArray): ByteArray = bloc(key, 0, "QCM-keyid-v1".toByteArray(Charsets.US_ASCII)).copyOf(3)

    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    // ----- base64url sans « = »
    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun b64(b: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i < b.size) {
            val k = minOf(3, b.size - i)
            var n = 0
            for (j in 0 until 3) n = (n shl 8) or (if (j < k) b[i + j].toInt() and 255 else 0)
            for (j in 0..k) sb.append(B64[(n ushr (18 - 6 * j)) and 63])
            i += 3
        }
        return sb.toString()
    }

    fun b64dec(texte: String): ByteArray? {
        val t = texte.replace("=", "").replace('+', '-').replace('/', '_')
        if (t.length % 4 == 1) return null
        val out = java.io.ByteArrayOutputStream()
        var n = 0
        var bits = 0
        for (ch in t) {
            val v = B64.indexOf(ch)
            if (v < 0) return null
            n = ((n shl 6) or v) and 0xffffff
            bits += 6
            if (bits >= 8) { bits -= 8; out.write((n ushr bits) and 255) }
        }
        return out.toByteArray()
    }
}

/** Clé secrète d'un lot : texte « QCMKEY1:<clé base64url>:<nom du lot> ». */
class CleLot(val cle: ByteArray, val nom: String) {
    val id: String get() = Chiffre.hex(Chiffre.idCle(cle))
    fun texte(): String = "QCMKEY1:" + Chiffre.b64(cle) + ":" + nom

    companion object {
        /** Lit une clé dans un texte (fichier qcm_cle.txt ou ligne collée). Null si invalide. */
        fun lire(texte: String): CleLot? {
            for (brute in texte.lines()) {
                val l = brute.trim().removePrefix("﻿").trim()
                if (l.isEmpty() || l.startsWith("#")) continue
                val m = Regex("^QCMKEY1:([A-Za-z0-9_-]+):?(.*)$").matchEntire(l)
                val (b, nom) = if (m != null) m.groupValues[1] to m.groupValues[2].trim()
                else if (Regex("^[A-Za-z0-9_-]+$").matches(l)) l to "" else return null
                val k = Chiffre.b64dec(b) ?: return null
                return if (k.size == 32) CleLot(k, nom) else null
            }
            return null
        }
    }
}

/** Contenu déchiffré d'un QR : nom de l'élève et clé de réponses (10 lettres A–D). */
data class ContenuQr(val nom: String, val cle: String)

object QrQcm {
    /** Identifiant de clé (6 chiffres hexadécimaux) annoncé en clair par le QR, ou null. */
    fun idCle(texte: String): String? {
        val bin = Chiffre.b64dec(texte.trim()) ?: return null
        if (bin.size < 4 + 12 + 3 + 16 || bin[0].toInt() != Chiffre.VERSION) return null
        return Chiffre.hex(bin.copyOfRange(1, 4))
    }

    /** Déchiffre le QR ; null si ce n'est pas un QR de ce lot ou s'il a été modifié. */
    fun dechiffrer(cle: ByteArray, texte: String): ContenuQr? {
        val bin = Chiffre.b64dec(texte.trim()) ?: return null
        if (bin.size < 4 + 12 + 3 + 16 || bin[0].toInt() != Chiffre.VERSION) return null
        val aad = bin.copyOf(4)
        if (!aad.copyOfRange(1, 4).contentEquals(Chiffre.idCle(cle))) return null
        val clair = Chiffre.aeadDechiffrer(cle, bin.copyOfRange(4, 16), aad, bin.copyOfRange(16, bin.size)) ?: return null
        var n = ((clair[0].toInt() and 255) shl 16) or ((clair[1].toInt() and 255) shl 8) or (clair[2].toInt() and 255)
        n = n ushr 4
        val rep = CharArray(10)
        for (i in 9 downTo 0) { rep[i] = 'A' + (n and 3); n = n ushr 2 }
        return ContenuQr(String(clair, 3, clair.size - 3, Charsets.UTF_8), String(rep))
    }
}
