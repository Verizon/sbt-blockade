package verizon.build

import org.scalatest._
import Fixtures._
import SieveOps._
import depgraph._
import sbt._

class RecursiveSieveSpec extends FreeSpec with MustMatchers {

  "given a dependency graph can topo sort the graph" in {
    GraphOps.topoSort(graphWithNestedShapeless).toList mustBe List(
      toModuleId(`toplevel-has-direct-dep-on-scalaz`),
      toModuleId(`toplevel-has-trans-dep-on-shapeless`),
      toModuleId(`doobie-core-0.2.3`),
      toModuleId(`shapeless-2.2.5`),
      toModuleId(`scalaz-stream-0.8`),
      toModuleId(`scalaz-core-7.1.4`),
      toModuleId(`scalaz-effect-7.1.4`)
    )
  }

  "pruneEvicted" - {
    "when a root node is evicted, returns the graph without that node" in {
      val m1a = Module(
        id = ModuleId("m1org", "m1", "1.0.0a"),
        evictedByVersion = Some("notrelevant")
      )
      val m1b = Module(
        id = ModuleId("m1org", "m1", "1.0.0b"),
        evictedByVersion = None
      )

      val given =
        ModuleGraph(
          nodes = Seq(m1a, m1b),
          edges = Seq.empty
        )

      val expected = ModuleGraph(
        nodes = Seq(m1b),
        edges = Seq.empty
      )

      GraphOps.pruneEvicted(given) mustBe expected
    }

    "when a non-root node is evicted, returns the graph without that node" in {

      GraphOps.pruneEvicted(graphWithNestedShapeless) mustBe {
        graphWithNestedShapelessWithoutEvicted

      }

    }

  }

  "given a restricted transitive dependency" - {
    val fo: (ModuleFilter, ModuleOutcome) = (
      (id: ModuleID) => toModuleId(id) == toModuleId(`shapeless-2.2.5`),
      (id: ModuleID) => (Outcome.Restricted(id), "some message")
      )
    def constraints = Seq(fo)
    "returns a representation of a warning that contains the path to the nested dep" in {
      val transposed = GraphOps.transpose(graphWithNestedShapeless)

      findTransitiveWarning(constraints, transposed).get.fromCauseToRoot.toList mustBe
        List(
          toModuleId(`shapeless-2.2.5`),
          toModuleId(`doobie-core-0.2.3`),
          toModuleId(`toplevel-has-trans-dep-on-shapeless`)
        )
    }
    "if the dep is evicted, does not return a warning" in {
      pending
      val transposed = GraphOps.transpose(graphWithNestedShapeless)

      findTransitiveWarning(constraints, transposed).get.fromCauseToRoot.toList mustBe
        List(
          toModuleId(`shapeless-2.2.5`),
          toModuleId(`doobie-core-0.2.3`),
          toModuleId(`toplevel-has-trans-dep-on-shapeless`)
        )
    }

  }
  "displaying restriction warning" in {
    val message = "some range message"
    val w = TransitiveWarning(
      List(
        toModuleId(`shapeless-2.2.5`),
        toModuleId(`doobie-core-0.2.3`),
        toModuleId(`toplevel-has-trans-dep-on-shapeless`)
      ),
      message
    )
    showTransitiveDepResults(w) mustBe
      s"""
         |org.foo:has-trans-dep-on-shapeless:1.2.3 has a restricted transitive dependency: com.chuusai:shapeless:2.2.5
         |  some range message
         |
           |Here is the dependency chain:
         |  org.foo:has-trans-dep-on-shapeless:1.2.3
         |    org.tpolecat:doobie-core:0.2.3
         |      com.chuusai:shapeless:2.2.5
         |""".stripMargin
  }
}

