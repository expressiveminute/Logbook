package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class LayoverTimeScaleTest {

    @Test
    fun scaleHours_followsTheStepTheTimeReaches() {
        // Die Skala ist immer die Einheit, in der gerade gezählt wird: unter
        // einem Tag ein Tag, darüber eine Woche usw.
        assertEquals(24, LayoverTimeScale.scaleHours(0))
        assertEquals(24, LayoverTimeScale.scaleHours(12))
        assertEquals(24, LayoverTimeScale.scaleHours(24))
        assertEquals(168, LayoverTimeScale.scaleHours(25))
        assertEquals(168, LayoverTimeScale.scaleHours(168))
        assertEquals(720, LayoverTimeScale.scaleHours(169))
        assertEquals(2160, LayoverTimeScale.scaleHours(721))
        assertEquals(8760, LayoverTimeScale.scaleHours(2161))
        assertEquals(8760, LayoverTimeScale.scaleHours(8760))
    }

    @Test
    fun scaleHours_growsInWholeYears() {
        // Über ein Jahr hinaus läuft die Leiste weiter, aber nur in ganzen
        // Jahren - ein Bruchteil davon wäre eine Skala, die schon bei 9000 h
        // wieder am Ende steht.
        assertEquals(17520, LayoverTimeScale.scaleHours(8761))
        assertEquals(17520, LayoverTimeScale.scaleHours(12000))
        assertEquals(26280, LayoverTimeScale.scaleHours(20000))
    }

    @Test
    fun stepIndex_namesTheUnitOnTheBar() {
        assertEquals(0, LayoverTimeScale.stepIndex(1))
        assertEquals(0, LayoverTimeScale.stepIndex(24))
        assertEquals(1, LayoverTimeScale.stepIndex(30))
        assertEquals(2, LayoverTimeScale.stepIndex(200))
        assertEquals(3, LayoverTimeScale.stepIndex(1000))
        assertEquals(4, LayoverTimeScale.stepIndex(4000))
        // Auch bei mehr als einem Jahr bleibt es die Jahresstufe.
        assertEquals(4, LayoverTimeScale.stepIndex(10000))
    }

    @Test
    fun scaleUnits_isOneUntilTheYearIsOver() {
        assertEquals(1, LayoverTimeScale.scaleUnits(0))
        assertEquals(1, LayoverTimeScale.scaleUnits(2161))
        assertEquals(1, LayoverTimeScale.scaleUnits(8760))
        assertEquals(2, LayoverTimeScale.scaleUnits(8761))
        assertEquals(3, LayoverTimeScale.scaleUnits(20000))
    }

    @Test
    fun fraction_withoutTimeIsEmpty() {
        assertEquals(0f, LayoverTimeScale.fraction(0), 0.0001f)
    }

    @Test
    fun fraction_atTheSteps() {
        // Der jeweils volle Wert einer Stufe füllt die Leiste ganz.
        assertEquals(0.5f, LayoverTimeScale.fraction(12), 0.0001f)
        assertEquals(1f, LayoverTimeScale.fraction(24), 0.0001f)
        assertEquals(0.25f, LayoverTimeScale.fraction(42), 0.0001f)
        assertEquals(1f, LayoverTimeScale.fraction(168), 0.0001f)
        assertEquals(1f, LayoverTimeScale.fraction(720), 0.0001f)
        assertEquals(1f, LayoverTimeScale.fraction(2160), 0.0001f)
        assertEquals(1f, LayoverTimeScale.fraction(8760), 0.0001f)
    }

    @Test
    fun fraction_beyondOneYearKeepsGrowing() {
        // Bei zwei Jahren Skala ist die Hälfte genau ein Jahr gefüllt.
        assertEquals(0.5f, LayoverTimeScale.fraction(8760, 17520), 0.0001f)
        assertEquals(1f, LayoverTimeScale.fraction(17520, 17520), 0.0001f)
    }

    @Test
    fun fraction_neverExceedsTheBar() {
        // Mehr Stunden als die Skala trägt: Der Balken ist voll und läuft nicht
        // aus der Leiste. Im Regelfall wächst stattdessen die Skala, siehe
        // fraction_beyondOneYearKeepsGrowing().
        assertEquals(1f, LayoverTimeScale.fraction(99999, 8760), 0.0001f)
    }

    @Test
    fun daysText_writesWholeDaysWithoutDecimals() {
        assertEquals("7", LayoverTimeScale.daysText(168, Locale.GERMAN))
        assertEquals("1", LayoverTimeScale.daysText(24, Locale.GERMAN))
        assertEquals("0", LayoverTimeScale.daysText(0, Locale.GERMAN))
    }

    @Test
    fun daysText_writesOneDecimalInTheLanguageOfTheApp() {
        // deutsch mit Komma, englisch mit Punkt
        assertEquals("1,5", LayoverTimeScale.daysText(36, Locale.GERMAN))
        assertEquals("1.5", LayoverTimeScale.daysText(36, Locale.ENGLISH))
        assertEquals("0,5", LayoverTimeScale.daysText(12, Locale.GERMAN))
    }

    @Test
    fun daysText_roundsToOneDecimal() {
        assertEquals("1,2", LayoverTimeScale.daysText(29, Locale.GERMAN))
        assertEquals("2,1", LayoverTimeScale.daysText(50, Locale.GERMAN))
    }

    @Test
    fun stepsAreTheFiveNamedOnTheBar() {
        assertEquals(listOf(24, 168, 720, 2160, 8760), LayoverTimeScale.STEPS)
        assertEquals(24, LayoverTimeScale.DAY_HOURS)
        assertEquals(8760, LayoverTimeScale.YEAR_HOURS)
    }

    @Test
    fun daysQuantity_isSingularOnlyForExactlyOneDay() {
        assertEquals(1, LayoverTimeScale.daysQuantity(24))
        // "1,5 Tage", nicht "eineinhalb Tag"
        assertEquals(2, LayoverTimeScale.daysQuantity(36))
        assertEquals(2, LayoverTimeScale.daysQuantity(0))
        assertEquals(2, LayoverTimeScale.daysQuantity(48))
        assertEquals(2, LayoverTimeScale.daysQuantity(23))
    }

    @Test
    fun totals_recomputesEveryUnitFromTheWholeTime() {
        val t = LayoverTimeScale.totals(499)
        assertEquals(499, t.hours)
        assertEquals(499 / 24.0, t.days, 0.0001)
        assertEquals(499 / 168.0, t.weeks, 0.0001)
        assertEquals(499 / 720.0, t.months, 0.0001)
        assertEquals(499 / 2160.0, t.quarters, 0.0001)
        assertEquals(499 / 8760.0, t.years, 0.0001)
    }

    @Test
    fun totals_isZeroForNothing() {
        val t = LayoverTimeScale.totals(0)
        assertEquals(0, t.hours)
        assertEquals(0.0, t.days, 0.0001)
        assertEquals(0.0, t.weeks, 0.0001)
        assertEquals(0.0, t.months, 0.0001)
        assertEquals(0.0, t.quarters, 0.0001)
        assertEquals(0.0, t.years, 0.0001)
    }

    @Test
    fun totals_ignoresNegativeInput() {
        assertEquals(0, LayoverTimeScale.totals(-5).hours)
        assertEquals(0.0, LayoverTimeScale.totals(-5).days, 0.0001)
    }

    @Test
    fun oneDecimal_alwaysWritesOneDecimal() {
        assertEquals("20,8", LayoverTimeScale.oneDecimal(499 / 24.0, Locale.GERMAN))
        assertEquals("20.8", LayoverTimeScale.oneDecimal(499 / 24.0, Locale.ENGLISH))
        assertEquals("3,0", LayoverTimeScale.oneDecimal(499 / 168.0, Locale.GERMAN))
        assertEquals("0,5", LayoverTimeScale.oneDecimal(0.5, Locale.GERMAN))
        assertEquals("7,0", LayoverTimeScale.oneDecimal(7.0, Locale.GERMAN))
    }
}