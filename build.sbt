import Dependencies._

ThisBuild / scalaVersion := "2.13.1"

lazy val root = (project in file(".")).aggregate(classic, typed)

lazy val classic = (project in file("classic"))
  .settings(
    name := "akka-sandbox-classic",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor" % "2.5.26",
      scalaTest % Test,
      "com.typesafe.akka" %% "akka-testkit" % "2.5.26" % Test,
    )
  )

lazy val typed = (project in file("typed"))
  .settings(
    name := "akka-sandbox-typed",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor-typed" % "2.6.0-RC1",
      scalaTest % Test,
      "com.typesafe.akka" %% "akka-testkit" % "2.6.0-RC1" % Test,
      "com.typesafe.akka" %% "akka-actor-testkit-typed" % "2.6.0-RC1" % Test,
    )
  )
