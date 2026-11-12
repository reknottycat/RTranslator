package nie.translator.rtranslator.qnnprobe;
public final class NativeProbe {
    static { System.loadLibrary("qnn_probe"); }
    private NativeProbe() {}
    public static native String run(String packDir, String nativeLibDir, String profilePrefix);
}
