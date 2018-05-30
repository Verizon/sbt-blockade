package verizon.build

import java.text.SimpleDateFormat
import java.util.Date
import org.scalatest.{FreeSpec, MustMatchers}
import sbt.ModuleID
import scala.util.{Failure, Success}

class BlockadeSpec extends FreeSpec with MustMatchers {

  import BlockadeOps._
  import Fixtures._

  def df = new SimpleDateFormat("yyyy-MM-dd kk:mm:ss")

  def time(l: Long): String = df.format(new Date(l))

  def tomorrow: String = time(System.currentTimeMillis + 86400000)

  def yesterday: String = time(System.currentTimeMillis - 86400000)

  object check {
    def apply(defined: Seq[ModuleID], expected: Seq[Outcome])(json: String) = {
      process(defined)(json) must equal(expected.toList)
    }

    def process(defined: Seq[ModuleID])(json: String): Seq[Outcome] = {
      val blockade: Blockade = BlockadeOps.parseBlockade(json) match {
        case Failure(_) => fail("Problem parsing test json.")
        case Success(x) => x
      }
      val fos = BlockadeOps.filterAndOutcomeFns(blockade)
      analyseImmediateDeps(defined, fos).map(_._1)
    }
  }

  "module restriction and deprecation" - {
    "whitelisting" - {
      "inside of acceptable range is allowed" in {
        check(
          defined = Seq(`scalaz-core-7.1.5`),
          expected = Seq()) {
          s"""
             |{
             |  "whitelist": [
             |    {
             |      "organization": "org.scalaz",
             |      "name": "scalaz-core",
             |      "range": "[7.1.0, 7.2.0["
             |    }
             |  ],
             |  "blacklist": []
             |}
         """.stripMargin
        }
      }
     "lower than acceptable range is restricted" in {
       check(
         defined = Seq(`scalaz-core-7.0.4`),
         expected = Seq(Outcome.Restricted(`scalaz-core-7.0.4`))) {
         s"""
            |{
            |  "whitelist": [
            |    {
            |      "organization": "org.scalaz",
            |      "name": "scalaz-core",
            |      "range": "[7.1.0, 7.2.0["
            |    }
            |  ],
            |  "blacklist": []
            |}
         """.stripMargin
       }
     }

      "higher than acceptable range is restricted" in {
        check(
          defined = Seq(`scalaz-core-7.2.0`),
          expected = Seq(Outcome.Restricted(`scalaz-core-7.2.0`))) {
          s"""
             |{
             |  "whitelist": [
             |    {
             |      "organization": "org.scalaz",
             |      "name": "scalaz-core",
             |      "range": "[7.1.0, 7.2.0["
             |    }
             |  ],
             |  "blacklist": []
             |}
         """.stripMargin
        }
      }
    }
    "blacklisting" - {
      "available 1, deprecate 1, restrict 0, ignore 0" in {
        check(
          defined = Seq(`commons-codec-1.9`),
          expected = Seq(Outcome.Deprecated(`commons-codec-1.9`))) {
          s"""
             |{
             |  "whitelist": [],
             |  "blacklist": [
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

      "available 1, deprecate 0, restrict 1, ignore 0" in {
        check(
          defined = Seq(`commons-codec-1.9`),
          expected = Seq(Outcome.Restricted(`commons-codec-1.9`))) {
          s"""
             |{
             |  "whitelist": [],
             |  "blacklist": [
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

      "available 2, deprecate 1, restrict 0, ignore 0" in {
        check(
          defined = Seq(`commons-io-2.2`, `commons-codec-1.9`),
          expected = Seq(Outcome.Deprecated(`commons-codec-1.9`))) {
          s"""
             |{
             |  "whitelist": [],
             |  "blacklist": [
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

      "available 1, deprecate 0, restrict 0, ignore 1 with fixed version and lower bound" in {
        check(
          defined = Seq(`funnel-1.3.71`),
          expected = Seq.empty) {
          s"""
             |{
             |  "whitelist": [],
             |  "blacklist": [
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

      "available 1, deprecate 0, restrict 0, ignore 1 with dynamic version and lower bound" in {
        check(
          defined = Seq(`funnel-1.3.+`),
          expected = Seq.empty) {
          s"""
             |{
             |  "whitelist": [],
             |  "blacklist": [
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

      "available 1, deprecate 0, restrict 0, ignore 1 with dynamic version defined and dynamic lower bound" in {
        check(
          defined = Seq(`funnel-1.3.+`),
          expected = Seq.empty) {
          s"""
             |{
             |  "whitelist": [],
             |  "blacklist": [
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

      "available 1, deprecate 0, restrict 1, ignore 0 with dynamic version defined and fixed lower bound" in {
        check(
          defined = Seq(`funnel-1.3.+`),
          expected = Seq(Outcome.Restricted(`funnel-1.3.+`))) {
          s"""
             |{
             |  "whitelist": [],
             |  "blacklist": [
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

      "available 1, deprecate 0, restrict 0, ignore 1 with dynamic version defined and dynamic upper bound" in {
        check(
          defined = Seq(`funnel-1.3.+`),
          expected = Seq.empty) {
          s"""
             |{
             |  "whitelist": [],
             |  "blacklist": [
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

      "available 2, deprecate 0, restrict 1, ignore 0" in {
        check(
          defined = Seq(`commons-io-2.2`, `commons-codec-1.9`),
          expected = Seq(Outcome.Restricted(`commons-codec-1.9`))) {
          s"""
             |{
             |  "whitelist": [],
             |  "blacklist": [
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

      "when blacklist item's range is null, all versions of the item are restricted" in {
        check(
          defined = Seq(`commons-io-2.2`, `commons-codec-2.9`, `commons-codec-1.9`),
          expected = Seq(Outcome.Restricted(`commons-codec-2.9`), Outcome.Restricted(`commons-codec-1.9`))) {
          s"""
             |{
             |  "whitelist": [],
             |  "blacklist": [
             |    {
             |      "organization": "commons-codec",
             |      "name": "commons-codec",
             |      "range": null,
             |      "expiry": "${yesterday}"
             |    }
             |  ]
             |}
      """.stripMargin
        }
      }

      "when blacklist item's range is missing, all versions of the item are restricted" in {
        check(
          defined = Seq(`commons-io-2.2`, `commons-codec-2.9`, `commons-codec-1.9`),
          expected = Seq(Outcome.Restricted(`commons-codec-2.9`), Outcome.Restricted(`commons-codec-1.9`))) {
          s"""
             |{
             |  "whitelist": [],
             |  "blacklist": [
             |    {
             |      "organization": "commons-codec",
             |      "name": "commons-codec",
             |      "expiry": "${yesterday}"
             |    }
             |  ]
             |}
      """.stripMargin
        }
      }

      "available 4, deprecate 1, restrict 1, ignore 2" in {
        check(
          defined = Seq(
            `commons-codec-1.9`, // ignore    (newer than the restricted range)
            `commons-net-3.3`, // ignore    (newer than the restricted range)
            `commons-lang-2.2`, // deprecate (within range, expires tomorrow)
            `commons-io-2.2` // restrict  (less than the restricted rage)
          ),
          expected = Seq(Outcome.Restricted(`commons-io-2.2`), Outcome.Deprecated(`commons-lang-2.2`))) {
          s"""
             |{
             |  "whitelist": [],
             |  "blacklist": [
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

    }
    "both whitelisting and blacklisting" - {
      "passes blacklist, but fails whitelist is restricted" in {
        check(
          defined = Seq(
            `commons-codec-1.9`, // restrict  (newer than the restricted range, BUT outside of package's whitelist)
            `commons-net-3.3`, // ignore    (newer than the restricted range)
            `commons-lang-2.2`, // deprecate (within range, expires tomorrow)
            `commons-io-2.2` // restrict  (less than the restricted rage)
          ),
          expected = Seq(
            Outcome.Restricted(`commons-io-2.2`),
            Outcome.Deprecated(`commons-lang-2.2`),
            Outcome.Restricted(`commons-codec-1.9`)
          )) {
          s"""
             |{
             |  "whitelist": [
             |    {
             |      "organization": "commons-codec",
             |      "name": "commons-codec",
             |      "range": "[2.0,3.0["
             |    },
             |  ],
             |  "blacklist": [
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

    }

  }


  "blockade definition failure modes" - {
    "fail with a meaningful message when the blockade is invalid" in {
      BlockadeOps.parseBlockade(
        s"""
           |this will never parse
      """.stripMargin
      ).isInstanceOf[Failure[_]] must equal(true)
    }

  }

}
