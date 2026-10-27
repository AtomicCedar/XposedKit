package cc.meteormc.xposedkit.nativelib;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Executable;
import java.lang.reflect.Member;

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

    @NonNull
    public static native <T> T AllocObject(@NonNull Class<T> clazz);

    @Nullable
    public static native Object CallNonvirtualMethod(@NonNull Executable method, @NonNull Object instance, @NonNull Object... args);

    public static native long CreateXmlParseState(@NonNull String entries);

    @Nullable
    public static native Member FindClassInitializer(@NonNull Class<?> clazz) throws IllegalStateException;

    @Nullable
    public static native <T> T[] VisitHeapObjects(@NonNull Class<T> clazz, boolean assignable) throws IllegalArgumentException;
}