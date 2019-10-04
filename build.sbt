scalaVersion := "2.12.7"

name := "styx"
organization := "de.amazon"
version := "1.5-R3"

libraryDependencies ++= Seq(
  "org.typelevel" %% "cats-effect" % "1.3.0" withSources() withJavadoc(),
  "org.typelevel" %% "cats-core" % "2.0.0-M1" withSources() withJavadoc(),
  "org.typelevel" %% "kittens" % "1.2.1" withSources() withJavadoc(),

  "com.chuusai" %% "shapeless" % "2.3.3" withSources() withJavadoc(),

  "com.47deg" %% "fetch" % "1.0.0" withSources(),

  "org.log4s" %% "log4s" % "1.7.0",
  "org.slf4j" % "slf4j-simple" % "1.7.26",

  "org.specs2" %% "specs2-core" % "4.3.4" % "test",
  "org.specs2" %% "specs2-mock" % "4.3.4" % "test",
  "org.specs2" %% "specs2-shapeless" % "4.3.4" % "test"
)

scalacOptions += "-Ypartial-unification"
scalacOptions in Test ++= Seq("-Yrangepos")

assemblyOutputPath in assembly := baseDirectory.value / "styx.jar"

