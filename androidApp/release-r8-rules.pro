# Avoid a VerifyError on launch caused by invalid R8-optimized bytecode.
# Continue shrinking and obfuscating; re-enable optimization after addressing the compiler issue.
-dontoptimize
