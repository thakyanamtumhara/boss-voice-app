package com.ketu.boss.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class ParserTest {

    // Fixed clock: Sunday 6 Sep 2026, 14:00 IST. Every expectation below is
    // relative to this instant.
    private var now = 0L

    @Before fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
        now = Calendar.getInstance().apply {
            clear(); set(2026, Calendar.SEPTEMBER, 6, 14, 0, 0)
        }.timeInMillis
    }

    private fun at(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ENGLISH).format(ms)

    private fun parse(s: String) = CommandParser.parse(s, now)

    // ---------- alarms ----------

    @Test fun alarmExplicitAm() {
        val c = parse("set an alarm for 6:30 am") as Command.Alarm
        assertEquals("2026-09-07 06:30", at(c.atMillis))
        assertEquals(6, c.hour); assertEquals(30, c.minute)
    }

    @Test fun alarmBareHourPicksNextOccurrence() {
        // 14:00 now, so "7" is 19:00 today, not 07:00 tomorrow.
        val c = parse("set alarm for 7") as Command.Alarm
        assertEquals("2026-09-06 19:00", at(c.atMillis))
    }

    @Test fun alarmMorningWordForcesAm() {
        val c = parse("alarm subah 7 baje") as Command.Alarm
        assertEquals("2026-09-07 07:00", at(c.atMillis))
    }

    @Test fun alarmTomorrowMorning() {
        val c = parse("set an alarm for tomorrow morning at 6") as Command.Alarm
        assertEquals("2026-09-07 06:00", at(c.atMillis))
    }

    @Test fun alarmHalfPast() {
        val c = parse("wake me at half past six") as Command.Alarm
        assertEquals("2026-09-06 18:30", at(c.atMillis))
    }

    @Test fun alarmQuarterTo() {
        val c = parse("set an alarm for quarter to seven") as Command.Alarm
        assertEquals("2026-09-06 18:45", at(c.atMillis))
    }

    @Test fun alarmSaadeHinglish() {
        val c = parse("saade 6 baje ka alarm laga do") as Command.Alarm
        assertEquals("2026-09-06 18:30", at(c.atMillis))
    }

    @Test fun alarmPauneHinglish() {
        val c = parse("paune 8 baje alarm lagao") as Command.Alarm
        assertEquals("2026-09-06 19:45", at(c.atMillis))
    }

    @Test fun alarmSevenThirtyWords() {
        val c = parse("alarm at seven thirty am") as Command.Alarm
        assertEquals("2026-09-07 07:30", at(c.atMillis))
    }

    @Test fun alarmRelative() {
        val c = parse("set an alarm in 20 minutes") as Command.Alarm
        assertEquals("2026-09-06 14:20", at(c.atMillis))
    }

    @Test fun alarmNightHourStaysAm() {
        val c = parse("alarm raat 2 baje") as Command.Alarm
        assertEquals("2026-09-07 02:00", at(c.atMillis))
    }

    @Test fun alarmWithLabel() {
        val c = parse("set an alarm for 5 am gym") as Command.Alarm
        assertEquals("2026-09-07 05:00", at(c.atMillis))
        assertEquals("gym", c.label)
    }

    @Test fun alarmWithNoTimeAsksForOne() {
        val c = parse("set an alarm") as Command.NeedTime
        assertEquals(Command.NeedTime.Kind.ALARM, c.kind)
    }

    // ---------- timers ----------

    @Test fun timerMinutes() {
        val c = parse("set a timer for 10 minutes") as Command.Timer
        assertEquals(600, c.seconds)
    }

    @Test fun timerHalfHour() {
        val c = parse("timer for half an hour") as Command.Timer
        assertEquals(1800, c.seconds)
    }

    @Test fun timerHinglish() {
        val c = parse("5 minute ka timer laga do") as Command.Timer
        assertEquals(300, c.seconds)
    }

    @Test fun timerSeconds() {
        val c = parse("timer 90 seconds") as Command.Timer
        assertEquals(90, c.seconds)
    }

    // ---------- reminders ----------

    @Test fun reminderAtTimeWithBody() {
        val c = parse("remind me at 5 pm to call the CA") as Command.Reminder
        assertEquals("2026-09-06 17:00", at(c.atMillis))
        assertEquals("call the ca", c.text)
    }

    @Test fun reminderRelativeWithBody() {
        val c = parse("remind me in 2 hours to check the stock") as Command.Reminder
        assertEquals("2026-09-06 16:00", at(c.atMillis))
        assertEquals("check the stock", c.text)
    }

    @Test fun reminderBodyBeforeTime() {
        val c = parse("remind me to pay the transporter at 7 pm") as Command.Reminder
        assertEquals("2026-09-06 19:00", at(c.atMillis))
        assertEquals("pay the transporter", c.text)
    }

    @Test fun reminderTomorrow() {
        val c = parse("remind me tomorrow at 10 am to send the invoice") as Command.Reminder
        assertEquals("2026-09-07 10:00", at(c.atMillis))
        assertEquals("send the invoice", c.text)
    }

    @Test fun reminderHinglish() {
        val c = parse("shaam 6 baje yaad dilana godam band karna") as Command.Reminder
        assertEquals("2026-09-06 18:00", at(c.atMillis))
    }

    @Test fun reminderWithoutTimeAsks() {
        val c = parse("remind me to buy milk") as Command.NeedTime
        assertEquals(Command.NeedTime.Kind.REMINDER, c.kind)
        assertEquals("buy milk", c.carry)
    }

    /** The one that must not become a phone call. */
    @Test fun reminderToCallIsNotACall() {
        val c = parse("remind me at 5 to call rajesh")
        assertTrue("expected Reminder, got $c", c is Command.Reminder)
        assertEquals("call rajesh", (c as Command.Reminder).text)
    }

    // ---------- calls ----------

    @Test fun callByName() {
        val c = parse("call rajesh") as Command.Call
        assertEquals("rajesh", c.who)
    }

    @Test fun callHinglish() {
        val c = parse("rajesh ko call karo") as Command.Call
        assertEquals("rajesh", c.who)
    }

    @Test fun callNumber() {
        val c = parse("call 9876543210") as Command.Call
        assertEquals("9876543210", c.who)
    }

    @Test fun callNeedsConfirmation() {
        assertTrue((parse("call rajesh") as Command.Call).needsConfirm)
    }

    // ---------- messages ----------

    @Test fun whatsappWithBody() {
        val c = parse("whatsapp rajesh saying i will be there in ten minutes") as Command.Message
        assertEquals("rajesh", c.who)
        assertEquals("i will be there in ten minutes", c.body)
    }

    @Test fun whatsappNoBody() {
        val c = parse("whatsapp rajesh") as Command.Message
        assertEquals("rajesh", c.who)
        assertEquals(null, c.body)
    }

    // ---------- the rest ----------

    @Test fun openApp() { assertEquals("instagram", (parse("open instagram") as Command.OpenApp).name) }
    @Test fun openAppHinglish() { assertEquals("gallery", (parse("gallery kholo") as Command.OpenApp).name) }
    @Test fun navigate() { assertEquals("sadar bazar", (parse("navigate to sadar bazar") as Command.Navigate).place) }
    @Test fun torchOn() { assertTrue((parse("turn on the torch") as Command.Torch).on) }
    @Test fun torchOff() { assertTrue(!(parse("torch off") as Command.Torch).on) }
    @Test fun playMusic() { assertEquals("kishore kumar", (parse("play kishore kumar") as Command.Play).query) }
    @Test fun timeNow() { assertTrue(parse("what's the time") is Command.TimeNow) }
    @Test fun timeNowHinglish() { assertTrue(parse("kitne baje hai") is Command.TimeNow) }
    @Test fun battery() { assertTrue(parse("battery kitni hai") is Command.BatteryNow) }
    @Test fun silent() { assertEquals(Command.Volume.Kind.SILENT, (parse("silent mode") as Command.Volume).kind) }
    @Test fun searchExplicit() { assertEquals("gst rate on cotton", (parse("search gst rate on cotton") as Command.Search).query) }
    @Test fun questionBecomesSearch() { assertTrue(parse("who is the ceo of tata") is Command.Search) }
    @Test fun cancel() { assertTrue(parse("cancel") is Command.Cancel) }
    @Test fun garbageIsUnknown() { assertTrue(parse("zzz frrp glub") is Command.Unknown) }

    /** The wake word often leaks into the transcript; it must not confuse anything. */
    @Test fun wakeWordPrefixIsIgnored() {
        val c = parse("hey boss set an alarm for 6 am") as Command.Alarm
        assertEquals("2026-09-07 06:00", at(c.atMillis))
    }

    @Test fun showReminders() { assertTrue(parse("what are my reminders") is Command.ShowReminders) }
}
