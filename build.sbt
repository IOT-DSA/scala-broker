// properties
val APP_VERSION = "0.4.0-SNAPSHOT"
val SCALA_VERSION = "2.12.4"
val AKKA_VERSION = "2.5.8"
val JSON_VERSION = "2.6.8"

// settings
name := "scala-broker"
organization := "org.iot-dsa"
version := APP_VERSION
scalaVersion := SCALA_VERSION

lazy val root = (project in file(".")).enablePlugins(PlayScala)

// eclipse
EclipseKeys.preTasks := Seq(compile in Compile, compile in Test)
EclipseKeys.withSource := true
EclipseKeys.withJavadoc := true

// building
resolvers += Resolver.bintrayRepo("cakesolutions", "maven")
scalacOptions ++= Seq(
  "-feature", 
  "-unchecked", 
  "-deprecation", 
  "-Yno-adapted-args", 
  "-Ywarn-dead-code", 
  "-language:_", 
  "-target:jvm-1.8", 
  "-encoding", "UTF-8", 
  "-Xexperimental")

// packaging
enablePlugins(DockerPlugin, JavaAppPackaging)
dockerBaseImage := "java:latest"
maintainer := "Vlad Orzhekhovskiy <vlad@uralian.com>"
packageName in Docker := "iotdsa/broker-scala"
dockerExposedPorts := Seq(9000, 9443, 2551)
dockerExposedVolumes := Seq("/opt/docker/conf", "/opt/docker/logs")

mappings in Universal ++= Seq(
  file("scripts/setup-influx") -> "bin/setup-influx",
  file("scripts/start-broker") -> "bin/start-broker",
  file("scripts/stop-broker") -> "bin/stop-broker",
  file("scripts/start-backend") -> "bin/start-backend",
  file("scripts/stop-backend") -> "bin/stop-backend",
  file("scripts/start-frontend") -> "bin/start-frontend",
  file("scripts/stop-frontend") -> "bin/stop-frontend")

// scoverage options
coverageMinimum := 80
coverageFailOnMinimum := true

// dependencies
libraryDependencies ++= Seq(
  guice,
  ehcache,
  jdbc,
  "com.typesafe.play"       %% "play-json"               % JSON_VERSION,
  "com.typesafe.play"       %% "play-json-joda"          % JSON_VERSION,
  "com.typesafe.akka"       %% "akka-cluster"            % AKKA_VERSION,
  "com.typesafe.akka"       %% "akka-cluster-metrics"    % AKKA_VERSION,
  "com.typesafe.akka"       %% "akka-cluster-tools"      % AKKA_VERSION,
  "com.typesafe.akka"       %% "akka-cluster-sharding"   % AKKA_VERSION,
  "com.typesafe.akka"       %% "akka-slf4j"              % AKKA_VERSION,
  "com.paulgoldbaum"        %% "scala-influxdb-client"   % "0.5.2",
  "com.h2database"           % "h2"                      % "1.4.193",
  "com.typesafe.play"       %% "anorm"                   % "2.5.3",
  "com.maxmind.geoip2"       % "geoip2"                  % "2.10.0",
  "ch.qos.logback"           % "logback-classic"         % "1.2.3",
  "org.scalatest"           %% "scalatest"               % "3.0.4"         % "test",
  "org.scalacheck"          %% "scalacheck"              % "1.13.5"        % "test",
  "org.scalatestplus.play"  %% "scalatestplus-play"      % "3.1.2"         % "test",
  "org.mockito"              % "mockito-core"            % "2.13.0"        % "test",
  "com.typesafe.akka"       %% "akka-testkit"            % AKKA_VERSION    % "test"
)
