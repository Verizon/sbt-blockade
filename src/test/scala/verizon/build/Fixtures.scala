package verizon.build

import sbt.impl.DependencyBuilders
import java.net.URL
import depgraph._
import SieveOps._

object Fixtures extends Fixtures
trait Fixtures extends DependencyBuilders {
  def load(p: String): URL =
    getClass.getClassLoader.getResource(p)

  def commons(s: String, v: String): sbt.ModuleID =
    s"commons-$s" % s"commons-$s" % v

  val `commons-codec-1.9` = commons("codec", "1.9")
  val `commons-codec-1.+` = commons("codec", "1.+")
  val `commons-io-2.2` = commons("io", "2.2")
  val `commons-lang-2.2` = commons("lang", "2.2")
  val `commons-net-3.3` = commons("net", "3.3")
  val `funnel-1.3.71` = "intelmedia.ws.funnel" %% "http" % "1.3.71"
  val `funnel-1.3.+` = "intelmedia.ws.funnel" %% "http" % "1.3.+"


  val `toplevel-has-direct-dep-on-scalaz` = "org.foo" %% "has-direct-dep-on-scalaz" % "1.2.4"
  val `toplevel-has-trans-dep-on-shapeless` = "org.foo" %% "has-trans-dep-on-shapeless" % "1.2.3"
  val `doobie-core-0.2.3` = "org.tpolecat" %% "doobie-core" % "0.2.3"
  val `scalaz-core-7.1.4` = "org.scalaz" %% "scalaz-core" % "7.1.4"
  val `scalaz-effect-7.1.4` = "org.scalaz" %% "scalaz-effect" % "7.1.4"
  val `scalaz-stream-0.8` = "org.scalaz.stream" %% "scalaz-stream" % "0.8"
  val `shapeless-2.2.5` = "com.chuusai" %% "shapeless" % "2.2.5"

  val m0HasScalazDep = Module(toModuleId(`toplevel-has-direct-dep-on-scalaz`))
  val m0HasShapelessTransDep = Module(toModuleId(`toplevel-has-trans-dep-on-shapeless`))
  val m1 = Module(toModuleId(`doobie-core-0.2.3`))
  val m2 = Module(toModuleId(`scalaz-core-7.1.4`))
  val m3 = Module(
    id = toModuleId(`scalaz-effect-7.1.4`),
    evictedByVersion = Some("notrelevant")
  )
  val m4 = Module(toModuleId(`scalaz-stream-0.8`))
  val m5 = Module(toModuleId(`shapeless-2.2.5`))
  val graphWithNestedShapeless = ModuleGraph(
    nodes = Seq(m5, m3, m4, m0HasShapelessTransDep, m1, m2, m0HasScalazDep),
    edges = Seq(
      m0HasShapelessTransDep.id -> m1.id,
      m1.id -> m2.id,
      m1.id -> m3.id,
      m1.id -> m4.id,
      m1.id -> m5.id,
      m0HasScalazDep.id -> m2.id
    )
  )
  val graphWithNestedShapelessWithoutEvicted = ModuleGraph(
    nodes = Seq(m5, m4, m0HasShapelessTransDep, m1, m2, m0HasScalazDep),
    edges = Seq(
      m0HasShapelessTransDep.id -> m1.id,
      m1.id -> m2.id,
      m1.id -> m4.id,
      m1.id -> m5.id,
      m0HasScalazDep.id -> m2.id
    )
  )
}
