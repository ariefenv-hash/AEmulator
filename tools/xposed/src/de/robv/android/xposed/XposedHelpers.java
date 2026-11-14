package de.robv.android.xposed;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Reflection helpers module code reaches for. Only the java.*-based surface ships here;
 * helpers whose signatures involve Android platform types come with the hook engine
 * bundle, not with this declaration layer.
 */
public final class XposedHelpers {

    private XposedHelpers() {
    }

    /** findClass by name over any loader, rethrowing lookup failures as Xposed-style errors. */
    public static Class<?> findClass(String className, ClassLoader classLoader) {
        try {
            return Class.forName(className, false, classLoader);
        } catch (ClassNotFoundException e) {
            throw new ClassNotFoundError(className, e);
        }
    }

    /** findClass, with a fallback name if the primary one is missing. */
    public static Class<?> findClassIfExists(String className, ClassLoader classLoader) {
        try {
            return Class.forName(className, false, classLoader);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /** An exact declared method by name and parameter types. */
    public static Method findMethodExact(Class<?> clazz, String methodName, Object... parameterTypes) {
        Class<?>[] types = toTypes(parameterTypes);
        try {
            Method m = clazz.getDeclaredMethod(methodName, types);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            throw new NoSuchMethodError(clazz.getName() + "#" + methodName);
        }
    }

    /** A declared field by name. */
    public static Field findField(Class<?> clazz, String fieldName) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldError(clazz.getName() + "#" + fieldName);
    }

    /** A declared field by name, or null. */
    public static Field findFieldIfExists(Class<?> clazz, String fieldName) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    /** Read an instance or static field as Object. */
    public static Object getObjectField(Object obj, String fieldName) {
        return fieldGet(obj, fieldName);
    }

    /** Write an instance or static field. */
    public static void setObjectField(Object obj, String fieldName, Object value) {
        fieldSet(obj, fieldName, value);
    }

    /** Read a boolean field. */
    public static boolean getBooleanField(Object obj, String fieldName) {
        return (Boolean) fieldGet(obj, fieldName);
    }

    /** Read an int field. */
    public static int getIntField(Object obj, String fieldName) {
        return (Integer) fieldGet(obj, fieldName);
    }

    /** Read a long field. */
    public static long getLongField(Object obj, String fieldName) {
        return (Long) fieldGet(obj, fieldName);
    }

    private static Object fieldGet(Object obj, String fieldName) {
        try {
            return getReflectionField(obj, fieldName).get(obj);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(obj.getClass().getName() + "#" + fieldName, e);
        }
    }

    private static void fieldSet(Object obj, String fieldName, Object value) {
        try {
            getReflectionField(obj, fieldName).set(obj, value);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(obj.getClass().getName() + "#" + fieldName, e);
        }
    }

    /** Call the first declared method with this name and the given argument types. */
    public static Object callMethod(Object obj, String methodName, Object... args) {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) types[i] = args[i].getClass();
        try {
            Method m = obj.getClass().getDeclaredMethod(methodName, types);
            m.setAccessible(true);
            return m.invoke(obj, args);
        } catch (ReflectiveOperationException e) {
            throw new NoSuchMethodError(obj.getClass().getName() + "#" + methodName);
        }
    }

    /** Thrown when a reachable field refuses access anyway. */
    public static final class IllegalAccessError extends Error {
        public IllegalAccessError(String where, Throwable cause) {
            super("Cannot access " + where, cause);
        }
    }

    private static Field getReflectionField(Object obj, String fieldName) {
        Field f = findField(obj.getClass(), fieldName);
        try {
            return f;
        } finally {
            f.setAccessible(true);
        }
    }

    private static Class<?>[] toTypes(Object... parameterTypes) {
        Class<?>[] types = new Class<?>[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            Object t = parameterTypes[i];
            types[i] = (t instanceof Class<?>) ? (Class<?>) t : findClass((String) t, XposedBridge.class.getClassLoader());
        }
        return types;
    }

    /** Thrown instead of a ClassNotFoundException, mirroring the original helper set. */
    public static final class ClassNotFoundError extends Error {
        public ClassNotFoundError(String className, Throwable cause) {
            super("Class not found: " + className, cause);
        }
    }
}
