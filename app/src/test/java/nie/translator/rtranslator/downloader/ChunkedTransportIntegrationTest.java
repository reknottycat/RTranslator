package nie.translator.rtranslator.downloader;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.BitSet;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Actual Range transport, disk journal and SHA-256 integration (no substitute engine). */
public class ChunkedTransportIntegrationTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private MockWebServer server;
    private byte[] bytes;
    private final ConcurrentHashMap<String, AtomicInteger> counts = new ConcurrentHashMap<>();
    private ModelDownloadManifest.Entry metadata;
    private String canonical = "https://github.com/example/models/releases/download/v1/model.bin";
    @Before public void setup() throws Exception {
        bytes = new byte[ChunkedModelDownloader.CHUNK_BYTES * 2 + 257];
        new Random(42).nextBytes(bytes);
        metadata = new ModelDownloadManifest.Entry(bytes.length, hash(bytes));
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                String range = request.getHeader("Range");
                counts.computeIfAbsent(request.getPath()+":"+range, k -> new AtomicInteger()).incrementAndGet();
                if (request.getPath().equals("/html")) return new MockResponse().setResponseCode(200).setHeader("Content-Type","text/html").setBody("not a model");
                String[] endpoints = range.substring(6).split("-");
                int start=Integer.parseInt(endpoints[0]), end=Integer.parseInt(endpoints[1]);
                if (request.getPath().equals("/wrong")) return new MockResponse().setResponseCode(206).setHeader("Content-Range","bytes 0-3/4").setBody("oops");
                return new MockResponse().setResponseCode(206).setHeader("Content-Type","application/octet-stream")
                        .setHeader("Content-Range","bytes "+start+"-"+end+"/"+bytes.length)
                        .setChunkedBody(new Buffer().write(bytes,start,end-start+1),65536);
            }
        });
        server.start();
    }
    @After public void shutdown() throws Exception { server.shutdown(); }
    private ChunkedModelDownloader engine(String... paths) {
        java.util.ArrayList<String> urls=new java.util.ArrayList<>();
        for(String path:paths) urls.add(server.url(path).toString());
        return new ChunkedModelDownloader(url -> urls,true);
    }
    private static String hash(byte[] b) throws Exception {
        StringBuilder out=new StringBuilder();
        for(byte x:MessageDigest.getInstance("SHA-256").digest(b)) out.append(String.format("%02x",x&255));
        return out.toString();
    }
    @Test public void parallelChunksAssembleExactFileAndRejectHtmlFirstSource() throws Exception {
        File target=new File(temp.getRoot(),"model.bin");
        engine("/html","/good").download(canonical,target,metadata,14,(done,total)->{});
        assertArrayEquals(bytes,Files.readAllBytes(target.toPath()));
        assertTrue(counts.keySet().stream().anyMatch(k->k.startsWith("/html:")));
        assertTrue(counts.keySet().stream().anyMatch(k->k.startsWith("/good:")));
        assertFalse(new File(temp.getRoot(),"model.bin.chunks.done").exists());
    }
    @Test public void preferredWorkingSourceDoesNotSendTrafficToFallback() throws Exception {
        File target=new File(temp.getRoot(),"model.bin");
        engine("/good","/unused").download(canonical,target,metadata,28,(done,total)->{});
        assertArrayEquals(bytes,Files.readAllBytes(target.toPath()));
        assertFalse(counts.keySet().stream().anyMatch(k->k.startsWith("/unused:")));
    }
    @Test public void rejectsWrongContentRangeWithoutPublishing() throws Exception {
        File target=new File(temp.getRoot(),"model.bin");
        try { engine("/wrong").download(canonical,target,metadata,2,(done,total)->{}); fail("wrong ranges accepted"); }
        catch(IOException expected) { assertFalse(target.exists()); }
    }
    @Test public void pauseAndResumeKeepsCompletedChunks() throws Exception {
        File target=new File(temp.getRoot(),"model.bin");
        try {
            engine("/good").download(canonical,target,metadata,1,(done,total)->{
                if(done==ChunkedModelDownloader.CHUNK_BYTES) Thread.currentThread().interrupt();
            });
            fail("pause ignored");
        } catch(IOException expected) { assertFalse(target.exists()); }
        assertTrue(new File(temp.getRoot(),"model.bin.chunks.done").isFile());
        String first="/good:bytes=0-"+(ChunkedModelDownloader.CHUNK_BYTES-1);
        assertEquals(1,counts.get(first).get());
        engine("/good").download(canonical,target,metadata,14,(done,total)->{});
        assertArrayEquals(bytes,Files.readAllBytes(target.toPath()));
        assertEquals("Completed chunk downloaded again",1,counts.get(first).get());
    }
    @Test public void corruptedWholeFileHashIsNeverPublished() throws Exception {
        File target=new File(temp.getRoot(),"model.bin");
        ModelDownloadManifest.Entry wrong=new ModelDownloadManifest.Entry(bytes.length,hash(new byte[]{1}));
        try { engine("/good").download(canonical,target,wrong,14,(done,total)->{}); fail("bad hash accepted"); }
        catch(IOException expected) { assertTrue(expected.getMessage().contains("SHA-256")); }
        assertFalse(target.exists());
    }
    @Test public void incompleteJournalRecordIsNotAccepted() throws Exception {
        File part=new File(temp.getRoot(),"model.bin.cnchunks.part");
        File journal=new File(temp.getRoot(),"model.bin.chunks.done");
        Files.write(part.toPath(),bytes);
        String h="rtranslator-cn-chunks-v1 "+bytes.length+" "+ChunkedModelDownloader.CHUNK_BYTES+" "+Integer.toHexString(canonical.hashCode())+" "+metadata.sha256;
        Files.write(journal.toPath(),(h+"\n0\n1").getBytes(StandardCharsets.UTF_8));
        Method method=ChunkedModelDownloader.class.getDeclaredMethod("readCheckpoint",File.class,File.class,String.class,ModelDownloadManifest.Entry.class,int.class);
        method.setAccessible(true);
        BitSet done=(BitSet)method.invoke(engine("/good"),journal,part,canonical,metadata,3);
        assertTrue(done.get(0)); assertFalse(done.get(1));
        assertEquals(h+"\n0\n", new String(Files.readAllBytes(journal.toPath()),StandardCharsets.UTF_8));
    }
    @Test public void truncatedStagingInvalidatesOldJournal() throws Exception {
        File part=new File(temp.getRoot(),"model.bin.cnchunks.part");
        File journal=new File(temp.getRoot(),"model.bin.chunks.done");
        Files.write(part.toPath(),new byte[]{0});
        Files.write(journal.toPath(),"stale\n0\n1\n".getBytes(StandardCharsets.UTF_8));
        Method method=ChunkedModelDownloader.class.getDeclaredMethod("readCheckpoint",File.class,File.class,String.class,ModelDownloadManifest.Entry.class,int.class);
        method.setAccessible(true);
        BitSet done=(BitSet)method.invoke(engine("/good"),journal,part,canonical,metadata,3);
        assertTrue(done.isEmpty()); assertFalse(journal.exists());
    }
    @Test public void modelScopeMappingPinsEveryWhisperAsset() {
        long count=ModelDownloadManifest.entries().entrySet().stream()
                .filter(e->e.getKey().contains("/Whisper_")).peek(e->{
                    assertNotNull(e.getValue().sha256);
                    assertEquals(64,e.getValue().sha256.length());
                    assertTrue(DownloadSourceOrder.candidates(e.getKey()).get(0).startsWith("https://www.modelscope.cn/"));
                }).count();
        assertEquals(6,count);
    }
}
