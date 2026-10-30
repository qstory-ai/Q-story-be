package com.qstory.backend.extension.learning;

/** Isolated, opt-in switches. JVM properties override process environment for tests. */
public final class ExtensionFlags {
    private ExtensionFlags() {}
    public static String value(String key, String fallback) {
        String value = System.getProperty(key);
        if (value == null) value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
    public static boolean enabled(String key) {
        return "true".equalsIgnoreCase(value(key, "false"));
    }
    public static boolean storyPal() { return enabled("QSTORY_STORYPAL_ENABLED"); }
    public static boolean speech() { return enabled("QSTORY_CHILD_SPEECH_ENABLED"); }
    public static boolean whisper() {
        if (!speech()) return false;
        String provider = value("QSTORY_COMPANION_STT", "rtzr");
        if (!provider.equalsIgnoreCase("rtzr") && !provider.equalsIgnoreCase("whisper")) {
            throw new IllegalStateException("QSTORY_COMPANION_STT must be rtzr or whisper");
        }
        return provider.equalsIgnoreCase("whisper");
    }
}
