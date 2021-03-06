import sbt.Keys._
import sbt._
//import Dependencies._

import spray.revolver.RevolverPlugin._

object BuildSettings {
  val VERSION = "3.1.0"

  val defaultBuildSettings = Defaults.coreDefaultSettings ++ Format.settings ++ /* Revolver.settings ++ */ Seq(
    version := VERSION,
    organization := "com.github.dmrolfs",
    licenses += ("MIT", url("http://opensource.org/licenses/MIT")),
    crossScalaVersions := Seq( "2.12.2" ),
    scalaVersion := crossScalaVersions{ (vs: Seq[String]) => vs.head }.value,
    // updateOptions := updateOptions.value.withCachedResolution(true),
    scalacOptions ++= Seq(
      // "-encoding",
      // "utf8",
      "-target:jvm-1.8",
      "-feature",
      "-unchecked",
      "-deprecation",
      "-language:implicitConversions",
//      "-Ylog-classpath",
      // "-Xlog-implicits",
      // "-Ymacro-debug-verbose",
      // "-Ywarn-adapted-args",
//      "-Xfatal-warnings",
      "-Xlog-reflective-calls"
    ),
    javacOptions ++= Seq(
      "-source", "1.8",
      "-target", "1.8"
    ),
    javaOptions ++= Seq(
      "-Dconfig.trace=loads"
    ),
    homepage := Some( url("http://github.com/dmrolfs/spotlight") ),
    // licenses := Seq("Apache 2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.html")),
    conflictManager := ConflictManager.latestRevision,
    dependencyOverrides := Dependencies.defaultDependencyOverrides,

    resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
    resolvers += "krasserm at bintray" at "http://dl.bintray.com/krasserm/maven",
    resolvers += "omen-bintray" at "http://dl.bintray.com/omen/maven",
    resolvers += "dnvriend at bintray" at "http://dl.bintray.com/dnvriend/maven",
    resolvers += "IESL Releases" at "http://dev-iesl.cs.umass.edu/nexus/content/groups/public",
    resolvers += "spray repo" at "http://repo.spray.io",
    resolvers += "Typesafe releases" at "http://repo.typesafe.com/typesafe/releases",
    resolvers += "eaio releases" at "http://eaio.com/maven2",
    resolvers += "Sonatype OSS Releases"  at "http://oss.sonatype.org/content/repositories/releases/",
    resolvers += "Scalaz Bintray Repo" at "http://dl.bintray.com/scalaz/releases",
    resolvers += "velvia maven" at "http://dl.bintray.com/velvia/maven",
//    resolvers += "Numerical Method's Repository" at "http://repo.numericalmethod.com/maven/",  // don't want to use due to $$$
    resolvers += Resolver.jcenterRepo,
    resolvers += Resolver.sonatypeRepo( "snapshots" ),
    resolvers += Classpaths.sbtPluginReleases,
    resolvers += "OSS JFrog Artifactory" at "http://oss.jfrog.org/artifactory/oss-snapshot-local",

    // SLF4J initializes itself upon the first logging call.  Because sbt
    // runs tests in parallel it is likely that a second thread will
    // invoke a second logging call before SLF4J has completed
    // initialization from the first thread's logging call, leading to
    // these messages:
    //   SLF4J: The following loggers will not work because they were created
    //   SLF4J: during the default configuration phase of the underlying logging system.
    //   SLF4J: See also http://www.slf4j.org/codes.html#substituteLogger
    //   SLF4J: com.imageworks.common.concurrent.SingleThreadInfiniteLoopRunner
    //
    // As a workaround, load SLF4J's root logger before starting the unit
    // tests [1].
    //
    // [1] http://stackoverflow.com/a/12095245
    testOptions in Test += Tests.Setup( classLoader =>
      classLoader
        .loadClass( "org.slf4j.LoggerFactory" )
        .getMethod( "getLogger", classLoader.loadClass("java.lang.String") )
        .invoke( null, "ROOT" )
    ),
    parallelExecution in Test := false,
    testOptions in Test += Tests.Argument( TestFrameworks.ScalaTest, "-oDFT" ),
    triggeredMessage in ThisBuild := Watched.clearWhenTriggered,
    cancelable in Global := true
  )

  def doNotPublishSettings = Seq( publish := {} )

  def publishSettings = {
//    if ( (version in ThisBuild).toString.endsWith("-SNAPSHOT") ) {
    if ( VERSION.toString.endsWith("-SNAPSHOT") ) {
      Seq(
        publishTo := Some("Artifactory Realm" at "http://oss.jfrog.org/artifactory/oss-snapshot-local"),
        publishMavenStyle := true,
        // Only setting the credentials file if it exists (#52)
        credentials := List(Path.userHome / ".bintray" / ".artifactory").filter(_.exists).map(Credentials(_))
      )
    } else {
      Seq(
        pomExtra := {
          <scm>
            <url>https://github.com</url>
            <connection>https://github.com/dmrolfs/spotlight.git</connection>
          </scm>
          <developers>
            <developer>
              <id>dmrolfs</id>
              <name>Damon Rolfs</name>
              <url>http://dmrolfs.github.io/</url>
            </developer>
          </developers>
        },
        publishMavenStyle := true,
        resolvers += Resolver.url("omen bintray resolver", url("http://dl.bintray.com/omen/maven"))(Resolver.ivyStylePatterns),
        licenses := ("MIT", url("http://opensource.org/licenses/MIT")) :: Nil // this is required! otherwise Bintray will reject the code
      )
    }
  }
}
