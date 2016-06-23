package verizon.build

import scala.io.Source
import java.io.File
import java.net.URL
import scala.util.{Try, Failure, Success}
import net.liftweb.json._
import sbt._
import scala.language.reflectiveCalls
import depgraph._
import aux._

import org.apache.ivy.plugins.version.VersionRangeMatcher
import org.apache.ivy.core.module.id.ModuleRevisionId
import org.apache.ivy.plugins.latest.LatestRevisionStrategy

import java.text.SimpleDateFormat
import java.util.Date

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


  def parseSieve(json: String): Try[Sieve] =
    for {
      a <- Try(parse(json))
      b <- Try(a.extract[Sieve])
    } yield b

  /**
   * Currently has an *intersection* semantics for whitelist.
   * We likely want to change this to have *union* semantics.
   */
  def coalesceWhites(whites: Seq[JModuleWhitelistRangeFilter]): Seq[JModuleWhitelistRangeFilter] =
    whites

  /**
   * Given sieves, analyse immediate deps and transitive deps.
   */
  def analyseDeps(ms: Seq[ModuleID], ts: Seq[Sieve], rawgraph: ModuleGraph): (Seq[(Outcome, Message)], Option[RestrictionWarning]) = {
    val sieve = Sieve.catSieves(ts)
    val g = transpose(stripUnderscores(rawgraph))
    val fos = filterAndOutcomeFns(sieve)
    val omsAndFilters = analyseImmediateDeps(ms, fos)
    val warning = findTransitiveWarning(fos, g)
    (omsAndFilters, warning)
  }

  /**
   *  Given constraints, analyse immediate deps.
   */
  def analyseImmediateDeps[A](ms: Seq[ModuleID], constraints: Seq[(ModuleFilter, ModuleOutcome)]): Seq[(Outcome, Message)] = for {
    (mf,of) <- constraints
    m <- ms.filter(mf).map(of)
  } yield m

  def findTransitiveWarning(restrictions: Seq[(ModuleFilter, ModuleOutcome)], g: ModuleGraph): Option[RestrictionWarning] = {
    val sortedIds = topoSort(g)
    findRestrictedTransitiveDep(sortedIds, restrictions).map {
      case (badModuleId, message) =>
        val pathFromBadDepToRoot = getPathToRoot(sortedIds.dropWhile(_ != badModuleId), badModuleId, g.edges)
        RestrictionWarning(pathFromBadDepToRoot, message)
    }
  }

  /**
   * Given constraints, find a transitive dependency in the DAG that does not satisfy the constaints.
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
   */
  def toModuleFilter(f: SieveModuleFilter): (ModuleFilter, ModuleOutcome) = f match {
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

}
