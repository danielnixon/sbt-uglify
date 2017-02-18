sbtPlugin := true

organization := "org.danielnixon"

name := "sbt-uglify"

version := "1.0.7-SNAPSHOT"

scalaVersion := "2.10.6"

scalacOptions += "-feature"

libraryDependencies ++= Seq(
  "org.webjars.npm" % "uglify-js" % "2.7.5",
  "io.monix" %% "monix" % "2.2.1"
)

resolvers ++= Seq(
  "Typesafe Releases Repository" at "http://repo.typesafe.com/typesafe/releases/",
  Resolver.url("sbt snapshot plugins", url("http://repo.scala-sbt.org/scalasbt/sbt-plugin-snapshots"))(Resolver.ivyStylePatterns),
  Resolver.sonatypeRepo("snapshots"),
  "Typesafe Snapshots Repository" at "http://repo.typesafe.com/typesafe/snapshots/",
  Resolver.mavenLocal
)

addSbtPlugin("com.typesafe.sbt" % "sbt-js-engine" % "1.1.4")
addSbtPlugin("com.typesafe.sbt" % "sbt-web" % "1.4.0")

description := "sbt-web UglifyJS plugin"
licenses := Seq("The Apache Software License, Version 2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt"))
publishMavenStyle := true
publishArtifact in Test := false
publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (isSnapshot.value)
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases"  at nexus + "service/local/staging/deploy/maven2")
}
homepage := Some(url("https://github.com/danielnixon/sbt-uglify"))
pomExtra := {
  <scm>
    <url>git@github.com:danielnixon/sbt-uglify.git</url>
    <connection>scm:git:git@github.com:danielnixon/sbt-uglify.git</connection>
  </scm>
    <developers>
      <developer>
        <id>danielnixon</id>
        <name>Daniel Nixon</name>
        <url>https://danielnixon.org/</url>
      </developer>
    </developers>
}

scriptedSettings

scriptedLaunchOpts += s"-Dproject.version=${version.value}"
