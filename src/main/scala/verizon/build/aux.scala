package verizon.build

object aux {

  import sbt._
  import scala.language.reflectiveCalls
  import java.io.File
  import scala.collection.mutable.{MultiMap, HashMap, Set}
  import sbinary.{Format, DefaultProtocol}
  import depgraph._

  def toModuleId(smid: sbt.ModuleID): ModuleId = {
    ModuleId(
      organisation = smid.organization,
      name = smid.name,
      version = smid.revision
    )
  }

  def toModuleID(mid: ModuleId): sbt.ModuleID = {
    mid.organisation %% mid.name % mid.version
  }

  def stripUnderscores(g: ModuleGraph): ModuleGraph = {
    def stripUnderscore(mid: ModuleId): ModuleId = mid.copy(name = mid.name.takeWhile(_ != '_'))
    g.copy(
      nodes = g.nodes.map(n => n.copy(id = stripUnderscore(n.id))),
      edges = g.edges.map {
        case (from, to) => stripUnderscore(from) -> stripUnderscore(to)
      }
    )
  }

  def removeNodes(g: ModuleGraph, nodesForRemovalIds: Seq[ModuleId]): ModuleGraph = {

    val updatedNodes = g.nodes.filter(n => !nodesForRemovalIds.contains(n.id))

    val updatedEdges = g.edges.filter(e => !nodesForRemovalIds.contains(e._1))

    ModuleGraph(updatedNodes, updatedEdges)
  }

  def transpose(g: ModuleGraph): ModuleGraph = {
    g.copy(edges = g.edges.map { case (from, to) => (to, from) })
  }

  final case class RestrictionWarning(fromCauseToRoot: Seq[ModuleId], rangeMessage: String)

  type PathToRoot = Seq[ModuleId]

  def getPathToRoot(sortedIds: Seq[ModuleId], from: ModuleId, edges: Seq[Edge]): PathToRoot = {
    require(sortedIds.head == from) // YOLO
    edges.find(_._1 == from).map(_._2).fold(Seq(from))(
      next => from +: getPathToRoot(sortedIds.dropWhile(_ != next), next, edges)
    )
  }

  def showWarnings(w: RestrictionWarning): String = {
    val path = w.fromCauseToRoot.reverse
    def go(indent: Int, remaining: Seq[ModuleId], acc: String): String = remaining match {
      case Nil =>
        acc.stripMargin
      case x +: xs =>
        go(indent + 2, xs, acc + List.fill(indent)(' ').mkString + x.idString + '\n')
    }
    val preamble =
      s"""
         |${path.head.idString} has a restricted transitive dependency: ${path.last.idString}
         |  ${w.rangeMessage}
         |
         |Here is the dependency chain:
         |""".stripMargin
    preamble + go(2, path, "")
  }

  def topoSort(g: ModuleGraph): Seq[ModuleId] = {
    def go(curGraph: ModuleGraph, acc: Seq[ModuleId]): Seq[ModuleId] = {
      if (curGraph.isEmpty) acc
      else {
        val roots = curGraph.roots.map(_.id)
        go(removeNodes(curGraph, roots), acc ++ roots)
      }
    }

    go(g, Seq.empty)
  }
}
