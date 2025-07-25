ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.3.6"

lazy val core = (project in file("src/core"))
  .settings(
    name := "RiskCore",
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % "3.2.17" % Test,
      "com.typesafe.play" %% "play-json" % "2.10.0-RC5",
      "it.unibo.alice.tuprolog" % "tuprolog" % "3.3.0"
    )
  )

import sbtassembly.AssemblyPlugin.autoImport._

lazy val client = (project in file("src/client"))
  .settings(
    name := "RiskClient",
    libraryDependencies ++= Seq(
      "org.scalafx" %% "scalafx" % "20.0.0-R31",
      "org.openjfx" % "javafx-controls" % "20.0.2",
      "org.openjfx" % "javafx-fxml" % "20.0.2",
      "org.openjfx" % "javafx-web" % "20.0.2",
      "org.openjfx" % "javafx-swing" % "20.0.2",
      "com.typesafe.akka" %% "akka-actor-typed" % "2.8.3",
      "com.typesafe.akka" %% "akka-stream-typed" % "2.8.3",
      "io.spray" %% "spray-json" % "1.3.6",
      "com.typesafe.akka" %% "akka-http" % "10.5.3",
      "org.scalatest" %% "scalatest" % "3.2.17" % Test
    ),
    mainClass := Some("client.ui.ClientUI"),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "substitute", "config", "reflectionconfig.json") => MergeStrategy.first
      case PathList("META-INF", xs @ _*) => MergeStrategy.discard
      case "reference.conf" => MergeStrategy.concat
      case PathList("json", xs @ _*) => MergeStrategy.first
      case PathList("images", xs @ _*) => MergeStrategy.first 
      case x => MergeStrategy.first
    }
  )
  .dependsOn(core)
  .enablePlugins(AssemblyPlugin)

lazy val bot = (project in file("src/bot"))
  .settings(
    name := "RiskBot",
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % "3.2.17" % Test
    )
  ).dependsOn(core)

lazy val server = (project in file("src/server"))
  .settings(
    name := "RiskServer",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor-typed" % "2.8.3",
      "com.typesafe.akka" %% "akka-stream-typed" % "2.8.3",
      "com.typesafe.akka" %% "akka-http" % "10.5.3",
      "io.spray" %% "spray-json" % "1.3.6",
      "ch.qos.logback" % "logback-classic" % "1.4.5",
      "org.scalatest" %% "scalatest" % "3.2.17" % Test
    ),
    mainClass := Some("server.RisikoServer"),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", xs @ _*) => MergeStrategy.discard
      case "reference.conf" => MergeStrategy.concat
      case "application.conf" => MergeStrategy.concat
      case x => MergeStrategy.first
    }
  ).dependsOn(core, bot, client)
  .enablePlugins(AssemblyPlugin)

lazy val root = (project in file("."))
  .settings(
    name := "Risk"
  ).aggregate(core, client, server, bot)
