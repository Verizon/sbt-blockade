package verizon.build

import org.scalatest.{FlatSpec,Matchers}
import scala.util.{Try,Failure,Success}
import sbt.ModuleID
import java.text.SimpleDateFormat
import java.util.Date

class SieveSpec extends FlatSpec with Matchers {
  import Fixtures._
  import SieveOps._

  val df = new SimpleDateFormat("yyyy-MM-dd kk:mm:ss")
  def time(l: Long): String = df.format(new Date(l))
  def tomorrow: String = time(System.currentTimeMillis + 86400000)
  def yesterday: String = time(System.currentTimeMillis - 86400000)

  def check(defined: Seq[ModuleID], expected: Seq[Outcome])(json: String) =
    process(defined.toList)(json).get should equal (expected.toList)

  // hack to remove messages for testsing
  def process(defined: Seq[ModuleID])(json: String): Try[Seq[Outcome]] =
    exe(defined.toList, Seq(loadFromString(json))).map(s => s.map(_._1))

  behavior of "Module restriction and deprecation"

  it should "avalible 1, deprecate 1, restrict 0, ignore 0" in {
    check(
      defined  = Seq(`commons-codec-1.9`),
      expected = Seq(Deprecated(`commons-codec-1.9`))){
      s"""
      |{
      |  "modules": [
      |    {
      |      "organization": "commons-codec",
      |      "name": "commons-codec",
      |      "range": "[1.0,2.0]",
      |      "expiry": "${tomorrow}"
      |    }
      |  ]
      |}
      """.stripMargin
    }
  }

  it should "avalible 1, deprecate 0, restrict 1, ignore 0" in {
    check(
      defined  = Seq(`commons-codec-1.9`),
      expected = Seq(Restricted(`commons-codec-1.9`))){
      s"""
      |{
      |  "modules": [
      |    {
      |      "organization": "commons-codec",
      |      "name": "commons-codec",
      |      "range": "[1.0,2.0]",
      |      "expiry": "${yesterday}"
      |    }
      |  ]
      |}
      """.stripMargin
    }
  }

  it should "avalible 2, deprecate 1, restrict 0, ignore 0" in {
    check(
      defined  = Seq(`commons-io-2.2`, `commons-codec-1.9`),
      expected = Seq(Deprecated(`commons-codec-1.9`))){
      s"""
      |{
      |  "modules": [
      |    {
      |      "organization": "commons-codec",
      |      "name": "commons-codec",
      |      "range": "[1.0,2.0]",
      |      "expiry": "${tomorrow}"
      |    }
      |  ]
      |}
      """.stripMargin
    }
  }

  it should "avalible 1, deprecate 0, restrict 0, ignore 1 with fixed version and lower bound" in {
    check(
      defined  = Seq(`funnel-1.3.71`),
      expected = Seq.empty){
      s"""
      |{
      |  "modules": [
      |    {
      |      "organization": "commons-codec",
      |      "name": "commons-codec",
      |      "range": "[1.3.50,)",
      |      "expiry": "${yesterday}"
      |    }
      |  ]
      |}
      """.stripMargin
    }
  }

  it should "avalible 1, deprecate 0, restrict 0, ignore 1 with dynamic version and lower bound" in {
    check(
      defined  = Seq(`funnel-1.3.+`),
      expected = Seq.empty){
      s"""
      |{
      |  "modules": [
      |    {
      |      "organization": "commons-codec",
      |      "name": "commons-codec",
      |      "range": "[1.3.+,)",
      |      "expiry": "${yesterday}"
      |    }
      |  ]
      |}
      """.stripMargin
    }
  }

  it should "avalible 1, deprecate 0, restrict 0, ignore 1 with dynamic version defined and dynamic lower bound" in {
    check(
      defined  = Seq(`funnel-1.3.+`),
      expected = Seq.empty){
      s"""
      |{
      |  "modules": [
      |    {
      |      "organization": "commons-codec",
      |      "name": "commons-codec",
      |      "range": "[1.2.+,)",
      |      "expiry": "${yesterday}"
      |    }
      |  ]
      |}
      """.stripMargin
    }
  }

  it should "avalible 1, deprecate 0, restrict 1, ignore 0 with dynamic version defined and fixed lower bound" in {
    check(
      defined  = Seq(`funnel-1.3.+`),
      expected = Seq(Restricted(`funnel-1.3.+`))){
      s"""
      |{
      |  "modules": [
      |    {
      |      "organization": "intelmedia.ws.funnel",
      |      "name": "http",
      |      "range": "[1.3.0,)",
      |      "expiry": "${yesterday}"
      |    }
      |  ]
      |}
      """.stripMargin
    }
  }

  it should "avalible 1, deprecate 0, restrict 0, ignore 1 with dynamic version defined and dynamic upper bound" in {
    check(
      defined  = Seq(`funnel-1.3.+`),
      expected = Seq.empty){
      s"""
      |{
      |  "modules": [
      |    {
      |      "organization": "intelmedia.ws.funnel",
      |      "name": "http",
      |      "range": "(,1.3.+[",
      |      "expiry": "${yesterday}"
      |    }
      |  ]
      |}
      """.stripMargin
    }
  }

  it should "avalible 2, deprecate 0, restrict 1, ignore 0" in {
    check(
      defined  = Seq(`commons-io-2.2`, `commons-codec-1.9`),
      expected = Seq(Restricted(`commons-codec-1.9`))){
      s"""
      |{
      |  "modules": [
      |    {
      |      "organization": "commons-codec",
      |      "name": "commons-codec",
      |      "range": "[1.0,2.0]",
      |      "expiry": "${yesterday}"
      |    }
      |  ]
      |}
      """.stripMargin
    }
  }

  it should "avalible 4, deprecate 1, restrict 1, ignore 2" in {
    check(
      defined  = Seq(
        `commons-codec-1.9`, // ignore    (newer than the restricted range)
        `commons-net-3.3`,   // ignore    (newer than the restricted range)
        `commons-lang-2.2`,  // deprecate (within range, expires tomorrow)
        `commons-io-2.2`     // restrict  (less than the restricted rage)
      ),
      expected = Seq(Restricted(`commons-io-2.2`), Deprecated(`commons-lang-2.2`))){
      s"""
      |{
      |  "modules": [
      |    {
      |      "organization": "commons-codec",
      |      "name": "commons-codec",
      |      "range": "[1.0,1.6]",
      |      "expiry": "${tomorrow}"
      |    },
      |    {
      |      "organization": "commons-io",
      |      "name": "commons-io",
      |      "range": "(,2.4[",
      |      "expiry": "${yesterday}"
      |    },
      |    {
      |      "organization": "commons-net",
      |      "name": "commons-net",
      |      "range": "(,3.0[",
      |      "expiry": "${tomorrow}"
      |    },
      |    {
      |      "organization": "commons-lang",
      |      "name": "commons-lang",
      |      "range": "[1.0,2.3]",
      |      "expiry": "${tomorrow}"
      |    }
      |  ]
      |}
      """.stripMargin
    }
  }

  behavior of "Sieve definition failure modes"

  it should "fail with a meaningful message when the sieve is invalid" in {
    process(Seq(`commons-io-2.2`, `commons-codec-1.9`)){
      s"""
      |this will never parse
      """.stripMargin
    }.isInstanceOf[Failure[_]] should equal (true)
  }
}
