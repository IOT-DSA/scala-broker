// properties
val APP_VERSION = "0.1.0-SNAPSHOT"
val SCALA_VERSION = "2.11.8"

// settings
name := "scala-broker"
organization := "org.iot-dsa"
version := APP_VERSION
scalaVersion := SCALA_VERSION
crossScalaVersions := Seq("2.10.5", SCALA_VERSION)

// building
scalacOptions ++= Seq("-feature", "-unchecked", "-deprecation", "-Xlint", "-Ywarn-dead-code", "-language:_", "-target:jvm-1.7", "-encoding", "UTF-8")
run in Compile <<= Defaults.runTask(fullClasspath in Compile, mainClass in (Compile, run), runner in (Compile, run))

// packaging
enablePlugins(JavaAppPackaging)

// scoverage options
coverageMinimum := 80
coverageFailOnMinimum := true

// dependencies
libraryDependencies ++= Seq(
  "org.scalatest"      %% "scalatest"               % "2.2.1"         % "test",
  "org.scalacheck"     %% "scalacheck"              % "1.12.1"        % "test",
  "org.mockito"         % "mockito-core"            % "1.10.19"       % "test"
)
