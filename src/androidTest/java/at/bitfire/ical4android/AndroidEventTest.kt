/*
 * Copyright © Ricki Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 */
package at.bitfire.ical4android

import android.Manifest
import android.accounts.Account
import android.content.ContentUris
import android.content.ContentValues
import android.database.DatabaseUtils
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.*
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import androidx.test.rule.GrantPermissionRule
import at.bitfire.ical4android.MiscUtils.ContentProviderClientHelper.closeCompat
import at.bitfire.ical4android.impl.TestCalendar
import at.bitfire.ical4android.impl.TestEvent
import at.bitfire.ical4android.util.AndroidTimeUtils
import net.fortuna.ical4j.model.Date
import net.fortuna.ical4j.model.DateList
import net.fortuna.ical4j.model.DateTime
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.component.VAlarm
import net.fortuna.ical4j.model.parameter.Email
import net.fortuna.ical4j.model.parameter.Value
import net.fortuna.ical4j.model.property.*
import net.fortuna.ical4j.util.TimeZones
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.net.URI
import java.time.Duration
import java.util.*

class AndroidEventTest {

    @JvmField
    @Rule
    val permissionRule = GrantPermissionRule.grant(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR
    )!!

    private val testAccount = Account("ical4android.AndroidEventTest", CalendarContract.ACCOUNT_TYPE_LOCAL)

    private val tzVienna = DateUtils.ical4jTimeZone("Europe/Vienna")!!
    private val tzShanghai = DateUtils.ical4jTimeZone("Asia/Shanghai")!!

    private val tzIdDefault = java.util.TimeZone.getDefault().id
    private val tzDefault = DateUtils.ical4jTimeZone(tzIdDefault)


    private val provider by lazy {
        getInstrumentation().targetContext.contentResolver.acquireContentProviderClient(CalendarContract.AUTHORITY)!!
    }

    private lateinit var calendarUri: Uri
    private lateinit var calendar: TestCalendar

