package verizon.build

import sbt._
import sbt.Keys._
import scala.concurrent.duration._

object SieveKeys {
  import java.net.URL

  val enforcementInterval = SettingKey[Duration]("sieve-enforcement-interval")
  val cacheFile           = SettingKey[File]("sieve-cache-file")
  val sieves              = SettingKey[Seq[URL]]("sieve-urls")
  val sieve               = TaskKey[Unit]("sieve")
}

object SievePlugin {
  import SieveKeys._
  import SieveOps._
  import scala.Console.{CYAN,RED,YELLOW,GREEN,RESET}
  import scala.util.{Try,Failure,Success} // poor mans scalaz.\/
  import scala.io.Source

  def display(name: String, so: Seq[(Outcome,Message)]): String = {
    CYAN + s"[$name] The following dependencies were caught in the sieve: " + RESET +
    so.distinct.map {
      case (Restricted(m), Some(msg)) => RED + s"Restricted: ${m.toString}. $msg" + RESET
      case (Deprecated(m), Some(msg)) => YELLOW + s"Deprecated: ${m.toString}. $msg" + RESET
      case (o, m) => "Unkonwn input to sieve display."
    }.mkString("\n\t",",\n\t","")
  }

  private def dependenciesOK(name: String) =
    GREEN + s"[$name] All dependencies are within current restrictions." + RESET

  private def writeCheckFile(f: File, period: Duration): Unit = {
    val contents = System.nanoTime + period.toNanos
    IO.write(f, contents.toString.getBytes("UTF-8"))
  }

  private def readCheckFile(f: File): Boolean =
    (for {
      a <- Try(Source.fromFile(f).mkString.toLong)
      b <- Try(Duration.fromNanos(a).toNanos)
    } yield b > System.nanoTime).getOrElse(true)

  def settings: Seq[Def.Setting[_]] = Seq(
    update               <<= update.dependsOn(sieve),
    cacheFile             := target.value / "sieved",
    enforcementInterval   := 30.minutes,
    sieves                := Seq.empty,
    skip in sieve         := {
      val f = cacheFile.value
      if(f.exists) readCheckFile(f) else false
    },
    sieve                 := {
      val log = streams.value.log

      if(!(skip in sieve).value){
        SieveOps.exe((libraryDependencies in Compile).value, sieves.value.map(loadFromURL)) match {
        case Failure(_: java.net.UnknownHostException) => ()

        case Failure(e) =>
          log.error(s"Unable to execute the specified sieves because an error occoured: $e")

        case Success(y) =>
          y.toList match {
            case Nil  =>
              writeCheckFile(cacheFile.value, enforcementInterval.value)
              log.info(dependenciesOK(name.value))
            case list => {
              log.warn(display(name.value, list))
              if(list.exists(_._1.raisesError == true)) sys.error("One or more of the specified dependencies are restricted.")
              else ()
            }
          }
        }
      } else {
        () // do nothing here as the project has already been sieved recently.
      }
    }
  )
}
