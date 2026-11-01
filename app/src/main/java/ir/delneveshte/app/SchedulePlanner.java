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
}
