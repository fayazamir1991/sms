package ir.delneveshte.app;
import java.time.*;
import java.util.*;
public final class SchedulePlanner {
 public static List<Long> plan(LocalDate day,ZoneId zone,int start,int end,int count,long now,Random rng){
  if(start<0||end>1440||start>=end||count<0||count>20)throw new IllegalArgumentException();
  long lo=Math.max(day.atStartOfDay().plusMinutes(start).atZone(zone).toInstant().toEpochMilli(),now+1);
  long hi=day.atStartOfDay().plusMinutes(end).atZone(zone).toInstant().toEpochMilli();
  List<Long> out=new ArrayList<>();if(lo>=hi||count==0)return out;
  int n=(int)Math.min(count,hi-lo);
  for(int i=0;i<n;i++){long a=lo+(hi-lo)*i/n,b=lo+(hi-lo)*(i+1)/n;out.add(a+(long)(rng.nextDouble()*(b-a)));}return out;
 }
 /** Reserve a concrete future time, spilling into later days when the daily quota is full. */
 public static long next(ZoneId zone,int start,int end,int daily,long now,Map<String,Integer> used,Set<Long> occupied,Random rng){
  if(start<0||end>1440||start>=end||daily<1||daily>20)throw new IllegalArgumentException();
  LocalDate day=Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
  for(;;day=day.plusDays(1)){
   if(used.getOrDefault(day.toString(),0)>=daily)continue;
   long lo=Math.max(day.atStartOfDay().plusMinutes(start).atZone(zone).toInstant().toEpochMilli(),now+60_000L);
   long hi=day.atStartOfDay().plusMinutes(end).atZone(zone).toInstant().toEpochMilli();
   if(lo>=hi)continue;
   long at=lo+(long)(rng.nextDouble()*(hi-lo));
   while(occupied.contains(at)){at++;if(at>=hi)at=lo;}
   used.put(day.toString(),used.getOrDefault(day.toString(),0)+1);occupied.add(at);return at;
  }
 }
}
