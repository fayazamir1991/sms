package ir.delneveshte.app;

import android.icu.util.Calendar;
import android.icu.util.TimeZone;
import android.icu.util.ULocale;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/** Android ICU's Persian calendar; stored times remain epoch milliseconds. */
public final class PersianDates {
    public static final String[] MONTHS={"فروردین","اردیبهشت","خرداد","تیر","مرداد","شهریور","مهر","آبان","آذر","دی","بهمن","اسفند"};
    private PersianDates() { }
    private static Calendar calendar(ZoneId zone){
        Calendar c=Calendar.getInstance(TimeZone.getTimeZone(zone.getId()),new ULocale("fa_IR@calendar=persian"));
        c.setLenient(false);return c;
    }
    public static int[] fields(LocalDate date,ZoneId zone){
        Calendar c=calendar(zone);c.setTimeInMillis(date.atTime(12,0).atZone(zone).toInstant().toEpochMilli());
        return new int[]{c.get(Calendar.YEAR),c.get(Calendar.MONTH)+1,c.get(Calendar.DAY_OF_MONTH)};
    }
    public static LocalDate date(int year,int month,int day,ZoneId zone){
        Calendar c=calendar(zone);c.clear();c.set(year,month-1,day,12,0,0);
        return Instant.ofEpochMilli(c.getTimeInMillis()).atZone(zone).toLocalDate();
    }
    public static int monthLength(int year,int month,ZoneId zone){
        Calendar c=calendar(zone);c.clear();c.set(year,month-1,1,12,0,0);return c.getActualMaximum(Calendar.DAY_OF_MONTH);
    }
    public static String solar(LocalDate date,ZoneId zone){
        int[] f=fields(date,zone);return digits(f[2])+" "+MONTHS[f[1]-1]+" "+digits(f[0]);
    }
    public static String clock(long at,ZoneId zone){
        ZonedDateTime time=Instant.ofEpochMilli(at).atZone(zone);
        return digits(String.format(Locale.ROOT,"%02d:%02d",time.getHour(),time.getMinute()));
    }
    public static String relative(long at,long now,ZoneId zone){
        if(at<=0)return "";
        LocalDate day=Instant.ofEpochMilli(at).atZone(zone).toLocalDate();
        long delta=ChronoUnit.DAYS.between(Instant.ofEpochMilli(now).atZone(zone).toLocalDate(),day);
        String label=delta==0?"امروز":delta==1?"فردا":delta==2?"پس‌فردا":delta==-1?"دیروز":delta==-2?"پریروز":digits(Math.abs(delta))+(delta>0?" روز دیگر":" روز پیش");
        return label+" ساعت "+clock(at,zone);
    }
    public static String digits(long number){return digits(Long.toString(number));}
    public static String digits(String text){StringBuilder out=new StringBuilder();for(char c:text.toCharArray())out.append(c>='0'&&c<='9'?(char)('۰'+c-'0'):c);return out.toString();}
}
