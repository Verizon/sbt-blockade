
import verizon.build._

scalaVersion := "2.10.5"

libraryDependencies ++= Seq(
  "commons-codec"  % "commons-codec" % "1.+",
  "commons-lang"   % "commons-lang" % "2.6",
  "io.verizon.knobs"   %% "core" % "3.10.21",
  "io.verizon.journal" %% "core" % "2.3.15"
)

blockadeUris := Seq(new java.net.URI(s"file:///${baseDirectory.value}/blockade.json"))
