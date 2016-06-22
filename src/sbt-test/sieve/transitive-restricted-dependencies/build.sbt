
import verizon.build._
import verizon.build.SieveKeys._
import java.net.URL

scalaVersion := "2.10.4"

SievePlugin.settings

libraryDependencies += "org.scodec" %% "scodec-core" % "1.10.0"
// libraryDependencies += "com.chuusai" %% "shapeless" % "2.3.1"


sieves := Seq(new URL(s"file:///${baseDirectory.value}/sieve.json"))
