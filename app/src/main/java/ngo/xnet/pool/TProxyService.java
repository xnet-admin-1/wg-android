package ngo.xnet.pool;

public class TProxyService {
    static { System.loadLibrary("hev-socks5-tunnel"); }
    public static native void TProxyStartService(String config_path, int fd);
    public static native void TProxyStopService();
    public static native long[] TProxyGetStats();
}
