#!/bin/sh
# Компиляция и запуск симуляции ядра без Android SDK (нужен только kotlin-compiler).
cd "$(dirname "$0")"
L=${KOTLIN_LIB:-/opt/gradle/lib}
CP="$L/kotlin-compiler-embeddable-2.0.21.jar:$L/kotlin-stdlib-2.0.21.jar:$L/trove4j-1.0.20200330.jar:$L/kotlin-script-runtime-2.0.21.jar:$L/annotations-24.0.1.jar:$L/kotlin-reflect-2.0.21.jar:$L/kotlinx-coroutines-core-jvm-1.6.4.jar:$L/kotlin-daemon-embeddable-2.0.21.jar"
java -Xmx2g -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn -no-stdlib -no-reflect -cp $L/kotlin-stdlib-2.0.21.jar -d out.jar ../app/src/main/java/app/soundfield/core/*.kt CoreSimulation.kt || exit 1
java -Xmx2g -cp out.jar:$L/kotlin-stdlib-2.0.21.jar CoreSimulationKt
