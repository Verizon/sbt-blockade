package verizon.build

import sbt._
import sbt.Keys._
import scala.concurrent.duration._
import depgraph._

object SievePlugin extends AutoPlugin { self =>

  object autoImport {
    import java.net.URI

    val sieveEnforcementInterval = settingKey[Duration]("sieve-enforcement-interval")
    val sieveCacheFile = settingKey[File]("sieve-cache-file")
    val sieveUris = settingKey[Seq[URI]]("sieve-uris")
    val sieve = taskKey[Unit]("sieve")
    val sieveDependencyGraphCrossProjectId = settingKey[ModuleID]("sieve-dependency-graph-cross-project-id")
  }

  import autoImport._

  /** sbt auto-plugin stuffs **/
  override def trigger = allRequirements
  override lazy val projectSettings = self.settings
  override lazy val globalSettings: Seq[Setting[_]] = Seq(
    sieveUris := Nil
  )

  /** actual plugin content **/
  import scala.Console.{CYAN, RED, YELLOW, GREEN, RESET}
  import scala.util.{Try, Failure, Success}

  import scala.io.Source
  import SieveOps._

  private def dependenciesOK(name: String, transitive: Boolean = false): String =
    GREEN + s"[$name] All ${if (transitive) "transitive" else "direct"} dependencies are within current restrictions." + RESET

  private def writeCheckFile(f: File, period: Duration): Unit = {
    val contents = System.nanoTime + period.toNanos
    IO.write(f, contents.toString.getBytes("UTF-8"))
  }

  private def readCheckFile(f: File): Boolean =
    (for {
      a <- Try(Source.fromFile(f).mkString.toLong)
      b <- Try(Duration.fromNanos(a).toNanos)
    } yield b > System.nanoTime).getOrElse(true)

  private def flattenTrys[T](xs: Seq[Try[T]]): Try[Seq[T]] = {
    val (ss: Seq[Success[T]]@unchecked, fs: Seq[Failure[T]]@unchecked) =
      xs.partition(_.isSuccess)

    if (fs.isEmpty) Success(ss map (_.get))
    else Failure[Seq[T]](fs(0).exception) // Only keep the first failure
  }

  val moduleGraphSbtTask =
    (sbt.Keys.update, sieveDependencyGraphCrossProjectId, sbt.Keys.configuration in Compile) map { (update, root, config) ⇒
      SbtUpdateReport.fromConfigurationReport(update.configuration(config.name).get, root)
    }

  def settings: Seq[Def.Setting[_]] = Seq(
    sieveDependencyGraphCrossProjectId <<= (Keys.scalaVersion, Keys.scalaBinaryVersion, Keys.projectID) ((sV, sBV, id) ⇒ CrossVersion(sV, sBV)(id)),
    sieveCacheFile := target.value / "sieved",
    sieveEnforcementInterval := 30.minutes,
    sieveUris := Seq.empty,
    skip in sieve := {
      val f = sieveCacheFile.value
      if (f.exists) readCheckFile(f) else false
    },
    sieve := {
      val log = streams.value.log

      // If the project has not been sieved recently, we attempt to sieve and display results.
      if (!(skip in sieve).value) {
        val parsedSieveItems: Try[Seq[Sieve]] = flattenTrys(sieveUris.value.map(url => sieveio.loadFromURI(url).flatMap(SieveOps.parseSieve)))
        val deps: Seq[ModuleID] = (libraryDependencies in Compile).value
        val graph: ModuleGraph = moduleGraphSbtTask.value
        parsedSieveItems.map((sieves: Seq[Sieve]) => SieveOps.analyseDeps(deps, sieves, graph)) match {
          case Failure(_: java.net.UnknownHostException) => ()

          case Failure(e) =>
            log.error(s"Unable to execute the specified sieves because an error occurred: $e")

          // If we succeed in parsing the Sieves, we evaluate the dependency graph and display the results.
          case Success((immediateOutcomes, maybeWarning)) =>
            immediateOutcomes.toList match {
              case Nil =>
                writeCheckFile(sieveCacheFile.value, sieveEnforcementInterval.value)
                log.info(dependenciesOK(name.value))
              case list => {
                log.warn(showImmediateDepResults(name.value, list))
                if (list.exists(_._1.raisesError == true))
                  sys.error("One or more of the specified immediate dependencies are restricted.")
                else ()
              }
            }
            maybeWarning match {
              case None =>
                log.info(dependenciesOK(name = name.value, transitive = true))
              case Some(w) =>
                log.warn(YELLOW + s"[${name.value}]" + showTransitiveDepResults(w) + RESET)
            }
        }
      } else {
        // Do nothing since the project has been sieved recently.
        ()
      }
    }
  )
}
