package verizon.build

import sbt.impl.DependencyBuilders
import java.net.URL

object Fixtures extends DependencyBuilders {
  def load(p: String): URL =
    getClass.getClassLoader.getResource(p)

  def commons(s: String, v: String): sbt.ModuleID =
    s"commons-$s" % s"commons-$s" % v

  val `commons-codec-1.9` = commons("codec", "1.9")
  val `commons-codec-1.+` = commons("codec", "1.+")
  val `commons-io-2.2`    = commons("io",    "2.2")
  val `commons-lang-2.2`  = commons("lang",  "2.2")
  val `commons-net-3.3`   = commons("net",   "3.3")
  val `funnel-1.3.71`     = "intelmedia.ws.funnel" %% "http" % "1.3.71"
  val `funnel-1.3.+`      = "intelmedia.ws.funnel" %% "http" % "1.3.+"
}
