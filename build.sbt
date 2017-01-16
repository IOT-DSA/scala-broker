// properties
val APP_VERSION = "0.1.0-SNAPSHOT"
val SCALA_VERSION = "2.11.8"
val KAFKA_VERSION = "0.10.1.0"

// settings
name := "scala-broker"
organization := "org.iot-dsa"
version := APP_VERSION
scalaVersion := SCALA_VERSION
EclipseKeys.createSrc := EclipseCreateSrc.All

lazy val root = (project in file(".")).enablePlugins(PlayScala)

// building
resolvers += Resolver.bintrayRepo("cakesolutions", "maven")
scalacOptions ++= Seq("-feature", "-unchecked", "-deprecation", "-Xlint", "-Ywarn-dead-code", 
  "-language:_", "-target:jvm-1.7", "-encoding", "UTF-8", "-Xexperimental")

// packaging
enablePlugins(JavaAppPackaging)

// scoverage options
coverageMinimum := 80
coverageFailOnMinimum := true

// dependencies
libraryDependencies ++= Seq(
  cache,
  "org.apache.kafka"         % "kafka-streams"           % KAFKA_VERSION,
  "net.cakesolutions"       %% "scala-kafka-client"      % KAFKA_VERSION,
  "org.scalatest"           %% "scalatest"               % "2.2.1"         % "test",
  "org.scalacheck"          %% "scalacheck"              % "1.12.1"        % "test",
  "org.scalatestplus.play"  %% "scalatestplus-play"      % "1.5.1"         % "test",
  "org.mockito"              % "mockito-core"            % "1.10.19"       % "test",
  "com.typesafe.akka"       %% "akka-testkit"            % "2.4.12"        % "test"
)