package oncue.build

import scala.io.Source
import java.io.File
import java.net.URL
import scala.util.{Try,Failure,Success} // poor mans scalaz.\/
import net.liftweb.json._
import sbt.{ModuleFilter,ModuleID}

case class Sieve(modules: List[JModuleFilter]){
  def +++(that: Sieve): Sieve =
    Sieve((modules ++ that.modules).distinct)
}

object Sieve {
  def empty: Sieve = Sieve(Nil)
}

/**
 * {
 *   "organization": "commons-codec",
 *   "name": "commons-codec",
 *   "range": "[1.0,2.0]",
 *   "expiry": "2014-12-25 13:00:00"
 * }
 */
case class JModuleFilter(
  organization: String,
  name: String,
  range: String,
  expiry: String){
  import java.text.SimpleDateFormat
  import java.util.Date

  private def expiryDateFromString(str: String): Try[Date] =
    Try(new SimpleDateFormat("yyyy-MM-dd kk:mm:ss") parse(str))

  val endsAt: Date = expiryDateFromString(expiry).getOrElse(new Date)

  def isValid: Boolean = endsAt.after(new Date)
  def isExpired: Boolean = !isValid
}

abstract class Outcome(
  val underlying: Option[ModuleID],
  val raisesError: Boolean = false
)
final case class Restricted(module: ModuleID) extends Outcome(Option(module), true)
final case class Deprecated(module: ModuleID) extends Outcome(Option(module))
final case object Ignored extends Outcome(None)

object SieveOps {
  import org.apache.ivy.plugins.version.VersionRangeMatcher
  import org.apache.ivy.core.module.id.ModuleRevisionId
  import org.apache.ivy.plugins.latest.LatestRevisionStrategy

  type Message = Option[String]
  type ModuleOutcome = ModuleID => (Outcome, Message)

  private implicit val formats: Formats = DefaultFormats
  private val matcher = new VersionRangeMatcher("range", new LatestRevisionStrategy)

  private def flatten[T](xs: Seq[Try[T]]): Try[Seq[T]] = {
    val (ss: Seq[Success[T]]@unchecked, fs: Seq[Failure[T]]@unchecked) =
      xs.partition(_.isSuccess)

    if (fs.isEmpty) Success(ss map (_.get))
    else Failure[Seq[T]](fs(0).exception) // Only keep the first failure
  }

  private def messageWithRange(r: String, e: String): Option[String] =
    Option(s"Module within the exclusion range '$r' and expires/expired at $e.")

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

  /**
   * This is the edge of the world: call this function to "run" the plugin
   */
  def exe[A](ms: Seq[ModuleID], ts: Seq[Try[Sieve]]): Try[Seq[(Outcome, Message)]] =
    for {
      s <- flatten(ts).map(_.foldLeft(Sieve.empty)(_ +++ _))
    } yield s.modules.map(toModuleFilter).flatMap { case (mf,of) =>
      ms.filter(mf).map(of)
    }

  // for information on how to define the version ranges, please
  // read the ivy documentaiton here:
  // http://ant.apache.org/ivy/history/2.1.0/settings/version-matchers.html
  def toModuleFilter(f: JModuleFilter): (ModuleFilter, ModuleOutcome) = (
    (m: ModuleID) =>
      m.organization == f.organization &&
      m.name == f.name &&
      matcher.accept(
        ModuleRevisionId.newInstance(f.organization, f.name, f.range),
        ModuleRevisionId.newInstance(m.organization, m.name, m.revision)),
    (m: ModuleID) => ((if(f.isExpired) Restricted(m) else Deprecated(m)), messageWithRange(f.range, f.expiry))
  )

}
