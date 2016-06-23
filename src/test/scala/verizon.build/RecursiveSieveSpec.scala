package verizon.build

import org.scalatest._

class RecursiveSieveSpec extends FreeSpec with MustMatchers {

  import sbt.{ModuleFilter, ModuleID}
  import Fixtures._
  import aux._
  import depgraph._
  import SieveOps._

  "given a dependency graph" - {
    "can topo sort the graph" in {
      topoSort(graphWithNestedShapeless).toList mustBe List(
        toModuleId(`toplevel-has-direct-dep-on-scalaz`),
        toModuleId(`toplevel-has-trans-dep-on-shapeless`),
        toModuleId(`doobie-core-0.2.3`),
        toModuleId(`shapeless-2.2.5`),
        toModuleId(`scalaz-stream-0.8`),
        toModuleId(`scalaz-core-7.1.4`),
        toModuleId(`scalaz-effect-7.1.4`)
      )
    }

    "given a restricted transitive dependency" - {
      val fo: (ModuleFilter, ModuleOutcome) = (
        (id: ModuleID) => toModuleId(id) == toModuleId(`shapeless-2.2.5`),
        (id: ModuleID) => (Restricted(id), "some message")
        )
      def constraints = Seq(fo)
      "returns a representation of a warning that contains the path to the nested dep" in {
        val transposed = transpose(graphWithNestedShapeless)

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
      val w = RestrictionWarning(
        List(
          toModuleId(`shapeless-2.2.5`),
          toModuleId(`doobie-core-0.2.3`),
          toModuleId(`toplevel-has-trans-dep-on-shapeless`)
        ),
        message
      )
      showWarnings(w) mustBe
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
}

