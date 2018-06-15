//: ----------------------------------------------------------------------------
//: Copyright 2015 Johannes Rudolph
//:
//: Distributed under the Apache 2.0 License, please see the NOTICE
//: file in the root of the project for further details.
//: ----------------------------------------------------------------------------
package verizon.build

object depgraph {

  import java.io.File
  import sbt._
  import scala.collection.mutable.{HashMap, MultiMap, Set}
  import scala.language.reflectiveCalls

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

