/*
 * Copyright (c) ProInnovate Limited, 2010
 */

package com.proinnovate.sbt.coverage

import com.proinnovate.sbt.utils.SbtVersion
import _root_.sbt._
import _root_.sbt.processor.BasicProcessor
import java.io.File
import java.util.logging.{Level,Logger}

/**
 * An sbt processor to perform code coverage analysis on the running of tests of a standard sbt scala project.  This
 * processor makes heavy use of the undercover code coverage library.
 */
class SbtCoverageProcessor extends BasicProcessor {

  val ReportPattern = "report((?: [a-z]*)*)".r

  /**
   * Respond to commands sent to this processor.
   */
  def apply(project: Project, args: String) {
    // Turn off excessive undercover logging...
    Logger.getLogger("").setLevel(Level.OFF)

    // Check SBT version is 0.7.5.RC0 or greater as this is required for the test callback classloader functionality...
    if (SbtVersion(project.sbtVersion.value) < SbtVersion("0.7.5.RC0")) {
      project.log.error("This coverage processor relies on functionality introduced in sbt version 0.7.5RC0.  " +
          "You are using an earlier version!")
    } else {
      // Handle command...
      project match {
        case scalaProject: BasicScalaProject => {
          args match {
            case "compile" =>
              project.act("test-compile")
              project.log.info("Instrumenting all classes for code coverage analysis")
              instrumentAllClasses(scalaProject)
            case "test" =>
              project.act("test-compile")
              project.log.info("Instrumenting all classes for code coverage analysis")
              instrumentAllClasses(scalaProject)
              project.log.info("Copying resources")
              project.act("copy-resources")
              project.act("copy-test-resources")
              project.log.info("Testing code coverage")
              testCoverage(scalaProject)
            case ReportPattern(arg) =>
              project.act("test-compile")
              project.log.info("Instrumenting all classes for code coverage analysis")
              instrumentAllClasses(scalaProject)
              project.log.info("Copying resources")
              project.act("copy-resources")
              project.act("copy-test-resources")
              project.log.info("Testing code coverage")
              testCoverage(scalaProject)
              project.log.info("Producing coverage report")

              val rawFormats = arg.split(' ').filter(!_.isEmpty)
              val formats = if(rawFormats.isEmpty) List("html") else rawFormats
              testCoverageReport(scalaProject, formats)
            case "clean" =>
              cleanCoverageOutput(scalaProject)
            case "" =>
              apply(project, "report")
            case x =>
              project.log.warn("Unknown command: " + x)
          }
        }
        case _ => project.log.error("This instrumentation process will only work on a Scala project!")
      }

    }

  }


  /**
   * Instrument the compiled class files, both the main and test classes.
   *
   * @param project the project to be instrumented.
   */
  private def instrumentAllClasses(project: BasicScalaProject) {
    // Instrument main classes...
    instrumentClasses(project.mainCompilePath, project.outputPath / "classes-inst")
    // Instrument test classes...
    instrumentClasses(project.testCompilePath, project.outputPath / "test-classes-inst")
  }


  /**
   * Use undercover to instrument the compiled classes in the given input path and put the instrumented ones in the
   * given output path.
   *
   * @param inputPath path to the root of the class files to be instrumented.
   * @param outputPath path to the location where the instrumented versions of the classes are to be placed.
   */
  private def instrumentClasses(inputPath: Path, outputPath: Path) {
    if (inputPath.exists) {
      val instr = new undercover.instrument.OfflineInstrument
      val paths = new java.util.ArrayList[java.io.File]
      paths.add(inputPath.asFile)
      instr.setInstrumentPaths(paths)
      instr.setOutputDirectory(outputPath.asFile)

      val globFilter = new undercover.instrument.filter.GlobFilter(Array(), Array())
      instr.setFilter(globFilter)

      val metaFile = (outputPath / "undercover.md").asFile
      instr.setMetaDataFile(metaFile)

      instr.fullcopy()
    }
  }


