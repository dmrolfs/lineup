import sbt.Keys._
import sbt._

object Dependencies {
  object peds {
    val version = "0.2.6"
    def module( id: String ) = "com.github.dmrolfs" %% s"peds-$id" % version
    def all = Seq( commons, akka, archetype )

    val commons = module( "commons" )
    val archetype = module( "archetype" )
    val akka = module( "akka" )
    val builder = "com.github.dmrolfs" %% "shapeless-builder" % "1.0.0"
  }

  object demesne {
    val version = "1.0.5"
    def module( id: String ) = "com.github.dmrolfs" %% s"demesne-$id" % version
    val core = module( "core" )
    val testkit = module( "testkit" )
  }

  object akka {
    val version = "2.4.7"
    def module( id: String ) = "com.typesafe.akka" %% s"akka-$id" % version
    val all = Seq( actor, stream, agent, cluster, clusterSharding, contrib, persistence, remote, slf4j )

    val actor = module( "actor" )
    val stream = module( "stream" )
    val agent = module( "agent" )
    val cluster = module( "cluster" )
    val clusterSharding = module( "cluster-sharding" )
    val contrib = module( "contrib" )
    val persistence = module( "persistence" )
    val remote = module( "remote" )
    val slf4j = module( "slf4j" )
    val testkit = module( "testkit" )
    val streamsTestkit = module( "stream-testkit" )
  }

  object scalaz {
    val version = "7.2.1"
    def module( id: String ) = "org.scalaz" %% s"scalaz-$id" % version

    val core = module( "core" )
    val concurrent = module( "concurrent" )
  }

  object time {
    val joda = "joda-time" % "joda-time" % "2.9.2"
    val jodaConvert = "org.joda" % "joda-convert" % "1.7"
    val scalaTime = "com.github.nscala-time" %% "nscala-time" % "2.10.0"
    def all = Seq( joda, jodaConvert, scalaTime )
  }

  object log {
    val typesafe = "com.typesafe.scala-logging" %% "scala-logging" % "3.1.0"

    object logback {
      val version = "1.1.3"
      def module( id: String ) = "ch.qos.logback" % s"logback-$id" % version

      val core = module( "core" )
      val classic = module( "classic" )
    }

    val slf4j = "org.slf4j" % "slf4j-api" % "1.7.21"

    def all = Seq( typesafe, logback.core, logback.classic, slf4j )
  }

  object metrics {
    val version = "3.1.2"
    def module( id: String ) = "io.dropwizard.metrics" % s"metrics-$id" % "3.1.2"
    def all = Seq( sigar, core, graphite, metricsScala, hdrhistogram ) ++ kamon.all

    val sigar = "org.hyperic" % "sigar" % "1.6.4"

    val core = module( "core" )
    val graphite = module( "graphite" )
    val metricsScala = "nl.grons" %% "metrics-scala" % "3.5.4_a2.3"
    val hdrhistogram = "org.mpierce.metrics.reservoir" % "hdrhistogram-metrics-reservoir" % "1.1.0"

    object kamon {
      val version = "0.5.2"
      def module( id: String ) = "io.kamon" %% s"kamon-$id" % version
      def all = Seq( core, scala, akka, akkaRemote, system, statsd )

      val core = module( "core" )
      val scala = module( "scala" )
      val akka = module( "akka" )
      val akkaRemote = module( "akka-remote" )
      val system = module( "system-metrics" )
      val statsd = module( "statsd" )
      val logReporter = module( "log-reporter" )
    }
  }

  object facility {
    val uuid = "com.eaio.uuid" % "uuid" % "3.4"
    val config = "com.typesafe" % "config" % "1.3.0"
//    val pureConfig = "com.github.melrief" %% "pureconfig" % "0.1.5"
    val shapeless = "com.chuusai" %% "shapeless" % "2.3.0"
    val parboiled = "org.parboiled" %% "parboiled" % "2.1.0"
    val inflector = "org.atteo" % "evo-inflector" % "1.2.1"
    val squants = "com.squants"  %% "squants"  % "0.5.3"
    val accord = "com.wix" %% "accord-core" % "0.5"
    val math3 = "org.apache.commons" % "commons-math3" % "3.6"
//    val suanshu = "com.numericalmethod" % "suanshu" % "3.4.0" intransitive()  // don't want to use due to $$$
    val scopt = "com.github.scopt" %% "scopt" % "3.3.0"
    val pyrolite = "net.razorvine" % "pyrolite" % "4.10"
    val msgpack = "org.velvia" % "msgpack4s_2.11" % "0.5.1"

    val hadoopClient = "org.apache.hadoop" % "hadoop-client" % "2.7.1"

    object avro {
      val version = "1.8.0"
      def all = Seq( core, scavro )
      val core = "org.apache.avro" % "avro" % version
      val tools = "org.apache.avro" % "avro-tools" % version
      val mapred = "org.apache.avro" % "avro-mapred" % version
      val scavro = "org.oedura" %% "scavro" % "1.0.1"
    }

    object betterFiles {
      val version = "2.14.0"
      val core = "com.github.pathikrit" %% "better-files" % version
      val akka = "com.github.pathikrit" %% "better-files-akka" % version
      def all = Seq( core, akka )
    }
  }

  object quality {
    val scalatest = "org.scalatest" %% "scalatest" % "2.2.4"

    object mockito {
      val version = "1.10.19"
      def module( id: String ) = "org.mockito" % s"mockito-$id" % version
      val core = module( "core" )
    }

    object persistence {
      val inMemory = "com.github.dnvriend" %% "akka-persistence-inmemory" % "1.1.6"
      val testkit = "com.github.krasserm" %% "akka-persistence-testkit" % "0.3.4"
    }
  }


  val commonDependencies = log.all ++ peds.all ++ time.all ++ Seq(
    akka.actor,
    akka.stream,
    akka.slf4j,
    log.logback.classic,
    facility.uuid,
    facility.config,
    facility.shapeless,
    scalaz.core
  ) ++ Scope.test(
    akka.testkit,
    quality.scalatest,
    quality.mockito.core
  )

  val defaultDependencyOverrides = Set(
    scalaz.core //,
//    akka.actor,
//    time.joda
  )


  object Scope {
    def compile( deps: ModuleID* ): Seq[ModuleID] = deps map ( _ % "compile" )
    def provided( deps: ModuleID* ): Seq[ModuleID] = deps map ( _ % "provided" )
    def test( deps: ModuleID* ): Seq[ModuleID] = deps map ( _ % "test" )
    def runtime( deps: ModuleID* ): Seq[ModuleID] = deps map ( _ % "runtime" )
    def container( deps: ModuleID* ): Seq[ModuleID] = deps map ( _ % "container" )
  }
}
