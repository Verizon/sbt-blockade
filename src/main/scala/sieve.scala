package verizon.build

import scala.io.Source
import java.io.File
import java.net.URL
import scala.util.{Try, Failure, Success}
import net.liftweb.json._
import sbt._
import scala.language.reflectiveCalls
import depgraph._

case class Sieve(blacklist: List[JBlacklistedModuleFilter],
                 whitelist: List[JModuleWhitelistRangeFilter]) {
  def +++(that: Sieve): Sieve =
    Sieve((blacklist ++ that.blacklist).distinct, (whitelist ++ that.whitelist).distinct)

}

object Sieve {
  def empty: Sieve = Sieve(Nil, Nil)

  def catSieves(ss: Seq[Sieve]): Sieve = ss.foldLeft(Sieve.empty)(_ +++ _)

  def fromTrys(ts: Seq[Try[Sieve]]): Try[Sieve] = flatten(ts).map(catSieves)


  private def flatten[T](xs: Seq[Try[T]]): Try[Seq[T]] = {
    val (ss: Seq[Success[T]]@unchecked, fs: Seq[Failure[T]]@unchecked) =
      xs.partition(_.isSuccess)

    if (fs.isEmpty) Success(ss map (_.get))
    else Failure[Seq[T]](fs(0).exception) // Only keep the first failure
  }
}

/**
  * {
  * "organization": "commons-codec",
  * "name": "commons-codec",
  * "range": "[1.0,2.0]",
  * "expiry": "2014-12-25 13:00:00"
  * }
  */
sealed trait SieveModuleFilter

final case class JBlacklistedModuleFilter(organization: String,
                                          name: String,
                                          range: String,
                                          expiry: String) extends SieveModuleFilter {

  import java.text.SimpleDateFormat
  import java.util.Date

  private def expiryDateFromString(str: String): Try[Date] =
    Try(new SimpleDateFormat("yyyy-MM-dd kk:mm:ss") parse (str))

  val endsAt: Date = expiryDateFromString(expiry).getOrElse(new Date)

  def isValid: Boolean = endsAt.after(new Date)

  def isExpired: Boolean = !isValid
}

final case class JModuleWhitelistRangeFilter(organization: String,
                                             name: String, range: String) extends SieveModuleFilter

abstract class Outcome(val underlying: Option[ModuleID],
                       val raisesError: Boolean = false)

final case class Restricted(module: ModuleID) extends Outcome(Option(module), true)

final case class Deprecated(module: ModuleID) extends Outcome(Option(module))

final case object Ignored extends Outcome(None)

object SieveOps {

  import aux._

  import org.apache.ivy.plugins.version.VersionRangeMatcher
  import org.apache.ivy.core.module.id.ModuleRevisionId
  import org.apache.ivy.plugins.latest.LatestRevisionStrategy

  type Message = String
  type ModuleOutcome = ModuleID => (Outcome, Message)

  private implicit val formats: Formats = DefaultFormats
  private val matcher = new VersionRangeMatcher("range", new LatestRevisionStrategy)

  def mkFiltersAndOutcomes(s: Sieve): Seq[(ModuleFilter, ModuleOutcome)] =
    s.blacklist.map(toModuleFilter) ++ s.whitelist.map(toModuleFilter)

  private def messageWithRange(r: String, e: String): String =
    s"Module within the exclusion range '$r' and expires/expired at $e."

  private def messageWithRange(r: String): String =
    s"Module not within the inclusion range '$r'."

  def loadFromURL(url: String): Try[Sieve] =
    loadFromURL(new URL(url))

  def loadFromURL(url: URL): Try[Sieve] =
    for {
      a <- Try(Source.fromURL(url))
      b <- loadFromString(a.mkString)
    } yield b

  def loadFromString(json: String): Try[Sieve] =
    for {
      a <- Try(parse(json))
      b <- Try(a.extract[Sieve])
    } yield b

  // Currently has an *intersection* semantics for whitelist.
  // We likely want to change this to have *union* semantics.
  def coalesceWhites(whites: Seq[JModuleWhitelistRangeFilter]): Seq[JModuleWhitelistRangeFilter] =
    whites


  /**
    * This is the edge of the world: call this function to "run" the plugin
    */
  def exe(ms: Seq[ModuleID], ts: Seq[Try[Sieve]], rawgraph: ModuleGraph): Try[(Seq[(Outcome, Message)], Option[RestrictionWarning])] = {
    val g = transpose(stripUnderscores(rawgraph))
    for {
      omsAndFilters <- checkImmediateDeps(ms, ts)
      warning = scanGraphForWarnings(omsAndFilters._2)(g)
    } yield (omsAndFilters._1, warning)
  }

  def checkImmediateDeps[A](ms: Seq[ModuleID], ts: Seq[Try[Sieve]]): Try[(Seq[(Outcome, Message)], Seq[(ModuleFilter, ModuleOutcome)])] = {
    for {
      s <- Sieve.fromTrys(ts)
      fos = mkFiltersAndOutcomes(s)
      oms = for {
        (mf, of) <- fos
        m <- ms.filter(mf).map(of)
      } yield m
    } yield (oms, fos)

  }

  def scanGraphForWarnings(fos: Seq[(ModuleFilter, ModuleOutcome)]): ModuleGraph => Option[RestrictionWarning] = { (g: ModuleGraph) =>

    val sortedIds: Seq[ModuleId] = topoSort(g)
    val edges = g.edges

    warningWithPath(sortedIds, fos, edges)
  }

  def warningWithPath(sortedIds: Seq[ModuleId], fos: Seq[(ModuleFilter, ModuleOutcome)], edges: Seq[Edge]): Option[RestrictionWarning] = {
    val x: Option[(ModuleId, SieveOps.Message)] = sortedIds.map { id =>
      fos.map {
        case (mf, of) =>
          val ID = toModuleID(id)
          if (mf(ID)) Some((id, of(ID)._2))
          else None
      }.flatten.headOption
    }.flatten.headOption

    x.map {
      case (badModuleId, message) =>
        RestrictionWarning(
          getPathToRoot(sortedIds.dropWhile(_ != badModuleId), badModuleId, edges), message)
    }
  }

  def toModuleFilter(f: SieveModuleFilter): (ModuleFilter, ModuleOutcome) = f match {
    case f: JBlacklistedModuleFilter => (
      (m: ModuleID) =>
        m.organization == f.organization &&
          m.name == f.name &&
          matcher.accept(
            ModuleRevisionId.newInstance(f.organization, f.name, f.range),
            ModuleRevisionId.newInstance(m.organization, m.name, m.revision)),
      (m: ModuleID) => (if (f.isExpired) Restricted(m) else Deprecated(m), messageWithRange(f.range, f.expiry))
      )
    case f: JModuleWhitelistRangeFilter => (
      (m: ModuleID) =>
        m.organization == f.organization &&
          m.name == f.name &&
          !matcher.accept(
            ModuleRevisionId.newInstance(f.organization, f.name, f.range),
            ModuleRevisionId.newInstance(m.organization, m.name, m.revision)),
      (m: ModuleID) => (Restricted(m), messageWithRange(f.range))
      )
  }

}
