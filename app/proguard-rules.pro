# -------------------------------------------------------------
# 1. Strip Debug Attributes & Stack Trace Line Numbers
# -------------------------------------------------------------
# Omitting LineNumberTable, SourceFile, and LocalVariableTable
# ensures stack traces have no class line numbers or source names.
-renamesourcefileattribute ""
-keepattributes !SourceFile,!LineNumberTable,!LocalVariableTable,!LocalVariableTypeTable

# -------------------------------------------------------------
# 2. Aggressive Name Obfuscation & Repackaging
# -------------------------------------------------------------
# Flattens all obfuscated packages into a single root package name
-repackageclasses ""

# Uses maximum passes of dead-code removal and member inlining
-optimizationpasses 5
-allowaccessmodification

# Use aggressive overload/name-reuse obfuscation
-useuniqueclassmembernames
-overloadaggressively

# -------------------------------------------------------------
# 3. Strip Logging and Crash Output Calls
# -------------------------------------------------------------
# Completely removes standard android.util.Log invocations at bytecode level
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}

# Removes standard Java/Kotlin stack trace printing
-assumenosideeffects class java.lang.Throwable {
    public void printStackTrace();
}

# Removes System.out / System.err prints
-assumenosideeffects class java.lang.System {
    public static void exit(int);
}
-assumenosideeffects class java.io.PrintStream {
    public void println(...);
    public void print(...);
}

# (Optional) If you use Timber for logging, remove it entirely:
-assumenosideeffects class timber.log.Timber {
    public static void v(...);
    public static void d(...);
    public static void i(...);
    public static void w(...);
    public static void e(...);
    public static void wtf(...);
}