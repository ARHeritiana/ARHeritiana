package qcm.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExportTest {
    private val copies = listOf(
        Copie("Alain Térieur", "ADABACCDDA", "ADABACCDDC", 9, "TEST", "2026-11-13 10:00"),
        Copie("Sacha; \"Touille\" <&>", "CB*C.ACACA", "CBCCCACDCA", 7, "TEST", "2026-11-13 10:01"),
        Copie("=HYPERLINK(\"x\")", "..........", "AAABACCACA", 0, "TEST", "2026-11-13 10:02"))

    @Test fun notation() {
        assertEquals(9, Notation.note("ADABACCDDC", "ADABACCDDA"))
        assertEquals(7, Notation.note("CBCCCACDCA", "CB*C.ACACA"))
        assertEquals(0, Notation.note("AAABACCACA", ".........."))
        assertEquals(10, Notation.note("AAABACCACA", "AAABACCACA"))
        val c = Array(4) { BooleanArray(10) }
        c[0][0] = true; c[3][1] = true; c[1][2] = true; c[2][2] = true
        assertEquals("AD*.......", Notation.reponses(c))
    }

    @Test fun csv() {
        val t = Export.csv(copies)
        assertTrue(t.startsWith("﻿Nom;R1;R2;R3;R4;R5;R6;R7;R8;R9;R10;Note /10;C1;"))
        val l = t.split("\r\n")
        assertEquals(5, l.size)
        assertEquals("Alain Térieur;A;D;A;B;A;C;C;D;D;A;9;A;D;A;B;A;C;C;D;D;C;TEST;2026-11-13 10:00", l[1])
        assertTrue(l[2].startsWith("\"Sacha; \"\"Touille\"\" <&>\";C;B;*;C;;A;C;A;C;A;7;"))
        assertTrue(l[3].startsWith("\"'=HYPERLINK(\"\"x\"\")\";;;"))
    }

    @Test fun xlsx() {
        val out = ByteArrayOutputStream()
        Export.xlsx(copies, out)
        val noms = HashSet<String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                noms.add(e.name)
                // chaque partie doit être du XML bien formé
                DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(zip.readBytes()))
            }
        }
        assertTrue(noms.containsAll(listOf("[Content_Types].xml", "xl/workbook.xml", "xl/worksheets/sheet1.xml")))
        System.getProperty("qcm.xlsx")?.let { File(it).writeBytes(out.toByteArray()) }
    }
}
