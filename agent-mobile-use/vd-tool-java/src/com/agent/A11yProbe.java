package com.agent;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.HandlerThread;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Android 9 diagnostic for the accessibility channel: reports whether UiAutomation
 * connects, how many windows getWindows() yields, and the exact exception when it fails.
 */
public class A11yProbe {
    public static void main(String[] args) {
        try {
            if (android.os.Looper.getMainLooper() == null) {
                android.os.Looper.prepareMainLooper();
            }
            HandlerThread ht = new HandlerThread("a11y-probe");
            ht.start();

            Class<?> uacClass = Class.forName("android.app.UiAutomationConnection");
            Object uac = uacClass.getConstructor().newInstance();
            Class<?> uiClass = Class.forName("android.app.UiAutomation");
            Class<?> iuacClass = Class.forName("android.app.IUiAutomationConnection");
            Object ui = uiClass.getConstructor(android.os.Looper.class, iuacClass)
                    .newInstance(ht.getLooper(), uac);
            try {
                uiClass.getMethod("connect", int.class).invoke(ui, 0);
            } catch (NoSuchMethodException e) {
                uiClass.getMethod("connect").invoke(ui);
            }
            System.out.println("[A11yProbe] connected");

            AccessibilityServiceInfo info = new AccessibilityServiceInfo();
            info.eventTypes = -1;
            info.feedbackType = 16;
            info.flags = 0x2 | 0x8 | 0x10 | 0x40;
            uiClass.getMethod("setServiceInfo", AccessibilityServiceInfo.class).invoke(ui, info);
            System.out.println("[A11yProbe] service info set");

            try {
                Object wins = uiClass.getMethod("getWindows").invoke(ui);
                int n = (wins instanceof List) ? ((List<?>) wins).size() : -1;
                System.out.println("[A11yProbe] getWindows size=" + n);
                if (wins instanceof List) {
                    int i = 0;
                    for (Object w : (List<?>) wins) {
                        if (i++ > 6) break;
                        Object title = null;
                        try {
                            title = w.getClass().getMethod("getTitle").invoke(w);
                        } catch (Throwable ignored) {}
                        Object root = null;
                        try {
                            root = w.getClass().getMethod("getRoot").invoke(w);
                        } catch (Throwable ignored) {}
                        System.out.println("[A11yProbe] win title=" + title + " root=" + (root != null));
                    }
                }
            } catch (Throwable t) {
                System.out.println("[A11yProbe] getWindows FAIL " + t);
                Throwable c = t.getCause();
                if (c != null) System.out.println("[A11yProbe] cause " + c);
            }

            try {
                Object displays = uiClass.getMethod("getWindowsOnAllDisplays").invoke(ui);
                System.out.println("[A11yProbe] getWindowsOnAllDisplays=" + displays);
            } catch (Throwable t) {
                System.out.println("[A11yProbe] getWindowsOnAllDisplays unavailable: " + t);
            }

            try {
                Method m = uiClass.getMethod("disconnect");
                m.invoke(ui);
            } catch (Throwable ignored) {}
            ht.quit();
        } catch (Throwable t) {
            System.out.println("[A11yProbe] FATAL " + t);
            t.printStackTrace();
        }
        System.out.flush();
        Runtime.getRuntime().halt(0);
    }
}
