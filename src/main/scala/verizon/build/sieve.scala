package verizon.build

import scala.io.Source
import java.io.File
import java.net.URL
import scala.util.{Try, Failure, Success}
import net.liftweb.json._
import sbt._
import scala.language.reflectiveCalls
import depgraph._

import org.apache.ivy.plugins.version.VersionRangeMatcher
import org.apache.ivy.core.module.id.ModuleRevisionId
import org.apache.ivy.plugins.latest.LatestRevisionStrategy

import java.text.SimpleDateFormat
import java.util.Date
import sbt._
import scala.language.reflectiveCalls
import java.io.File
import scala.collection.mutable.{MultiMap, HashMap, Set}
import sbinary.{Format, DefaultProtocol}
import depgraph._
import scala.Console.{CYAN, RED, YELLOW, GREEN, RESET}

/**
 * Represents a collection of whitelist and blacklist constraints.
 *
 * @param blacklist
 * @param whitelist
 */

case class Sieve(blacklist: List[JBlacklistedModuleFilter],
                 whitelist: List[JModuleWhitelistRangeFilter]) {
  def +++(that: Sieve): Sieve =
    Sieve((blacklist ++ that.blacklist).distinct, (whitelist ++ that.whitelist).distinct)

}

object Sieve {
  def empty: Sieve = Sieve(Nil, Nil)

  def catSieves(ss: Seq[Sieve]): Sieve = ss.foldLeft(Sieve.empty)(_ +++ _)

}

sealed trait SieveModuleFilter

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
 */
final case class JBlacklistedModuleFilter(organization: String,
                                          name: String,
                                          range: String,
                                          expiry: String) extends SieveModuleFilter {


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
 */
final case class JModuleWhitelistRangeFilter(organization: String,
                                             name: String, range: String) extends SieveModuleFilter

trait Outcome {
  def underlying: Option[ModuleID]
  def raisesError: Boolean
}

object Outcome {
  final case class Restricted(module: ModuleID) extends Outcome {
    override val underlying: Option[ModuleID] = Option(module)
    override def raisesError: Boolean = true
  }

  final case class Deprecated(module: ModuleID) extends Outcome {
    override def underlying: Option[ModuleID] = Option(module)
    override def raisesError: Boolean = false
  }

  final case object Ignored extends Outcome {
    override def underlying: Option[ModuleID] = None
    override def raisesError: Boolean = false
  }
}


object SieveOps {

  type Message = String
  type ModuleOutcome = ModuleID => (Outcome, Message)

  private implicit val formats: Formats = DefaultFormats
  private val matcher = new VersionRangeMatcher("range", new LatestRevisionStrategy)

  def filterAndOutcomeFns(s: Sieve): Seq[(ModuleFilter, ModuleOutcome)] =
    s.blacklist.map(toModuleFilter) ++ s.whitelist.map(toModuleFilter)

  private def messageWithRange(r: String, e: String): String =
    s"Module within the exclusion range '$r' and expires/expired at $e."

  private def messageWithRange(r: String): String =
    s"Module not within the inclusion range '$r'."


  /**
   * Attempts to parse a `Sieve` from a String.
   *
   * @param json
   * @return
   */
  def parseSieve(json: String): Try[Sieve] =
    for {
      a <- Try(parse(json))
      b <- Try(a.extract[Sieve])
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
   * Given sieves, analyse immediate deps and transitive deps.
   *
   * @param ms
   * @param rawgraph
   * @param ts
   */
  def analyseDeps(ms: Seq[ModuleID], ts: Seq[Sieve], rawgraph: ModuleGraph): (Seq[(Outcome, Message)], Option[TransitiveWarning]) = {
    val sieve = Sieve.catSieves(ts)
    val g = transpose(stripUnderscores(rawgraph))
    val fos = filterAndOutcomeFns(sieve)
    val omsAndFilters = analyseImmediateDeps(ms, fos)
    val warning = findTransitiveWarning(fos, g)
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

  def findTransitiveWarning(restrictions: Seq[(ModuleFilter, ModuleOutcome)], g: ModuleGraph): Option[TransitiveWarning] = {
    val sortedIds = topoSort(g)
    findRestrictedTransitiveDep(sortedIds, restrictions).map {
      case (badModuleId, message) =>
        val pathFromBadDepToRoot = getPathToRoot(sortedIds.dropWhile(_ != badModuleId), badModuleId, g.edges)
        TransitiveWarning(pathFromBadDepToRoot, message)
    }
  }

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
   * Given constraints, find a transitive dependency in the DAG that does not satisfy the constaints.
   *
   * @param sortedNodes
   * @param restrictions
   */
  def findRestrictedTransitiveDep(sortedNodes: Seq[ModuleId],
                                  restrictions: Seq[(ModuleFilter, ModuleOutcome)]): Option[(ModuleId, SieveOps.Message)] = {
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
  def toModuleFilter(filter: SieveModuleFilter): (ModuleFilter, ModuleOutcome) = filter match {
    case f: JBlacklistedModuleFilter => (
      (m: ModuleID) =>
        m.organization == f.organization &&
          m.name == f.name &&
          matcher.accept(
            ModuleRevisionId.newInstance(f.organization, f.name, f.range),
            ModuleRevisionId.newInstance(m.organization, m.name, m.revision)),
      (m: ModuleID) => (if (f.isExpired) Outcome.Restricted(m) else Outcome.Deprecated(m), messageWithRange(f.range, f.expiry))
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
    def stripUnderscore(mid: ModuleId): ModuleId = mid.copy(name = mid.name.takeWhile(_ != '_'))
    g.copy(
      nodes = g.nodes.map(n => n.copy(id = stripUnderscore(n.id))),
      edges = g.edges.map {
        case (from, to) => stripUnderscore(from) -> stripUnderscore(to)
      }
    )
  }


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
    CYAN + s"[$name] The following dependencies were caught in the sieve: " + RESET +
      so.distinct.map {
        case (Outcome.Restricted(m), msg) => RED + s"Restricted: ${m.toString}. $msg" + RESET
        case (Outcome.Deprecated(m), msg) => YELLOW + s"Deprecated: ${m.toString}. $msg" + RESET
        case (o, m) => "Unkonwn input to sieve display."
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
