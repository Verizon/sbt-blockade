
import verizon.build._
import verizon.build.SieveKeys._
import java.net.URL
import scala.concurrent.duration._

scalaVersion := "2.10.4"

SievePlugin.settings

libraryDependencies ++= Seq(
  "commons-codec" % "commons-codec" % "1.9",
  "commons-lang" % "commons-lang" % "2.6"
)

sieves := Seq(new URL(s"file:///${baseDirectory.value}/sieve.json"))

enforcementInterval := 3.seconds
