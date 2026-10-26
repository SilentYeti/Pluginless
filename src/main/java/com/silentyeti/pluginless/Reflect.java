package com.silentyeti.pluginless;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Reflection helpers for the claim-mod adapters: the mods are optional, so none of them is a build dependency. */
final class Reflect {
    private Reflect() {}

    /**
     * Calls a public method by name. Looks it up on a public class or interface of the target, because the
     * runtime class of an API object is usually an internal one that reflection may not enter.
     */
    static Object call(Object target, String name, Object... args) throws ReflectiveOperationException {
        var method = find(target.getClass(), name, args.length);
        if (method == null) throw new NoSuchMethodException(target.getClass().getName() + "." + name);
        return method.invoke(target, args);
    }

    private static Method find(Class<?> type, String name, int arguments) {
        if (type == null) return null;
        if (Modifier.isPublic(type.getModifiers()))
            for (var method : type.getMethods())
                if (method.getName().equals(name) && method.getParameterCount() == arguments && method.getDeclaringClass().isAssignableFrom(type)
                        && Modifier.isPublic(method.getDeclaringClass().getModifiers())) return method;
        for (var parent : type.getInterfaces()) {
            var found = find(parent, name, arguments);
            if (found != null) return found;
        }
        return find(type.getSuperclass(), name, arguments);
    }

    /** A public field of a public class, e.g. a plain data object like WarForge's {@code Faction}. */
    static Object field(Object target, String name) throws ReflectiveOperationException {
        return target.getClass().getField(name).get(target);
    }

    /** A public static method of a named class, e.g. {@code FTBChunksAPI.api()}. */
    static Object callStatic(String className, String name, Object... args) throws ReflectiveOperationException {
        var type = Class.forName(className);
        for (var method : type.getMethods())
            if (method.getName().equals(name) && method.getParameterCount() == args.length && Modifier.isStatic(method.getModifiers())) return method.invoke(null, args);
        throw new NoSuchMethodException(className + "." + name);
    }
}
