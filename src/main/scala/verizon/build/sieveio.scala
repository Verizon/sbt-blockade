/**
 * Created by mohrlan on 6/23/16.
 */

package verizon.build

import scala.util.Try
import java.net.URL
import scala.io.Source
import net.liftweb.json._

object sieveio {
  type JsonAsString = String

  def loadFromURLString(url: String): Try[JsonAsString] =
    loadFromURL(new URL(url))

  def loadFromURL(url: URL): Try[JsonAsString] =
    Try(Source.fromURL(url)).map(_.toString)
}
