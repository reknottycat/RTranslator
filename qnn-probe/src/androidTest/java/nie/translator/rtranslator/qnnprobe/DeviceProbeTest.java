package nie.translator.rtranslator.qnnprobe;
import android.content.Context;
import android.os.Build;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class DeviceProbeTest {
    private static void verifySha(File file, String expected) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        try (java.io.InputStream input = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[65536]; int n;
            while ((n = input.read(buffer)) != -1) digest.update(buffer,0,n);
        }
        StringBuilder hex = new StringBuilder();
        for(byte b:digest.digest()) hex.append(String.format("%02x",b&255));
        assertEquals("Wrong official pack bytes: "+file.getName(),expected,hex.toString());
    }
    @Test public void officialContextsExecuteOnQnn() throws Exception {
        Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File dir = new File(c.getExternalFilesDir(null), "whisper-pack");
        assertTrue("Import the separately verified official pack into " + dir, new File(dir,"metadata.json").isFile());
        verifySha(new File(dir,"decoder.onnx"),"f7efbd6e3006ba9800449c92666f5d0a699478cfc4b1a1803173f8d7a38e111d");
        verifySha(new File(dir,"encoder.bin"),"e4b24b7b6b5ba333926660f213836fe199160eb0e6d4e9a0d0153c6d0f0c9abf");
        verifySha(new File(dir,"metadata.json"),"90ef472b3f24ebd695a82d66f8c851b924e89f50c5dbbd827c84d31775a795af");
        verifySha(new File(dir,"decoder.bin"),"076cd7b4a5dc0c3b9958dd839d104d02e247e1ae36b7aaa7f53cf1adae891b10");
        verifySha(new File(dir,"encoder.onnx"),"fa4ba753f87f60dd59ab822558b6eb2e1813b146db89856a9e7dff4db3b71aeb");
        JSONObject meta = new JSONObject(Files.readString(new File(dir,"metadata.json").toPath()));
        assertEquals("whisper_small_quantized",meta.getString("model_id"));
        String result = NativeProbe.run(dir.getAbsolutePath(),c.getApplicationInfo().nativeLibraryDir,
                new File(c.getFilesDir(),"qnn-profile").getAbsolutePath());
        JSONObject report=new JSONObject(result);
        report.put("hardware_qnn_verified",false);
        report.put("device",Build.MODEL); report.put("soc",Build.SOC_MODEL);
        report.put("socManufacturer",Build.SOC_MANUFACTURER);
        Files.writeString(new File(c.getExternalFilesDir(null),"qnn-result.json").toPath(),report.toString(2));
        assertTrue(result,report.getBoolean("ok"));
        // Require actual QNN node execution, not merely an installed provider.
        for(String name:new String[]{"encoder","decoder"}) {
            JSONArray events = new JSONArray(Files.readString(new File(report.getJSONObject(name).getString("profile")).toPath()));
            boolean executed=false;
            for(int i=0;i<events.length();i++) {
                JSONObject args=events.getJSONObject(i).optJSONObject("args");
                if(args!=null && "QNNExecutionProvider".equals(args.optString("provider"))) executed=true;
            }
            assertTrue(name+" has no QNN execution evidence",executed);
        }
        report.put("hardware_qnn_verified",true);
        Files.writeString(new File(c.getExternalFilesDir(null),"qnn-result.json").toPath(),report.toString(2));
    }
}
