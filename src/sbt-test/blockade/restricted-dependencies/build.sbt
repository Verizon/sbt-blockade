
import verizon.build._

scalaVersion := "2.10.5"

libraryDependencies ++= Seq(
  "commons-codec"  % "commons-codec" % "1.+",
  "commons-lang"   % "commons-lang"  % "2.6",
  "com.chuusai"   %% "shapeless"     % "2.3.1"
)

blockadeUris := Seq(new java.net.URI(s"file:///${baseDirectory.value}/blockade.json"))
