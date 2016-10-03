
organization := "io.verizon.build"

name := "sbt-blockade"

scalacOptions ++= Seq("-deprecation", "-feature")

sbtPlugin := true

ScriptedPlugin.scriptedSettings

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

libraryDependencies += "net.liftweb"   %% "lift-json" % "2.5.1"

addCommandAlias("validate", ";test;scripted")
