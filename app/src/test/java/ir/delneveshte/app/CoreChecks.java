package ir.delneveshte.app;
import java.time.*;
import java.util.*;
public class CoreChecks {
 public static void main(String[] args){
  for(String zone:new String[]{"Asia/Tehran","Europe/Berlin","UTC"})for(int seed=0;seed<1000;seed++){
   ZoneId z=ZoneId.of(zone);LocalDate d=LocalDate.of(2026,10,25);
   long start=d.atTime(9,0).atZone(z).toInstant().toEpochMilli(),end=d.atTime(22,0).atZone(z).toInstant().toEpochMilli();
   long now=start+(seed%14)*3600000L;
   List<Long> p=SchedulePlanner.plan(d,z,540,1320,20,now,new Random(seed));
   if(now>=end&&!p.isEmpty())throw new AssertionError("expired window");
   long prev=now;for(long t:p){if(t<=prev||t<start||t>=end)throw new AssertionError("outside window/order");prev=t;}
  }
  for(String category:TextBank.categories())for(int i=0;i<100;i++){
   List<String> texts=TextBank.suggestions(category,"",new Random(i));
   if(texts.size()!=5||new HashSet<>(texts).size()!=5)throw new AssertionError("duplicate candidates");
   for(String text:texts){
    if(text.codePoints().noneMatch(cp->cp>=0x1F000||(cp>=0x2600&&cp<=0x27BF)))throw new AssertionError("missing romantic emoji");
    if(category.equals("شعر")){
     String[] lines=text.split("\n");
     if(lines.length!=3||!lines[2].startsWith("— "))throw new AssertionError("verse attribution changed");
    }
   }
  }
  for(String name:new String[]{"Asia/Tehran","Europe/Berlin","UTC"}){
   ZoneId zone=ZoneId.of(name);long now=LocalDate.of(2026,10,25).atTime(23,30).atZone(zone).toInstant().toEpochMilli();
   Map<String,Integer> used=new HashMap<>();Set<Long> occupied=new HashSet<>();Random rng=new Random(42);
   for(int n=0;n<101;n++){
    long at=SchedulePlanner.next(zone,540,1260,3,now,used,occupied,rng);ZonedDateTime t=Instant.ofEpochMilli(at).atZone(zone);
    if(at<=now||t.getHour()<9||t.getHour()>=21||used.get(t.toLocalDate().toString())>3)throw new AssertionError("invalid reservation");
   }
   if(occupied.size()!=101||used.size()!=34)throw new AssertionError("reservation duplicates or overflow");
  }
  Settings s=new Settings();if(s.valid())throw new AssertionError("missing settings allowed");
  s.recipient="+989121234567";s.subscriptionId=1;if(!s.valid())throw new AssertionError("valid settings rejected");
  s.endMinute=s.startMinute;if(s.valid())throw new AssertionError("invalid time allowed");
  System.out.println("PASS: 3000 schedule scenarios, 600 candidate batches, settings validation, multi-day reservations");
 }
}
