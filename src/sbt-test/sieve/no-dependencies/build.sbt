
import verizon.build._
import scala.concurrent.duration._

scalaVersion := "2.10.4"

libraryDependencies ++= Seq(
  "commons-codec" % "commons-codec" % "1.9",
  "commons-lang" % "commons-lang" % "2.6"
)

sieveUris := Seq(new java.net.URI(s"file:///${baseDirectory.value}/sieve.json"))

sieveEnforcementInterval := 3.seconds
