lazy val root = (project in file(".")).enablePlugins(SbtWeb)

libraryDependencies += "org.webjars" % "bootstrap" % "3.0.2"

pipelineStages := Seq(uglify)

UglifyKeys.uglifyOps := { js =>
  Seq(UglifyOps.UglifyOpGrouping(js.sortBy(_._2), "javascripts/concat.min.js", None, Some("javascripts/concat.min.js.map")))
}

val checkMapFileContents = taskKey[Unit]("check that map contents are correct")

checkMapFileContents := {
  val contents = IO.read(file("target/web/stage/javascripts/concat.min.js.map"))
  if (!contents.contains("""{"version":3,"sources":["a.js","b.js","x.js"],"names":["a","b","define","number","opposite","call","this"],"mappings":""") ||
    !contents.contains(""","file":"concat.min.js"}""")) {
    sys.error(s"Unexpected contents: $contents")
  }
}
