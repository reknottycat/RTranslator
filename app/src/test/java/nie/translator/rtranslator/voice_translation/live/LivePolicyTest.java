package nie.translator.rtranslator.voice_translation.live;
import org.junit.Test;
import static org.junit.Assert.*;

public class LivePolicyTest {
    @Test public void russianAndChineseAreBothValid() {
        assertTrue(CaptionPolicy.usable("Здравствуйте"));
        assertTrue(CaptionPolicy.usable("你好"));
    }
    @Test public void silenceAndMetaTextAreRejected() {
        assertFalse(CaptionPolicy.usable(null)); assertFalse(CaptionPolicy.usable("  "));
        assertFalse(CaptionPolicy.usable("[Music]")); assertFalse(CaptionPolicy.usable("..."));
        assertFalse(CaptionPolicy.usable("<|nospeech|>"));
    }
    @Test public void validCandidateBeatsInvalidRegardlessOfScore() {
        assertEquals(CaptionPolicy.SECOND, CaptionPolicy.choose("", 0, "你好", -100, true, false));
        assertEquals(-1, CaptionPolicy.choose("", 0, "[Music]", 0, false, false));
    }
    @Test public void languageEvidenceWinsBeforeScore() {
        assertEquals(CaptionPolicy.FIRST, CaptionPolicy.choose("Привет", -12, "你好", -1, true, false));
        assertEquals(CaptionPolicy.SECOND, CaptionPolicy.choose("Привет", -1, "你好", -12, false, true));
    }
    @Test public void nonFiniteScoresAreNotPreferred() {
        assertEquals(CaptionPolicy.SECOND, CaptionPolicy.choose("Привет", Double.NaN, "你好", -12, false, false));
    }
    @Test public void packetSupportsOriginalProtocolAndUnicode() {
        ConversationPacket p = ConversationPacket.parse("Приветru2");
        assertNotNull(p); assertEquals("Привет", p.text); assertEquals("ru", p.language);
        p = ConversationPacket.parse("你好zh2"); assertEquals("你好", p.text);
        p = ConversationPacket.parse("zh-CN5"); assertEquals("", p.text); assertEquals("zh-CN", p.language);
    }
    @Test public void malformedPacketsFailClosed() {
        for (String s : new String[]{"", "a", "x9", "hello!2", "abcx", "abc0", "<script7"})
            assertNull(s, ConversationPacket.parse(s));
        assertNull(ConversationPacket.parse(null));
    }
    @Test public void gainDoesNotAmplifySilence() {
        CaptureGain gain = new CaptureGain(); float[] x = new float[512];
        for (int i=0; i<20; i++) gain.process(x,0,x.length);
        assertEquals(1f,gain.getGain(),0f); for (float f:x) assertEquals(0f,f,0f);
    }
    @Test public void gainIsLimitedAndEnhancesQuietSpeech() {
        CaptureGain gain = new CaptureGain(); float[] x = new float[512];
        for (int n=0;n<100;n++) { java.util.Arrays.fill(x,0.01f); gain.process(x,0,x.length); }
        assertTrue(gain.getGain() <= 4f); assertTrue(x[511] > 0.01f);
    }
    @Test public void strongInputAndInvalidSamplesDoNotClipOrPropagateNan() {
        CaptureGain gain = new CaptureGain(); float[] x = {10f,-10f,Float.NaN,Float.POSITIVE_INFINITY};
        gain.process(x,0,x.length);
        for (float f:x) { assertTrue(Float.isFinite(f)); assertTrue(Math.abs(f)<=0.95f); }
    }
    @Test public void offsetDoesNotModifyOtherFrames() {
        CaptureGain gain = new CaptureGain(); float[] x = {0.5f,0.02f,0.5f};
        gain.process(x,1,1); assertEquals(0.5f,x[0],0f); assertEquals(0.5f,x[2],0f);
    }
    @Test(expected=IllegalArgumentException.class) public void invalidPcmRangeRejected() {
        new CaptureGain().process(new float[3],2,2);
    }
}
