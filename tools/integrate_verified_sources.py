"""Assertion-guarded one-time integration; source is committed BEFORE building the APK."""
from pathlib import Path
import json
root=Path('app/src/main/java/nie/translator/rtranslator')
marker=Path('docs/MODELSCOPE_INTEGRATION.md')
if marker.exists():
    print('Integration already committed. Building the checked-in sources.')
    raise SystemExit(0)
def edit(path,old,new):
    text=path.read_text()
    if old not in text: raise RuntimeError('Source changed: '+str(path)+' :: '+repr(old[:100]))
    path.write_text(text.replace(old,new,1))
p=root/'downloader/ModelDownloadManifest.java'
edit(p,'ENTRIES.put(url, new Entry(size, sha256));',
     'String pinned = sha256 == null ? VerifiedModelMirrors.digestForUrl(url) : sha256;\n        ENTRIES.put(url, new Entry(size, pinned));')
p=Path('app/build.gradle')
edit(p,"versionNameSuffix '-cn.2'", "versionNameSuffix '-cn.3-modelscope'")
edit(p,'            versionCode 29','            versionCode 31')
p=root/'downloader/ChunkedModelDownloader.java'
edit(p,'    private final Object checkpointLock = new Object();','''    private final Object checkpointLock = new Object();
    private static final java.util.concurrent.Semaphore CONNECTIONS = new java.util.concurrent.Semaphore(28, true);
    interface SourceProvider { List<String> candidates(String canonical); }
    private final SourceProvider sourceProvider;
    private final boolean loopbackTest;
    public ChunkedModelDownloader() { this(DownloadSourceOrder::candidates, false); }
    // Test-only injection permits HTTP exclusively on loopback. Production requires HTTPS.
    ChunkedModelDownloader(SourceProvider provider, boolean loopbackTest) {
        this.sourceProvider = provider;
        this.loopbackTest = loopbackTest;
    }''')
edit(p,'final List<String> sources = DownloadSourceOrder.candidates(canonicalUrl);',
     'final List<String> sources = sourceProvider.candidates(canonicalUrl);')
edit(p,'                        if (completed.get(chunkIndex)) continue;',
     '                        synchronized (checkpointLock) { if (completed.get(chunkIndex)) continue; }')
edit(p,'''        if (!checkpoint.exists() || !staging.exists()
                || staging.length() != metadata.sizeBytes) return completed;
        String[] lines = new String(Files.readAllBytes(checkpoint.toPath()),
                java.nio.charset.StandardCharsets.UTF_8).split("\\\\n");''','''        if (!checkpoint.exists() || !staging.exists() || staging.length() != metadata.sizeBytes) {
            // A journal cannot describe a missing/truncated staging file.
            Files.deleteIfExists(checkpoint.toPath());
            return completed;
        }
        byte[] journalBytes = Files.readAllBytes(checkpoint.toPath());
        String journalText = new String(journalBytes, java.nio.charset.StandardCharsets.UTF_8);
        if (!journalText.endsWith("\\n")) {
            // A partial numeric record (e.g. "12" cut to "1") is not a completed chunk.
            // Remove it before future appends, otherwise records could concatenate.
            int end = journalText.lastIndexOf('\\n') + 1;
            journalText = journalText.substring(0, end);
            try (RandomAccessFile journal = new RandomAccessFile(checkpoint, "rw")) {
                journal.setLength(end); // Journal format is strictly ASCII.
                journal.getFD().sync();
            }
        }
        String[] lines = journalText.split("\\\\n");''')
edit(p,'    private static void getChunk(', '    private void getChunk(')
edit(p,'''            // Spread workers across candidates and rotate on errors.
            int firstPool = DownloadSourceOrder.get() == DownloadSourceOrder.OFFICIAL_FIRST
                    ? 1 : Math.min(sources.size(), 3);
            int endpointIndex = attempt == 0 ? chunkIndex % firstPool
                    : (chunkIndex + attempt) % sources.size();
            String candidate = sources.get(endpointIndex);''','''            // Every chunk respects ModelScope-first; rotate only after a real failure.
            String candidate = sources.get(attempt % sources.size());''')
edit(p,'''                fetchRange(candidate, staging, start, end, total);''','''                boolean acquired = false;
                try {
                    CONNECTIONS.acquire();
                    acquired = true;
                    fetchRange(candidate, staging, start, end, total);
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Connection wait interrupted");
                } finally {
                    if (acquired) CONNECTIONS.release();
                }''')
edit(p,'if (attempt < MAX_TRIES_PER_CHUNK - 1) {',
     'if (attempt < MAX_TRIES_PER_CHUNK - 1 && !loopbackTest) {')
