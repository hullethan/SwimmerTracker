plugins {
    kotlin("android") version "1.9.22" apply false // ✅ Downgrade to match Compose Compiler
    id("com.android.application") version "8.8.1" apply false
}


tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
