package com.typesafe.sbt.uglify

import sbt._
import sbt.Keys._
import com.typesafe.sbt.web.{incremental, SbtWeb, PathMapping}
import com.typesafe.sbt.web.pipeline.Pipeline
import com.typesafe.sbt.jse.{SbtJsEngine, SbtJsTask}
import com.typesafe.sbt.web.incremental._
import sbt.Task

object Import {

  val uglify = TaskKey[Pipeline.Stage]("uglify", "Perform UglifyJS optimization on the asset pipeline.")

  object UglifyKeys {
    val buildDir = SettingKey[File]("uglify-build-dir", "Where UglifyJS will copy source files and write minified files to. Default: resourceManaged / build")
    val comments = SettingKey[Option[String]]("uglify-comments", "Specifies comments handling. Default: None")
    val compress = SettingKey[Boolean]("uglify-compress", "Enables compression. Default: true")
    val compressOptions = SettingKey[Seq[String]]("uglify-compress-options", "Options for compression such as hoist_vars, if_return etc. Default: Nil")
    val define = SettingKey[Option[String]]("uglify-define", "Define globals. Default: None")
    val enclose = SettingKey[Boolean]("uglify-enclose", "Enclose in one big function. Default: false")
    val includeSource = SettingKey[Boolean]("uglify-include-source", "Include the content of source files in the source map as the sourcesContent property. Default: false")
    val mangle = SettingKey[Boolean]("uglify-mangle", "Enables name mangling. Default: true")
    val mangleOptions = SettingKey[Seq[String]]("uglify-mangle-options", "Options for mangling such as sort, topLevel etc. Default: Nil")
    val preamble = SettingKey[Option[String]]("uglify-preamble", "Any preamble to include at the start of the output. Default: None")
    val reserved = SettingKey[Seq[String]]("uglify-reserved", "Reserved names to exclude from mangling. Default: Nil")
    val uglifyOps = SettingKey[UglifyOps.UglifyOpsMethod]("uglify-ops", "A function defining how to combine input files into output files. Default: UglifyOps.singleFileWithSourceMapOut")
  }

  object UglifyOps {
    /** A list of input files mapping to a single output file. */
    case class UglifyOpGrouping(inputFiles: Seq[PathMapping], outputFile: String, inputMapFile: Option[PathMapping], outputMapFile: Option[String])
    type UglifyOpsMethod = (Seq[PathMapping]) => Seq[UglifyOpGrouping]

    def dotMin(file: String): String = {
      val exti = file.lastIndexOf('.')
      val (pfx, ext) = if (exti == -1) (file, "")
      else file.splitAt(exti)
      pfx + ".min" + ext
    }

    /** Use when uglifying single files */
    val singleFile: UglifyOpsMethod = { mappings =>
      mappings.map(fp => UglifyOpGrouping(Seq(fp), dotMin(fp._2), None, None))
    }

    /** Use when uglifying single files and you want a source map out */
    val singleFileWithSourceMapOut: UglifyOpsMethod = { mappings =>
      mappings.map(fp => UglifyOpGrouping(Seq(fp), dotMin(fp._2), None, Some(dotMin(fp._2) + ".map")))
    }

    /** Use when uglifying single files and you want a source map in and out - remember to includeFilter .map files */
    val singleFileWithSourceMapInAndOut: UglifyOpsMethod = { mappings =>
      val sources = mappings.filter(source => source._2.endsWith(".js"))
      val sourceMaps = mappings.filter(sourceMap => sourceMap._2.endsWith(".js.map"))

      sources.map { source =>
        UglifyOpGrouping(
          Seq(source),
          dotMin(source._2),
          sourceMaps.find(sourceMap =>
            sourceMap._2 equals (source._2 + ".map")
          ),
          Some(dotMin(source._2) + ".map")
        )
      }
    }
  }
}

object SbtUglify extends AutoPlugin {

  override def requires = SbtJsTask

  override def trigger = AllRequirements

  val autoImport = Import

  import SbtWeb.autoImport._
  import WebKeys._
  import SbtJsEngine.autoImport.JsEngineKeys._
  import SbtJsTask.autoImport.JsTaskKeys._
  import autoImport._
  import UglifyKeys._
  import UglifyOps._

  override def projectSettings = Seq(
    buildDir := (resourceManaged in uglify).value / "build",
    comments := None,
    compress := true,
    compressOptions := Nil,
    define := None,
    enclose := false,
    excludeFilter in uglify := new SimpleFileFilter({
      f =>
        def fileStartsWith(dir: File): Boolean = f.getPath.startsWith(dir.getPath)
        fileStartsWith((resourceDirectory in Assets).value) || fileStartsWith((WebKeys.webModuleDirectory in Assets).value)
    }),
    includeFilter in uglify := GlobFilter("*.js"),
    includeSource := false,
    resourceManaged in uglify := webTarget.value / uglify.key.label,
    mangle := true,
    mangleOptions := Nil,
    preamble := None,
    reserved := Nil,
    uglify := runOptimizer.dependsOn(webJarsNodeModules in Plugin).value,
    uglifyOps := singleFileWithSourceMapOut
  )

