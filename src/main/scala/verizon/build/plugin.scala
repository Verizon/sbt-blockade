package verizon.build

import sbt._
import sbt.Keys._
import scala.concurrent.duration._
import depgraph._

object SieveKeys {

  import java.net.URL

  val enforcementInterval = SettingKey[Duration]("sieve-enforcement-interval")
  val cacheFile = SettingKey[File]("sieve-cache-file")
  val sieves = SettingKey[Seq[URL]]("sieve-urls")
  val sieve = TaskKey[Unit]("sieve")
  val dependencyGraphCrossProjectId = SettingKey[ModuleID]("dependency-graph-cross-project-id")
}

object SievePlugin {

  import SieveKeys._
  import scala.Console.{CYAN, RED, YELLOW, GREEN, RESET}
  import scala.util.{Try, Failure, Success}

  import scala.io.Source
  import aux._
  import SieveOps._

  def display(name: String, so: Seq[(Outcome, Message)]): String = {
    CYAN + s"[$name] The following dependencies were caught in the sieve: " + RESET +
      so.distinct.map {
        case (Restricted(m), msg) => RED + s"Restricted: ${m.toString}. $msg" + RESET
        case (Deprecated(m), msg) => YELLOW + s"Deprecated: ${m.toString}. $msg" + RESET
        case (o, m) => "Unkonwn input to sieve display."
      }.mkString("\n\t", ",\n\t", "")
  }

  private def dependenciesOK(name: String, transitive: Boolean = false) =
    GREEN + s"[$name] All ${if (transitive) "transitive " else "direct"} dependencies are within current restrictions." + RESET

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
    (sbt.Keys.update, dependencyGraphCrossProjectId, sbt.Keys.configuration in Compile) map { (update, root, config) ⇒
      SbtUpdateReport.fromConfigurationReport(update.configuration(config.name).get, root)
    }

  def settings: Seq[Def.Setting[_]] = Seq(
    dependencyGraphCrossProjectId <<= (Keys.scalaVersion, Keys.scalaBinaryVersion, Keys.projectID) ((sV, sBV, id) ⇒ CrossVersion(sV, sBV)(id)),
    cacheFile := target.value / "sieved",
    enforcementInterval := 30.minutes,
    sieves := Seq.empty,
    skip in sieve := {
      val f = cacheFile.value
      if (f.exists) readCheckFile(f) else false
    },
    sieve := {
      val log = streams.value.log

      if (!(skip in sieve).value) {
        val triedSieves: Try[Seq[Sieve]] = flattenTrys(sieves.value.map(url => sieveio.loadFromURL(url).flatMap(SieveOps.parseSieve)))
        val deps: Seq[ModuleID] = (libraryDependencies in Compile).value
        val graph: ModuleGraph = moduleGraphSbtTask.value
        triedSieves.map((sieves: Seq[Sieve]) => SieveOps.exe(deps, sieves, graph)) match {
          case Failure(_: java.net.UnknownHostException) => ()

          case Failure(e) =>
            log.error(s"Unable to execute the specified sieves because an error occurred: $e")

          case Success((immediateOutcomes, maybeWarning)) =>
            immediateOutcomes.toList match {
              case Nil =>
                writeCheckFile(cacheFile.value, enforcementInterval.value)
                log.info(dependenciesOK(name.value))
              case list => {
                log.warn(display(name.value, list))
                if (list.exists(_._1.raisesError == true))
                  sys.error("One or more of the specified immediate dependencies are restricted.")
                else ()
              }
            }
            maybeWarning match {
              case None =>
                log.info(dependenciesOK(name = name.value, transitive = true))
              case Some(w) =>
                log.warn(YELLOW + s"[${name.value}]" + showWarnings(w) + RESET)
            }
        }
      } else {
        () // do nothing here as the project has already been sieved recently.
      }
    }
  )
}
