package cc.meteormc.xposedkit.nativelib;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;

public class NativeBridge {
    public static boolean isLoaded = false;
    public static UnsatisfiedLinkError error;

    static {
        try {
            System.loadLibrary("xposedkit");
            isLoaded = true;
        } catch (UnsatisfiedLinkError e) {
            error = e;
        }
    }

    public static native void Init();

    public static native void Cleanup();

    public static native <T> T AllocObject(Class<T> clazz);

    public static native Object CallNonvirtualMethod(Executable method, Object instance, Object... args);

    public static native Method FindClassInitializer(Class<?> clazz);

    public static native <T> T[] VisitHeapObjects(Class<T> clazz);
}