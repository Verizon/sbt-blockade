
resolvers += "internal.nexus" at "http://nexus.oncue.verizon.net/nexus/content/groups/internal"

libraryDependencies <+= (sbtVersion) { sv =>
  "org.scala-sbt" % "scripted-plugin" % sv
}

addSbtPlugin("verizon.inf.build" % "sbt-verizon" % "0.20.127")
