/**
 * Created by mohrlan on 6/23/16.
 */

package verizon.build

import scala.util.Try
import java.net.URI
import scala.io.Source
import net.liftweb.json._

object blockadeio {
  type JsonAsString = String

  def loadFromURLString(url: String): Try[JsonAsString] =
    loadFromURI(new URI(url))

  def loadFromURI(uri: URI): Try[JsonAsString] =
    Try(Source.fromURL(uri.toURL)).map(_.mkString)
}
