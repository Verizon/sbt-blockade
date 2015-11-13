
import oncue.build._
import oncue.build.SieveKeys._
import java.net.URL

scalaVersion := "2.10.4"

SievePlugin.settings

libraryDependencies ++= Seq(
  "commons-codec" % "commons-codec" % "1.+",
  "commons-lang" % "commons-lang" % "2.6",
  "intelmedia.ws.funnel" %% "http" % "1.8.+",
  "oncue.svc.journal" %% "core" % "1.3.71"
)

sieves := Seq(new URL(s"file:///${baseDirectory.value}/sieve.json"))