  private def runOptimizer: Def.Initialize[Task[Pipeline.Stage]] = Def.task {
    mappings =>

      val include = (includeFilter in uglify).value
      val exclude = (excludeFilter in uglify).value
      val optimizerMappings = mappings.filter(f => !f._1.isDirectory && include.accept(f._1) && !exclude.accept(f._1))

      SbtWeb.syncMappings(
        streams.value.cacheDirectory,
        optimizerMappings,
        buildDir.value
      )
      val appInputMappings = optimizerMappings.map(p => buildDir.value / p._2 -> p._2)
      val groupings = uglifyOps.value(appInputMappings)

      val options = Seq(
        comments.value,
        compress.value,
        compressOptions.value,
        define.value,
        enclose.value,
        (excludeFilter in uglify).value,
        (includeFilter in uglify).value,
        (resourceManaged in uglify).value,
        mangle.value,
        mangleOptions.value,
        preamble.value,
        reserved.value,
        includeSource.value
      ).mkString("|")

      implicit val opInputHasher = OpInputHasher[UglifyOpGrouping](io =>
        OpInputHash.hashString(
          (io.outputFile +: io.inputFiles.map(_._1.getAbsolutePath)).mkString("|") + "|" + options
        )
      )

      val (outputFiles, ()) = incremental.syncIncremental(streams.value.cacheDirectory / "run", groupings) {
        modifiedGroupings: Seq[UglifyOpGrouping] =>
          if (modifiedGroupings.nonEmpty) {

            streams.value.log.info(s"Optimizing ${modifiedGroupings.size} JavaScript(s) with Uglify")

            val nodeModulePaths = (nodeModuleDirectories in Plugin).value.map(_.getPath)
            val uglifyjsShell = (webJarsNodeModulesDirectory in Plugin).value / "uglify-js" / "bin" / "uglifyjs"


            val mangleArgs = if (mangle.value) {
              val stdArg = Seq("--mangle")
              val stdArgWithOptions = if (mangleOptions.value.isEmpty) stdArg else stdArg :+ mangleOptions.value.mkString(",")
              val reservedArgs = if (reserved.value.isEmpty) Nil else Seq("--reserved", reserved.value.mkString(","))
              stdArgWithOptions ++ reservedArgs
            } else {
              Nil
            }

            val compressArgs = if (compress.value) {
              val stdArg = Seq("--compress")
              if (compressOptions.value.isEmpty) stdArg else stdArg :+ compressOptions.value.mkString(",")
            } else {
              Nil
            }

            val defineArgs = define.value.map(Seq("--define", _)).getOrElse(Nil)

            val encloseArgs = if (enclose.value) Seq("--enclose") else Nil

            val commentsArgs = comments.value.map(Seq("--comments", _)).getOrElse(Nil)

            val preambleArgs = preamble.value.map(Seq("--preamble", _)).getOrElse(Nil)

            val includeSourceArgs = if (includeSource.value) Seq("--source-map-include-sources") else Nil

            val commonArgs =
              mangleArgs ++
                compressArgs ++
                defineArgs ++
                encloseArgs ++
                commentsArgs ++
                preambleArgs ++
                includeSourceArgs

            val executeUglify = SbtJsTask.executeJs(
              state.value,
              (engineType in uglify).value,
              (command in uglify).value,
              nodeModulePaths,
              uglifyjsShell,
              _: Seq[String],
              (timeoutPerSource in uglify).value
            )


            (modifiedGroupings.map {
              grouping =>
                val inputFiles = grouping.inputFiles.map(_._1)
                val inputFileArgs = inputFiles.map(_.getPath)

                val outputFile = buildDir.value / grouping.outputFile
                IO.createDirectory(outputFile.getParentFile)
                val outputFileArgs = Seq("--output", outputFile.getPath)

                val inputMapFileArgs = if (grouping.inputMapFile.isDefined) {
                  val inputMapFile = grouping.inputMapFile.map(_._1)
                  Seq("--in-source-map") ++ inputMapFile.map(_.getPath)
                } else {
                  Nil
                }

                val (outputMapFile, outputMapFileArgs) = if (grouping.outputMapFile.isDefined) {
                  val outputMapFile = buildDir.value / grouping.outputMapFile.get
                  IO.createDirectory(outputMapFile.getParentFile)
                  (Some(outputMapFile), Seq(
                    "--source-map", outputMapFile.getPath,
                    "--source-map-url", outputMapFile.getName,
                    "--prefix", "relative"))
                } else {
                  (None, Nil)
                }

                val args =
                  outputFileArgs ++
                    inputFileArgs ++
                    outputMapFileArgs ++
                    inputMapFileArgs ++
                    commonArgs

                val success = executeUglify(args).headOption.fold(true)(_ => false)
                grouping -> (
                  if (success)
                    OpSuccess(inputFiles.toSet, Set(outputFile) ++ outputMapFile)
                  else
                    OpFailure)
            }.toMap, ())

          } else {
            (Map.empty, ())
          }
      }

      (mappings.toSet ++ outputFiles.pair(relativeTo(buildDir.value))).toSeq
  }
}
