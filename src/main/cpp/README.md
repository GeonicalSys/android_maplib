# QuickJS engine

`quickjs/` is an unmodified subset of the official MIT-licensed QuickJS
2026-06-04 source archive: https://bellard.org/quickjs/quickjs-2026-06-04.tar.xz

Archive SHA-256: `b376e839b322978313d929fd20663b11ba58b75df5a46c126dd19ea2fa70ad2a`.
The license is in `quickjs/LICENSE`. Only the interpreter core is linked;
quickjs-libc, command-line tools, standard OS/file bindings, module loaders
and bytecode loading are not exposed. JNI exchanges UTF-8 byte arrays, avoiding
JNI modified-UTF-8 corruption of Cyrillic text and supplementary characters.

Updates require source checksum review and native sandbox/Unicode/timeout tests.
Keep the vendored sources unchanged; implement the host boundary in
`script_engine.c` and the Java project-script classes.
