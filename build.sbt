import Testing.itTest
import com.typesafe.sbt.packager.docker.{Cmd, ExecCmd}

// properties
val APP_VERSION = "0.4.0-SNAPSHOT"
val SCALA_VERSION = "2.12.4"
val AKKA_VERSION = "2.5.13"
val JSON_VERSION = "2.6.9"


// settings
name := "scala-broker"
organization := "org.iot-dsa"
version := APP_VERSION
scalaVersion := SCALA_VERSION

// base play-akka project
lazy val root = (project in file("."))
  .enablePlugins(PlayScala, JavaAgent)
  .settings(
    scalaVersion := SCALA_VERSION,
    libraryDependencies ++= commonDependencies.union(playTestDependencies)
  )
  .aggregate(msgpack)
  .dependsOn(msgpack)

javaAgents += "org.aspectj" % "aspectjweaver" % "1.8.13" // (2)
javaOptions in Universal += "-Dorg.aspectj.tracing.factory=default" // (3)

// project for temporary lib msgpack4s
lazy val msgpack = project.in(file("tools/msgpack4s"))
    .settings(
      scalaVersion := SCALA_VERSION,
      libraryDependencies ++= msgpackDependencies
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
resolvers += "velvia maven" at "http://dl.bintray.com/velvia/maven"
resolvers += Resolver.bintrayRepo("tanukkii007", "maven")

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

dockerEntrypoint ++= Seq(
  """-Dakka.remote.netty.tcp.hostname="$(eval "echo $AKKA_REMOTING_BIND_HOST")"""",
  """-Dakka.remote.netty.tcp.port="$AKKA_REMOTING_BIND_PORT"""",
  """$(IFS=','; I=0; for NODE in $AKKA_SEED_NODES; do echo "-Dakka.cluster.seed-nodes.$I=akka.tcp://$AKKA_ACTOR_SYSTEM_NAME@$NODE"; I=$(expr $I + 1); done)""",
  """-Dkamon.statsd.hostname="$STATSD_HOST"""",
  """-Dkamon.statsd.port=$STATSD_PORT""",
  """-Dkamon.zipkin.host="$ZIPKIN_HOST"""",
  """-Dkamon.zipkin.port=$ZIPKIN_PORT""",
  """-Dconfig.file="$CONF_FILE""""
)

dockerCommands :=
  dockerCommands.value.flatMap {
    case ExecCmd("ENTRYPOINT", args @ _*) => Seq(Cmd("ENTRYPOINT", args.mkString(" ")))
    case v => Seq(v)
  }


mappings in Universal ++= Seq(
  file("scripts/start-broker") -> "bin/start-broker",
  file("scripts/stop-broker") -> "bin/stop-broker",
  file("scripts/start-backend") -> "bin/start-backend",
  file("scripts/stop-backend") -> "bin/stop-backend",
  file("scripts/start-frontend") -> "bin/start-frontend",
  file("scripts/stop-frontend") -> "bin/stop-frontend")

// scoverage options
coverageMinimum := 70
coverageFailOnMinimum := true

coverageExcludedPackages := "controllers.javascript.*;facades.websocket.javascript.*;router.*;views.html.*"

sonarProperties ++= Map(
  "sonar.projectName" -> "iot-dsa",
  "sonar.projectKey" -> "iot-dsa",
  "sonar.projectVersion" -> APP_VERSION,
  "sonar.sourceEncoding" -> "UTF-8",
  "sonar.modules" -> "root, msgpack",

  "root.sonar.binaries" -> "target/scala-2.12/classes",
  "root.sonar.sources" -> "app",
  "root.sonar.tests" -> "test",
  "root.sonar.scoverage.reportPath" -> "target/scala-2.12/scoverage-report/scoverage.xml",
  "root.sonar.scapegoat.reportPath" -> "target/scala-2.12/scapegoat-report/scapegoat.xml",

  "msgpack.sonar.binaries" -> "tools/msgpack4s/target/scala-2.12/classes",
  "msgpack.sonar.sources" -> "tools/msgpack4s/src/main/scala",
  "msgpack.sonar.tests" -> "tools/msgpack4s/src/test/scala",
  "msgpack.sonar.scoverage.reportPath" -> "tools/msgpack4s/target/scala-2.12/scoverage-report/scoverage.xml",
  "msgpack.sonar.scapegoat.reportPath" -> "tools/msgpack4s/target/scala-2.12/scapegoat-report/scapegoat.xml"
)

coverageFailOnMinimum := true

// dependencies for scala-broker application
lazy val commonDependencies = Seq(
  guice,
  ehcache,
  jdbc,
  ws,
  "com.typesafe.play"           %% "play-json"                   % JSON_VERSION,
  "com.typesafe.play"           %% "play-json-joda"              % JSON_VERSION,
  "com.typesafe.akka"           %% "akka-cluster"                % AKKA_VERSION,
  "com.typesafe.akka"           %% "akka-cluster-metrics"        % AKKA_VERSION,
  "com.typesafe.akka"           %% "akka-cluster-tools"          % AKKA_VERSION,
  "com.typesafe.akka"           %% "akka-cluster-sharding"       % AKKA_VERSION,
  "com.typesafe.akka"           %% "akka-slf4j"                  % AKKA_VERSION,
  "com.typesafe.akka"           %% "akka-persistence"            % AKKA_VERSION,
  "org.fusesource.leveldbjni"    % "leveldbjni-all"              % "1.8",
  "com.paulgoldbaum"            %% "scala-influxdb-client"       % "0.5.2",
  "org.bouncycastle"             % "bcprov-jdk15on"              % "1.51",
  "com.github.romix.akka"       %% "akka-kryo-serialization"     % "0.5.1",
  "com.h2database"               % "h2"                          % "1.4.193",
  "com.typesafe.play"           %% "anorm"                       % "2.5.3",
  "com.maxmind.geoip2"           % "geoip2"                      % "2.10.0",
  "ch.qos.logback"               % "logback-classic"             % "1.2.3",
  "io.netty"                     % "netty-codec-http"            % "4.0.41.Final" force(),
  "io.netty"                     % "netty-handler"               % "4.0.41.Final" force(),
  "org.msgpack"                 %% "msgpack-scala"               % "0.8.13",
  "org.json4s"                  %% "json4s-native"               % "3.5.0",
  "io.kamon"                    %% "kamon-akka-remote-2.5"       % "1.0.0",
  "io.kamon"                    %% "kamon-akka-2.5"              % "1.0.0",
  "io.kamon"                    %% "kamon-statsd"                % "1.0.0",
  "io.kamon"                    %% "kamon-system-metrics"        % "1.0.0",
  "io.kamon"                    %% "kamon-core"                  % "1.0.0",
  "io.kamon"                    %% "kamon-zipkin"                % "1.0.0",
  "com.github.TanUkkii007"      %% "akka-cluster-custom-downing" % "0.0.12"
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
  "org.mockito"              % "mockito-core"            % "2.13.0"        % "test",
  "com.typesafe.akka"       %% "akka-testkit"            % AKKA_VERSION    % "test",
  "com.typesafe.akka"       %% "akka-multi-node-testkit" % AKKA_VERSION    % "test"
)

// dependencies for it module
lazy val itDependencies = Seq(
  "com.whisk" %% "docker-testkit-scalatest" % "0.9.5" % "test",
  "com.whisk" %% "docker-testkit-impl-spotify" % "0.9.5" % "test",
  "com.spotify" % "docker-client" % "8.10.0" % "test",
  "org.iot-dsa" % "dslink" % "0.18.3" % "test",
  "io.projectreactor" % "reactor-core" % "3.1.6.RELEASE" % "test",
  "io.projectreactor" %% "reactor-scala-extensions" % "0.3.4" % "test"
)

lazy val msgpackDependencies = Seq(
  "org.scalatest" %% "scalatest" % "3.0.4" % "test",
  "org.mockito" % "mockito-all" % "1.9.0" % "test",
  "com.rojoma" %% "rojoma-json-v3" % "3.7.0",
  "org.json4s" %% "json4s-native" % "3.5.0",
  "org.apache.commons" % "commons-io" % "1.3.2",
  "com.typesafe.play" %% "play-json" % JSON_VERSION
)



