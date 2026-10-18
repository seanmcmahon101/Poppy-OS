plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.roborazzi) apply false
}

// This checkout lives in OneDrive, which locks files mid-build. On Windows, build outputs go to
// %LOCALAPPDATA%/foyer-build instead; the APK ends up in %LOCALAPPDATA%/foyer-build/app/outputs/apk.
System.getenv("LOCALAPPDATA")?.let { base ->
    allprojects { layout.buildDirectory.set(file("$base/foyer-build/${project.name}")) }
}
