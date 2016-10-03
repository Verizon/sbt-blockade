//: ----------------------------------------------------------------------------
//: Copyright (C) 2016 Verizon.  All Rights Reserved.
//:
//:   Licensed under the Apache License, Version 2.0 (the "License");
//:   you may not use this file except in compliance with the License.
//:   You may obtain a copy of the License at
//:
//:       http://www.apache.org/licenses/LICENSE-2.0
//:
//:   Unless required by applicable law or agreed to in writing, software
//:   distributed under the License is distributed on an "AS IS" BASIS,
//:   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//:   See the License for the specific language governing permissions and
//:   limitations under the License.
//:
//: ----------------------------------------------------------------------------
package verizon.build

import org.apache.ivy.plugins.version.VersionRangeMatcher
import org.apache.ivy.core.module.id.ModuleRevisionId
import org.apache.ivy.plugins.latest.LatestRevisionStrategy

import java.io.File
import java.net.URL
import java.util.Date
import scala.io.Source
import java.text.SimpleDateFormat
import scala.language.reflectiveCalls
import sbinary.{Format, DefaultProtocol}
import scala.util.{Try, Failure, Success}
import scala.Console.{CYAN, RED, YELLOW, GREEN, RESET}
import scala.collection.mutable.{MultiMap, HashMap, Set}

import sbt._
import depgraph._
import net.liftweb.json._

/**
 * Represents a collection of whitelist and blacklist constraints.
 *
 * @param blacklist
 * @param whitelist
 */

case class Blockade(blacklist: List[JBlacklistedModuleFilter],
                 whitelist: List[JModuleWhitelistRangeFilter]) {
  def +++(that: Blockade): Blockade =
    Blockade((blacklist ++ that.blacklist).distinct, (whitelist ++ that.whitelist).distinct)

}

object Blockade {
  def empty: Blockade = Blockade(Nil, Nil)

  def catBlockades(ss: Seq[Blockade]): Blockade = ss.foldLeft(Blockade.empty)(_ +++ _)

}

sealed trait BlockadeModuleFilter

/**
 * Scala representation of blacklist item that is parsed from JSON.
 *
 * Example:
 * {
 * "organization": "commons-codec",
 * "name": "commons-codec",
 * "range": "[1.0,2.0]",
 * "expiry": "2014-12-25 13:00:00"
 * }
 *
 * @param range
 * @param expiry
 * @param name
 * @param organization
 */
final case class JBlacklistedModuleFilter(organization: String,
                                          name: String,
                                          range: Option[String],
                                          expiry: String) extends BlockadeModuleFilter {


  private def expiryDateFromString(str: String): Try[Date] =
    Try(new SimpleDateFormat("yyyy-MM-dd kk:mm:ss") parse (str))

  val endsAt: Date = expiryDateFromString(expiry).getOrElse(new Date)

  def isValid: Boolean = endsAt.after(new Date)

  def isExpired: Boolean = !isValid
}

/**
 * Scala representation of whitelist item that is parsed from JSON.
 *
 * Example:
 * {
 * "organization": "commons-codec",
 * "name": "commons-codec",
 * "range": "[1.0,2.0]"
 * }
 *
 * @param range
 * @param name
 * @param organization
 */
final case class JModuleWhitelistRangeFilter(organization: String,
                                             name: String, range: String) extends BlockadeModuleFilter

trait Outcome {
  def underlying: Option[ModuleID]
  def raisesError: Boolean
}

object Outcome {

  /**
   * Restricted immediate dependency result.
   *
   * @param module
   */
  final case class Restricted(module: ModuleID) extends Outcome {
    override val underlying: Option[ModuleID] = Option(module)
    override def raisesError: Boolean = true
  }

  final val restricted: ModuleID => Outcome = Restricted(_)

  /**
   * Deprecated immediate dependency result.
   *
   * @param module
   */
  final case class Deprecated(module: ModuleID) extends Outcome {
    override def underlying: Option[ModuleID] = Option(module)
    override def raisesError: Boolean = false
  }

  /**
   * Ignored immediate dependency result.
   */
  final case object Ignored extends Outcome {
    override def underlying: Option[ModuleID] = None
    override def raisesError: Boolean = false
  }
}


object BlockadeOps {

  type Message = String
  type ModuleOutcome = ModuleID => (Outcome, Message)

  private implicit val formats: Formats = DefaultFormats
  private val matcher = new VersionRangeMatcher("range", new LatestRevisionStrategy)

  def filterAndOutcomeFns(s: Blockade): Seq[(ModuleFilter, ModuleOutcome)] =
    s.blacklist.map(toModuleFilter) ++ s.whitelist.map(toModuleFilter)

  private def messageWithRange(r: String, e: String): String =
    s"Module within the exclusion range '$r' and expires/expired at $e."

