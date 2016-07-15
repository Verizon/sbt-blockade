
import verizon.build._

resolvers += "internal" at "http://nexus.oncue.verizon.net/nexus/content/groups/internal/"

scalaVersion := "2.10.4"

libraryDependencies ++= Seq(
  "commons-codec"  % "commons-codec" % "1.+",
  "commons-lang"   % "commons-lang" % "2.6",
  "oncue.knobs"   %% "core" % "3.3.3",
  "oncue.journal" %% "core" % "2.2.1"
)

blockadeUris := Seq(new java.net.URI(s"file:///${baseDirectory.value}/blockade.json"))
