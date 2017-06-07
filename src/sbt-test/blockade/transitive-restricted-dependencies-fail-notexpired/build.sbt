
import verizon.build._

scalaVersion := "2.10.4"

libraryDependencies ++= List(
  "org.scodec" %% "scodec-core"   % "1.10.0",
  "com.github.alexarchambault" %% "argonaut-shapeless_6.2" % "1.2.0-M5"
)

blockadeUris := Seq(new java.net.URI(s"file:///${baseDirectory.value}/blockade.json"))

blockadeFailTransitive := true
