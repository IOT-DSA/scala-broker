// properties
val APP_VERSION = "0.2.0-SNAPSHOT"
val SCALA_VERSION = "2.11.8"
val AKKA_VERSION = "2.4.12"

// settings
name := "scala-broker"
organization := "org.iot-dsa"
version := APP_VERSION
scalaVersion := SCALA_VERSION

lazy val root = (project in file(".")).enablePlugins(PlayScala)

// building
resolvers += Resolver.bintrayRepo("cakesolutions", "maven")
scalacOptions ++= Seq("-feature", "-unchecked", "-deprecation", "-Xlint:_,-missing-interpolator", 
  "-Ywarn-dead-code", "-language:_", "-target:jvm-1.7", "-encoding", "UTF-8", "-Xexperimental")

// packaging
enablePlugins(JavaAppPackaging)
javaOptions in Universal ++= Seq(s"-Dpidfile.path=/var/run/${packageName.value}/play.pid")
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
  cache,
  "com.typesafe.akka"       %% "akka-cluster"            % AKKA_VERSION,
  "com.typesafe.akka"       %% "akka-cluster-metrics"    % AKKA_VERSION,
  "com.typesafe.akka"       %% "akka-cluster-tools"      % AKKA_VERSION,
  "com.typesafe.akka"       %% "akka-cluster-sharding"   % AKKA_VERSION,
  "com.typesafe.akka"       %% "akka-slf4j"              % AKKA_VERSION,
  "com.paulgoldbaum"        %% "scala-influxdb-client"   % "0.5.2",
  "ch.qos.logback"           % "logback-classic"         % "1.1.7",
  "org.scalatest"           %% "scalatest"               % "2.2.1"         % "test",
  "org.scalacheck"          %% "scalacheck"              % "1.12.1"        % "test",
  "org.scalatestplus.play"  %% "scalatestplus-play"      % "1.5.1"         % "test",
  "org.mockito"              % "mockito-core"            % "1.10.19"       % "test",
  "com.typesafe.akka"       %% "akka-testkit"            % "2.4.12"        % "test"
)