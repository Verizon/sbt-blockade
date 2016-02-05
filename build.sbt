import verizon.build._

common.settings

name := "sbt-dependency-sieve"

teamName := Some("inf")

projectName := Some("build")

sbtPlugin := true

scalacOptions := Seq(
  "-deprecation",
  "-encoding", "UTF-8",
  "-unchecked",
  "-feature",
  "-language:implicitConversions",
  "-Ywarn-inaccessible",
  "-Xfatal-warnings"
)

ScriptedPlugin.scriptedSettings

scriptedLaunchOpts ++= Seq(
  "-Xmx1024M",
  "-Dplugin.version=" + version.value,
  "-Dscripted=true")

scriptedBufferLog := false

fork := true

libraryDependencies += "net.liftweb"   %% "lift-json" % "2.5.1"

addCommandAlias("validate", ";test;scripted")
