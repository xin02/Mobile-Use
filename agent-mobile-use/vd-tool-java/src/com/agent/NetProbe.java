package com.agent;

import java.net.InetSocketAddress;
import java.net.ServerSocket;

/**
 * Diagnostic helper for the Android 9 port: reports whether this process can
 * still create/listen on TCP and unix-domain sockets after dropping to uid 1000.
 *
 * Usage: app_process /system/bin com.agent.NetProbe <uid>
 */
public class NetProbe {
    public static void main(String[] args) {
        int targetUid = args.length > 0 ? Integer.parseInt(args[0]) : 1000;
        System.out.println("[NetProbe] before uid=" + readUid() + " selinux=" + selinux());
        try {
            if (targetUid > 0) {
                Class<?> procCls = Class.forName("android.os.Process");
                procCls.getMethod("setGid", int.class).invoke(null, targetUid);
                procCls.getMethod("setUid", int.class).invoke(null, targetUid);
            }
        } catch (Throwable t) {
            System.out.println("[NetProbe] uid drop failed: " + t);
        }
        System.out.println("[NetProbe] after uid=" + readUid() + " selinux=" + selinux());

        for (int port : new int[] { 3071, 3072, 40000 }) {
            ServerSocket ss = null;
            try {
                ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress("127.0.0.1", port));
                System.out.println("[NetProbe] tcp bind 127.0.0.1:" + port + " OK");
            } catch (Throwable t) {
                System.out.println("[NetProbe] tcp bind 127.0.0.1:" + port + " FAIL " + t);
            } finally {
                try { if (ss != null) ss.close(); } catch (Throwable ignored) {}
            }
        }

        try {
            Class<?> lssCls = Class.forName("android.net.LocalServerSocket");
            Object lss = lssCls.getConstructor(String.class).newInstance("agent_net_probe");
            System.out.println("[NetProbe] unix socket OK " + lss);
            lssCls.getMethod("close").invoke(lss);
        } catch (Throwable t) {
            System.out.println("[NetProbe] unix socket FAIL " + t);
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

    private static String selinux() {
        try {
            byte[] buf = new byte[128];
            java.io.FileInputStream fis = new java.io.FileInputStream("/proc/self/attr/current");
            int n = fis.read(buf);
            fis.close();
            return n > 0 ? new String(buf, 0, n).trim() : "?";
        } catch (Throwable t) {
            return "?";
        }
    }
}
