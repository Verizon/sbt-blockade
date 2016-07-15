// The following was taken from sbt-dependency-graph, which has the following license:
/*
* Copyright 2015 Johannes Rudolph
*
*    Licensed under the Apache License, Version 2.0 (the "License");
*    you may not use this file except in compliance with the License.
*    You may obtain a copy of the License at
*
*        http://www.apache.org/licenses/LICENSE-2.0
*
*    Unless required by applicable law or agreed to in writing, software
*    distributed under the License is distributed on an "AS IS" BASIS,
*    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
*    See the License for the specific language governing permissions and
*    limitations under the License.
*/

package verizon.build

object depgraph {

  import sbt._
  import scala.language.reflectiveCalls
  import java.io.File
  import scala.collection.mutable.{MultiMap, HashMap, Set}
  import sbinary.{Format, DefaultProtocol}

  object SbtUpdateReport {

    type OrganizationArtifactReport = {
      def modules: Seq[ModuleReport]
    }

    def fromConfigurationReport(report: ConfigurationReport, rootInfo: sbt.ModuleID): ModuleGraph = {
      implicit def id(sbtId: sbt.ModuleID): ModuleId = ModuleId(sbtId.organization, sbtId.name, sbtId.revision)

      def moduleEdges(orgArt: OrganizationArtifactReport): Seq[(Module, Seq[Edge])] = {
        val chosenVersion = orgArt.modules.find(!_.evicted).map(_.module.revision)
        orgArt.modules.map(moduleEdge(chosenVersion))
      }

      def moduleEdge(chosenVersion: Option[String])(report: ModuleReport): (Module, Seq[Edge]) = {
        val evictedByVersion = if (report.evicted) chosenVersion else None
        val jarFile = report.artifacts.find(_._1.`type` == "jar").orElse(report.artifacts.find(_._1.extension == "jar")).map(_._2)
        (Module(
          id = report.module,
          license = report.licenses.headOption.map(_._1),
          evictedByVersion = evictedByVersion,
          jarFile = jarFile,
          error = report.problem
        ), report.callers.map(caller ⇒ Edge(caller.caller, report.module)))
      }

      val (nodes, edges) = report.details.flatMap(moduleEdges).unzip
      val root = Module(rootInfo)

      ModuleGraph(root +: nodes, edges.flatten)
    }
  }

  type Edge = (ModuleId, ModuleId)

  def Edge(from: ModuleId, to: ModuleId): Edge = from -> to

  case class ModuleId(organisation: String,
                      name: String,
                      version: String) {
    def idString: String = organisation + ":" + name + ":" + version
  }

  case class Module(id: ModuleId,
                    license: Option[String] = None,
                    extraInfo: String = "",
                    evictedByVersion: Option[String] = None,
                    jarFile: Option[File] = None,
                    error: Option[String] = None) {
    def hadError: Boolean = error.isDefined

    def isUsed: Boolean = !isEvicted

    def isEvicted: Boolean = evictedByVersion.isDefined
  }

  case class ModuleGraph(nodes: Seq[Module], edges: Seq[Edge]) {
    lazy val modules: Map[ModuleId, Module] =
      nodes.map(n ⇒ (n.id, n)).toMap

    def module(id: ModuleId): Module = modules(id)

    lazy val dependencyMap: Map[ModuleId, Seq[Module]] =
      createMap(identity)

    lazy val reverseDependencyMap: Map[ModuleId, Seq[Module]] =
      createMap { case (a, b) ⇒ (b, a) }

    def createMap(bindingFor: ((ModuleId, ModuleId)) ⇒ (ModuleId, ModuleId)): Map[ModuleId, Seq[Module]] = {
      val m = new HashMap[ModuleId, Set[Module]] with MultiMap[ModuleId, Module]
      edges.foreach { entry ⇒
        val (f, t) = bindingFor(entry)
        m.addBinding(f, module(t))
      }
      m.toMap.mapValues(_.toSeq.sortBy(_.id.idString)).withDefaultValue(Nil)
    }

    def roots: Seq[Module] =
      nodes.filter(n ⇒ !edges.exists(_._2 == n.id)).sortBy(_.id.idString)

    def isEmpty: Boolean = nodes.isEmpty
  }

}

