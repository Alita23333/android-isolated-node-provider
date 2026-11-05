plugins { id("com.android.application") version "8.7.2" }
android {
    namespace = "dev.alita.providerprobe"
    compileSdk = 35
    defaultConfig { applicationId = "dev.alita.providerprobe"; minSdk = 31; targetSdk = 35; versionCode = 1; versionName = "probe1" }
    sourceSets.getByName("main").assets.srcDir("build/payload/assets")
    sourceSets.getByName("main").jniLibs.srcDir("build/payload/jniLibs")
    // Compile the exact product Process facade, not an experiment-only imitation.
    sourceSets.getByName("main").java.srcDir("build/product-source")
    packaging { jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/*.so" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