  private def messageWithRange(r: String): String =
    s"Module not within the inclusion range '$r'."


  /**
   * Attempts to parse a `Blockade` from a String.
   *
   * @param json
   * @return
   */
  def parseBlockade(json: String): Try[Blockade] =
    for {
      a <- Try(parse(json))
      b <- Try(a.extract[Blockade])
    } yield b

  /**
   * Currently has an *intersection* semantics for whitelist.
   * We likely want to change this to have *union* semantics.
   *
   * @param whites
   */
  def coalesceWhites(whites: Seq[JModuleWhitelistRangeFilter]): Seq[JModuleWhitelistRangeFilter] =
    whites

  /**
   * Given blockades, analyse immediate deps and transitive deps.
   *
   * @param ms
   * @param rawgraph
   * @param ts
   */
  def analyseDeps(ms: Seq[ModuleID], ts: Seq[Blockade], rawgraph: ModuleGraph): (Seq[(Outcome, Message)], Option[TransitiveWarning]) = {
    val fos = {
      val blockade = Blockade.catBlockades(ts)
      filterAndOutcomeFns(blockade)
    }

    val omsAndFilters =
      analyseImmediateDeps(ms, fos)

    val warning = {
      // We transpose the graph so that *depended-upon* things point to *dependent* things.
      val g = GraphOps.transpose(stripUnderscores(rawgraph))
      findTransitiveWarning(fos, g)
    }

    (omsAndFilters, warning)
  }

  /**
   * Given constraints, analyse immediate deps.
   *
   * @param constraints
   * @param ms
   * @tparam A
   */
  def analyseImmediateDeps[A](ms: Seq[ModuleID], constraints: Seq[(ModuleFilter, ModuleOutcome)]): Seq[(Outcome, Message)] = for {
    (mf,of) <- constraints
    m <- ms.filter(mf).map(of)
  } yield m

  /**
   * Search the graph to find a restricted transitive dependency and return info associated with it.
   *
   * @param restrictions
   * @param g
   * @return
   */
  def findTransitiveWarning(restrictions: Seq[(ModuleFilter, ModuleOutcome)], g: ModuleGraph): Option[TransitiveWarning] = {
    // Topological sort the id nodes.
    val sortedIds = GraphOps.topoSort(g)

    // Attempt to find a restricted id node.
    findRestrictedTransitiveDep(sortedIds, restrictions).map {
      case (badModuleId, message) =>

        // If we find a restricted id node, we find a path from an immediate dependency to that node,
        // so that users know where the offending module resides.
        val pathFromBadDepToRoot = getPathToRoot(sortedIds.dropWhile(_ != badModuleId), badModuleId, g.edges)

        // Return a representation of the collected info.
        TransitiveWarning(pathFromBadDepToRoot, message)
    }
  }

  /**
   * Given constraints, find a transitive dependency in the DAG that does not satisfy the constaints.
   *
   * @param sortedNodes
   * @param restrictions
   */
  def findRestrictedTransitiveDep(sortedNodes: Seq[ModuleId],
                                  restrictions: Seq[(ModuleFilter, ModuleOutcome)]): Option[(ModuleId, BlockadeOps.Message)] = {
    sortedNodes.map { id =>
      restrictions.map {
        case (mf, of) =>
          val ID = toModuleID(id)
          if (mf(ID)) Some((id, of(ID)._2))
          else None
      }.flatten.headOption
    }.flatten.headOption
  }

  /**
   * Create a ModuleFilter from a whitelist or blacklist item.
   *
   * @param filter
   */
  def toModuleFilter(filter: BlockadeModuleFilter): (ModuleFilter, ModuleOutcome) = filter match {
    case f: JBlacklistedModuleFilter => (
      (m: ModuleID) =>
        m.organization == f.organization &&
          m.name == f.name && f.range.fold(true) { range =>
          matcher.accept(
            ModuleRevisionId.newInstance(f.organization, f.name, range),
            ModuleRevisionId.newInstance(m.organization, m.name, m.revision))
        },
      (m: ModuleID) => f.range.fold((Outcome.restricted(m), "All versions of module excluded.")) { range =>
        (if (f.isExpired) Outcome.Restricted(m) else Outcome.Deprecated(m), messageWithRange(range, f.expiry))
      }
      )
    case f: JModuleWhitelistRangeFilter => (
      (m: ModuleID) =>
        m.organization == f.organization &&
          m.name == f.name &&
          !matcher.accept(
            ModuleRevisionId.newInstance(f.organization, f.name, f.range),
            ModuleRevisionId.newInstance(m.organization, m.name, m.revision)),
      (m: ModuleID) => (Outcome.Restricted(m), messageWithRange(f.range))
      )
  }

  def toModuleId(smid: sbt.ModuleID): ModuleId = {
    ModuleId(
      organisation = smid.organization,
      name = smid.name,
      version = smid.revision
    )
  }

