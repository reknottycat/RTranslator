package nie.translator.rtranslator.tools;
import java.util.Locale;
import org.junit.Test;
import static org.junit.Assert.*;
public class OfflineVoicePolicyTest {
 @Test public void supportsRussianEnglishChinese() {
  for(String code:new String[]{"ru","en","zh"})
   assertTrue(OfflineVoicePolicy.score(new Locale(code),new Locale(code),false,false,300)>=0);
 }
 @Test public void rejectsOnline() { assertEquals(-1,OfflineVoicePolicy.score(Locale.ENGLISH,Locale.ENGLISH,true,false,500)); }
 @Test public void rejectsMissingData() { assertEquals(-1,OfflineVoicePolicy.score(Locale.ENGLISH,Locale.ENGLISH,false,true,500)); }
 @Test public void rejectsWrongLanguage() { assertEquals(-1,OfflineVoicePolicy.score(new Locale("ru"),Locale.ENGLISH,false,false,500)); }
 @Test public void rejectsNull() { assertEquals(-1,OfflineVoicePolicy.score(null,Locale.ENGLISH,false,false,500)); assertEquals(-1,OfflineVoicePolicy.score(Locale.ENGLISH,null,false,false,500)); }
 @Test public void regionPreferredBeforeQuality() { assertTrue(OfflineVoicePolicy.score(Locale.US,Locale.US,false,false,100)>OfflineVoicePolicy.score(Locale.US,Locale.UK,false,false,500)); }
 @Test public void regionFallbackAllowed() { assertTrue(OfflineVoicePolicy.score(Locale.US,Locale.UK,false,false,100)>=0); }
 @Test public void lowQualityOfflineStillUsable() { assertTrue(OfflineVoicePolicy.score(new Locale("ru"),new Locale("ru"),false,false,100)>=0); }
}
