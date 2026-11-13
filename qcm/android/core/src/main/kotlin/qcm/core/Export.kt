package qcm.core

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Une copie corrigée. reponses et cle : 10 caractères (voir Notation). */
data class Copie(val nom: String, val reponses: String, val cle: String, val note: Int, val lot: String, val scan: String)

/**
 * Export du tableau d'un lot : Nom, réponse donnée à chaque question (R1..R10),
 * note sur 10, puis bonne réponse de chaque question (C1..C10), lot et date du scan.
 */
object Export {
    fun entetes(): List<String> =
        listOf("Nom") + (1..10).map { "R$it" } + listOf("Note /10") + (1..10).map { "C$it" } + listOf("Lot", "Scan")

    /** Cellules d'une ligne ; la note est un nombre, tout le reste du texte. */
    private fun ligne(c: Copie): List<Any> =
        listOf<Any>(c.nom) + (0 until 10).map { affiche(c.reponses.getOrElse(it) { Notation.VIDE }) } +
            listOf<Any>(c.note) + (0 until 10).map { c.cle.getOrElse(it) { '?' }.toString() } + listOf<Any>(c.lot, c.scan)

    /** « . » (pas de réponse) est exporté comme cellule vide ; « * » = plusieurs cases cochées. */
    private fun affiche(r: Char): String = if (r == Notation.VIDE) "" else r.toString()

    /** CSV pour Excel français : UTF-8 avec BOM, séparateur point-virgule, fins de ligne CRLF. */
    fun csv(copies: List<Copie>): String {
        fun champ(v: Any): String {
            val s = v.toString()
            // apostrophe devant = + - @ : empêche Excel d'y voir une formule
            val t = if (s.isNotEmpty() && s[0] in "=+-@\t\r") "'$s" else s
            return if (t.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) "\"" + t.replace("\"", "\"\"") + "\"" else t
        }
        val sb = StringBuilder("﻿")
        sb.append(entetes().joinToString(";")).append("\r\n")
        for (c in copies) sb.append(ligne(c).joinToString(";") { champ(it) }).append("\r\n")
        return sb.toString()
    }

    private fun xml(s: String): String = buildString {
        for (ch in s) when {
            ch == '&' -> append("&amp;"); ch == '<' -> append("&lt;"); ch == '>' -> append("&gt;")
            ch == '"' -> append("&quot;")
            ch < ' ' && ch != '\t' && ch != '\n' -> {}          // caractères interdits en XML
            else -> append(ch)
        }
    }

    private fun colonne(i: Int): String {            // 0 -> A, 25 -> Z, 26 -> AA
        var n = i; var s = ""
        while (true) { s = ('A' + n % 26) + s; n = n / 26 - 1; if (n < 0) break }
        return s
    }

    /** Classeur XLSX minimal (une feuille « Notes », première ligne en gras et figée). */
    fun xlsx(copies: List<Copie>, sortie: OutputStream) {
        val lignes = ArrayList<List<Any>>()
        lignes.add(entetes())
        copies.forEach { lignes.add(ligne(it)) }
        val feuille = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
            append("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>")
            append("<cols><col min=\"1\" max=\"1\" width=\"28\" customWidth=\"1\"/><col min=\"2\" max=\"11\" width=\"5\" customWidth=\"1\"/>")
            append("<col min=\"12\" max=\"12\" width=\"9\" customWidth=\"1\"/><col min=\"13\" max=\"22\" width=\"5\" customWidth=\"1\"/>")
            append("<col min=\"23\" max=\"24\" width=\"18\" customWidth=\"1\"/></cols><sheetData>")
            lignes.forEachIndexed { r, cells ->
                append("<row r=\"${r + 1}\">")
                cells.forEachIndexed { c, v ->
                    val ref = colonne(c) + (r + 1)
                    val style = if (r == 0) " s=\"1\"" else ""
                    if (v is Int) append("<c r=\"$ref\"$style><v>$v</v></c>")
                    else if (v.toString().isNotEmpty())
                        append("<c r=\"$ref\"$style t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(v.toString())}</t></is></c>")
                }
                append("</row>")
            }
            append("</sheetData></worksheet>")
        }
        val fichiers = linkedMapOf(
            "[Content_Types].xml" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
                "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
                "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
                "</Types>",
            "_rels/.rels" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
                "</Relationships>",
            "xl/workbook.xml" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
                "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
                "<sheets><sheet name=\"Notes\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>",
            "xl/_rels/workbook.xml.rels" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
                "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
                "</Relationships>",
            "xl/styles.xml" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
                "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>" +
                "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>" +
                "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
                "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
                "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
                "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/></cellXfs>" +
                "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>" +
                "</styleSheet>",
            "xl/worksheets/sheet1.xml" to feuille)
        ZipOutputStream(sortie).use { zip ->
            for ((nom, contenu) in fichiers) {
                zip.putNextEntry(ZipEntry(nom))
                zip.write(contenu.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }
}
