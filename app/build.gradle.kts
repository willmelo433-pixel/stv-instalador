plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
 namespace = "com.stv.instalador"
 compileSdk = 34
 defaultConfig {
  applicationId = "com.stv.instalador"
  minSdk = 21
  targetSdk = 34
  versionCode = 5
  versionName = "4.0.0"
  buildConfigField("String", "PANEL_URL", "\"https://stv-installer-hub.lovable.app\"")
 }
 signingConfigs {
  create("stv") {
   storeFile = file("stv.keystore")
   storePassword = "stvinstalador"
   keyAlias = "stv"
   keyPassword = "stvinstalador"
  }
 }
 buildFeatures { buildConfig = true }
 buildTypes { release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("stv") } }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation("androidx.core:core-ktx:1.13.1") }
