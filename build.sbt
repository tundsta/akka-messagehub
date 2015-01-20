name := """akka-messaging-hub"""

version := "1.0"

scalaVersion := "2.11.1"

resolvers += "Akka Snapshot Repository" at "http://repo.akka.io/snapshots/"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.4-SNAPSHOT",
  "com.typesafe.akka" %% "akka-testkit" % "2.4-SNAPSHOT" % "test",
  "org.scalatest" %% "scalatest" % "2.2.1" % "test")
  