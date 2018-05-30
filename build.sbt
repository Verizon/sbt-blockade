import sbt.Keys._
import sbt._

organization := "io.verizon.build"

name := "sbt-blockade"

scalaVersion := "2.12.6"

sbtVersion in Global := "1.1.6"

scalacOptions ++= Seq("-deprecation", "-feature", "-language:implicitConversions")

sbtPlugin := true

scriptedLaunchOpts ++= Seq(
  "-Xmx1024M",
  "-Dplugin.version=" + version.value,
  "-Dscripted=true")

scriptedBufferLog := false

fork := true

coverageEnabled := false

licenses := Seq("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0.html"))

homepage := Some(url("https://github.com/verizon/sbt-blockade"))

scmInfo := Some(ScmInfo(url("https://github.com/verizon/sbt-blockade"),
                            "git@github.com:verizon/sbt-blockade.git"))

// To sync with Maven central, you need to supply the following information:
pomExtra in Global := {
  <developers>
    <developer>
      <id>timperrett</id>
      <name>Timothy Perrett</name>
      <url>github.com/timperrett</url>
    </developer>
  </developers>
}

sonatypeProfileName := "io.verizon"

pomPostProcess := { identity }

libraryDependencies += "net.liftweb" %% "lift-json" % "3.2.0"

enablePlugins(ScalaTestPlugin)

addCommandAlias("validate", ";test;scripted")
