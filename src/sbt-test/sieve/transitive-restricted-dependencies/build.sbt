
import verizon.build._
import verizon.build.SieveKeys._

scalaVersion := "2.10.4"

libraryDependencies += "org.scodec" %% "scodec-core" % "1.10.0"
// libraryDependencies += "com.chuusai" %% "shapeless" % "2.3.1"

sieveUris := Seq(new java.net.URI(s"file:///${baseDirectory.value}/sieve.json"))
