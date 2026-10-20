package com.agent;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Second Android 9 diagnostic: bind the listener while still uid 0, then drop to
 * uid 1000 and see whether accept()/write still work. If this passes, the daemon can
 * keep TCP ports without needing a unix-socket redesign.
 *
 * Usage: app_process /system/bin com.agent.NetProbe2 <port> <uid>
 */
public class NetProbe2 {
    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 3071;
        int targetUid = args.length > 1 ? Integer.parseInt(args[1]) : 1000;
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress("127.0.0.1", port));
            System.out.println("[NetProbe2] bound as uid=" + readUid() + " port=" + port);
            Class<?> procCls = Class.forName("android.os.Process");
            procCls.getMethod("setGid", int.class).invoke(null, targetUid);
            procCls.getMethod("setUid", int.class).invoke(null, targetUid);
            System.out.println("[NetProbe2] dropped to uid=" + readUid() + ", waiting accept");
            ss.setSoTimeout(8000);
            Socket s = ss.accept();
            System.out.println("[NetProbe2] ACCEPT OK from " + s.getRemoteSocketAddress());
            s.getOutputStream().write("hello\n".getBytes("UTF-8"));
            s.getOutputStream().flush();
            System.out.println("[NetProbe2] WRITE OK");
            s.close();
            ss.close();
        } catch (Throwable t) {
            System.out.println("[NetProbe2] FAIL " + t);
        }
        System.out.flush();
        Runtime.getRuntime().halt(0);
    }

    private static String readUid() {
        try {
            Class<?> procCls = Class.forName("android.os.Process");
            return String.valueOf(procCls.getMethod("myUid").invoke(null));
        } catch (Throwable t) {
            return "?";
        }
    }
}
