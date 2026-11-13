package qcm.core

/**
 * Géométrie de la zone de réponses, en millimètres, origine en haut à gauche.
 * DOIT rester identique à la table Z de qcm_generateur.lua (qcm_personnalise.tex).
 */
object Zone {
    const val W = 178.0
    const val H = 42.0
    const val FID = 5.0            // côté des carrés de repérage, collés aux 4 coins
    const val X0 = 24.0; const val DX = 11.0    // centre de la colonne 1, pas horizontal
    const val Y0 = 12.0; const val DY = 7.5     // centre de la ligne A, pas vertical
    const val CASE = 5.5           // côté d'une case
    const val QR = 28.0; const val QRX = 155.0; const val QRY = 21.0   // côté et centre du QR
    const val LIGNES = 4
    const val COLONNES = 10

    /** Centres des 4 carrés de repérage : haut gauche, haut droit, bas droit, bas gauche. */
    val REPERES = arrayOf(
        doubleArrayOf(FID / 2, FID / 2), doubleArrayOf(W - FID / 2, FID / 2),
        doubleArrayOf(W - FID / 2, H - FID / 2), doubleArrayOf(FID / 2, H - FID / 2))

    fun caseX(colonne: Int) = X0 + colonne * DX
    fun caseY(ligne: Int) = Y0 + ligne * DY
}
