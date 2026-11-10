plugins { kotlin("jvm") version "2.2.10"; application }
repositories { mavenCentral() }
dependencies { testImplementation("junit:junit:4.13.2") }
kotlin { jvmToolchain(21) }
application { mainClass.set(providers.gradleProperty("main").getOrElse("AnalyzeKt")) }