  /**
   * Produce code coverage data from pre-instrumented classes by running the test classes.
   *
   * Note that there is currently a problem with this implementation.  The undercover instrumentation code collects
   * coverage data and waits for the JVM to exit in order to write that data out.  However, when running under sbt
   * the JVM continues after the tests have completed so the coverage data is not written until you exit sbt completely.
   *
   * So, to make this work at the moment you have to run the code coverage code.  Exit sbt and then run it again to
   * build a report from the previous runs' output.
   *
   * @param project the project to be tested.
   */
  private def testCoverage(project: BasicScalaProject) {
    // Create a classpath of all the classes including dependent libraries that need to be loaded to run the tests over
    // the instrumented versions of the code.  This is generated by taking the default testClassPath, removing all the
    // uninstrumented main and test classes and adding the instrumented ones.  It also adds the undercover library to
    // the classpath.
    val instTestClasspath = project.testClasspath.
            --- (project.outputPath / "classes").
            --- (project.outputPath / "test-classes").
            +++ (project.outputPath / "classes-inst" / "classes").
            +++ (project.outputPath / "test-classes-inst" / "classes").
            +++ (Path.finder(List(project.rootProject.info.projectPath.asFile)) ** GlobFilter("undercover*.jar")).
            +++ (Path.finder(List(project.rootProject.info.projectPath.asFile)) ** GlobFilter("sbt-coverage*.jar"))

    val coverageDataPath = project.outputPath / "coverage" / "undercover.cd"

    val settings = new undercover.runtime.UndercoverSettings
    settings.setCoverageSaveOnExit(false)
    settings.setCoverageFile(coverageDataPath.asFile)
    (project.outputPath / "classes-inst" / "classes").asFile.mkdirs
    val propertiesFile = (project.outputPath / "classes-inst" / "classes" / "undercover.properties").asFile
    settings.save(propertiesFile)

    class UndercoverTestCompileConfig extends project.TestCompileConfig {
      override def label = "instrumentedTest"
      override def classpath = instTestClasspath
    }
    val compileConditional = new CompileConditional(new UndercoverTestCompileConfig, project.buildCompiler)

    /**
     * A curriable function that will take a project and produce a function that takes a classloader and returns an
     * optional status string.  This critical function instantiates the `UndercoverClassLoaderRunner` within the same
     * class loader as the tests being run and the undercover probe code.
     *
     * @param project the current project.
     * @param classLoader the class loader used to run the tests and the instance of undercover carrying out the code
     *    coverage analysis.
     */
    def undercoverCleanup(project: Project)(classLoader: ClassLoader): Option[String] = {
      val obj = Class.forName("com.proinnovate.sbt.coverage.UndercoverClassLoaderRunner", true, classLoader)
      (project.outputPath / "coverage").asFile.mkdirs
      val instance = obj.newInstance
      val method = obj.getMethod("exitUndercoverRuntime")
      method.invoke(instance)
      None
    }

    val exitUndercoverTestCleanup: project.TestOption = new project.TestCleanup(undercoverCleanup(project))
    val testOptions: Seq[project.TestOption] = project.testOptions ++ Seq(exitUndercoverTestCleanup)
    val instrumentedTestTask = project.testTask(project.testFrameworks, instTestClasspath, compileConditional.analysis,
          testOptions)
    _root_.sbt.RunnerHack.run(project, instrumentedTestTask, "InstrumentedTestTask")
  }


  /**
   * Produce a code coverage report from the code coverage data previously produced by undercover.
   *
   * @param project the project to be reported on.
   */
  private def testCoverageReport(project: BasicScalaProject, formats: Iterable[String]) {
    val sourceFinder = new undercover.report.SourceFinder
    val sourcePathRoots = (project.mainSourceRoots +++ project.testSourceRoots).getFiles.toList
    sourceFinder.setSourcePaths(listToJavaList(sourcePathRoots))

    val metaDataFile = (project.outputPath / "classes-inst" / "undercover.md").asFile
    val coverageDataFile = (project.outputPath / "coverage" / "undercover.cd").asFile
    val builder = new undercover.report.ReportDataBuilder(metaDataFile, coverageDataFile)
    builder.setProjectName(project.projectName.toString)
    builder.setSourceFinder(sourceFinder)
    val reportData = builder.build()
    val outputEncoding = "UTF-8"

    formats.foreach { format =>
      project.log.info("Generating " + format + " report")
      format match {
        case "html" => {
            val report = new undercover.report.html.HtmlReport
            report.setReportData(reportData)
            val outputDirectory = (project.outputPath / "coverage" / "html").asFile
            report.setOutputDirectory(outputDirectory)
            report.setEncoding(outputEncoding)
            report.generate()
            // Launch report file in operating system default associated application...
            if (java.awt.Desktop.isDesktopSupported) {
              java.awt.Desktop.getDesktop.open(new File(outputDirectory, "index.html"))              
            }
          }
        case "coberturaxml" => {
            val report = new undercover.report.xml.CoberturaXmlReport(reportData)
            val outputFile = (project.outputPath / "coverage" / "cobertura.xml").asFile
            report.writeTo(outputFile, outputEncoding)
          }
        case "emmaxml" => {
            val report = new undercover.report.xml.EmmaXmlReport(reportData)
            val outputFile = (project.outputPath / "coverage" / "emma.xml").asFile
            report.writeTo(outputFile, outputEncoding)
          }
        case _ => project.log.warn("Unknown report format: " + format)
      }
    }
  }


  /**
   * Clean the project of code coverage output files which are most likely within the target directory of your project.
   *
   * @param project the project to be cleaned of code coverage output files.
   */
  private def cleanCoverageOutput(project: BasicScalaProject) {
    def deleteAll(dfile: File) {
      if (dfile.isDirectory) dfile.listFiles.foreach(deleteAll)
      dfile.delete
    }

    deleteAll((project.outputPath / "coverage").asFile)
    deleteAll((project.outputPath / "classes-inst").asFile)
    deleteAll((project.outputPath / "test-classes-inst").asFile)
  }


  /**
   * Utility method to convert a Scala list to a Java list for some of the undercover methods which require Java lists.
   */
  private def listToJavaList[T](sequence: Seq[T]) = {
    sequence.foldLeft(new java.util.ArrayList[T](sequence.size)){ (al, e) => al.add(e); al }
  }

}