edit(p,'    private static void fetchRange(', '    private void fetchRange(')
edit(p,'''            if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getUserInfo() != null)
                throw new IOException("Unsafe redirect target");''','''            boolean localTest = loopbackTest && "http".equals(url.getProtocol())
                    && ("localhost".equals(url.getHost()) || "127.0.0.1".equals(url.getHost()));
            if ((!"https".equalsIgnoreCase(url.getProtocol()) && !localTest) || url.getUserInfo() != null)
                throw new IOException("Unsafe redirect target");''')
edit(p,'                    long left = end - start + 1;',
     '                    long left = end - start + 1;\n                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);')
edit(p,'''                    while (left > 0) {
                        checkInterrupted();''','''                    while (left > 0) {
                        checkInterrupted();
                        if (System.nanoTime() > deadline) throw new IOException("Chunk stalled for 90 seconds");''')
edit(p,'        DownloadTrace.transferred(completedBytes.get(), entry.sizeBytes);',
     '''        DownloadTrace.trying(DownloadSourceOrder.label(sources.get(0)), destination.getName(),
                1, sources.size(), completedBytes.get(), entry.sizeBytes);
        DownloadTrace.transferred(completedBytes.get(), entry.sizeBytes);''')
p=root/'access/DownloadFragment.java'
text=p.read_text().replace('国内镜像优先（推荐）','ModelScope 国内优先（推荐）')
text=text.replace('DownloadTrace.status() + "\\\\n" + DownloadTrace.recentLines()',
                  'DownloadTrace.status() + System.lineSeparator() + DownloadTrace.recentLines()')
p.write_text(text)
for name in ['ParallelChunkStream','SegmentedModelStream']:
    assert not any(name in p.read_text() for p in root.rglob('*.java')), name+' still referenced'
p=Path('app/src/test/java/nie/translator/rtranslator/downloader/FallbackHttpClientTest.java')
old='assertNull(ModelDownloadManifest.forUrl(\n                "https://github.com/niedev/RTranslator/releases/download/2.0.0/Whisper_initializer.onnx").sha256);'
edit(p,old,old.replace('assertNull','assertNotNull'))
p=Path('app/src/test/java/nie/translator/rtranslator/downloader/PickedModelStageTest.java')
text=p.read_text().replace('startsWith("https://gh-proxy.org/")','startsWith("https://www.modelscope.cn/")').replace('startsWith("https://ghfast.top/")','startsWith("https://www.modelscope.cn/")')
p.write_text(text)
report=json.loads(Path('reports/verified-modelscope.json').read_text())
verified=report['verified']; whisper=[x for x in verified if x['name'].startswith('Whisper_')]
assert len(whisper)==6 and all(x.get('whole_file_check') for x in whisper)
marker.write_text('# ModelScope-first CN test build\n\n'
+f"Verified exact mirrors: {len(verified)}. All six Whisper files were fully downloaded and hashed against official GitHub assets.\n\n"
+'Other mapped archives matched the upstream SHA-256 and exact length in ModelScope metadata and passed a nonzero HTTP byte-range check. The app checks every completed download against a pinned whole-file SHA-256 before use. These are community mirrors, not upstream-endorsed model repositories.\n\n'
+'Priority: verified ModelScope URL, gh-proxy.org, ghproxy.imciel.com, gh-proxy.com, official GitHub. Official-first and mirrors-only remain optional. Failed domains from the user report are excluded.\n\n'
+'4 MiB chunks, 14 default / 28 optional workers, 8 attempts per chunk, durable chunks.done, SHA-256 before atomic publish. Total concurrent chunk HTTP connections across model groups are capped at 28. Small files retain the tested PRDownloader transport.\n\n'
+'A system file picker and live source/error logs are on the setup screen. Model files stay in their original form and filenames; ZIPs must not be manually extracted. No device-specific speed or inference acceptance is claimed.\n\n'
+'Unmatched manifest assets: '+', '.join(report['unmatched_official_files'])+'. Those use proxy fallback, never a guessed mirror URL.\n\n'
+'Evidence: reports/verified-modelscope.json and audit runs 42647267376 / 42647994639. Measured on Azure eastus, not a mainland Android device.\n\n'
+'Debug-signed ARM64 test APK. Fresh CI debug keys may prevent in-place upgrades. Back up app data and models before uninstalling an old build.\n')
print('INTEGRATION_SUMMARY',json.dumps({'verified':len(verified),'unmatched':report['unmatched_official_files'],'whisper_full_hash_checks':len(whisper)}))
