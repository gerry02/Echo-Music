plugins {
  id("com.android.library")
  id("com.google.devtools.ksp")
  id("com.google.dagger.hilt.android")
  alias(libs.plugins.kotlin.serialization)
}

android {
  namespace = "com.music.echo.playback"
  compileSdk = 37
  defaultConfig { minSdk = 26 }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
  }
  flavorDimensions += "variant"
  productFlavors {
    create("gms") { dimension = "variant" }
    create("foss") { dimension = "variant" }
  }
  testOptions {
    unitTests.isReturnDefaultValues = true
  }
}

kotlin { jvmToolchain(21) }

dependencies {
  implementation(project(":core"))
  api(project(":audio-dsp"))
  api(project(":dsp-core"))
  api(project(":usbaudio"))
  "gmsImplementation"(libs.cast.framework)
  api(libs.media3)
  api(libs.media3.session)
  api(libs.media3.hls)

  implementation(libs.hilt)
  ksp(libs.hilt.compiler)

  testImplementation(libs.junit)
  // Beat This! (beat and downbeat tracking network) runs on ONNX Runtime: the app already ships it.
  implementation("com.microsoft.onnxruntime:onnxruntime-android:1.28.0")
  // On the PC (unit tests, the mix bench) the desktop build of the same API.
  testImplementation("com.microsoft.onnxruntime:onnxruntime:1.28.0")
}

// The Android build has no PC native library: tests use the desktop one instead.
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
  exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android")
}

// PC test bench for the mix engine (MixBench): -Pmix.a=<song> -Pmix.b=<song> [-Pmix.out=<wav>]
// renders the transition between two audio files to a WAV, without building the app.
tasks.withType<Test>().configureEach {
  val bench = listOf("mix.a", "mix.b", "mix.out", "mix.shift", "mix.scan", "mix.tracker", "mix.max").mapNotNull { key ->
    providers.gradleProperty(key).orNull?.let { key to it }
  }
  bench.forEach { (key, value) -> systemProperty(key, value) }
  if (bench.isNotEmpty()) outputs.upToDateWhen { false }
}
