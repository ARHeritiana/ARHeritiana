package qcm.core

/**
 * Barème, identique à corriger.lua : 1 point par bonne réponse, sur 10, sans pénalité.
 * Réponses de l'élève : 10 caractères ; A–D = case cochée, « . » = pas de réponse,
 * « * » = plusieurs cases cochées (0 point).
 */
object Notation {
    const val VIDE = '.'
    const val MULTIPLE = '*'

    /** cochees[ligne][colonne], lignes A–D, colonnes = questions 1 à 10. */
    fun reponses(cochees: Array<BooleanArray>): String {
        val sb = StringBuilder()
        for (j in 0 until Zone.COLONNES) {
            val lignes = (0 until Zone.LIGNES).filter { cochees[it][j] }
            sb.append(when (lignes.size) { 0 -> VIDE; 1 -> 'A' + lignes[0]; else -> MULTIPLE })
        }
        return sb.toString()
    }

    fun note(cle: String, reponses: String): Int =
        cle.indices.count { it < reponses.length && reponses[it] == cle[it] && cle[it] in 'A'..'D' }
}
