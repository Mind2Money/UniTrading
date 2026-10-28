# Error Prone's IncompatibleModifiers annotation is CLASS-retained compiler metadata.
# Its deprecated enum type belongs to the JDK compiler, not the Android runtime.
# Keep this rule limited to the test APK and this metadata-only type.
-dontwarn javax.lang.model.element.Modifier
