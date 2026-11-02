package ir.delneveshte.app;
public class Settings {
 public String recipient="",nickname="عزیزم";
 public String tone="colloquial";
 public int dailyCount=2,startMinute=540,endMinute=1260,subscriptionId=-1;
 public boolean enabled=false;
 public boolean valid(){return recipient!=null && recipient.matches("\\+?[0-9]{7,15}") && dailyCount>=1 && dailyCount<=20 && startMinute>=0 && endMinute<=1440 && startMinute<endMinute && subscriptionId>=0;}
 public Settings copy(){Settings s=new Settings();s.recipient=recipient;s.nickname=nickname;s.dailyCount=dailyCount;s.startMinute=startMinute;s.endMinute=endMinute;s.subscriptionId=subscriptionId;s.enabled=enabled;s.tone=tone;return s;}
}