  def toModuleID(mid: ModuleId): sbt.ModuleID = {
    mid.organisation %% mid.name % mid.version
  }

  def stripUnderscores(g: ModuleGraph): ModuleGraph = {
    def stripUnderscore(mid: ModuleId): ModuleId = {
      val updatedName = mid.name.split("_2\\.").head // yolo
      mid.copy(name = updatedName)
    }
    g.copy(
      nodes = g.nodes.map(n => n.copy(id = stripUnderscore(n.id))),
      edges = g.edges.map {
        case (from, to) => stripUnderscore(from) -> stripUnderscore(to)
      }
    )
  }

  /**
   * Contains info associated with a restricted transitive dependency.
   * We save the path from the offending dependency to an immediate dependency containing it.
   *
   * @param fromCauseToRoot
   * @param rangeMessage
   */
  final case class TransitiveWarning(fromCauseToRoot: Seq[ModuleId], rangeMessage: String)

  type PathToRoot = Seq[ModuleId]

  def getPathToRoot(sortedIds: Seq[ModuleId], from: ModuleId, edges: Seq[Edge]): PathToRoot = {
    require(sortedIds.head == from) // YOLO
    edges.find(_._1 == from).map(_._2).fold(Seq(from))(
      next => from +: getPathToRoot(sortedIds.dropWhile(_ != next), next, edges)
    )
  }

  /**
   * Turns immediate dependency results into presentable form.
   *
   * @param name
   * @param so
   * @return
   */
  def showImmediateDepResults(name: String, so: Seq[(Outcome, Message)]): String = {
    CYAN + s"[$name] The following dependencies were caught in the blockade: " + RESET +
      so.distinct.map {
        case (Outcome.Restricted(m), msg) => RED + s"Restricted: ${m.toString}. $msg" + RESET
        case (Outcome.Deprecated(m), msg) => YELLOW + s"Deprecated: ${m.toString}. $msg" + RESET
        case (o, m) => "Unkonwn input to blockade display."
      }.mkString("\n\t", ",\n\t", "")
  }

  /**
   * Turns transitive dependency results into presentable form.
   *
   * @param w
   * @return
   */
  def showTransitiveDepResults(w: TransitiveWarning): String = {
    val path = w.fromCauseToRoot.reverse
    def go(indent: Int, remaining: Seq[ModuleId], acc: String): String = remaining match {
      case Nil =>
        acc.stripMargin
      case x +: xs =>
        go(indent + 2, xs, acc + List.fill(indent)(' ').mkString + x.idString + '\n')
    }
    val preamble =
      s"""
         |${path.head.idString} has a restricted transitive dependency: ${path.last.idString}
         |  ${w.rangeMessage}
         |
         |Here is the dependency chain:
         |""".stripMargin
    preamble + go(2, path, "")
  }

}

object GraphOps {
  /**
   * Make the arrows go in the opposite direction.
   *
   * @param g
   * @return
   */
  def transpose(g: ModuleGraph): ModuleGraph =
    g.copy(edges = g.edges.map { case (from, to) => (to, from) })

  /**
   * Topological sort a ModuleGraph.
   *
   * @param g
   * @return
   */
  def topoSort(g: ModuleGraph): Seq[ModuleId] = {
    def removeNodes(g: ModuleGraph, nodesForRemovalIds: Seq[ModuleId]): ModuleGraph = {
      val updatedNodes = g.nodes.filter(n => !nodesForRemovalIds.contains(n.id))
      val updatedEdges = g.edges.filter(e => !nodesForRemovalIds.contains(e._1))

      ModuleGraph(updatedNodes, updatedEdges)
    }

    def go(curGraph: ModuleGraph, acc: Seq[ModuleId]): Seq[ModuleId] = {
      if (curGraph.isEmpty) acc
      else {
        val roots = curGraph.roots.map(_.id)
        go(removeNodes(curGraph, roots), acc ++ roots)
      }
    }

    go(g, Seq.empty)
  }

  /**
   * Ivy (and sbt-dependency-graph) gives us a DAG containing
   * evicted modules, but not the evicted modules' dependencies
   * (unless those sub-dependencies are used by a non-evicted module,
   * in which case we want to keep them anyway).
   * So, we need only remove evicted nodes and their in-bound (and out-bound,
   * if they happen to exist) edges.
   */
  def pruneEvicted(g: ModuleGraph): ModuleGraph = {
    val usedModules = g.nodes.filterNot(_.isEvicted)
    val usedModuleIds = usedModules.map(_.id).toSet
    val legitEdges = g.edges.filter {
      case (from, to) =>
        usedModuleIds.contains(from) && usedModuleIds.contains(to)
    }

    g.copy(
      nodes = usedModules,
      edges = legitEdges
    )

  }

}

