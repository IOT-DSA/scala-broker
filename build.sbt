import Testing.itTest

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

// base play-akka project
lazy val root = (project in file("."))
  .enablePlugins(PlayScala)
  .settings(
    scalaVersion := SCALA_VERSION,
    libraryDependencies ++= commonDependencies.union(playTestDependencies)
)

// project for integrational tests
lazy val it = project.in(file("it"))
  .settings(Testing.settings(Docker): _*)
  .settings(
    scalaVersion := SCALA_VERSION,
    libraryDependencies ++= testDependencies.union(itDependencies)
  ).aggregate(root)

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
dockerUpdateLatest := true

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

// dependencies for scala-broker application
lazy val commonDependencies = Seq(
  guice,
  ehcache,
  jdbc,
  ws,
  "com.typesafe.play"       %% "play-json"               % JSON_VERSION,
  "com.typesafe.play"       %% "play-json-joda"          % JSON_VERSION,
  "com.typesafe.akka"       %% "akka-cluster"            % AKKA_VERSION,
  "com.typesafe.akka"       %% "akka-cluster-metrics"    % AKKA_VERSION,
  "com.typesafe.akka"       %% "akka-cluster-tools"      % AKKA_VERSION,
  "com.typesafe.akka"       %% "akka-cluster-sharding"   % AKKA_VERSION,
  "com.typesafe.akka"       %% "akka-slf4j"              % AKKA_VERSION,
  "com.paulgoldbaum"        %% "scala-influxdb-client"   % "0.5.2",
  "org.bouncycastle"         % "bcprov-jdk15on"          % "1.51",
  "com.github.romix.akka"   %% "akka-kryo-serialization" % "0.5.1",
  "com.h2database"           % "h2"                      % "1.4.193",
  "com.typesafe.play"       %% "anorm"                   % "2.5.3",
  "com.maxmind.geoip2"       % "geoip2"                  % "2.10.0",
  "ch.qos.logback"           % "logback-classic"         % "1.2.3",
  "io.netty"                 % "netty-codec-http"        % "4.0.41.Final" force(),
  "io.netty"                 % "netty-handler"           % "4.0.41.Final" force()
)

// akka and play test dependencies
lazy val playTestDependencies = Seq(
  "org.scalatestplus.play"  %% "scalatestplus-play"      % "3.1.2"         % "test",
  "com.typesafe.akka"       %% "akka-testkit"            % AKKA_VERSION    % "test"
).union(testDependencies)

// common test dependencies
lazy val testDependencies = Seq(
  "org.scalatest"           %% "scalatest"               % "3.0.4"         % "test",
  "org.scalacheck"          %% "scalacheck"              % "1.13.5"        % "test",
  "org.mockito"              % "mockito-core"            % "2.13.0"        % "test"
)

// dependencies for it module
lazy val itDependencies = Seq(
  "com.whisk" %% "docker-testkit-scalatest" % "0.9.5" % "test",
  "com.whisk" %% "docker-testkit-impl-spotify" % "0.9.5" % "test",
  "com.spotify" % "docker-client" % "8.10.0" % "test",
  "org.iot-dsa" % "dslink" % "0.18.3" % "test",
  "io.projectreactor" % "reactor-core" % "3.1.6.RELEASE",
  "io.projectreactor" %% "reactor-scala-extensions" % "0.3.4"

)