    @Before
    fun prepare() {
        calendar = TestCalendar.findOrCreate(testAccount, provider)
        assertNotNull(calendar)
        calendarUri = ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendar.id)
    }

    @After
    fun shutdown() {
        calendar.delete()
        provider.closeCompat()
    }


    /**
     * buildEvent() BASIC TEST MATRIX:
     *
     * all-day event | hasDtEnd | hasDuration | recurring event | notes
     *        0            0            0              0          dtEnd = dtStart
     *        0            0            0              1          duration = 0s, rRule/rDate set
     *        0            0            1              0          dtEnd calulcated from duration
     *        0            0            1              1
     *        0            1            0              0
     *        0            1            0              1          dtEnd calulcated from duration
     *        0            1            1              0          duration ignored
     *        0            1            1              1          dtEnd ignored
     *        1            0            0              0          duration = 1d
     *        1            0            0              1          duration = 1d
     *        1            0            1              0          dtEnd calculated from duration
     *        1            0            1              1
     *        1            1            0              0
     *        1            1            0              1          duration calculated from dtEnd; ignore times in rDate
     *        1            1            1              0          duration ignored
     *        1            1            1              1          dtEnd ignored
     *
     *  buildEvent() EXTRA TESTS:
     *
     *  - floating times
     *  - floating times in rdate/exdate
     *  - UTC times
     */

    private fun buildEvent(automaticDates: Boolean, eventBuilder: Event.() -> Unit): ContentValues {
        val event = Event().apply {
            if (automaticDates)
                dtStart = DtStart(DateTime())
            eventBuilder()
        }
        val uri = TestEvent(calendar, event).add()
        provider.query(uri, null, null, null, null)!!.use {
            it.moveToNext()
            val values = ContentValues()
            DatabaseUtils.cursorRowToContentValues(it, values)
            return values
        }
    }

    private fun firstUnknownProperty(values: ContentValues): Property? {
        val id = values.getAsInteger(Events._ID)
        provider.query(CalendarContract.ExtendedProperties.CONTENT_URI, arrayOf(CalendarContract.ExtendedProperties.VALUE),
                "${CalendarContract.ExtendedProperties.EVENT_ID}=?", arrayOf(id.toString()), null)?.use {
            if (it.moveToNext())
                return UnknownProperty.fromJsonString(it.getString(0))
        }
        return null
    }

    @Test
    fun testBuildEvent_NonAllDay_NoDtEnd_NoDuration_NonRecurring() {
        val values = buildEvent(false) {
            dtStart = DtStart("20200601T123000", tzVienna)
        }
        assertEquals(0, values.getAsInteger(Events.ALL_DAY))

        assertEquals(1591007400000L, values.getAsLong(Events.DTSTART))
        assertEquals(tzVienna.id, values.get(Events.EVENT_TIMEZONE))

        assertEquals(1591007400000L, values.getAsLong(Events.DTEND))
        assertEquals(tzVienna.id, values.get(Events.EVENT_END_TIMEZONE))
    }

    @Test
    fun testBuildEvent_NonAllDay_NoDtEnd_NoDuration_Recurring() {
        val values = buildEvent(false) {
            dtStart = DtStart("20200601T123000", tzVienna)
            rRules += RRule("FREQ=DAILY;COUNT=5")
            rRules += RRule("FREQ=WEEKLY;COUNT=10")
            rDates += RDate(DateList("20210601T123000", Value.DATE_TIME, tzVienna))
        }
        assertEquals(0, values.getAsInteger(Events.ALL_DAY))

        assertEquals(1591007400000L, values.getAsLong(Events.DTSTART))
        assertEquals(tzVienna.id, values.get(Events.EVENT_TIMEZONE))

        assertEquals("P0D", values.getAsString(Events.DURATION))
        assertNull(values.get(Events.DTEND))
        assertNull(values.get(Events.EVENT_END_TIMEZONE))

        assertEquals("FREQ=DAILY;COUNT=5\nFREQ=WEEKLY;COUNT=10", values.getAsString(Events.RRULE))
        assertEquals("${tzVienna.id};20200601T123000,20210601T123000", values.getAsString(Events.RDATE))
    }

    @Test
    fun testBuildEvent_NonAllDay_NoDtEnd_Duration_NonRecurring() {
        val values = buildEvent(false) {
            dtStart = DtStart("20200601T123000", tzVienna)
            duration = Duration(null, "PT1H30M")
        }
        assertEquals(0, values.getAsInteger(Events.ALL_DAY))

        assertEquals(1591007400000L, values.getAsLong(Events.DTSTART))
        assertEquals(tzVienna.id, values.get(Events.EVENT_TIMEZONE))

        assertEquals(1591007400000L + 90*60000, values.getAsLong(Events.DTEND))
        assertEquals(tzVienna.id, values.get(Events.EVENT_END_TIMEZONE))
        assertNull(values.get(Events.DURATION))
    }

    @Test
    fun testBuildEvent_NonAllDay_NoDtEnd_Duration_Recurring() {
        val values = buildEvent(false) {
            dtStart = DtStart("20200601T123000", tzVienna)
            duration = Duration(null, "PT1H30M")
            rDates += RDate(DateList("20200602T113000", Value.DATE_TIME, tzVienna))
        }
        assertEquals(0, values.getAsInteger(Events.ALL_DAY))

        assertEquals(1591007400000L, values.getAsLong(Events.DTSTART))
        assertEquals(tzVienna.id, values.get(Events.EVENT_TIMEZONE))

        assertEquals("PT1H30M", values.getAsString(Events.DURATION))
        assertNull(values.get(Events.DTEND))
        assertNull(values.get(Events.EVENT_END_TIMEZONE))

        assertEquals("${tzVienna.id};20200601T123000,20200602T113000", values.get(Events.RDATE))
    }

    @Test
    fun testBuildEvent_NonAllDay_DtEnd_NoDuration_NonRecurring() {
        val values = buildEvent(false) {
            dtStart = DtStart("20200601T123000", tzVienna)
            dtEnd = DtEnd("20200602T143000", tzShanghai)
        }
        assertEquals(0, values.getAsInteger(Events.ALL_DAY))

        assertEquals(1591007400000L, values.getAsLong(Events.DTSTART))
        assertEquals(tzVienna.id, values.get(Events.EVENT_TIMEZONE))

        assertEquals(1591079400000L, values.getAsLong(Events.DTEND))
        assertEquals(tzShanghai.id, values.get(Events.EVENT_END_TIMEZONE))
        assertNull(values.get(Events.DURATION))
    }

    @Test
    fun testBuildEvent_NonAllDay_DtEnd_NoDuration_Recurring() {
        val values = buildEvent(false) {
            dtStart = DtStart("20200601T123000", tzShanghai)
            dtEnd = DtEnd("20200601T123000", tzVienna)
            rDates += RDate(DateList("20200701T123000,20200702T123000", Value.DATE_TIME, tzVienna))
            rDates += RDate(DateList("20200801T123000,20200802T123000", Value.DATE_TIME, tzShanghai))
        }
        assertEquals(0, values.getAsInteger(Events.ALL_DAY))

        assertEquals(1590985800000L, values.getAsLong(Events.DTSTART))
        assertEquals(tzShanghai.id, values.get(Events.EVENT_TIMEZONE))

        assertEquals("PT6H", values.getAsString(Events.DURATION))
        assertNull(values.get(Events.DTEND))
        assertNull(values.get(Events.EVENT_END_TIMEZONE))

        assertEquals("${tzShanghai.id};20200601T123000,20200701T183000,20200702T183000,20200801T123000,20200802T123000", values.getAsString(Events.RDATE))
    }

    @Test
    fun testBuildEvent_NonAllDay_DtEnd_Duration_NonRecurring() {
        val values = buildEvent(false) {
            dtStart = DtStart("20200601T123000", tzVienna)
            dtEnd = DtEnd("20200601T143000", tzVienna)
            duration = Duration(null, "PT10S")
        }
        assertEquals(0, values.getAsInteger(Events.ALL_DAY))

        assertEquals(1591007400000L, values.getAsLong(Events.DTSTART))
        assertEquals(tzVienna.id, values.get(Events.EVENT_TIMEZONE))

        assertEquals(1591014600000L, values.getAsLong(Events.DTEND))
        assertEquals(tzVienna.id, values.get(Events.EVENT_END_TIMEZONE))
        assertNull(values.get(Events.DURATION))
    }

    @Test
    fun testBuildEvent_NonAllDay_DtEnd_Duration_Recurring() {
        val values = buildEvent(false) {
            dtStart = DtStart("20200601T123000", tzVienna)
            dtEnd = DtEnd("20200601T143000", tzVienna)
            duration = Duration(null, "PT10S")
            rRules += RRule("FREQ=MONTHLY;COUNT=1")
        }
        assertEquals(0, values.getAsInteger(Events.ALL_DAY))

        assertEquals(1591007400000L, values.getAsLong(Events.DTSTART))
        assertEquals(tzVienna.id, values.get(Events.EVENT_TIMEZONE))

        assertEquals("PT10S", values.getAsString(Events.DURATION))
        assertNull(values.get(Events.DTEND))
        assertNull(values.get(Events.EVENT_END_TIMEZONE))

        assertEquals("FREQ=MONTHLY;COUNT=1", values.get(Events.RRULE))
    }

    @Test
    fun testBuildEvent_AllDay_NoDtEnd_NoDuration_NonRecurring() {
        val values = buildEvent(false) {
            dtStart = DtStart(Date("20200601"))
        }
        assertEquals(1, values.getAsInteger(Events.ALL_DAY))

        assertEquals(1590969600000L, values.getAsLong(Events.DTSTART))
        assertEquals(AndroidTimeUtils.TZID_ALLDAY, values.get(Events.EVENT_TIMEZONE))

        assertEquals(1591056000000L, values.getAsLong(Events.DTEND))
        assertEquals(AndroidTimeUtils.TZID_ALLDAY, values.get(Events.EVENT_END_TIMEZONE))
        assertNull(values.get(Events.DURATION))
    }

    @Test
    fun testBuildEvent_AllDay_NoDtEnd_NoDuration_Recurring() {
        val values = buildEvent(false) {
            dtStart = DtStart(Date("20200601"))
            rRules += RRule("FREQ=MONTHLY;COUNT=3")
        }
        assertEquals(1, values.getAsInteger(Events.ALL_DAY))

        assertEquals(1590969600000L, values.getAsLong(Events.DTSTART))
        assertEquals(AndroidTimeUtils.TZID_ALLDAY, values.get(Events.EVENT_TIMEZONE))

        assertEquals("P1D", values.getAsString(Events.DURATION))
        assertNull(values.get(Events.DTEND))
        assertNull(values.get(Events.EVENT_END_TIMEZONE))

        assertEquals("FREQ=MONTHLY;COUNT=3", values.get(Events.RRULE))
    }

    @Test
    fun testBuildEvent_AllDay_NoDtEnd_Duration_NonRecurring() {
        val values = buildEvent(false) {
            dtStart = DtStart(Date("20200601"))
            duration = Duration(null, "P2W1D")
        }
        assertEquals(1, values.getAsInteger(Events.ALL_DAY))

        assertEquals(1590969600000L, values.getAsLong(Events.DTSTART))
        assertEquals(AndroidTimeUtils.TZID_ALLDAY, values.get(Events.EVENT_TIMEZONE))

        assertEquals(1592265600000L, values.getAsLong(Events.DTEND))
        assertEquals(AndroidTimeUtils.TZID_ALLDAY, values.get(Events.EVENT_END_TIMEZONE))
        assertNull(values.get(Events.DURATION))
    }

    @Test
    fun testBuildEvent_AllDay_NoDtEnd_Duration_Recurring() {
        val values = buildEvent(false) {
            dtStart = DtStart(Date("20200601"))
            duration = Duration(null, "P2D")
            rRules += RRule("FREQ=YEARLY;BYMONTH=4;BYDAY=-1SU")
        }
        assertEquals(1, values.getAsInteger(Events.ALL_DAY))

        assertEquals(1590969600000L, values.getAsLong(Events.DTSTART))
        assertEquals(AndroidTimeUtils.TZID_ALLDAY, values.get(Events.EVENT_TIMEZONE))

        assertEquals("P2D", values.getAsString(Events.DURATION))
        assertNull(values.get(Events.DTEND))
        assertNull(values.get(Events.EVENT_END_TIMEZONE))

        assertEquals("FREQ=YEARLY;BYMONTH=4;BYDAY=-1SU", values.get(Events.RRULE))
    }

    @Test
    fun testBuildEvent_AllDay_DtEnd_NoDuration_NonRecurring() {
        val values = buildEvent(false) {
            dtStart = DtStart(Date("20200601"))
            dtEnd = DtEnd(Date("20200701"))
        }
        assertEquals(1, values.getAsInteger(Events.ALL_DAY))

        assertEquals(1590969600000L, values.getAsLong(Events.DTSTART))
        assertEquals(AndroidTimeUtils.TZID_ALLDAY, values.get(Events.EVENT_TIMEZONE))

        assertEquals(1593561600000L, values.getAsLong(Events.DTEND))
        assertEquals(AndroidTimeUtils.TZID_ALLDAY, values.get(Events.EVENT_END_TIMEZONE))
        assertNull(values.get(Events.DURATION))
    }

    @Test
    fun testBuildEvent_AllDay_DtEnd_NoDuration_Recurring() {
        val values = buildEvent(false) {
            dtStart = DtStart(Date("20200601"))
            dtEnd = DtEnd(Date("20200701"))
            rDates += RDate(DateList("20210601", Value.DATE))
            rDates += RDate(DateList("20220601T120030", Value.DATE_TIME))
        }
        assertEquals(1, values.getAsInteger(Events.ALL_DAY))

        assertEquals(1590969600000L, values.getAsLong(Events.DTSTART))
        assertEquals(AndroidTimeUtils.TZID_ALLDAY, values.get(Events.EVENT_TIMEZONE))

        assertEquals("P30D", values.getAsString(Events.DURATION))
        assertNull(values.get(Events.DTEND))
        assertNull(values.get(Events.EVENT_END_TIMEZONE))

        assertEquals("20200601T000000Z,20210601T000000Z,20220601T000000Z", values.get(Events.RDATE))
    }

    @Test
    fun testBuildEvent_AllDay_DtEnd_Duration_NonRecurring() {
        val values = buildEvent(false) {
            dtStart = DtStart(Date("20200601"))
            dtEnd = DtEnd(Date("20200701"))
            duration = Duration(null, "PT1M")
        }
        assertEquals(1, values.getAsInteger(Events.ALL_DAY))

        assertEquals(1590969600000L, values.getAsLong(Events.DTSTART))
        assertEquals(AndroidTimeUtils.TZID_ALLDAY, values.get(Events.EVENT_TIMEZONE))

        assertEquals(1593561600000L, values.getAsLong(Events.DTEND))
        assertEquals(AndroidTimeUtils.TZID_ALLDAY, values.get(Events.EVENT_END_TIMEZONE))
        assertNull(values.get(Events.DURATION))
    }

    @Test
    fun testBuildEvent_AllDay_DtEnd_Duration_Recurring() {
        val values = buildEvent(false) {
            dtStart = DtStart(Date("20200601"))
            dtEnd = DtEnd(Date("20200701"))
            duration = Duration(null, "PT1M")
            rRules += RRule("FREQ=DAILY;COUNT=1")
        }
        assertEquals(1, values.getAsInteger(Events.ALL_DAY))

        assertEquals(1590969600000L, values.getAsLong(Events.DTSTART))
        assertEquals(AndroidTimeUtils.TZID_ALLDAY, values.get(Events.EVENT_TIMEZONE))

        assertEquals("PT1M", values.getAsString(Events.DURATION))
        assertNull(values.get(Events.DTEND))
        assertNull(values.get(Events.EVENT_END_TIMEZONE))

        assertEquals("FREQ=DAILY;COUNT=1", values.get(Events.RRULE))
    }

    @Test
    fun testBuildEvent_FloatingTimes() {
        val values = buildEvent(false) {
            dtStart = DtStart("20200601T123000")
            dtEnd = DtEnd("20200601T123001")
        }
        assertEquals(0, values.getAsInteger(Events.ALL_DAY))

        assertEquals(DateTime("20200601T123000", tzDefault).time, values.getAsLong(Events.DTSTART))
        assertEquals(tzIdDefault, values.get(Events.EVENT_TIMEZONE))

        assertEquals(DateTime("20200601T123001", tzDefault).time, values.getAsLong(Events.DTEND))
        assertEquals(tzIdDefault, values.get(Events.EVENT_END_TIMEZONE))
    }

    @Test
    fun testBuildEvent_FloatingTimesInRecurrenceDates() {
        val values = buildEvent(false) {
            dtStart = DtStart("20200601T123000", tzShanghai)
            duration = Duration(null, "PT5M30S")
            rDates += RDate(DateList("20200602T113000", Value.DATE_TIME))
            exDates += ExDate(DateList("20200602T113000", Value.DATE_TIME))
        }
        assertEquals(0, values.getAsInteger(Events.ALL_DAY))

        assertEquals(1590985800000L, values.getAsLong(Events.DTSTART))
        assertEquals(tzShanghai.id, values.get(Events.EVENT_TIMEZONE))

        assertEquals("PT5M30S", values.getAsString(Events.DURATION))
        assertNull(values.get(Events.EVENT_END_TIMEZONE))

        val rewritten = DateTime("20200602T113000")
        rewritten.timeZone = tzShanghai
        assertEquals("${tzShanghai.id};20200601T123000,$rewritten", values.get(Events.RDATE))
        assertEquals("$tzIdDefault;20200602T113000", values.get(Events.EXDATE))
    }

    @Test
    fun testBuildEvent_UTC() {
        val values = buildEvent(false) {
            dtStart = DtStart("20200601T123000Z")
            dtEnd = DtEnd("20200601T143001Z")
        }
        assertEquals(0, values.getAsInteger(Events.ALL_DAY))

        assertEquals(1591014600000L, values.getAsLong(Events.DTSTART))
        assertEquals(TimeZones.UTC_ID, values.get(Events.EVENT_TIMEZONE))

        assertEquals(1591021801000L, values.getAsLong(Events.DTEND))
        assertEquals(TimeZones.UTC_ID, values.get(Events.EVENT_END_TIMEZONE))
    }

    @Test
    fun testBuildEvent_Summary() {
        buildEvent(true) {
            summary = "Sample Summary"
        }.let { result ->
            assertEquals("Sample Summary", result.get(Events.TITLE))
        }
    }

    @Test
    fun testBuildEvent_Location() {
        buildEvent(true) {
            location = "Sample Location"
        }.let { result ->
            assertEquals("Sample Location", result.get(Events.EVENT_LOCATION))
        }
    }

    @Test
    fun testBuildEvent_Description() {
        buildEvent(true) {
            description = "Sample Description"
        }.let { result ->
            assertEquals("Sample Description", result.get(Events.DESCRIPTION))
        }
    }

    @Test
    fun testBuildEvent_Color_WhenNotAvailable() {
        AndroidCalendar.removeColors(provider, testAccount)
        buildEvent(true) {
            color = Css3Color.darkseagreen
        }.let { result ->
            assertNull(result.get(Events.CALENDAR_COLOR_KEY))
        }
    }

    @Test
    fun testBuildEvent_Color_WhenAvailable() {
        AndroidCalendar.insertColors(provider, testAccount)
        buildEvent(true) {
            color = Css3Color.darkseagreen
        }.let { result ->
            assertEquals(Css3Color.darkseagreen.name, result.get(Events.EVENT_COLOR_KEY))
        }
    }

    @Test
    fun testBuildEvent_Organizer_NotGroupScheduled() {
        buildEvent(true) {
            organizer = Organizer("mailto:organizer@example.com")
        }.let { result ->
            assertNull(result.get(Events.ORGANIZER))
        }
    }

    @Test
    fun testBuildEvent_Organizer_MailTo() {
        buildEvent(true) {
            organizer = Organizer("mailto:organizer@example.com")
            attendees += Attendee("mailto:attendee@example.com")
        }.let { result ->
            assertEquals("organizer@example.com", result.get(Events.ORGANIZER))
        }
    }

    @Test
    fun testBuildEvent_Organizer_EmailParameter() {
        buildEvent(true) {
            organizer = Organizer("local-id:user").apply {
                parameters.add(Email("organizer@example.com"))
            }
            attendees += Attendee("mailto:attendee@example.com")
        }.let { result ->
            assertEquals("organizer@example.com", result.get(Events.ORGANIZER))
        }
    }

    @Test
    fun testBuildEvent_Organizer_NotEmail() {
        buildEvent(true) {
            organizer = Organizer("local-id:user")
            attendees += Attendee("mailto:attendee@example.com")
        }.let { result ->
            assertNull(result.get(Events.ORGANIZER))
        }
    }

    @Test
    fun testBuildEvent_Status_Confirmed() {
        buildEvent(true) {
            status = Status.VEVENT_CONFIRMED
        }.let { result ->
            assertEquals(Events.STATUS_CONFIRMED, result.getAsInteger(Events.STATUS))
        }
    }

    @Test
    fun testBuildEvent_Status_Cancelled() {
        buildEvent(true) {
            status = Status.VEVENT_CANCELLED
        }.let { result ->
            assertEquals(Events.STATUS_CANCELED, result.getAsInteger(Events.STATUS))
        }
    }

    @Test
    fun testBuildEvent_Status_Tentative() {
        buildEvent(true) {
            status = Status.VEVENT_TENTATIVE
        }.let { result ->
            assertEquals(Events.STATUS_TENTATIVE, result.getAsInteger(Events.STATUS))
        }
    }

    @Test
    fun testBuildEvent_Status_Invalid() {
        buildEvent(true) {
            status = Status.VTODO_IN_PROCESS
        }.let { result ->
            assertEquals(Events.STATUS_TENTATIVE, result.getAsInteger(Events.STATUS))
        }
    }

    @Test
    fun testBuildEvent_Status_None() {
        buildEvent(true) {
        }.let { result ->
            assertNull(result.get(Events.STATUS))
        }
    }

    @Test
    fun testBuildEvent_Opaque_True() {
        buildEvent(true) {
            opaque = true
        }.let { result ->
            assertEquals(Events.AVAILABILITY_BUSY, result.getAsInteger(Events.AVAILABILITY))
        }
    }

    @Test
    fun testBuildEvent_Opaque_False() {
        buildEvent(true) {
            opaque = false
        }.let { result ->
            assertEquals(Events.AVAILABILITY_FREE, result.getAsInteger(Events.AVAILABILITY))
        }
    }

    @Test
    fun testBuildEvent_Classification_Public() {
        buildEvent(true) {
            classification = Clazz.PUBLIC
        }.let { result ->
            assertEquals(Events.ACCESS_PUBLIC, result.getAsInteger(Events.ACCESS_LEVEL))
            assertNull(firstUnknownProperty(result))
        }
    }

    @Test
    fun testBuildEvent_Classification_Private() {
        buildEvent(true) {
            classification = Clazz.PRIVATE
        }.let { result ->
            assertEquals(Events.ACCESS_PRIVATE, result.getAsInteger(Events.ACCESS_LEVEL))
            assertNull(firstUnknownProperty(result))
        }
    }

    @Test
    fun testBuildEvent_Classification_Confidential() {
        buildEvent(true) {
            classification = Clazz.CONFIDENTIAL
        }.let { result ->
            assertEquals(Events.ACCESS_CONFIDENTIAL, result.getAsInteger(Events.ACCESS_LEVEL))
            assertEquals(Clazz.CONFIDENTIAL, firstUnknownProperty(result))
        }
    }

    @Test
    fun testBuildEvent_Classification_Custom() {
        buildEvent(true) {
            classification = Clazz("TOP-SECRET")
        }.let { result ->
            assertEquals(Events.ACCESS_PRIVATE, result.getAsInteger(Events.ACCESS_LEVEL))
            assertEquals(Clazz("TOP-SECRET"), firstUnknownProperty(result))
        }
    }

    @Test
    fun testBuildEvent_Classification_None() {
        buildEvent(true) {
        }.let { result ->
            assertEquals(Events.ACCESS_PUBLIC, result.getAsInteger(Events.ACCESS_LEVEL))
            assertNull(firstUnknownProperty(result))
        }
    }

    // TODO tests: build alarms, attendees, exceptions, unknown properties


    private fun populateEvent(
            automaticDates: Boolean,
            asSyncAdapter: Boolean = false,
            insertCallback: (id: Long) -> Unit = {},
            valuesBuilder: ContentValues.() -> Unit
    ): Event {
        val values = ContentValues()
        values.put(Events.CALENDAR_ID, calendar.id)
        if (automaticDates) {
            values.put(Events.DTSTART, 1592733600000L)  // 21/06/2020 12:00 +0200
            values.put(Events.EVENT_TIMEZONE, "Europe/Berlin")
            values.put(Events.DTEND, 1592742600000L)    // 21/06/2020 14:30 +0200
            values.put(Events.EVENT_END_TIMEZONE, "Europe/Berlin")
        }
        valuesBuilder(values)
        Ical4Android.log.info("Inserting test event: $values")
        val uri = provider.insert(
                if (asSyncAdapter) calendar.syncAdapterURI(Events.CONTENT_URI) else Events.CONTENT_URI,
                values)!!
        val id = ContentUris.parseId(uri)

        // insert additional rows etc.
        insertCallback(id)

        val androidEvent = calendar.findById(id)
        return androidEvent.event!!
    }

    @Test
    fun testPopulateEvent_NonAllDay_NonRecurring() {
        populateEvent(false) {
            put(Events.DTSTART, 1592733600000L)  // 21/06/2020 12:00 +0200
            put(Events.EVENT_TIMEZONE, "Europe/Vienna")
            put(Events.DTEND, 1592742600000L)    // 21/06/2020 14:30 +0200
            put(Events.EVENT_END_TIMEZONE, "Europe/Vienna")
        }.let { result ->
            assertEquals(DtStart(DateTime("20200621T120000", tzVienna)), result.dtStart)
            assertEquals(DtEnd(DateTime("20200621T143000", tzVienna)), result.dtEnd)
            assertNull(result.duration)
        }
    }

    @Test
    fun testPopulateEvent_NonAllDay_NonRecurring_MixedZones() {
        populateEvent(false) {
            put(Events.DTSTART, 1592733600000L)  // 21/06/2020 18:00 +0800
            put(Events.EVENT_TIMEZONE, "Asia/Shanghai")
            put(Events.DTEND, 1592742600000L)    // 21/06/2020 14:30 +0200
            put(Events.EVENT_END_TIMEZONE, "Europe/Vienna")
        }.let { result ->
            assertEquals(DtStart(DateTime("20200621T180000", tzShanghai)), result.dtStart)
            assertEquals(DtEnd(DateTime("20200621T143000", tzVienna)), result.dtEnd)
            assertNull(result.duration)
        }
    }

    @Test
    fun testPopulateEvent_NonAllDay_NonRecurring_Duration() {
        /* This should not happen, because according to the documentation, non-recurring events MUST
        have a dtEnd. However, the calendar provider doesn't enforce this for non-sync-adapters. */
        populateEvent(false, asSyncAdapter = false) {
            put(Events.DTSTART, 1592733600000L)  // 21/06/2020 18:00 +0800
            put(Events.EVENT_TIMEZONE, "Asia/Shanghai")
            put(Events.DURATION, "PT1H")
        }.let { result ->
            assertEquals(DtStart(DateTime("20200621T180000", tzShanghai)), result.dtStart)
            assertNull(result.dtEnd)
            assertEquals(Duration(null, "PT1H"), result.duration)
        }
    }

    @Test
    fun testPopulateEvent_NonAllDay_NonRecurring_NoTime() {
        populateEvent(false) {
            put(Events.DTSTART, 1592742600000L)  // 21/06/2020 14:30 +0200
            put(Events.EVENT_TIMEZONE, "Europe/Vienna")
            put(Events.DTEND, 1592742600000L)    // 21/06/2020 14:30 +0200
            put(Events.EVENT_END_TIMEZONE, "Europe/Vienna")
        }.let { result ->
            assertEquals(DtStart(DateTime("20200621T143000", tzVienna)), result.dtStart)
            assertNull(result.dtEnd)
            assertNull(result.duration)
        }
    }

    @Test
    fun testPopulateEvent_AllDay_NonRecurring_NoTime() {
        populateEvent(false) {
            put(Events.ALL_DAY, 1)
            put(Events.DTSTART, 1592697600000L)  // 21/06/2020
            put(Events.EVENT_TIMEZONE, AndroidTimeUtils.TZID_ALLDAY)
            put(Events.DTEND, 1592697600000L)    // 21/06/2020
            put(Events.EVENT_END_TIMEZONE, AndroidTimeUtils.TZID_ALLDAY)
        }.let { result ->
            assertEquals(DtStart(Date("20200621")), result.dtStart)
            assertNull(result.dtEnd)
            assertNull(result.duration)
        }
    }

    @Test
    fun testPopulateEvent_AllDay_NonRecurring_1Day() {
        populateEvent(false) {
            put(Events.ALL_DAY, 1)
            put(Events.DTSTART, 1592697600000L)  // 21/06/2020
            put(Events.EVENT_TIMEZONE, AndroidTimeUtils.TZID_ALLDAY)
            put(Events.DTEND, 1592784000000L)    // 22/06/2020
            put(Events.EVENT_END_TIMEZONE, AndroidTimeUtils.TZID_ALLDAY)
        }.let { result ->
            assertEquals(DtStart(Date("20200621")), result.dtStart)
            assertEquals(DtEnd(Date("20200622")), result.dtEnd)
            assertNull(result.duration)
        }
    }

    @Test
    fun testPopulateEvent_AllDay_NonRecurring_AllDayDuration() {
        /* This should not happen, because according to the documentation, non-recurring events MUST
        have a dtEnd. However, the calendar provider doesn't enforce this for non-sync-adapters. */
        populateEvent(false, asSyncAdapter = false) {
            put(Events.ALL_DAY, 1)
            put(Events.DTSTART, 1592697600000L)  // 21/06/2020
            put(Events.EVENT_TIMEZONE, AndroidTimeUtils.TZID_ALLDAY)
            put(Events.DURATION, "P1W")
        }.let { result ->
            assertEquals(DtStart(Date("20200621")), result.dtStart)
            assertNull(result.dtEnd)
            assertEquals(Duration(null, "P1W"), result.duration)
        }
    }

    @Test
    fun testPopulateEvent_AllDay_NonRecurring_NonAllDayDuration_LessThanOneDay() {
        /* This should not happen, because according to the documentation, non-recurring events MUST
        have a dtEnd. However, the calendar provider doesn't enforce this for non-sync-adapters. */
        populateEvent(false, asSyncAdapter = false) {
            put(Events.ALL_DAY, 1)
            put(Events.DTSTART, 1592697600000L)  // 21/06/2020
            put(Events.EVENT_TIMEZONE, AndroidTimeUtils.TZID_ALLDAY)
            put(Events.DURATION, "PT1H30M")
        }.let { result ->
            assertEquals(DtStart(Date("20200621")), result.dtStart)
            assertNull(result.dtEnd)
            assertNull(result.duration)
        }
    }
    @Test
    fun testPopulateEvent_AllDay_NonRecurring_NonAllDayDuration_MoreThanOneDay() {
        /* This should not happen, because according to the documentation, non-recurring events MUST
        have a dtEnd. However, the calendar provider doesn't enforce this for non-sync-adapters. */
        populateEvent(false, asSyncAdapter = false) {
            put(Events.ALL_DAY, 1)
            put(Events.DTSTART, 1592697600000L)  // 21/06/2020
            put(Events.EVENT_TIMEZONE, AndroidTimeUtils.TZID_ALLDAY)
            put(Events.DURATION, "PT49H2M")
        }.let { result ->
            assertEquals(DtStart(Date("20200621")), result.dtStart)
            assertNull(result.dtEnd)
            assertEquals(Duration(null, "P2D"), result.duration)
        }
    }

    @Test
    fun testPopulateEvent_Summary() {
        populateEvent(true) {
            put(Events.TITLE, "Sample Title")
        }.let { result ->
            assertEquals("Sample Title", result.summary)
        }
    }

    @Test
    fun testPopulateEvent_Location() {
        populateEvent(true) {
            put(Events.EVENT_LOCATION, "Sample Location")
        }.let { result ->
            assertEquals("Sample Location", result.location)
        }
    }

    @Test
    fun testPopulateEvent_Description() {
        populateEvent(true) {
            put(Events.DESCRIPTION, "Sample Description")
        }.let { result ->
            assertEquals("Sample Description", result.description)
        }
    }

    @Test
    fun testPopulateEvent_Color() {
        AndroidCalendar.insertColors(provider, testAccount)
        populateEvent(true) {
            put(Events.EVENT_COLOR_KEY, Css3Color.silver.name)
        }.let { result ->
            assertEquals(Css3Color.silver, result.color)
        }
    }

    @Test
    fun testPopulateEvent_Status_Confirmed() {
        populateEvent(true) {
            put(Events.STATUS, Events.STATUS_CONFIRMED)
        }.let { result ->
            assertEquals(Status.VEVENT_CONFIRMED, result.status)
        }
    }

    @Test
    fun testPopulateEvent_Status_Tentative() {
        populateEvent(true) {
            put(Events.STATUS, Events.STATUS_TENTATIVE)
        }.let { result ->
            assertEquals(Status.VEVENT_TENTATIVE, result.status)
        }
    }

    @Test
    fun testPopulateEvent_Status_Cancelled() {
        populateEvent(true) {
            put(Events.STATUS, Events.STATUS_CANCELED)
        }.let { result ->
            assertEquals(Status.VEVENT_CANCELLED, result.status)
        }
    }

    @Test
    fun testPopulateEvent_Status_None() {
        populateEvent(true) {
        }.let { result ->
            assertNull(result.status)
        }
    }

    @Test
    fun testPopulateEvent_Availability_Busy() {
        populateEvent(true) {
            put(Events.AVAILABILITY, Events.AVAILABILITY_BUSY)
        }.let { result ->
            assertTrue(result.opaque)
        }
    }

    @Test
    fun testPopulateEvent_Availability_Tentative() {
        populateEvent(true) {
            put(Events.AVAILABILITY, Events.AVAILABILITY_TENTATIVE)
        }.let { result ->
            assertTrue(result.opaque)
        }
    }

    @Test
    fun testPopulateEvent_Availability_Free() {
        populateEvent(true) {
            put(Events.AVAILABILITY, Events.AVAILABILITY_FREE)
        }.let { result ->
            assertFalse(result.opaque)
        }
    }

    @Test
    fun testPopulateEvent_Organizer_NotGroupScheduled() {
        populateEvent(true) {
        }.let { result ->
            assertNull(result.organizer)
        }
    }

    @Test
    fun testPopulateEvent_Organizer_NotGroupScheduled_ExplicitOrganizer() {
        populateEvent(true) {
            put(Events.ORGANIZER, "sample@example.com")
        }.let { result ->
            assertNull(result.organizer)
        }
    }

    @Test
    fun testPopulateEvent_Organizer_GroupScheduled() {
        populateEvent(true, valuesBuilder = {
            put(Events.ORGANIZER, "organizer@example.com")
        }, insertCallback = { id ->
            provider.insert(calendar.syncAdapterURI(Attendees.CONTENT_URI), ContentValues().apply {
                put(Attendees.EVENT_ID, id)
                put(Attendees.ATTENDEE_EMAIL, "organizer@example.com")
                put(Attendees.ATTENDEE_TYPE, Attendees.RELATIONSHIP_ORGANIZER)
            })
        }).let { result ->
            assertEquals("mailto:organizer@example.com", result.organizer?.value)
        }
    }

    @Test
    fun testPopulateEvent_Classification_Public() {
        populateEvent(true) {
            put(Events.ACCESS_LEVEL, Events.ACCESS_PUBLIC)
        }.let { result ->
            assertEquals(Clazz.PUBLIC, result.classification)
        }
    }

    @Test
    fun testPopulateEvent_Classification_Private() {
        populateEvent(true) {
            put(Events.ACCESS_LEVEL, Events.ACCESS_PRIVATE)
        }.let { result ->
            assertEquals(Clazz.PRIVATE, result.classification)
        }
    }

    @Test
    fun testPopulateEvent_Classification_Confidential() {
        populateEvent(true) {
            put(Events.ACCESS_LEVEL, Events.ACCESS_CONFIDENTIAL)
        }.let { result ->
            assertEquals(Clazz.CONFIDENTIAL, result.classification)
        }
    }

    @Test
    fun testPopulateEvent_Classification_Confidential_Retained() {
        populateEvent(true, valuesBuilder = {
            put(Events.ACCESS_LEVEL, Events.ACCESS_DEFAULT)
        }, insertCallback = { id ->
            provider.insert(calendar.syncAdapterURI(ExtendedProperties.CONTENT_URI), ContentValues().apply {
                put(ExtendedProperties.EVENT_ID, id)
                put(ExtendedProperties.NAME, UnknownProperty.CONTENT_ITEM_TYPE)
                put(ExtendedProperties.VALUE, UnknownProperty.toJsonString(Clazz.CONFIDENTIAL))
            })
        }).let { result ->
            assertEquals(Clazz.CONFIDENTIAL, result.classification)
        }
    }

    @Test
    fun testPopulateEvent_Classification_Default() {
        populateEvent(true) {
            put(Events.ACCESS_LEVEL, Events.ACCESS_DEFAULT)
        }.let { result ->
            assertNull(result.classification)
        }
    }

    @Test
    fun testPopulateEvent_Classification_Custom() {
        populateEvent(true, valuesBuilder = {
            put(Events.ACCESS_LEVEL, Events.ACCESS_DEFAULT)
        }, insertCallback = { id ->
            provider.insert(calendar.syncAdapterURI(ExtendedProperties.CONTENT_URI), ContentValues().apply {
                put(ExtendedProperties.EVENT_ID, id)
                put(ExtendedProperties.NAME, UnknownProperty.CONTENT_ITEM_TYPE)
                put(ExtendedProperties.VALUE, UnknownProperty.toJsonString(Clazz("TOP-SECRET")))
            })
        }).let { result ->
            assertEquals(Clazz("TOP-SECRET"), result.classification)
        }
    }

    @Test
    fun ttestPopulateEvent_Classification_None() {
        populateEvent(true) {
        }.let { result ->
            assertNull(result.classification)
        }
    }

    // TODO tests: populate alarms, attendees, exceptions, unknown properties


    @Test
    fun testUpdateEvent() {
        // add test event without reminder
        val event = Event()
        event.uid = "sample1@testAddEvent"
        event.summary = "Sample event"
        event.dtStart = DtStart("20150502T120000Z")
        event.dtEnd = DtEnd("20150502T130000Z")
        event.organizer = Organizer(URI("mailto:organizer@example.com"))
        val uri = TestEvent(calendar, event).add()

        // update test event in calendar
        val testEvent = calendar.findById(ContentUris.parseId(uri))
        val event2 = testEvent.event!!
        event2.summary = "Updated event"
        // add data rows
        event2.alarms += VAlarm(Duration.parse("-P1DT2H3M4S"))
        event2.attendees += Attendee(URI("mailto:user@example.com"))
        val uri2 = testEvent.update(event2)

        // read again and verify result
        val updatedEvent = calendar.findById(ContentUris.parseId(uri2))
        try {
            val event3 = updatedEvent.event!!
            assertEquals(event2.summary, event3.summary)
            assertEquals(1, event3.alarms.size)
            assertEquals(1, event3.attendees.size)
        } finally {
            updatedEvent.delete()
        }
    }

    @LargeTest
    @Test
    fun testLargeTransactionManyRows() {
        val event = Event()
        event.uid = "sample1@testLargeTransaction"
        event.summary = "Large event"
        event.dtStart = DtStart("20150502T120000Z")
        event.dtEnd = DtEnd("20150502T130000Z")
        for (i in 0 until 4000)
            event.attendees += Attendee(URI("mailto:att$i@example.com"))
        val uri = TestEvent(calendar, event).add()

        val testEvent = calendar.findById(ContentUris.parseId(uri))
        try {
            assertEquals(4000, testEvent.event!!.attendees.size)
        } finally {
            testEvent.delete()
        }
    }

    @Test(expected = CalendarStorageException::class)
    fun testLargeTransactionSingleRow() {
        val event = Event()
        event.uid = "sample1@testLargeTransaction"
        event.dtStart = DtStart("20150502T120000Z")
        event.dtEnd = DtEnd("20150502T130000Z")

        // 1 MB SUMMARY ... have fun
        val data = CharArray(1024*1024)
        Arrays.fill(data, 'x')
        event.summary = String(data)

        TestEvent(calendar, event).add()
    }

}
