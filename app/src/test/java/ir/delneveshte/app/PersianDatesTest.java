package ir.delneveshte.app;

import java.time.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={28,34})
public class PersianDatesTest {
    private final ZoneId iran=ZoneId.of("Asia/Tehran");
    @Test public void relativeDatesUseLocalCalendarDaysAcrossMidnight(){
        long now=Instant.parse("2026-11-02T20:25:00Z").toEpochMilli();
        assertEquals("امروز ساعت ۲۳:۵۵",PersianDates.relative(now,now,iran));
        assertEquals("فردا ساعت ۰۰:۰۵",PersianDates.relative(now+10*60_000,now,iran));
        long noon=LocalDate.of(2026,11,4).atTime(12,30).atZone(iran).toInstant().toEpochMilli();assertEquals("پس‌فردا ساعت ۱۲:۳۰",PersianDates.relative(noon,now,iran));
        assertEquals("دیروز ساعت ۲۳:۵۵",PersianDates.relative(now-86400_000,now,iran));assertEquals("پریروز ساعت ۲۳:۵۵",PersianDates.relative(now-2*86400_000,now,iran));
        assertEquals("۱۰ روز دیگر ساعت ۲۳:۵۵",PersianDates.relative(now+10*86400_000L,now,iran));
    }
    @Test public void daylightSavingDoesNotTurnTomorrowIntoToday(){
        ZoneId berlin=ZoneId.of("Europe/Berlin");long now=LocalDate.of(2026,3,28).atTime(23,45).atZone(berlin).toInstant().toEpochMilli(),at=LocalDate.of(2026,3,29).atTime(23,30).atZone(berlin).toInstant().toEpochMilli();assertEquals("فردا ساعت ۲۳:۳۰",PersianDates.relative(at,now,berlin));
    }
    @Test public void solarCalendarHandlesNowruzLeapEsfandAndPersianMonthNames(){
        assertEquals(LocalDate.of(2024,3,20),PersianDates.date(1403,1,1,iran));assertEquals(LocalDate.of(2025,3,20),PersianDates.date(1403,12,30,iran));assertEquals(LocalDate.of(2025,3,21),PersianDates.date(1404,1,1,iran));assertEquals(LocalDate.of(2026,3,21),PersianDates.date(1405,1,1,iran));
        assertEquals(30,PersianDates.monthLength(1403,12,iran));assertEquals(29,PersianDates.monthLength(1404,12,iran));assertEquals(31,PersianDates.monthLength(1405,1,iran));assertEquals("۱۱ آبان ۱۴۰۵",PersianDates.solar(LocalDate.of(2026,11,2),iran));
        try{PersianDates.date(1404,12,30,iran);fail("Non-leap Esfand 30 must be rejected");}catch(IllegalArgumentException expected){}
    }
    @Test public void solarMonthEndsRoundTripAcrossYearsAndTimeZones(){
        for(String name:new String[]{"Asia/Tehran","UTC","Europe/Berlin"})for(int year=1400;year<=1408;year++)for(int month=1;month<=12;month++){
            ZoneId zone=ZoneId.of(name);int day=PersianDates.monthLength(year,month,zone);assertArrayEquals(new int[]{year,month,day},PersianDates.fields(PersianDates.date(year,month,day,zone),zone));
        }
    }
}
